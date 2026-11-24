# Android app (BLE central)

Build (laptop): `gradlew.bat assembleDebug` with `JAVA_HOME` = Android Studio's `jbr`.
APK: `app/build/outputs/apk/debug/app-debug.apk`. AGP 9.0.1, Gradle 9.3.1, BouncyCastle 1.87 (ML-KEM-768, SHA3-256), minSdk 33.

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (USB debugging on).

Run:
1. **Quick test** (10 handshakes, MTU 517, HIGH priority, 240 MHz): checks the whole path.
2. **Full sweep**: 4 MTU x 3 connection priority x 3 ESP32 clock = 36 cells, N per cell (default 100).
   Each cell: set clock, 10 s idle baseline, then N handshakes 200 ms apart. Screen kept on.
   For phone energy the phone must be **unplugged** (on battery); `samples.csv` records the plugged state.

Output on the phone: `Android/data/com.zyeemzia.pqcble/files/run_<time>/`
- `handshakes.csv`: one row per handshake, including failures (`tag_ok=0`, `error`).
- `samples.csv`: BatteryManager CURRENT_NOW (uA, raw vendor sign) and voltage every 50 ms, tagged `idle:/hs:/gap:<cell>`.
- `meta.txt`: phone model, Android version, SoC, settings.

Pull: `adb pull /sdcard/Android/data/com.zyeemzia.pqcble/files/ results/phone/`

The ESP32 also prints `BLE,...` lines (incl. connection interval) on USB serial; log them on the laptop during the sweep.
