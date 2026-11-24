# pqc-ble-phone

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.24507281.svg)](https://doi.org/10.5281/zenodo.24507281)

ML-KEM-768 key establishment over Bluetooth Low Energy between an Android phone
(central) and an ESP32 (peripheral): latency, fragmentation and energy.
Paper: "Post-Quantum Key Establishment over Bluetooth Low Energy Between a Smartphone and an ESP32: Link Parameters Dominate" (letter draft in `paper/`).
Separate from `pqc-deception-journal`; no RL or deception content here.

Rule: every number in the paper comes from a CSV produced by this repo. Never invent results.

## Layout
- `firmware/ble_mlkem/` - ESP32 GATT server (PQClean ML-KEM-768 CLEAN, commit in `PQCLEAN_COMMIT.txt`).
- `tools/ble_test_client.py` - laptop debug client (not paper data).
- `android/` - phone app (to do).
- `docs/novelty.md` - closest prior work and our delta.
- `results/` - CSVs.

## Build and flash
```
arduino-cli compile --fqbn esp32:esp32:esp32 -u -p COM5 firmware/ble_mlkem
```
Core `esp32:esp32@4.0.0` (new `BLE.h` API). Uses 86% of the default app partition.

Status (2026-11-24): flashed and verified from an Android phone with nRF Connect (service and all 4 characteristics visible).
The laptop client cannot be used on this laptop: its Intel 3160 (BT 4.0) connects and negotiates MTU 517, but Windows never
completes GATT service discovery and drops the link after 30 s (reason 0x13). The ESP32 side is healthy (verbose log).

## Protocol (service 7a1e0001-5c2b-4c7e-9f3a-1d2e3f405a01)
1. Phone subscribes to PK (…0003) and RES (…0005) notifications.
2. Phone writes `K` to CTRL (…0002). ESP32: fresh keypair, then streams the 1184 B public key as notifications of <= MTU-3 bytes.
3. Phone encapsulates and writes the 1088 B ciphertext to CT (…0004) in order, in chunks of <= MTU-3 bytes.
4. ESP32 decapsulates and notifies a 20 B result: status, keygen/pk_send/ct_rx/decaps in us, cpu_mhz, first byte of SHA3-256(shared secret). The phone checks the tag.
- `F` + u16 MHz on CTRL sets the ESP32 clock (80/160/240) between handshakes.
- Serial line per handshake: `BLE,n,cpu_mhz,mtu,interval_units(x1.25ms),keygen_us,pk_send_us,ct_rx_us,decaps_us,ss_tag`.

## Experiment plan
Factors: ATT MTU (23, 185, 247, 517), connection priority on Android (LOW_POWER / BALANCED / HIGH ~ interval), ESP32 clock (80/160/240 MHz). 100 handshakes per cell, all verified.
Measures: end-to-end latency on the phone, per-phase split, ESP32 compute, phone battery current (BatteryManager CURRENT_NOW) with screen state fixed.

## Data collected (2026-11-24, Xiaomi 25078RA3EA, Android 16, MediaTek MT6769; ESP32-D0WD-V3)
- `results/phone/run_20261124_123122`: full 36-cell sweep, 3600 handshakes, 3599 verified (1 tag mismatch, m185_LOW_POWER_f80 #31).
  **Android 16 negotiated MTU 517 when 185/247 was requested**, so its m185/m247 cells are effectively MTU 517 (`mtu` column). Use it for MTU 23 and 517 only.
- `results/phone/run_20261124_133502`: cells 10-27 rerun with the MTU set from the ESP32 side (CTRL `M` + reconnect): real MTU 185 / 247, 1800/1800 verified.
- `results/phone/run_20261124_120634`: pilot (first 3 cells, 300/300), interrupted by a board reset; not used.
- `results/esp32/*.log`: ESP32 serial (BLE lines incl. connection interval: 12/32/90 units = 15/40/112.5 ms for HIGH/BALANCED/LOW_POWER).

## Reproduce the analysis
`python tools/analyze.py` (numpy, matplotlib) reads the run CSVs and writes `results/analysis/`.

## License
Code: MIT (see `LICENSE`). PQClean sources in `firmware/ble_mlkem/` keep their own license (`LICENSE_PQClean`).
