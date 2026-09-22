#!/usr/bin/env python3
"""Install the already-built Windows bundle for the current user.

The ZIP is an explicit SDLC prerequisite. Installation validates every archive
path, stages a complete replacement beside the target, and restores the prior
installation if replacement fails. The user settings directory is separate and
is never removed by this helper.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import shutil
import sys
import zipfile
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[1]
BUNDLE = ROOT / ".build" / "windows" / "WobblePad-Windows.zip"


def default_target() -> pathlib.Path | None:
    local = os.environ.get("LOCALAPPDATA")
    return pathlib.Path(local) / "Programs" / "WobblePad" if local else None


def artifact_identity(path: pathlib.Path, root: pathlib.Path = ROOT) -> dict[str, Any]:
    return {
        "type": "windows-app-zip",
        "path": path.relative_to(root).as_posix(),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "size": path.stat().st_size,
    }


def result(
    status: str,
    *,
    target: pathlib.Path | None = None,
    artifact: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "schema": "ceratops-deployment-result.v1",
        "status": status,
        "target": str(target) if target is not None else None,
        "artifact": artifact,
    }


def safe_extract(bundle: pathlib.Path, destination: pathlib.Path) -> None:
    """Reject traversal, links, and unexpected roots before extracting bytes."""

    destination = destination.resolve()
    with zipfile.ZipFile(bundle) as archive:
        members = archive.infolist()
        for member in members:
            path = pathlib.PurePosixPath(member.filename)
            if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != "WobblePad":
                raise ValueError(f"Unsafe bundle member: {member.filename}")
            if member.external_attr >> 16 & 0o170000 == 0o120000:
                raise ValueError(f"Bundle links are not allowed: {member.filename}")
            resolved = destination.joinpath(*path.parts).resolve()
            resolved.relative_to(destination)
        archive.extractall(destination)


def install(bundle: pathlib.Path, target: pathlib.Path) -> None:
    """Replace one current-user installation with rollback on any failure."""

    parent = target.parent
    parent.mkdir(parents=True, exist_ok=True)
    staging = target.with_name(f".{target.name}.installing")
    backup = target.with_name(f".{target.name}.backup")
    if backup.exists() and not target.exists():
        backup.replace(target)
    elif backup.exists():
        shutil.rmtree(backup)
    shutil.rmtree(staging, ignore_errors=True)
    staging.mkdir()
    try:
        safe_extract(bundle, staging)
        candidate = staging / "WobblePad"
        if not (candidate / "WobblePad.exe").is_file():
            raise ValueError("The bundle does not contain WobblePad/WobblePad.exe.")
        if target.exists():
            target.replace(backup)
        candidate.replace(target)
        shutil.rmtree(backup, ignore_errors=True)
    except Exception:
        if backup.exists() and not target.exists():
            backup.replace(target)
        raise
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", type=pathlib.Path, help="Current-user installation directory")
    args = parser.parse_args(argv)
    target = args.target.expanduser().resolve() if args.target else default_target()
    if os.name != "nt" or target is None:
        print(json.dumps(result("blocked", target=target), separators=(",", ":")))
        print("Windows and LOCALAPPDATA are required for installation.", file=sys.stderr)
        return 2
    if not BUNDLE.is_file():
        print(json.dumps(result("blocked", target=target), separators=(",", ":")))
        print(f"Built Windows bundle is missing: {BUNDLE}", file=sys.stderr)
        return 2
    artifact = artifact_identity(BUNDLE)
    try:
        install(BUNDLE, target)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(json.dumps(result("failed", target=target, artifact=artifact), separators=(",", ":")))
        print(f"Windows installation failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result("passed", target=target, artifact=artifact), separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
