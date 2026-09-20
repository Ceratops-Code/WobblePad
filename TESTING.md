# Testing

WobblePad separates repository validation, automated tests, and delivery. The
SDLC contract owns the commands. `scripts/validate-repository.py` runs repository
checks and Android lint, while `scripts/run-tests.py` runs repository-script and
Kotlin/JVM test groups. Each retained result identifies the exact source content
it exercised.

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
uv run --project scripts --locked python scripts/validate-repository.py
uv run --project scripts --locked python scripts/run-tests.py
```

Validation replaces `.test-results/validation.json`. Tests replace the aggregate
`.test-results/tests.json` and the group records under `.test-results/groups/`.
The test runner rejects a zero-test JVM run and retains each case outcome and a
digest of every nonignored source input. Aggregate group references include the
SHA-256 digest of the exact group record. `sourceCommit` names the latest commit
that changed non-result source, so a later commit containing only refreshed
records does not make the next run rewrite those records.

Each runner writes `running` before executing work, then atomically replaces it
with `passed`, `failed`, or `blocked`. An interrupted attempt therefore cannot
leave an older passing status. Diagnostic logs under
`.test-results/evidence/` are local and ignored.

APK assembly and device installation are delivery operations:

```text
uv run --project scripts --locked python scripts/build-android.py
uv run --project scripts --locked python scripts/deploy-android.py [--serial DEVICE]
```

The build command emits bounded JSON on standard output and leaves the APK in
Gradle's standard ignored output directory. Deployment requires that APK and
never builds an implicit replacement.
