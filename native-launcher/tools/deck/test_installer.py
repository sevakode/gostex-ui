#!/usr/bin/env python3
"""User-local install and exec recovery tests; never start a real application."""

from __future__ import annotations

import hashlib
import importlib.util
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest import mock
import zipfile


TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import bootstrap  # noqa: E402


def load_installer():
    spec = importlib.util.spec_from_file_location("gostex_test_installer", TOOLS / "gostex-install.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ExecIntercept(BaseException):
    """Model a successful exec that never returns to the calling Python code."""


class InstallerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="gostex installer ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.home = self.root / "home % $ with spaces"
        self.xdg = self.root / "xdg % $ with spaces"
        self.data = self.xdg / "gostex"
        self.wrapper = self.home / ".local/bin/gostex-launch"
        self.desktop = self.xdg / "applications/gostex.desktop"
        self.icon = self.xdg / "icons/hicolor/scalable/apps/gostex.svg"
        environment = mock.patch.dict(
            os.environ,
            {"HOME": str(self.home), "XDG_DATA_HOME": str(self.xdg)},
            clear=True,
        )
        environment.start()
        self.addCleanup(environment.stop)
        self.installer = load_installer()

    def archive(self, code: int):
        archive = self.root / f"release {code}.zip"
        with zipfile.ZipFile(archive, "w") as bundle:
            for name, payload, mode in (
                ("GOSTEX/bin/GOSTEX", f"#!/bin/sh\n# fake {code}\nexit 0\n".encode(), 0o755),
                ("GOSTEX/lib/runtime/bin/java", b"#!/bin/sh\nexit 0\n", 0o755),
                # Bundled update code must never replace the reviewed local source.
                ("bootstrap.py", b"raise RuntimeError('untrusted ZIP code')\n", 0o644),
            ):
                entry = zipfile.ZipInfo(name)
                entry.create_system = 3
                entry.external_attr = (stat.S_IFREG | mode) << 16
                bundle.writestr(entry, payload)
        return archive, hashlib.sha256(archive.read_bytes()).hexdigest()

    def local_install(self, code: int, name: str = "") -> Path:
        archive, digest = self.archive(code)
        with mock.patch("builtins.print"):
            return self.installer.install_local(archive, code, digest, name)

    def publish(self, code: int, data: Path | None = None) -> Path:
        archive, digest = self.archive(code)
        return bootstrap.install_archive(archive, code, digest, data or self.data)

    def test_install_creates_user_layout_trusted_bootstrap_and_quoted_desktop_entry(self):
        release = self.local_install(41, "0.33.0 — Тест")

        self.assertEqual(self.data / "releases/41/GOSTEX", release)
        self.assertEqual(release, bootstrap.current_release(self.data))
        self.assertTrue(self.wrapper.is_file())
        self.assertTrue(self.wrapper.stat().st_mode & stat.S_IXUSR)
        self.assertEqual(
            (TOOLS / "bootstrap.py").read_bytes(),
            (self.data / "bootstrap.py").read_bytes(),
        )
        self.assertEqual("0.33.0 — Тест\n", (self.data / "installed-version.txt").read_text("utf-8"))
        self.assertTrue(self.icon.is_file())
        self.assertIn(b"<svg", self.icon.read_bytes())
        entry = self.desktop.read_text("utf-8")
        # Two backslashes survive Desktop Entry value decoding as one Exec escape.
        expected_exec = f'Exec="{self.root}/home %% \\\\$ with spaces/.local/bin/gostex-launch" --gaming-mode\n'
        self.assertIn(expected_exec, entry)
        self.assertIn(f"TryExec={self.wrapper}\n", entry)
        self.assertIn(f"Icon={self.icon}\n", entry)
        self.assertIn("StartupWMClass=GOSTEX\n", entry)
        self.assertFalse((self.home / ".local/share/gostex").exists())

    def test_update_keeps_stable_wrapper_and_forwards_literal_wrapper_arguments(self):
        original = self.local_install(41, "0.33.0")
        wrapper_contents = self.wrapper.read_bytes()
        desktop_contents = self.desktop.read_bytes()

        updated = self.local_install(42, "0.34.0")

        self.assertEqual(updated, bootstrap.current_release(self.data))
        self.assertEqual(original, (self.data / "previous").resolve())
        self.assertEqual(wrapper_contents, self.wrapper.read_bytes())
        self.assertEqual(desktop_contents, self.desktop.read_bytes())
        self.assertEqual("0.34.0\n", (self.data / "installed-version.txt").read_text("utf-8"))
        arguments = ["--gaming-mode", "two words", "$HOME", "100%", ""]
        runtime_interpreter = str(self.root / "runtime python" / "python3")
        with mock.patch.object(sys, "argv", [str(self.wrapper), *arguments]), \
                mock.patch.object(sys, "executable", runtime_interpreter), \
                mock.patch.object(os, "execv", side_effect=ExecIntercept) as execute:
            with self.assertRaises(ExecIntercept):
                exec(compile(wrapper_contents, str(self.wrapper), "exec"), {"__name__": "__main__"})
        execute.assert_called_once_with(
            runtime_interpreter,
            [runtime_interpreter, str(self.data / "bootstrap.py"), "--data-dir", str(self.data), *arguments],
        )

    def test_launch_passes_literal_argv_and_real_executable_environment(self):
        release = self.publish(41)
        executable = str(release / "bin/GOSTEX")
        literals = ["two words", "$HOME", "100%", 'a"quote', ";not a shell", ""]
        for arguments, expected in (
            (literals, ["--gaming-mode", *literals]),
            (["--gaming-mode", *literals], ["--gaming-mode", *literals]),
        ):
            with self.subTest(arguments=arguments):
                with mock.patch.object(os, "execv", side_effect=ExecIntercept) as execute:
                    with self.assertRaises(ExecIntercept):
                        bootstrap.launch(self.data, arguments)
                execute.assert_called_once_with(executable, [executable, *expected])
                self.assertEqual(executable, os.environ["GOSTEX_EXECUTABLE"])
                self.assertEqual(release, bootstrap.current_release(self.data))

    def test_exec_oserror_falls_back_to_previous_and_updates_environment(self):
        self.data.mkdir(parents=True)
        alias = self.root / "data alias with spaces"
        alias.symlink_to(self.data, target_is_directory=True)
        original = self.publish(41, alias)
        updated = self.publish(42, alias)
        arguments = ["a literal $value", ""]
        first_executable = str(updated / "bin/GOSTEX")
        fallback_executable = str(original / "bin/GOSTEX")
        flags = ["--gaming-mode", *arguments]

        with mock.patch.object(
            os, "execv", side_effect=[OSError("simulated missing loader"), ExecIntercept()]
        ) as execute:
            with self.assertRaises(ExecIntercept):
                bootstrap.launch(alias, arguments)

        self.assertEqual(
            [
                mock.call(first_executable, [first_executable, *flags]),
                mock.call(fallback_executable, [fallback_executable, *flags]),
            ],
            execute.call_args_list,
        )
        self.assertEqual(original, bootstrap.current_release(self.data))
        self.assertEqual(updated, (self.data / "previous").resolve())
        self.assertEqual(fallback_executable, os.environ["GOSTEX_EXECUTABLE"])
        # A second activation through the alias must not create pointer cycles.
        self.assertEqual(updated, bootstrap.rollback(alias))
        self.assertEqual(updated, bootstrap.current_release(alias))
        self.assertEqual(original, (self.data / "previous").resolve())

    def test_missing_current_launcher_is_repaired_from_previous_before_exec(self):
        original = self.publish(41)
        updated = self.publish(42)
        (updated / "bin/GOSTEX").unlink()
        executable = str(original / "bin/GOSTEX")

        with mock.patch.object(os, "execv", side_effect=ExecIntercept) as execute:
            with self.assertRaises(ExecIntercept):
                bootstrap.launch(self.data, ["--gaming-mode"])

        self.assertEqual(original, bootstrap.current_release(self.data))
        execute.assert_called_once_with(executable, [executable, "--gaming-mode"])
        self.assertEqual(executable, os.environ["GOSTEX_EXECUTABLE"])


if __name__ == "__main__":
    unittest.main()
