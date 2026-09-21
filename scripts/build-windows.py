#!/usr/bin/env python3
"""Build a self-contained Windows app bundle and emit one structured result.

PyInstaller runs through the current Windows-app interpreter so its locked
dependencies remain in that explicit environment. This helper owns and cleans
its temporary PyInstaller tree and atomically replaces the final ZIP artifact.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
import pathlib
import shutil
import subprocess
import sys
import zipfile
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "windows-app" / "src"
ENTRYPOINT = SOURCE_ROOT / "wobblepad_windows" / "__main__.py"
BUILD_ROOT = ROOT / ".build" / "pyinstaller"
OUTPUT = ROOT / ".build" / "windows" / "WobblePad-Windows.zip"
LEGAL_FILES = ("LICENSE", "DISCLAIMER.md", "THIRD_PARTY_NOTICES.md")


def build_command(python: str = sys.executable) -> list[str]:
    """Return the locked-environment PyInstaller command for an onedir build."""

    return [
        python,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--clean",
        "--windowed",
        "--name",
        "WobblePad",
        "--paths",
        str(SOURCE_ROOT),
        "--distpath",
        str(BUILD_ROOT / "dist"),
        "--workpath",
        str(BUILD_ROOT / "work"),
        "--specpath",
        str(BUILD_ROOT / "spec"),
        "--hidden-import",
        "bleak.backends.winrt.client",
        "--hidden-import",
        "bleak.backends.winrt.scanner",
        str(ENTRYPOINT),
    ]


def artifact_identity(path: pathlib.Path, root: pathlib.Path = ROOT) -> dict[str, Any]:
    return {
        "type": "windows-app-zip",
        "path": path.relative_to(root).as_posix(),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "size": path.stat().st_size,
    }


def result(status: str, artifact: dict[str, Any] | None = None) -> dict[str, Any]:
    return {"schema": "ceratops-build-result.v1", "status": status, "artifact": artifact}


def archive_app(source: pathlib.Path, output: pathlib.Path) -> None:
    """Atomically archive one onedir bundle under a stable top-level folder."""

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(f".{output.name}.tmp")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(source.rglob("*")):
                if path.is_file():
                    archive.write(path, pathlib.Path("WobblePad") / path.relative_to(source))
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)


def installed_license(distribution_name: str, suffix: str) -> pathlib.Path:
    """Resolve an exact installed dependency license from package metadata."""

    distribution = importlib.metadata.distribution(distribution_name)
    for entry in distribution.files or []:
        if entry.as_posix().endswith(suffix):
            path = pathlib.Path(str(distribution.locate_file(entry)))
            if path.is_file():
                return path
    raise FileNotFoundError(f"License not found for {distribution_name}: {suffix}")


def add_legal_files(
    bundle: pathlib.Path,
    root: pathlib.Path = ROOT,
    dependency_licenses: dict[str, pathlib.Path] | None = None,
) -> None:
    """Include project and third-party terms beside the packaged executable."""

    for name in LEGAL_FILES:
        source = root / name
        if not source.is_file():
            raise FileNotFoundError(f"Required legal file is missing: {source}")
        shutil.copy2(source, bundle / name)
    licenses = dependency_licenses or {
        "BLEAK.txt": installed_license("bleak", "/licenses/LICENSE"),
        "PYINSTALLER.txt": installed_license("PyInstaller", "/licenses/COPYING.txt"),
        "PYTHON.txt": pathlib.Path(sys.base_prefix) / "LICENSE.txt",
    }
    license_root = bundle / "THIRD_PARTY_LICENSES"
    license_root.mkdir()
    for name, source in licenses.items():
        if not source.is_file():
            raise FileNotFoundError(f"Required dependency license is missing: {source}")
        shutil.copy2(source, license_root / name)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args(argv)
    if os.name != "nt":
        print(json.dumps(result("blocked"), separators=(",", ":")))
        print("The Windows app must be built on Windows; PyInstaller is not a cross-compiler.", file=sys.stderr)
        return 2
    if not ENTRYPOINT.is_file():
        print(json.dumps(result("blocked"), separators=(",", ":")))
        print(f"Windows entrypoint is missing: {ENTRYPOINT}", file=sys.stderr)
        return 2
    shutil.rmtree(BUILD_ROOT, ignore_errors=True)
    OUTPUT.unlink(missing_ok=True)
    completed = subprocess.run(build_command(), cwd=ROOT, capture_output=True, text=True, check=False)
    if completed.stdout:
        print(completed.stdout, file=sys.stderr, end="")
    if completed.stderr:
        print(completed.stderr, file=sys.stderr, end="")
    bundle = BUILD_ROOT / "dist" / "WobblePad"
    executable = bundle / "WobblePad.exe"
    if completed.returncode != 0 or not executable.is_file():
        print(json.dumps(result("failed"), separators=(",", ":")))
        return completed.returncode if completed.returncode > 0 else 1
    try:
        add_legal_files(bundle)
        archive_app(bundle, OUTPUT)
    finally:
        shutil.rmtree(BUILD_ROOT, ignore_errors=True)
    print(json.dumps(result("passed", artifact_identity(OUTPUT)), separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
