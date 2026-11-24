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
| Javaid, Hassan, Umer, Panagiotou, Strydis, Bhatti, Pasha, Siddiqi, "Plug-and-Play Quantum-Resistant BLE Pairing for Medical Implants via NFC Out-of-Band", IEEE HealthCom 2026 (arXiv:2610.07870, Oct 2026) | Samsung Galaxy A54 (Android 14) + ESP32-S3 @ 80 MHz with PN532 NFC | Kyber-512/768/1024 and SABER exchanged **over NFC** (out-of-band), 11.4-20.7 s; implant-side current only | KEM not carried over BLE; no BLE handshake latency; MTU, connection interval and clock not varied; no phone energy |

**Update 2026-11-24:** Javaid et al. (above) DO use an Android phone with an ESP32-S3, so "first phone + ESP32" is NOT claimable. Claim instead: first measurement of ML-KEM-768 carried over the BLE GATT link itself between a phone and an ESP32, with MTU x connection interval x clock factorial and phone-side current.

## Our distinct contributions
1. ML-KEM-768 carried **over the BLE GATT link** (not out-of-band) between a real Android phone (central) and an ESP32 (Xtensa LX6, peripheral).
2. **Connection interval** sweep (phone-controlled) and **ESP32 CPU clock** sweep (80/160/240 MHz), both untouched above.
3. **Phone-side energy measured** through Android `BatteryManager` current, alongside ESP32-side timing.
4. Every handshake verified end to end (shared-secret tag), so failures are counted, not hidden.

## Must do before writing
- Read all four papers in full; cite them; state the delta in our own words.
- Re-search (IEEE Xplore, ACM DL, Google Scholar) for "post-quantum BLE smartphone" right before submission.
- Verify every reference (authors, venue, pages, DOI) against the publisher record.
