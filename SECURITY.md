# Security policy

## Supported versions

WobblePad is an experimental prototype without a stable release. Security fixes
are applied to the latest `main` branch. No older version is currently
supported.

## Report a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/Ceratops-Code/Wobblepad-Android/security/advisories/new).
Do not open a public issue for a vulnerability before maintainers have had a
reasonable opportunity to assess it.

Include the affected commit or version, Android device and version, required
preconditions, reproduction steps, impact, and any proposed mitigation. Remove
Bluetooth addresses, calibration samples, captured movement data, account data,
and secrets from the report unless they are essential and safe to share in the
private advisory.

## Trust boundaries

- BLE data comes from a nearby peripheral and is rejected unless it matches the
  expected 20-byte framing.
- Calibration and CSV data remain local unless the user exports them.
- Virtual controller output requires an explicit Shizuku grant and a separate
  user service running with Android shell identity.
- The privileged helper opens `/dev/uhid`, creates one virtual input device,
  accepts only the app's small controller protocol, and releases input on stop
  or failure.
- The app requests no Internet permission and stores no signing material in the
  repository.

A game declining virtual input, a board using a different undocumented packet
format, or Shizuku needing to restart after reboot is normally a compatibility
issue rather than a security vulnerability.
