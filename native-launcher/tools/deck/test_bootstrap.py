#!/usr/bin/env python3
"""Isolated contract tests for the user-local GOSTEX release bootstrap.

Only temporary files and fake app/runtime scripts are used. No executable from
an archive is launched, and no Steam, SSH, desktop, or device state is touched.
Run from any directory with ``python3 -m unittest discover -s tools/deck`` when
the working directory is native-launcher.
"""

from __future__ import annotations

import hashlib
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile


sys.path.insert(0, str(Path(__file__).resolve().parent))
import bootstrap  # noqa: E402


class BootstrapTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="gostex bootstrap ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.data_dir = self.root / "application data"
        self.archive_number = 0

    @staticmethod
    def archive_entry(name: str, content: bytes, mode: int = 0o644):
        entry = zipfile.ZipInfo(name)
        entry.create_system = 3
        entry.external_attr = (stat.S_IFREG | mode) << 16
        entry.compress_type = zipfile.ZIP_DEFLATED
        return entry, content

    def make_archive(self, *, extra=(), entries=None, destination=None) -> Path:
        self.archive_number += 1
        archive = destination or self.root / f"release {self.archive_number}.zip"
        archive.parent.mkdir(parents=True, exist_ok=True)
        if entries is None:
            entries = [
                self.archive_entry(
                    "GOSTEX/bin/GOSTEX",
                    f"#!/bin/sh\n# fake app {self.archive_number}\nexit 0\n".encode(),
                    0o755,
                ),
                self.archive_entry(
                    "GOSTEX/lib/runtime/bin/java",
                    b"#!/bin/sh\n# fake runtime; never executed\nexit 0\n",
                    0o755,
                ),
            ]
        with warnings.catch_warnings():
            # A duplicate entry is intentional in one malformed ZIP fixture.
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(archive, "w") as output:
                for entry, content in [*entries, *extra]:
                    output.writestr(entry, content)
        return archive

    @staticmethod
    def digest(archive: Path) -> str:
        return hashlib.sha256(archive.read_bytes()).hexdigest()

    def install(self, code: int, archive: Path | None = None) -> Path:
        archive = archive or self.make_archive()
        return bootstrap.install_archive(
            archive=archive,
            version_code=code,
            expected_sha256=self.digest(archive),
            data_dir=self.data_dir,
        )

    def expected_release(self, code: int) -> Path:
        return self.data_dir / "releases" / str(code) / "GOSTEX"

    def active_state(self):
        """Capture only published releases; validation may use scratch files."""
        links = []
        for name in ("current", "previous"):
            link = self.data_dir / name
            links.append(
                (name, link.is_symlink(), link.readlink() if link.is_symlink() else None)
            )
        current = bootstrap.current_release(self.data_dir)
        app_content = None if current is None else (current / "bin/GOSTEX").read_bytes()
        return links, current, app_content

    @staticmethod
    def escape_property(value: str) -> str:
        return "".join("\\" + char if char in "\\ :=" else char for char in value)

    def write_pending(self, code: int, archive: Path, *, sha256=None, version=None) -> Path:
        pending = self.data_dir / "pending.properties"
        pending.parent.mkdir(parents=True, exist_ok=True)
        pending.write_text(
            f"versionCode={code if version is None else version}\n"
            "versionName=Тестовая версия ñ\n"
            f"path={self.escape_property(str(archive))}\n"
            f"sha256={sha256 or self.digest(archive)}\n",
            encoding="utf-8",
        )
        return pending

    def test_current_release_is_none_before_first_install(self):
        self.assertIsNone(bootstrap.current_release(self.data_dir))

    def test_first_install_publishes_expected_layout(self):
        installed = self.install(41)

        self.assertEqual(self.expected_release(41), installed)
        self.assertEqual(installed, bootstrap.current_release(self.data_dir))
        self.assertTrue((self.data_dir / "current").is_symlink())
        self.assertEqual(installed, (self.data_dir / "current").resolve())
        self.assertTrue((installed / "bin/GOSTEX").is_file())
        self.assertTrue((installed / "lib/runtime/bin/java").is_file())
        self.assertTrue((installed / "bin/GOSTEX").stat().st_mode & stat.S_IXUSR)
        self.assertFalse((self.data_dir / "previous").is_symlink())

    def test_update_keeps_previous_release(self):
        original = self.install(41)
        original_contents = (original / "bin/GOSTEX").read_bytes()

        updated = self.install(42)

        self.assertEqual(self.expected_release(42), updated)
        self.assertEqual(updated, bootstrap.current_release(self.data_dir))
        self.assertTrue((self.data_dir / "previous").is_symlink())
        self.assertEqual(original, (self.data_dir / "previous").resolve())
        self.assertEqual(original_contents, (original / "bin/GOSTEX").read_bytes())

    def test_rollback_twice_toggles_current_and_previous(self):
        original = self.install(41)
        updated = self.install(42)

        self.assertEqual(original, bootstrap.rollback(self.data_dir))
        self.assertEqual(original, bootstrap.current_release(self.data_dir))
        self.assertEqual(updated, (self.data_dir / "previous").resolve())

        self.assertEqual(updated, bootstrap.rollback(self.data_dir))
        self.assertEqual(updated, bootstrap.current_release(self.data_dir))
        self.assertEqual(original, (self.data_dir / "previous").resolve())

    def rejected_version(self) -> int:
        marker = self.data_dir / "rejected-version.properties"
        return int(bootstrap.parse_properties(marker.read_text(encoding="utf-8"))["versionCode"])

    def test_rollback_blocks_recreated_pending_version_and_accepts_newer_release(self):
        archive41 = self.make_archive(destination=self.data_dir / "updates" / "41.zip")
        original = self.install(41, archive41)
        archive42 = self.make_archive(destination=self.data_dir / "updates" / "42.zip")
        self.install(42, archive42)

        self.assertEqual(original, bootstrap.rollback(self.data_dir))
        self.assertEqual(42, self.rejected_version())
        before = self.active_state()

        # Keep the original ZIP bytes, so a rejected version cannot be confused
        # with a conflicting archive that happens to use the same versionCode.
        for code, archive in ((41, archive41), (42, archive42)):
            with self.subTest(rejected_code=code):
                archive_digest = self.digest(archive)
                pending = self.write_pending(code, archive)
                metadata = pending.read_bytes()

                self.assertFalse(bootstrap.apply_pending(self.data_dir))

                self.assertEqual(before, self.active_state())
                self.assertFalse(pending.exists())
                self.assertEqual(
                    metadata,
                    (self.data_dir / "pending.rejected.properties").read_bytes(),
                )
                self.assertEqual(archive_digest, self.digest(archive))
                self.assertEqual(42, self.rejected_version())

        archive43 = self.make_archive(destination=self.data_dir / "updates" / "43.zip")
        self.write_pending(43, archive43)

        self.assertTrue(bootstrap.apply_pending(self.data_dir))
        self.assertEqual(self.expected_release(43), bootstrap.current_release(self.data_dir))
        self.assertEqual(original, (self.data_dir / "previous").resolve())
        self.assertEqual(42, self.rejected_version())

    def test_rejected_marker_is_monotonic_across_rollback_toggles(self):
        original = self.install(41)
        updated = self.install(42)

        self.assertEqual(original, bootstrap.rollback(self.data_dir))
        self.assertEqual(42, self.rejected_version())
        # Rolling away from the lower current version must retain the high mark.
        self.assertEqual(updated, bootstrap.rollback(self.data_dir))
        self.assertEqual(42, self.rejected_version())

        newer = self.install(43)
        self.assertEqual(updated, bootstrap.rollback(self.data_dir))
        self.assertEqual(43, self.rejected_version())
        self.assertEqual(newer, bootstrap.rollback(self.data_dir))
        self.assertEqual(43, self.rejected_version())

    def test_explicit_install_can_retry_a_rolled_back_version(self):
        archive41 = self.make_archive()
        original = self.install(41, archive41)
        archive42 = self.make_archive()
        updated = self.install(42, archive42)
        self.assertEqual(original, bootstrap.rollback(self.data_dir))
        self.assertEqual(42, self.rejected_version())

        self.assertEqual(updated, self.install(42, archive42))

        self.assertEqual(updated, bootstrap.current_release(self.data_dir))
        self.assertEqual(original, (self.data_dir / "previous").resolve())
        self.assertEqual(42, self.rejected_version())
        # The explicit retry bypasses only rejection, not the downgrade guard.
        before = self.active_state()
        with self.assertRaises(bootstrap.InstallError):
            self.install(41, archive41)
        self.assertEqual(before, self.active_state())

    def test_rollback_records_version_when_current_launcher_is_missing(self):
        original = self.install(41)
        broken = self.install(42)
        (broken / "bin/GOSTEX").unlink()

        self.assertEqual(original, bootstrap.rollback(self.data_dir))

        self.assertEqual(original, bootstrap.current_release(self.data_dir))
        self.assertEqual(42, self.rejected_version())

    def test_bad_hash_does_not_change_published_release(self):
        self.install(41)
        self.install(42)
        before = self.active_state()
        archive = self.make_archive()

        with self.assertRaises(bootstrap.InstallError):
            bootstrap.install_archive(archive, 43, "0" * 64, self.data_dir)

        self.assertEqual(before, self.active_state())

    def test_non_zip_does_not_change_published_release(self):
        self.install(41)
        before = self.active_state()
        archive = self.root / "not a zip.zip"
        archive.write_bytes(b"this is not an archive")

        with self.assertRaises(bootstrap.InstallError):
            self.install(42, archive)

        self.assertEqual(before, self.active_state())

    def test_traversal_entries_are_rejected(self):
        self.install(41)
        before = self.active_state()
        for name in ("../outside", "GOSTEX/../outside", "GOSTEX/lib/../../outside"):
            with self.subTest(name=name):
                archive = self.make_archive(extra=[self.archive_entry(name, b"bad")])
                with self.assertRaises(bootstrap.InstallError):
                    self.install(42, archive)
                self.assertEqual(before, self.active_state())
                self.assertFalse((self.root / "outside").exists())
                self.assertFalse((self.data_dir / "outside").exists())

    def test_absolute_entry_is_rejected_without_writing_its_target(self):
        self.install(41)
        before = self.active_state()
        outside = self.root / "absolute target"
        archive = self.make_archive(extra=[self.archive_entry(str(outside), b"bad")])

        with self.assertRaises(bootstrap.InstallError):
            self.install(42, archive)

        self.assertFalse(outside.exists())
        self.assertEqual(before, self.active_state())

    def test_symlink_entry_is_rejected(self):
        self.install(41)
        before = self.active_state()
        link = zipfile.ZipInfo("GOSTEX/lib/runtime/escape")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        archive = self.make_archive(extra=[(link, b"../../../../outside")])

        with self.assertRaises(bootstrap.InstallError):
            self.install(42, archive)

        self.assertEqual(before, self.active_state())

    def test_duplicate_entry_is_rejected(self):
        self.install(41)
        before = self.active_state()
        archive = self.make_archive(
            extra=[self.archive_entry("GOSTEX/bin/GOSTEX", b"replacement")]
        )

        with self.assertRaises(bootstrap.InstallError):
            self.install(42, archive)

        self.assertEqual(before, self.active_state())

    def test_unexpected_second_root_is_rejected(self):
        self.install(41)
        before = self.active_state()
        archive = self.make_archive(extra=[self.archive_entry("OTHER/readme", b"bad")])

        with self.assertRaises(bootstrap.InstallError):
            self.install(42, archive)

        self.assertEqual(before, self.active_state())

    def test_archive_requires_launcher_and_runtime(self):
        self.install(41)
        before = self.active_state()
        for entries in (
            [self.archive_entry("GOSTEX/bin/GOSTEX", b"#!/bin/sh\n", 0o755)],
            [self.archive_entry("GOSTEX/lib/runtime/bin/java", b"runtime", 0o755)],
        ):
            with self.subTest(entries=[entry.filename for entry, _ in entries]):
                archive = self.make_archive(entries=entries)
                with self.assertRaises(bootstrap.InstallError):
                    self.install(42, archive)
                self.assertEqual(before, self.active_state())

    def test_expanded_size_limit_is_enforced(self):
        self.install(41)
        before = self.active_state()
        archive = self.make_archive(
            extra=[self.archive_entry("GOSTEX/lib/oversized", b"x" * 128)]
        )

        with mock.patch.object(bootstrap, "MAX_EXPANDED_BYTES", 64):
            with self.assertRaises(bootstrap.InstallError):
                self.install(42, archive)

        self.assertEqual(before, self.active_state())

    def test_entry_count_limit_is_enforced(self):
        self.install(41)
        before = self.active_state()
        archive = self.make_archive(extra=[self.archive_entry("GOSTEX/readme", b"text")])

        with mock.patch.object(bootstrap, "MAX_ENTRIES", 2):
            with self.assertRaises(bootstrap.InstallError):
                self.install(42, archive)

        self.assertEqual(before, self.active_state())

    def test_apply_pending_without_a_file_is_false(self):
        self.assertFalse(bootstrap.apply_pending(self.data_dir))
        self.assertIsNone(bootstrap.current_release(self.data_dir))

    def test_apply_pending_accepts_own_archive_and_escaped_path_with_spaces(self):
        original = self.install(41)
        archive = self.make_archive(destination=self.data_dir / "updates" / "42.zip")
        self.write_pending(42, archive)

        self.assertTrue(bootstrap.apply_pending(self.data_dir))

        self.assertEqual(self.expected_release(42), bootstrap.current_release(self.data_dir))
        self.assertEqual(original, (self.data_dir / "previous").resolve())

    def test_apply_pending_rejects_outside_archive(self):
        self.install(41)
        self.install(42)
        before = self.active_state()
        archive = self.make_archive(destination=self.root / "outside update.zip")
        self.write_pending(43, archive)

        with self.assertRaises(bootstrap.InstallError):
            bootstrap.apply_pending(self.data_dir)

        self.assertTrue(archive.is_file())
        self.assertEqual(before, self.active_state())

    def test_apply_pending_rejects_wrong_own_archive_filename(self):
        self.install(41)
        before = self.active_state()
        archive = self.make_archive(destination=self.data_dir / "updates" / "43.zip")
        self.write_pending(42, archive)

        with self.assertRaises(bootstrap.InstallError):
            bootstrap.apply_pending(self.data_dir)

        self.assertEqual(before, self.active_state())

    def test_apply_pending_rejects_symlink_to_outside_archive(self):
        self.install(41)
        before = self.active_state()
        outside = self.make_archive(destination=self.root / "outside update.zip")
        archive = self.data_dir / "updates" / "42.zip"
        archive.parent.mkdir(parents=True, exist_ok=True)
        archive.symlink_to(outside)
        self.write_pending(42, archive)

        with self.assertRaises(bootstrap.InstallError):
            bootstrap.apply_pending(self.data_dir)

        self.assertEqual(before, self.active_state())

    def test_apply_pending_bad_hash_keeps_current_and_previous(self):
        self.install(41)
        self.install(42)
        before = self.active_state()
        archive = self.make_archive(destination=self.data_dir / "updates" / "43.zip")
        self.write_pending(43, archive, sha256="0" * 64)

        with self.assertRaises(bootstrap.InstallError):
            bootstrap.apply_pending(self.data_dir)

        self.assertEqual(before, self.active_state())

    def test_apply_pending_invalid_version_keeps_current(self):
        self.install(41)
        before = self.active_state()
        archive = self.make_archive(destination=self.data_dir / "updates" / "42.zip")
        for version in ("not-an-integer", "-1", "0"):
            with self.subTest(version=version):
                self.write_pending(42, archive, version=version)
                with self.assertRaises(bootstrap.InstallError):
                    bootstrap.apply_pending(self.data_dir)
                self.assertEqual(before, self.active_state())

    def test_parse_properties_handles_utf8_java_escapes_and_continuations(self):
        parsed = bootstrap.parse_properties(
            "# ignored comment\n"
            "  ! ignored comment\n"
            "simple=value\n"
            "  white : mixed\n"
            r"escaped\ key\:part\=x = spaces\ and\:equals\=" "\n"
            r"unicode=\u041c\u0438\u0440" "\n"
            "literalUtf8=Версия ñ\n"
            r"slash=C\:\\Tools\\GOSTEX" "\n"
            "continuation=hello\\\n"
            "  world\n"
            r"controls=line\nnext\tvalue\rf" "\n"
            "duplicate=first\n"
            "duplicate=last\n"
            "standalone\n"
        )

        self.assertEqual(
            {
                "simple": "value",
                "white": "mixed",
                "escaped key:part=x": "spaces and:equals=",
                "unicode": "Мир",
                "literalUtf8": "Версия ñ",
                "slash": "C:\\Tools\\GOSTEX",
                "continuation": "helloworld",
                "controls": "line\nnext\tvalue\rf",
                "duplicate": "last",
                "standalone": "",
            },
            parsed,
        )


if __name__ == "__main__":
    unittest.main()
