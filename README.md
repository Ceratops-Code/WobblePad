# WobblePad

**Turn your balance board into an Android gamepad.**

WobblePad is an experimental native Android bridge for a user-owned BoBo
Wobbly balance board. It connects directly over Bluetooth Low Energy (BLE),
turns the board's continuous tilt stream into calibrated X/Y values, and can
present those values to Android as a virtual gamepad or arrow keys.

> [!IMPORTANT]
> This is an independent, unofficial project. It is not affiliated with,
> endorsed by, or sponsored by BO&BO Ltd. “BoBo” and “BoBo Wobbly” are used
> only to identify compatible hardware. Read [DISCLAIMER.md](DISCLAIMER.md)
> before using the app.

## Current status

This repository contains a working prototype, not a production release.

- BLE scan, connection, notification streaming, packet parsing, calibration,
  stall detection, reconnect, battery reading, and CSV export are implemented.
- The direct BLE stream was exercised on a Samsung SM-X210 running Android 16
  for about ten minutes and received 3,204 packets. That test does not prove
  compatibility with every board revision or Android device.
- The virtual controller uses Shizuku to open Android's `/dev/uhid` interface.
  Kernel policy, manufacturer changes, Shizuku state, and the receiving game's
  input support can all affect whether controller output works.
- There is no signed production APK or Play Store release yet. Build and install
  the debug app from source while the compatibility surface is still being
  tested.

## What the app does

1. Scans for a device whose name contains `BoBo` or advertises the known custom
   service.
2. Connects with Android BLE/GATT without classic Bluetooth pairing.
3. Enables notifications once on the custom characteristic.
4. Parses each 20-byte notification into nine signed little-endian values.
5. Captures center, left, right, forward, and backward calibration poses.
6. Projects live sensor vectors onto calibrated X/Y axes with smoothing and a
   dead zone.
7. Sends either an analog gamepad or arrow-key device through Android UHID when
   Shizuku access is available.

Live BLE display, calibration, packet rate, and CSV export work without
Shizuku. Shizuku is needed only for system-visible controller output.

## Why Shizuku is needed

Android deliberately isolates ordinary apps from global input injection. A
normal app can receive input and can communicate with BLE peripherals, but it
cannot register an arbitrary system-wide hardware controller for other apps.
WobblePad's small user service asks Shizuku for the Android shell identity and
uses that identity to open `/dev/uhid`, the kernel interface that creates a
virtual HID device.

WobblePad does not bundle Shizuku, root the device, modify firmware, or bypass a
game's input policy. On a non-rooted Android device, Shizuku normally has to be
started again after reboot through Android's wireless-debugging flow. Review
the [Shizuku documentation](https://shizuku.rikka.app/guide/setup/) before
granting access.

## Requirements

- Android 12 or later (`minSdk 31`)
- Bluetooth LE hardware
- A compatible BoBo Wobbly board
- [Shizuku](https://shizuku.rikka.app/download/) for virtual controller output
- A game or app that accepts Android gamepad or keyboard input

Only one BLE central can commonly hold a peripheral connection at a time.
Disconnect nRF Connect, the official app, and other BLE clients before testing.

## Build

Install JDK 17 and Android SDK Platform 36, then clone the repository. The
Gradle wrapper downloads the declared Gradle version.

On Windows:

```powershell
python -B scripts/repository_checks.py validate
python -B scripts/repository_checks.py test
.\gradlew.bat :app:installDebug
```

On Linux or macOS:

```bash
python3 -B scripts/repository_checks.py validate
python3 -B scripts/repository_checks.py test
./gradlew :app:installDebug
```

Android Studio can also open the repository root directly. Debug builds use the
standard per-machine Android debug key; no signing key is stored here.

## Use

1. Power on the board and keep it awake.
2. Open WobblePad and allow Nearby devices and notification permissions.
3. Tap **Scan for BoBo**, then select the detected board.
4. Capture `CENTER`, `LEFT`, `RIGHT`, `UP`, and `DOWN`. `UP` means away from
   you. Hold each pose steady for three seconds.
5. Finish calibration and verify the live dot follows the board.
6. For controller output, start Shizuku, grant WobblePad access, choose analog
   stick or arrow keys, and tap **Start controller output**.
7. Open a controller-compatible app. Return to WobblePad or use its foreground
   notification to stop the bridge.

Calibration is stored locally and keyed to the board's Bluetooth address. It is
not included in this repository and is not transmitted by the app.

## BLE protocol

The prototype uses these observed GATT identifiers:

| Purpose | UUID |
| --- | --- |
| Service | `856b152a-734a-5546-bf2b-ed4898184e12` |
| Stream characteristic | `856b152b-734a-5546-bf2b-ed4898184e12` |
| Client Characteristic Configuration | `00002902-0000-1000-8000-00805f9b34fb` |
| Battery service | `0000180f-0000-1000-8000-00805f9b34fb` |

Notifications are enabled with the standard CCCD value `01 00`. Each accepted
packet starts with `0x41`, ends with `0x42`, and contains nine signed 16-bit
little-endian values between those markers. The mapper uses all nine observed
values and learns physical direction from the five calibration poses instead
of assigning undocumented sensor axes.

The format was independently documented by observing BLE data transmitted by
a user-owned device. The repository contains no BO&BO application code,
firmware, artwork, games, backend access, or paid features.

## Privacy and security

The manifest does not request Internet access. BLE data, calibration, and the
bounded recent-packet CSV buffer remain on the Android device. A CSV leaves the
app only when the user chooses a destination through Android's document picker.
Do not publish Bluetooth addresses or raw captures without sanitizing them.

Shizuku grants elevated local capability. WobblePad limits its user service to
creating and updating one virtual input device, releases all input on stop or
failure, and does not expose that service to other apps. See
[SECURITY.md](SECURITY.md) for reporting and trust-boundary details.

## Project layout

```text
app/src/main/       Android application, BLE client, calibration, and UHID bridge
scripts/            Shared local and CI validation entry point
sdlc/sdlc.yml       Ceratops repository validation contract
.github/            CI, dependency updates, and contribution templates
```

## Contributing

Contributions are welcome within the project's interoperability and safety
scope. Read [CONTRIBUTING.md](CONTRIBUTING.md), the
[Code of Conduct](CODE_OF_CONDUCT.md), and the
[third-party notices](THIRD_PARTY_NOTICES.md).

## License and names

WobblePad source code is licensed under the [Apache License 2.0](LICENSE).
Third-party components retain their own licenses.

WobblePad is the name of this independent project. BoBo, BoBo Wobbly, and any
related marks belong to their respective owners and are used here only to state
hardware compatibility.
