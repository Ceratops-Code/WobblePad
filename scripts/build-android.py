#!/usr/bin/env python3
"""Build the debug APK and emit one structured result.

Gradle owns its normal ``app/build`` output. This entrypoint deliberately does
not persist a second build receipt; callers that need installation select this
build action first and then consume the declared APK path.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[1]
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"


def gradle_wrapper(root: pathlib.Path = ROOT) -> pathlib.Path:
    """Return the repository wrapper selected for the current platform."""

    return root / ("gradlew.bat" if os.name == "nt" else "gradlew")


def build_command(wrapper: pathlib.Path) -> list[str]:
    """Return the sole supported debug-build command."""

    return [str(wrapper), "--no-daemon", ":app:assembleDebug"]


def artifact_identity(path: pathlib.Path, root: pathlib.Path = ROOT) -> dict[str, Any]:
    """Describe exact APK bytes without creating a persistent receipt."""

    return {
        "type": "android-apk",
        "path": path.relative_to(root).as_posix(),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "size": path.stat().st_size,
    }


def result(status: str, *, artifact: dict[str, Any] | None = None) -> dict[str, Any]:
    """Build one bounded SDLC result object."""

    return {
        "schema": "ceratops-build-result.v1",
        "status": status,
        "artifact": artifact,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args(argv)
    wrapper = gradle_wrapper()
    if not wrapper.is_file():
        print(json.dumps(result("blocked"), separators=(",", ":")))
        print(f"Gradle wrapper is missing: {wrapper}", file=sys.stderr)
        return 2

    completed = subprocess.run(
        build_command(wrapper),
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.stdout:
        print(completed.stdout, file=sys.stderr, end="")
    if completed.stderr:
        print(completed.stderr, file=sys.stderr, end="")
    if completed.returncode != 0:
        print(json.dumps(result("failed"), separators=(",", ":")))
        return completed.returncode if completed.returncode > 0 else 1
    if not APK.is_file():
        print(json.dumps(result("failed"), separators=(",", ":")))
        print(f"Gradle reported success but did not create {APK}", file=sys.stderr)
        return 1

    print(
        json.dumps(
            result("passed", artifact=artifact_identity(APK)),
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
