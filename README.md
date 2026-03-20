# TwinThrustBLE

**Dual-BLE 4-Motor Controller for Android**

Controls two AC6328/AC6329C BLE ESC modules simultaneously — one for Port (left), one for Starboard (right) — each driving two motors. Designed for air props (drones, airboats) and water props (RC boats).

---

## BLE Packet Format

Each AC6328 module drives **two motors**. Commands are sent as **separate 3-byte packets**, one per motor, to characteristic `ae03` (WRITE_WITHOUT_RESPONSE):

```
[CMD, dutyLo, dutyHi]   — 3 bytes, little-endian 16-bit duty value
```

| CMD  | Motor        |
|------|--------------|
| 0x01 | M1 (front)   |
| 0x02 | M2 (rear)    |
| 0xFF | STOP (both)  |

To drive both motors on one module, send two packets in sequence:
```
[0x01, m1_duty_lo, m1_duty_hi]   → sets M1 duty
[0x02, m2_duty_lo, m2_duty_hi]   → sets M2 duty
```

### Duty ranges

| Mode | Duty range | Notes |
|------|-----------|-------|
| ESC  | 500 – 1000 | Timer counts; 500 = 1000µs (stop/arm), 1000 = 2000µs (full) |
| BLDC | 0 – 10000  | 0 = stop, 10000 = 100% |

Both modes use the same `0x01` / `0x02` command bytes — the firmware interprets the duty value according to its current mode (ESC or BLDC), which is set separately via `ae10`.

### Mode switch (ae10 WRITE)

| Value | Mode      |
|-------|-----------|
| 0x01  | ESC mode  |
| 0x02  | BLDC mode |

### BLE Services

| UUID   | Type                    | Purpose                                          |
|--------|-------------------------|--------------------------------------------------|
| `ae00` | Service                 | AC6328 main service                              |
| `ae03` | WRITE_WITHOUT_RESPONSE  | Motor command packets (3-byte per motor)         |
| `ae02` | NOTIFY                  | Echo of last command / CASIC GNSS stream         |
| `ae10` | READ / WRITE            | Status read (`M<mode>A<mv>T<min>`) / mode switch |

---

## Motor Layout

```
        Port BLE module          Starboard BLE module
        ┌─────────────┐          ┌─────────────┐
   M1 ──┤  CMD 0x01   │     M3 ──┤  CMD 0x01   │
   M2 ──┤  CMD 0x02   │     M4 ──┤  CMD 0x02   │
        └─────────────┘          └─────────────┘
        (front-left, rear-left)  (front-right, rear-right)
```

---

## Features

- **Dual BLE** — connects to two AC6328 modules at once, one per side
- **ESC & BLDC modes** — toggle on the fly; auto-arms ESC on connect
- **3-level sync:**
    - **All Sync** (default) — one master throttle drives all 4 motors; side trim (L/R) and front/rear trim per side
    - **Side Sync** — port slider → M1+M2; starboard slider → M3+M4; F/R trim still available
    - **Independent** — four fully separate sliders: M1, M2, M3, M4
- **▼▲ buttons** alongside every slider for precise fine control; tap = small step, hold = ramp
- **GPS speed** — large real-time display (km/h or knots); full GPS data logged to CSV
- **Port/Starboard assignment** — connect one module, spin to identify, assign; persists across sessions
- **Scan filter** — shows only `ESC_PWM` / `BLDC_PWM` / `AC6328` devices; "Show all" chip for manual override; assigned devices hidden from scan list

---

## User Flow

### First-time Setup

1. **MainActivity** → tap **Scan for BLE Modules** (shows ESC_PWM/BLDC_PWM devices only)
2. Tap a device → **ControlActivity (single mode)**
3. Use **Test Throttle** slider / ▼▲ buttons to spin motors
4. Tap **Assign as PORT** or **Assign as STBD** → saved to SharedPreferences
5. Press Back → repeat for the second module
6. **CONNECT BOTH & LAUNCH** becomes active → tap to control all 4 motors

### Subsequent Sessions

- Saved assignments shown on MainActivity
- Tap **CONNECT BOTH & LAUNCH** — no re-assignment needed
- **Clear** buttons reset an assignment if hardware changes

---

## Project Structure

```
TwinThrustBLE/
├── README.md
├── LICENSE
├── .gitignore
├── build.gradle.kts                    ← root (project-level)
├── settings.gradle.kts                 ← includes :app, JitPack repo
├── gradlew / gradlew.bat
├── gradle/
│   ├── libs.versions.toml              ← Nordic BLE 2.7.0, GPS, etc.
│   └── wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/twinthrustble/
        │   ├── MainActivity.kt          — scan, saved assignments, launch
        │   ├── ControlActivity.kt       — single & dual mode control + GPS
        │   ├── AC6328BleManager.kt      — BLE manager; correct 3-byte per-motor packets
        │   ├── GpsManager.kt            — phone GPS, sensor fusion, CSV logging
        │   ├── SensorFusion.kt          — IMU + GNSS fusion
        │   ├── JoystickView.kt          — reserved
        │   └── CompassView.kt           — reserved
        └── res/
            ├── layout/
            │   ├── activity_main.xml
            │   ├── activity_control.xml
            │   └── item_device.xml
            └── values/
                ├── strings.xml
                └── themes.xml
```

---

## Dependencies

| Library | Purpose |
|---|---|
| `no.nordicsemi.android:ble:2.7.0` | Nordic BLE library (via JitPack) |
| `com.google.android.gms:play-services-location` | Fused GPS |
| `androidx.appcompat`, `material`, `recyclerview` | UI |
| `androidx.activity:activity-ktx` | Back press handler |

---

## Build

- **Android Studio** Hedgehog or newer
- **Kotlin** 2.0+, **minSdk** 26, **targetSdk** 35, **Gradle** 8.7

```bash
./gradlew assembleDebug
```

---

## Permissions

| Permission | Purpose |
|---|---|
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | BLE scan + connect (Android 12+) |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | BLE (Android ≤11) |
| `ACCESS_FINE_LOCATION` | GPS + BLE scan (Android ≤11) |
| `VIBRATE` | Haptic feedback on connect / stop |
| `WRITE_EXTERNAL_STORAGE` | GPS CSV log (Android ≤9 only) |

---

## GPS Logging

Logs written to `Downloads/TwinThrustBLE_<timestamp>.csv`:

```
timestamp, source, lat, lon, alt_m, speed_knots, speed_kmh, heading_deg, satellites, speed_acc_ms
```

---

## License

MIT — see [LICENSE](LICENSE).