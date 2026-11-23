"""Laptop-side BLE test client for firmware/ble_mlkem (sanity check only).

Plays the role the Android app will play: connects to "PQC-ESP32", asks for a
handshake, reassembles the public key from notifications, encapsulates with
ML-KEM-768, writes the ciphertext in MTU-sized chunks and checks the shared-
secret tag the ESP32 returns. It verifies the firmware and protocol end to
end before the phone app exists.

The laptop is NOT the study's central device: its numbers are for debugging,
never paper results. The paper's central is the Android phone.

Needs: pip install bleak kyber-py
Usage: python tools/ble_test_client.py --n 20 --out results/laptop_debug.csv
"""
from __future__ import annotations

import argparse
import asyncio
import csv
import hashlib
import struct
import time

from bleak import BleakClient, BleakScanner
from kyber_py.ml_kem import ML_KEM_768

NAME = "PQC-ESP32"
CTRL = "7a1e0002-5c2b-4c7e-9f3a-1d2e3f405a01"
PK = "7a1e0003-5c2b-4c7e-9f3a-1d2e3f405a01"
CT = "7a1e0004-5c2b-4c7e-9f3a-1d2e3f405a01"
RES = "7a1e0005-5c2b-4c7e-9f3a-1d2e3f405a01"
PK_BYTES, CT_BYTES = 1184, 1088
FIELDS = ["n", "mtu", "total_ms", "pk_rx_ms", "encaps_ms", "ct_tx_ms", "result_wait_ms",
          "esp_cpu_mhz", "esp_keygen_us", "esp_pk_send_us", "esp_ct_rx_us", "esp_decaps_us",
          "tag_ok"]


async def run(n: int, out: str):
    dev = await BleakScanner.find_device_by_name(NAME, timeout=15.0)
    if dev is None:
        raise SystemExit(f"{NAME} not found; is the ESP32 powered and advertising?")
    async with BleakClient(dev) as client:
        mtu = client.mtu_size
        chunk = max(20, mtu - 3)
        print(f"[ble] connected, mtu={mtu}, ct chunk={chunk} B")
        pk_buf = bytearray()
        pk_done, res_done = asyncio.Event(), asyncio.Event()
        res_val = {}

        def on_pk(_, data: bytearray):
            pk_buf.extend(data)
            if len(pk_buf) >= PK_BYTES:
                pk_done.set()

        def on_res(_, data: bytearray):
            res_val["v"] = bytes(data)
            res_done.set()

        await client.start_notify(PK, on_pk)
        await client.start_notify(RES, on_res)
        with open(out, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=FIELDS)
            w.writeheader()
            for i in range(1, n + 1):
                pk_buf.clear(); pk_done.clear(); res_done.clear()
                t0 = time.perf_counter()
                await client.write_gatt_char(CTRL, b"K", response=True)
                await asyncio.wait_for(pk_done.wait(), 20)
                t1 = time.perf_counter()
                key, ct = ML_KEM_768.encaps(bytes(pk_buf[:PK_BYTES]))
                t2 = time.perf_counter()
                for off in range(0, CT_BYTES, chunk):
                    await client.write_gatt_char(CT, ct[off:off + chunk], response=False)
                t3 = time.perf_counter()
                await asyncio.wait_for(res_done.wait(), 20)
                t4 = time.perf_counter()
                st, kg, pks, ctr, dec, mhz, tag = struct.unpack("<cIIIIHB", res_val["v"])
                ok = st == b"O" and tag == hashlib.sha3_256(key).digest()[0]
                w.writerow(dict(n=i, mtu=mtu, total_ms=f"{(t4-t0)*1e3:.3f}",
                                pk_rx_ms=f"{(t1-t0)*1e3:.3f}", encaps_ms=f"{(t2-t1)*1e3:.3f}",
                                ct_tx_ms=f"{(t3-t2)*1e3:.3f}", result_wait_ms=f"{(t4-t3)*1e3:.3f}",
                                esp_cpu_mhz=mhz, esp_keygen_us=kg, esp_pk_send_us=pks,
                                esp_ct_rx_us=ctr, esp_decaps_us=dec, tag_ok=int(ok)))
                print(f"[ble] {i}/{n} total={(t4-t0)*1e3:.1f} ms  tag_ok={ok}")
                await asyncio.sleep(0.2)
    print(f"[write] {out}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--n", type=int, default=20)
    p.add_argument("--out", default="results/laptop_debug.csv")
    a = p.parse_args()
    asyncio.run(run(a.n, a.out))


if __name__ == "__main__":
    main()
