"""Behavior tests for the Windows build and deployment entrypoints."""

from __future__ import annotations

import importlib.util
import pathlib
import tempfile
import types
import unittest
import zipfile

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]


def load_script(filename: str, module_name: str) -> types.ModuleType:
    specification = importlib.util.spec_from_file_location(module_name, SCRIPTS / filename)
    if specification is None or specification.loader is None:
        raise RuntimeError(f"Cannot load {filename}")
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


BUILD = load_script("build-windows.py", "wobblepad_build_windows")
DEPLOY = load_script("deploy-windows.py", "wobblepad_deploy_windows")


class BuildWindowsTests(unittest.TestCase):
    def test_build_uses_current_locked_interpreter(self) -> None:
        command = BUILD.build_command("C:/runtime/python.exe")
        self.assertEqual(command[:3], ["C:/runtime/python.exe", "-m", "PyInstaller"])
        self.assertIn("--windowed", command)
        self.assertIn("bleak.backends.winrt.client", command)
        self.assertIn("bleak.backends.winrt.scanner", command)

    def test_archive_has_stable_application_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            source = root / "source"
            source.mkdir()
            (source / "WobblePad.exe").write_bytes(b"exe")
            output = root / "bundle.zip"
            BUILD.archive_app(source, output)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(archive.namelist(), ["WobblePad/WobblePad.exe"])
            self.assertFalse((root / ".bundle.zip.tmp").exists())

    def test_legal_files_are_copied_beside_executable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            bundle = root / "bundle"
            bundle.mkdir()
            for name in BUILD.LEGAL_FILES:
                (root / name).write_text(name, encoding="utf-8")
            dependency_licenses = {}
            for name in ("BLEAK.txt", "PYINSTALLER.txt", "PYTHON.txt"):
                source = root / f"source-{name}"
                source.write_text(name, encoding="utf-8")
                dependency_licenses[name] = source

            BUILD.add_legal_files(bundle, root, dependency_licenses)

            self.assertEqual(
                {path.name for path in bundle.iterdir() if path.is_file()},
                set(BUILD.LEGAL_FILES),
            )
            self.assertEqual(
                {path.name for path in (bundle / "THIRD_PARTY_LICENSES").iterdir()},
                set(dependency_licenses),
            )


class DeployWindowsTests(unittest.TestCase):
    def test_traversal_member_is_rejected_before_extraction(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            bundle = root / "unsafe.zip"
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("WobblePad/../../outside.txt", "unsafe")
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                DEPLOY.safe_extract(bundle, root / "output")
            self.assertFalse((root / "outside.txt").exists())

    def test_install_replaces_only_target_and_keeps_settings_sibling(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            target = root / "Programs" / "WobblePad"
            target.mkdir(parents=True)
            (target / "old.txt").write_text("old", encoding="utf-8")
            settings = root / "WobblePad" / "settings.json"
            settings.parent.mkdir()
            settings.write_text("private", encoding="utf-8")
            bundle = root / "bundle.zip"
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("WobblePad/WobblePad.exe", b"new")

            DEPLOY.install(bundle, target)

            self.assertEqual((target / "WobblePad.exe").read_bytes(), b"new")
            self.assertFalse((target / "old.txt").exists())
            self.assertEqual(settings.read_text(encoding="utf-8"), "private")
            self.assertFalse(target.with_name(".WobblePad.installing").exists())
            self.assertFalse(target.with_name(".WobblePad.backup").exists())


if __name__ == "__main__":
    unittest.main()
