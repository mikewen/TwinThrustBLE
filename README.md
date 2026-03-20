# TwinThrustBLE

**Dual-BLE 4-Motor Controller for Android**

Control two AC6328/AC6329C BLE ESC modules simultaneously — one for the Port side, one for Starboard — each driving two BLDC or ESC motors. Designed for air props (drones, airboats) and water props (RC boats).

---

## Features

- **Dual BLE** — connects to two AC6328 modules at once, one per side
- **ESC & BLDC modes** — toggle on the fly; auto-arms ESC on connect
- **Sync mode** — single master throttle drives both sides identically
- **Trim** — per-side ±trim buttons (hold to ramp) for fine balance
- **Independent mode** — separate port and starboard sliders
- **GPS speed** — large real-time speed display (km/h or knots); logs full GPS data to CSV
- **Port/Starboard assignment** — connect one module at a time, spin to identify, assign and save; persists across sessions

---

## BLE Packet Format (AC6328 / ae03 characteristic)

```
[CMD, portLo, portHi, stbdLo, stbdHi]   — 5 bytes, little-endian 16-bit values
```

| CMD  | Mode      | Duty range         |
|------|-----------|--------------------|
| 0x01 | ESC PWM   | 500–1000 (timer units; 500=1000µs stop, 1000=2000µs full) |
| 0x02 | BLDC duty | 0–10000 (0=stop, 10000=100%) |
| 0xFF | STOP      | immediate stop, both motors |

Both motors on the same BLE module receive identical duty values (same port and stbd field), keeping the pair in perfect sync.

---

## BLE Services

| UUID   | Type                  | Use                                       |
|--------|-----------------------|-------------------------------------------|
| `ae00` | Service               | AC6328 main service                       |
| `ae03` | WRITE_WITHOUT_RESPONSE| Command packets (5-byte, above)           |
| `ae02` | NOTIFY                | Echo / CASIC GNSS stream                  |
| `ae10` | READ / WRITE          | Status read (`M<mode>A<mv>T<min>`) / mode switch |

---

## User Flow

### First-time Setup

1. **MainActivity** → tap **Scan for BLE Modules**
2. Tap a device in the list → opens **ControlActivity (single mode)**
3. Use the **Test Throttle** slider to spin the motors
4. Tap **Assign as PORT** or **Assign as STBD** — assignment saved
5. Press Back → repeat for the second module
6. **CONNECT BOTH & LAUNCH** button becomes active → tap to run all 4 motors

### Subsequent Sessions

- Saved assignments shown on MainActivity immediately
- Tap **CONNECT BOTH & LAUNCH** — no re-assignment needed
- Use **Clear** buttons to reset an assignment if hardware changes

---

## Project Structure

```
TwinThrustBLE/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/twinthrustble/
│       │   ├── MainActivity.kt          — scan, saved assignments, launch
│       │   ├── ControlActivity.kt       — single & dual mode control + GPS
│       │   ├── AC6328BleManager.kt      — BLE manager for AC6328/AC6329C
│       │   ├── GpsManager.kt            — phone GPS, fusion, CSV logging
│       │   ├── SensorFusion.kt          — IMU + GNSS sensor fusion
│       │   ├── JoystickView.kt          — custom joystick view (reserved)
│       │   └── CompassView.kt           — custom compass view (reserved)
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml
│           │   ├── activity_control.xml
│           │   └── item_device.xml
│           └── values/
│               ├── strings.xml
│               └── themes.xml
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
│       └── gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
├── .gitignore
└── README.md
```

---

## Dependencies

| Library | Purpose |
|---|---|
| `no.nordicsemi.android:ble:2.7.0` | Nordic BLE library (via JitPack) |
| `com.google.android.gms:play-services-location` | Fused GPS |
| `androidx.appcompat`, `material`, `recyclerview` | UI |
| `androidx.activity:activity-ktx` | `addCallback` for back press |

---

## Build Requirements

- **Android Studio** Hedgehog or newer
- **Kotlin** 2.0+
- **minSdk** 26 (Android 8.0)
- **targetSdk** 35
- **Gradle** 8.7

```bash
git clone <repo>
cd TwinThrustBLE
./gradlew assembleDebug
```

---

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | BLE scanning and connection (Android 12+) |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | BLE (Android ≤11) |
| `ACCESS_FINE_LOCATION` | GPS and BLE scan (Android ≤11) |
| `VIBRATE` | Haptic feedback on connect/stop |
| `WRITE_EXTERNAL_STORAGE` | GPS CSV log (Android ≤9 only) |

---

## GPS Logging

Logs are written to `Downloads/TwinThrustBLE_<timestamp>.csv` with columns:

```
timestamp, source, lat, lon, alt_m, speed_knots, speed_kmh, heading_deg, satellites, speed_acc_ms
```

---

## License

MIT — see [LICENSE](LICENSE) for details.
