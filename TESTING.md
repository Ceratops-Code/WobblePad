# Testing

WobblePad separates repository validation, automated tests, and delivery. The
SDLC contract owns the commands, `scripts/repository_checks.py` owns Android
task selection and result recording, and each result identifies the exact
source content it exercised.

## Feature-to-observation map

| Supported behavior | Test or observation | Current coverage |
| --- | --- | --- |
| Accept only 20-byte `0x41 … 0x42` packets and decode nine signed little-endian fields | `PacketParserTest` checks the observed frame and malformed boundaries | Automated JVM test |
| Derive independent X/Y axes from five calibration poses and reject unsafe calibration data | `JoystickMapperTest` checks cardinal projection plus missing, invalid, and collinear captures | Automated JVM test |
| Apply radial dead zone, time-based smoothing, full-scale clamping, and digital-key hysteresis | `JoystickMapperTest` supplies controlled sensor values and nanosecond timestamps | Automated JVM test |
| Discover the board, subscribe once, receive notifications, read battery level, and recover a stalled stream | Observe a physical supported board and packet counter on an Android device | Hardware qualification required after BLE changes |
| Persist calibration only for the selected Bluetooth address | Calibrate, restart the app, reconnect the same board, then try a different address | Hardware qualification required after storage changes |
| Move the on-screen dot in the physical tilt direction | Capture all five poses and compare left, right, forward, and backward movement | Hardware qualification required after mapper or UI changes |
| Create and release virtual gamepad or arrow-key input through Shizuku | Verify Android input-device discovery, events in a receiving app, stop, disconnect, and service failure | Hardware qualification required after controller-output changes |
| Keep sensor data local unless the user explicitly exports CSV | Inspect the merged manifest and exercise Android's document picker | Repository validation plus manual Android observation |

Historical prototype captures live under the ignored `Prototype/` directory.
They do not qualify a newer commit and must not be copied into tracked results.

## Commands and retained results

Run the locked workflow from the repository root:

```text
uv sync --project scripts --locked
uv run --locked scripts/validate-repository.py
uv run --locked scripts/repository_checks.py validate
uv run --locked scripts/repository_checks.py test
```

Tests rerun the JVM suite, reject a zero-test run, retain each case outcome in
`.test-results/android-unit-tests.json`, and print the assertion difference for
failures. The record includes a digest of every nonignored source input. An
exact Git tag identifies the tested version only when the source inputs still
match the tagged commit.

The helper overwrites `.test-results/evidence/validate.log` and
`.test-results/evidence/test.log` on the next matching run. Those diagnostic
logs are local and ignored. Commit the compact test record, rerun affected groups
after source changes, and never use an older pass to conceal a newer failure.
