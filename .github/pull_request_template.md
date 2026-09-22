## What changed

Describe the behavior change and why it is needed.

## Validation

- [ ] `uv run --project scripts --locked python scripts/validate-repository.py`
- [ ] `uv run --project scripts --locked python scripts/run-tests.py`
- [ ] `.test-results/validation.json`, `.test-results/tests.json`, and the group results describe this source.
- [ ] Hardware behavior was tested, or the hardware limitation is explained below.

## Safety and data

- [ ] No device addresses, captured packets, calibration data, signing keys, or proprietary assets are included.
- [ ] User-facing compatibility claims and documentation remain accurate.
