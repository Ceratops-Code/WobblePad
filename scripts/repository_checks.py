"""Run Android checks and retain compact results for the exact tested source.

CI and local validation use this entry point so Gradle task selection cannot
drift. The helper owns its generated records and overwrites its ignored evidence
log on the next run; Gradle owns the ignored app/build outputs.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
BUILD_RECORD = ROOT / ".build" / "android-debug.json"
TEST_RECORD = ROOT / ".test-results" / "android-unit-tests.json"
EVIDENCE_ROOT = ROOT / ".test-results" / "evidence"
TEST_REPORT_ROOT = ROOT / "app" / "build" / "test-results" / "testDebugUnitTest"
TASKS = {
    "validate": (":app:lintDebug", ":app:assembleDebug"),
    "test": ("--rerun-tasks", ":app:testDebugUnitTest"),
    "build": (":app:assembleDebug",),
}


def git_output(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, check=False
    )
    return completed.stdout.strip() if completed.returncode == 0 else ""


def source_is_clean() -> bool:
    """Return whether non-generated source inputs match HEAD exactly."""
    tracked = subprocess.run(
        [
            "git",
            "diff",
            "--quiet",
            "HEAD",
            "--",
            ".",
            ":(exclude).build/**",
            ":(exclude).test-results/**",
        ],
        cwd=ROOT,
        check=False,
    )
    if tracked.returncode != 0:
        return False

    untracked = subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
    )
    return all(
        raw.decode("utf-8", errors="surrogateescape")
        .replace("\\", "/")
        .startswith((".build/", ".test-results/"))
        for raw in untracked.split(b"\0")
        if raw
    )


def source_identity() -> dict[str, Any]:
    """Hash all nonignored source inputs while excluding generated records."""
    listed = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
    )
    paths = []
    for raw in listed.split(b"\0"):
        if not raw:
            continue
        relative = raw.decode("utf-8", errors="surrogateescape").replace("\\", "/")
        if relative.startswith((".build/", ".test-results/")):
            continue
        path = ROOT / relative
        if path.is_file():
            paths.append((relative, path))

    digest = hashlib.sha256()
    for relative, path in sorted(paths):
        content = path.read_bytes()
        digest.update(relative.encode("utf-8", errors="surrogateescape"))
        digest.update(b"\0")
        digest.update(str(len(content)).encode("ascii"))
        digest.update(b"\0")
        digest.update(content)
        digest.update(b"\0")

    tags = git_output("tag", "--points-at", "HEAD").splitlines() if source_is_clean() else []
    return {
        "contentSha256": digest.hexdigest(),
        "versionTag": sorted(tags)[0] if tags else None,
    }


def fingerprint(path: Path) -> dict[str, Any]:
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {
        "path": path.relative_to(ROOT).as_posix(),
        "length": path.stat().st_size,
        "sha256": digest,
    }


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    try:
        with temporary.open("w", encoding="utf-8", newline="\n") as stream:
            stream.write(json.dumps(payload, indent=2, sort_keys=True) + "\n")
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def run_gradle(action: str, wrapper: Path) -> tuple[int, list[str], Path]:
    arguments = [str(wrapper), "--no-daemon", *TASKS[action]]
    EVIDENCE_ROOT.mkdir(parents=True, exist_ok=True)
    evidence = EVIDENCE_ROOT / f"{action}.log"
    with evidence.open("w", encoding="utf-8", newline="\n") as log:
        process = subprocess.Popen(
            arguments,
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        assert process.stdout is not None
        for line in process.stdout:
            sys.stdout.write(line)
            log.write(line)
        return process.wait(), arguments, evidence


def parse_test_results() -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for report in sorted(TEST_REPORT_ROOT.glob("*.xml")):
        root = ET.parse(report).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
        for suite in suites:
            for case in suite.findall("testcase"):
                failure = case.find("failure")
                if failure is None:
                    failure = case.find("error")
                skipped = case.find("skipped")
                status = "failed" if failure is not None else "skipped" if skipped is not None else "passed"
                item: dict[str, Any] = {
                    "id": f"{case.get('classname', '')}.{case.get('name', '')}".strip("."),
                    "status": status,
                }
                if failure is not None:
                    details = "\n".join(
                        part.strip()
                        for part in (failure.get("message", ""), failure.text or "")
                        if part.strip()
                    )
                    item["difference"] = details[:2000]
                cases.append(item)
    return sorted(cases, key=lambda item: str(item["id"]))


def record_build(action: str, exit_code: int, arguments: list[str], evidence: Path) -> None:
    artifact = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    write_json(
        BUILD_RECORD,
        {
            "schema": "wobblepad-android-build-result.v1",
            "action": action,
            "status": "passed" if exit_code == 0 else "failed",
            "exitCode": exit_code,
            "source": source_identity(),
            "command": [Path(arguments[0]).name, *arguments[1:]],
            "artifact": fingerprint(artifact) if exit_code == 0 and artifact.is_file() else None,
            "evidence": evidence.relative_to(ROOT).as_posix(),
        },
    )


def record_tests(exit_code: int, arguments: list[str], evidence: Path) -> int:
    cases = parse_test_results()
    if exit_code == 0 and not cases:
        print("No JVM unit-test cases were executed.", file=sys.stderr)
        exit_code = 1

    counts = {
        status: sum(case["status"] == status for case in cases)
        for status in ("passed", "failed", "skipped")
    }
    write_json(
        TEST_RECORD,
        {
            "schema": "wobblepad-android-test-result.v1",
            "suite": "android-jvm-unit-tests",
            "status": "passed" if exit_code == 0 else "failed",
            "exitCode": exit_code,
            "source": source_identity(),
            "command": [Path(arguments[0]).name, *arguments[1:]],
            "summary": {"total": len(cases), **counts},
            "cases": cases,
            "coverageMap": "TESTING.md",
            "evidence": evidence.relative_to(ROOT).as_posix(),
        },
    )
    for case in cases:
        if case["status"] == "failed":
            difference = case.get("difference", "no assertion details")
            print(f"FAILED {case['id']}: {difference}", file=sys.stderr)
    return exit_code


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=TASKS)
    args = parser.parse_args()

    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        parser.error(f"Gradle wrapper is missing: {wrapper}")

    if args.action == "test":
        shutil.rmtree(TEST_REPORT_ROOT, ignore_errors=True)
    exit_code, arguments, evidence = run_gradle(args.action, wrapper)
    if args.action == "test":
        return record_tests(exit_code, arguments, evidence)
    record_build(args.action, exit_code, arguments, evidence)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
