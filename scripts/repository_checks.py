"""Run the repository's Android checks from one portable entry point.

CI and local validation use this helper so Gradle task selection cannot drift.
It changes only Gradle-managed build outputs under ignored directories.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
TASKS = {
    "validate": (":app:lintDebug", ":app:assembleDebug"),
    "test": (":app:testDebugUnitTest",),
    "build": (":app:assembleDebug",),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=TASKS)
    args = parser.parse_args()

    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        parser.error(f"Gradle wrapper is missing: {wrapper}")

    command = [str(wrapper), "--no-daemon", *TASKS[args.action]]
    completed = subprocess.run(command, cwd=ROOT, check=False)
    return completed.returncode


if __name__ == "__main__":
    sys.exit(main())
