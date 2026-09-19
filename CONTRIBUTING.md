# Contributing

Thank you for helping improve WobblePad.

## Before opening a change

- Keep the project independent and factual. Use BoBo product names only to
  describe compatibility, and do not add vendor logos, artwork, screenshots,
  code, firmware, backend access, or paid content.
- Do not commit Bluetooth addresses, real calibration samples, raw personal
  captures, signing keys, APKs, Android Studio caches, or local SDK paths.
- Avoid medical, rehabilitation, safety-certification, or universal
  compatibility claims.
- Do not design controller output to evade anti-cheat or another app's rules.

## Development

Use JDK 17 and Android SDK Platform 36. Run both repository commands before
opening a pull request:

```text
python -B scripts/repository_checks.py validate
python -B scripts/repository_checks.py test
```

CI calls the same helper. Keep deterministic build or validation behavior in
that helper rather than duplicating task lists in workflow YAML.

Hardware changes should describe the exact board and Android model tested, the
duration or packet count, and any part that could not be verified. Sanitize all
logs. A green JVM or emulator check does not establish physical BLE or
system-wide controller compatibility.

## Pull requests

Keep changes focused, explain the user-visible behavior, and update the README,
disclaimer, security notes, protocol description, and manifests when their
claims change. New dependencies must have a maintained source, a compatible
license, and an entry in `THIRD_PARTY_NOTICES.md` when required.

By contributing, you agree that your contribution is licensed under Apache
License 2.0 and that you will follow [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
