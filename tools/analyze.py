"""Analysis of the phone <-> ESP32 ML-KEM-768 over BLE measurements.

Inputs (all produced by this repo, see README "Data collected"):
  results/phone/run_20261124_123122  full sweep; only cells whose negotiated
                                     MTU equals the requested one (23, 517) are used
  results/phone/run_20261124_133502  MTU 185 / 247 rerun (MTU set from the ESP32)
  results/esp32/*.log                ESP32 serial: connection interval per handshake

Outputs: results/analysis/
  cells.csv       per cell: n, verified, median + bootstrap 95% CI of end-to-end
                  latency, phase medians, ESP32 compute medians, interval
  energy.csv      per cell of the full sweep: phone battery current during
                  handshakes vs the cell's idle baseline, energy per handshake
  failures.csv    every unverified handshake in the used data
  table_latency.tex, table_energy.tex, fig_latency.pdf, fig_phases.pdf
Every number in the paper comes from these files.
"""
from __future__ import annotations

import csv
import os
import statistics as st
from collections import OrderedDict, defaultdict

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PH = os.path.join(ROOT, "results", "phone")
OUT = os.path.join(ROOT, "results", "analysis")
FULL, RERUN = "run_20261124_123122", "run_20261124_133502"
ESP_LOGS = ["sweep_serial_20261124.log", "mtu_rerun_serial_20261124.log"]
PRIOS = ["LOW_POWER", "BALANCED", "HIGH"]
MTUS = [23, 185, 247, 517]
MHZ = [80, 160, 240]
RNG = np.random.default_rng(20261124)
B = 10000


def load(run):
    with open(os.path.join(PH, run, "handshakes.csv"), encoding="utf-8") as f:
        return list(csv.DictReader(f))


def boot_median_ci(x):
    x = np.asarray(x, float)
    idx = RNG.integers(0, len(x), size=(B, len(x)))
    m = np.median(x[idx], axis=1)
    return float(np.percentile(m, 2.5)), float(np.percentile(m, 97.5))


def interval_by_cell():
    """ESP32-reported connection interval (ms) per (mtu, priority), from serial BLE lines.
    The serial lines carry mtu and interval but not priority, so match them by
    handshake order: the ESP32 logs exactly one BLE line per verified handshake."""
    out = defaultdict(set)
    for name in ESP_LOGS:
        p = os.path.join(ROOT, "results", "esp32", name)
        for line in open(p, encoding="utf-8", errors="replace"):
            i = line.find("BLE,")
            if i < 0:
                continue
            f = line[i:].strip().split(",")
            if len(f) < 10:
                continue
            out[int(f[3])].add(int(f[4]) * 1.25)
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    full, rerun = load(FULL), load(RERUN)
    used = [r for r in full if r["mtu"] == r["mtu_req"] and int(r["mtu"]) in (23, 517)] + \
           [r for r in rerun if r["mtu"] == r["mtu_req"]]
    cells = OrderedDict()
    for m in MTUS:
        for p in PRIOS:
            for f in MHZ:
                cells[(m, p, f)] = []
    for r in used:
        cells[(int(r["mtu"]), r["priority"], int(r["mhz_req"]))].append(r)

    # ---- per-cell latency -------------------------------------------------
    rows, fails = [], []
    for (m, p, f), rs in cells.items():
        ok = [r for r in rs if r["tag_ok"] == "1"]
        fails += [r for r in rs if r["tag_ok"] != "1"]
        tot = [float(r["total_ms"]) for r in ok]
        lo, hi = boot_median_ci(tot)
        med = lambda k: st.median(float(r[k]) for r in ok)
        rows.append(dict(
            mtu=m, priority=p, mhz=f, n=len(rs), verified=len(ok),
            total_med_ms=round(st.median(tot), 2), total_ci_lo=round(lo, 2), total_ci_hi=round(hi, 2),
            total_q1=round(float(np.percentile(tot, 25)), 2), total_q3=round(float(np.percentile(tot, 75)), 2),
            pk_rx_med_ms=round(med("pk_rx_ms"), 2), encaps_med_ms=round(med("encaps_ms"), 2),
            ct_tx_med_ms=round(med("ct_tx_ms"), 2), res_wait_med_ms=round(med("res_wait_ms"), 2),
            pk_notifs=int(ok[0]["pk_notifs"]), ct_writes=int(ok[0]["ct_writes"]),
            esp_keygen_med_ms=round(med("esp_keygen_us") / 1000, 3), esp_decaps_med_ms=round(med("esp_decaps_us") / 1000, 3),
        ))
    write_csv(os.path.join(OUT, "cells.csv"), rows)
    if fails:
        write_csv(os.path.join(OUT, "failures.csv"), fails)

    # ---- phone energy (full sweep only; MTU 23 and 517 cells) -------------
    samples = list(csv.DictReader(open(os.path.join(PH, FULL, "samples.csv"), encoding="utf-8")))
    by_phase = defaultdict(list)
    volts = defaultdict(list)
    for s in samples:
        if s["plugged"] != "0":
            continue
        kind, _, cell = s["phase"].partition(":")
        # CURRENT_NOW is negative while discharging on this phone: use the drawn current.
        by_phase[(kind, cell)].append(-int(s["current_ua"]) / 1000.0)
        if s["voltage_mv"] != "0":
            volts[cell].append(int(s["voltage_mv"]) / 1000.0)
    erows = []
    for r in rows:
        if r["mtu"] not in (23, 517):
            continue
        cid = f"m{r['mtu']}_{r['priority']}_f{r['mhz']}"
        hs = np.array(by_phase[("hs", cid)])
        idle = np.array(by_phase[("idle", cid)])
        if len(hs) < 20 or len(idle) < 20:
            continue
        v = st.mean(volts[cid]) if volts[cid] else float("nan")
        # Block bootstrap (blocks of 8 samples = 0.4 s, the update period of CURRENT_NOW).
        d = []
        for _ in range(2000):
            d.append(block_mean(hs) - block_mean(idle))
        dlo, dhi = np.percentile(d, [2.5, 97.5])
        dI = float(hs.mean() - idle.mean())
        t = r["total_med_ms"] / 1000.0
        erows.append(dict(
            mtu=r["mtu"], priority=r["priority"], mhz=r["mhz"],
            idle_mA=round(float(idle.mean()), 1), hs_mA=round(float(hs.mean()), 1),
            delta_mA=round(dI, 1), delta_ci_lo=round(float(dlo), 1), delta_ci_hi=round(float(dhi), 1),
            voltage_V=round(v, 3), total_med_ms=r["total_med_ms"],
            e_incr_mJ=round(dI * v * t, 2), e_incr_ci_lo=round(float(dlo) * v * t, 2), e_incr_ci_hi=round(float(dhi) * v * t, 2),
            e_whole_phone_mJ=round(float(hs.mean()) * v * t, 1),
            n_hs_samples=len(hs), n_idle_samples=len(idle),
        ))
    write_csv(os.path.join(OUT, "energy.csv"), erows)

    iv = interval_by_cell()
    with open(os.path.join(OUT, "intervals.txt"), "w") as f:
        for m in sorted(iv):
            f.write(f"mtu {m}: intervals_ms {sorted(iv[m])}\n")

    tables(rows, erows)
    figures(rows)
    print(f"used {len(used)} handshakes, verified {sum(r['verified'] for r in rows)}, failures {len(fails)}")


def block_mean(x, b=8):
    nb = max(1, len(x) // b)
    starts = RNG.integers(0, len(x) - b + 1, size=nb)
    return float(np.concatenate([x[s:s + b] for s in starts]).mean())


def write_csv(path, rows):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)


PNAME = {"LOW_POWER": "Low power", "BALANCED": "Balanced", "HIGH": "High"}


def tables(rows, erows):
    get = {(r["mtu"], r["priority"], r["mhz"]): r for r in rows}
    L = [r"\begin{tabular}{llrrrr}", r"\toprule",
         r"Priority (interval) & Clock & \multicolumn{4}{c}{ATT MTU (B)} \\",
         r"\cmidrule(lr){3-6}", r" & (MHz) & 23 & 185 & 247 & 517 \\", r"\midrule"]
    ivl = {"LOW_POWER": "112.5", "BALANCED": "40", "HIGH": "15"}
    for p in PRIOS:
        for i, f in enumerate(MHZ):
            lab = f"{PNAME[p]} ({ivl[p]}\\,ms)" if i == 0 else ""
            vals = " & ".join(f"{get[(m, p, f)]['total_med_ms']:.0f}" for m in MTUS)
            L.append(f"{lab} & {f} & {vals} \\\\")
        if p != PRIOS[-1]:
            L.append(r"\addlinespace")
    L += [r"\bottomrule", r"\end{tabular}"]
    open(os.path.join(OUT, "table_latency.tex"), "w").write("\n".join(L) + "\n")

    # Only the 240 MHz cells go in the paper table (clock barely moves phone energy);
    # energy.csv keeps all 18.
    E = [r"\begin{tabular}{lrrrr}", r"\toprule",
         r"MTU, priority & $I_\mathrm{idle}$ & $I_\mathrm{hs}$ & $\Delta I$ [95\% CI] & $E_\mathrm{phone}$ \\",
         r" & (mA) & (mA) & (mA) & (mJ) \\", r"\midrule"]
    for e in erows:
        if e["mhz"] != 240:
            continue
        E.append(f"{e['mtu']}, {PNAME[e['priority']]} & {e['idle_mA']:.0f} & {e['hs_mA']:.0f} & "
                 f"{e['delta_mA']:.0f} [{e['delta_ci_lo']:.0f}, {e['delta_ci_hi']:.0f}] & "
                 f"{e['e_whole_phone_mJ']:.0f} \\\\")
    E += [r"\bottomrule", r"\end{tabular}"]
    open(os.path.join(OUT, "table_energy.tex"), "w").write("\n".join(E) + "\n")
    pos = sum(e["delta_ci_lo"] > 0 for e in erows)
    neg = sum(e["delta_ci_hi"] < 0 for e in erows)
    with open(os.path.join(OUT, "energy_summary.txt"), "w") as f:
        f.write(f"cells={len(erows)} deltaI_CI_above_0={pos} below_0={neg} spans_0={len(erows) - pos - neg}\n")
        f.write(f"idle_mA range {min(e['idle_mA'] for e in erows)}-{max(e['idle_mA'] for e in erows)}\n")
        f.write(f"hs_mA range {min(e['hs_mA'] for e in erows)}-{max(e['hs_mA'] for e in erows)}\n")
        f.write(f"E_phone_mJ range {min(e['e_whole_phone_mJ'] for e in erows)}-{max(e['e_whole_phone_mJ'] for e in erows)}\n")


def figures(rows):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams.update({"font.size": 8, "font.family": "serif"})
    get = {(r["mtu"], r["priority"], r["mhz"]): r for r in rows}
    ivl = {"LOW_POWER": 112.5, "BALANCED": 40.0, "HIGH": 15.0}

    fig, ax = plt.subplots(figsize=(3.5, 2.4))
    marks = {23: "o", 185: "s", 247: "^", 517: "D"}
    for m in MTUS:
        xs = [ivl[p] for p in PRIOS]
        y = [get[(m, p, 240)]["total_med_ms"] for p in PRIOS]
        lo = [get[(m, p, 240)]["total_med_ms"] - get[(m, p, 240)]["total_ci_lo"] for p in PRIOS]
        hi = [get[(m, p, 240)]["total_ci_hi"] - get[(m, p, 240)]["total_med_ms"] for p in PRIOS]
        ax.errorbar(xs, y, yerr=[lo, hi], marker=marks[m], ms=4, lw=1, capsize=2, label=f"MTU {m}")
    ax.set_xscale("log"); ax.set_yscale("log")
    from matplotlib.ticker import NullFormatter, FixedLocator, FixedFormatter
    ax.xaxis.set_minor_formatter(NullFormatter()); ax.yaxis.set_minor_formatter(NullFormatter())
    ax.set_xticks([15, 40, 112.5]); ax.set_xticklabels(["15", "40", "112.5"])
    yt = [100, 200, 500, 1000, 2000, 4000]
    ax.yaxis.set_major_locator(FixedLocator(yt)); ax.yaxis.set_major_formatter(FixedFormatter([str(v) for v in yt]))
    ax.set_xlabel("Connection interval (ms)"); ax.set_ylabel("Handshake latency (ms)")
    ax.grid(True, which="both", lw=0.3, alpha=0.5); ax.legend(frameon=False, fontsize=7)
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "fig_latency.pdf")); plt.close(fig)

    fig, ax = plt.subplots(figsize=(3.5, 2.4))
    labels, parts = [], {"pk_rx_med_ms": [], "encaps_med_ms": [], "ct_tx_med_ms": [], "res_wait_med_ms": []}
    for m in MTUS:
        for f in MHZ:
            r = get[(m, "HIGH", f)]
            labels.append(f"{m}\n{f}")
            for k in parts:
                parts[k].append(r[k])
    bottom = np.zeros(len(labels))
    names = {"pk_rx_med_ms": "PK transfer (incl. keygen)", "encaps_med_ms": "Phone encaps",
             "ct_tx_med_ms": "CT transfer", "res_wait_med_ms": "Result wait (incl. decaps)"}
    for k, v in parts.items():
        ax.bar(range(len(labels)), v, bottom=bottom, label=names[k], width=0.75)
        bottom += np.array(v)
    ax.set_xticks(range(len(labels))); ax.set_xticklabels(labels, fontsize=6)
    ax.set_xlabel("ATT MTU (B) / ESP32 clock (MHz); 15 ms interval")
    ax.set_ylabel("Median time (ms)"); ax.legend(frameon=False, fontsize=6)
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "fig_phases.pdf")); plt.close(fig)


if __name__ == "__main__":
    main()
