# Novelty check (2026-11-24)

Question: has anyone measured ML-KEM key establishment over BLE between an
**Android smartphone** and an **ESP32**?

## Answer: not found in this search. Closest work and how we differ

| Work | Hardware (both ends) | What it varies / measures | Gap we fill |
|---|---|---|---|
| Liu, Ramachandran, Jurdak, "Towards Quantum Resilient IoT: A Backward-Compatible Approach to Secure BLE Key Exchange Against Quantum Threats", IoTDI 2024, pp. 170-180 (QUT eprint 245861) | PC central (Python Bleak) + Particle Argon (nRF52840, Cortex-M4F) | Kyber-512 PQKE over a custom GATT service; ATT MTU, DLE, 1M/2M PHY; pairing time | No phone, no ESP32, Kyber-512 only |
| Liu, Ramachandran, Jurdak, "On the Energy Cost of Post-Quantum Key Establishment in Wireless Low-Power Personal Area Networks", arXiv:2602.18708 (2026) | nRF52840 DK, Zephyr; energy with Nordic PPK2 | ML-KEM-512/768/1024; ATT MTU and LL PDU (DLE on/off). **Connection interval fixed at 50 ms, PHY fixed at 1M** | No phone; connection interval and CPU clock not varied; no ESP32 |
| Gutierrez-Felix, Manzano, Lopez, "Quantum-Resistant Pairing Evaluation for Resource-Constrained Bluetooth Classic" (Elsevier preprint, NICS lab, UMA) | Cortex-M4 benchmark + Bluetooth **Classic** analysis | ML-KEM / HQC in pairing; transfer of public parameters dominates latency | Bluetooth Classic, not BLE; no phone |
| Future Internet 18(6):316 (MDPI, 2026) "Evaluating PQC in IoT Networks: Communication, Fragmentation, and Reliability" | radio **model**, not hardware (page returned 403; from search snippet only) | packets per handshake (ML-KEM-768: 10 at 247 B MTU) | Not measured; must read before citing |

## Our distinct contributions
1. Real **Android phone** as central (the common app-controls-device case), ESP32 (Xtensa LX6) as peripheral.
2. **Connection interval** sweep (phone-controlled) and **ESP32 CPU clock** sweep (80/160/240 MHz), both untouched above.
3. **Phone-side energy measured** through Android `BatteryManager` current, alongside ESP32-side timing.
4. Every handshake verified end to end (shared-secret tag), so failures are counted, not hidden.

## Must do before writing
- Read all four papers in full; cite them; state the delta in our own words.
- Re-search (IEEE Xplore, ACM DL, Google Scholar) for "post-quantum BLE smartphone" right before submission.
- Verify every reference (authors, venue, pages, DOI) against the publisher record.
