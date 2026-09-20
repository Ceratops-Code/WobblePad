#!/usr/bin/env python3
"""Install the already-built debug APK on one connected Android device.

The device may use USB or wireless ADB. This script never builds the APK; the
SDLC package build is an explicit prerequisite so deployment cannot hide build
work or install an unknown fallback artifact.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[1]
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"


def adb_executable() -> str | None:
    """Resolve adb from PATH or the standard Android SDK environment variables."""

    if found := shutil.which("adb"):
        return found
    executable = "adb.exe" if os.name == "nt" else "adb"
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if root := os.environ.get(variable):
            candidate = pathlib.Path(root) / "platform-tools" / executable
            if candidate.is_file():
                return str(candidate)
    return None


def connected_devices(output: str) -> list[str]:
    """Return only fully connected device serials from ``adb devices`` output."""

    devices: list[str] = []
    for raw in output.splitlines()[1:]:
        columns = raw.strip().split()
        if len(columns) >= 2 and columns[1] == "device":
            devices.append(columns[0])
    return devices


def select_device(devices: list[str], requested: str | None) -> str:
    """Select an explicit device or require exactly one connected target."""

    if requested:
        if requested not in devices:
            raise ValueError(f"Requested Android device is not connected: {requested}")
        return requested
    if len(devices) != 1:
        raise ValueError(
            "Expected exactly one connected Android device; use --serial when several are available."
        )
    return devices[0]


def artifact_identity(path: pathlib.Path, root: pathlib.Path = ROOT) -> dict[str, Any]:
    """Bind deployment evidence to the exact installed APK bytes."""

    return {
        "path": path.relative_to(root).as_posix(),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "size": path.stat().st_size,
    }


def result(
    status: str,
    *,
    serial: str | None = None,
    artifact: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Build one bounded deployment result object."""

    return {
        "schema": "ceratops-android-deployment-result.v1",
        "status": status,
        "target": serial,
        "artifact": artifact,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB serial or wireless IP:port target")
    args = parser.parse_args(argv)

    if not APK.is_file():
        print(json.dumps(result("blocked"), separators=(",", ":")))
        print(f"Built APK is missing: {APK}", file=sys.stderr)
        return 2
    adb = adb_executable()
    if adb is None:
        print(json.dumps(result("blocked"), separators=(",", ":")))
        print("adb was not found in PATH, ANDROID_SDK_ROOT, or ANDROID_HOME.", file=sys.stderr)
        return 2

    inventory = subprocess.run(
        [adb, "devices"], capture_output=True, text=True, check=False
    )
    if inventory.returncode != 0:
        print(json.dumps(result("failed"), separators=(",", ":")))
        print(inventory.stderr or inventory.stdout, file=sys.stderr)
        return inventory.returncode if inventory.returncode > 0 else 1
    try:
        serial = select_device(connected_devices(inventory.stdout), args.serial)
    except ValueError as error:
        print(json.dumps(result("blocked"), separators=(",", ":")))
        print(str(error), file=sys.stderr)
        return 2

    artifact = artifact_identity(APK)
    installed = subprocess.run(
        [adb, "-s", serial, "install", "-r", str(APK)],
        capture_output=True,
        text=True,
        check=False,
    )
    if installed.stdout:
        print(installed.stdout, file=sys.stderr, end="")
    if installed.stderr:
        print(installed.stderr, file=sys.stderr, end="")
    status = "passed" if installed.returncode == 0 else "failed"
    print(
        json.dumps(
            result(status, serial=serial, artifact=artifact),
            separators=(",", ":"),
        )
    )
    return installed.returncode if installed.returncode > 0 else 0


if __name__ == "__main__":
    raise SystemExit(main())
