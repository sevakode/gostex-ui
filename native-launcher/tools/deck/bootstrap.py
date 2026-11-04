#!/usr/bin/env python3
"""User-local GOSTEX launcher and bounded, atomic app-image activation.

This file is installed once from the reviewed installer. Update ZIPs are data:
their Python/shell installer files are never imported, copied over this file, or run.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import struct
import sys
import tempfile
import uuid
import zipfile

MAX_EXPANDED_BYTES = 1024 * 1024 * 1024
MAX_ARCHIVE_BYTES = 1024 * 1024 * 1024
MAX_ENTRIES = 20_000
MAX_CENTRAL_DIRECTORY_BYTES = 32 * 1024 * 1024
MAX_PENDING_BYTES = 64 * 1024
ROOT_ASSETS = {"bootstrap.py", "gostex-install.py", "gostex.svg"}


class InstallError(Exception):
    """A bundle or installation cannot be used without changing existing data."""


def default_data_dir() -> Path:
    xdg = os.environ.get("XDG_DATA_HOME", "")
    base = Path(xdg) if xdg and Path(xdg).is_absolute() else Path.home() / ".local/share"
    return base / "gostex"


def _unescape(text: str) -> str:
    output = []
    index = 0
    while index < len(text):
        char = text[index]
        index += 1
        if char != "\\":
            output.append(char)
            continue
        if index == len(text):
            raise InstallError("Incomplete properties escape")
        char = text[index]
        index += 1
        if char == "u":
            digits = text[index:index + 4]
            if len(digits) != 4 or not re.fullmatch(r"[0-9a-fA-F]{4}", digits):
                raise InstallError("Invalid properties Unicode escape")
            output.append(chr(int(digits, 16)))
            index += 4
        else:
            output.append({"t": "\t", "n": "\n", "r": "\r", "f": "\f"}.get(char, char))
    # Java Properties writes non-BMP characters as a UTF-16 surrogate pair.
    try:
        return "".join(output).encode("utf-16", "surrogatepass").decode("utf-16")
    except UnicodeError as error:
        raise InstallError("Invalid properties surrogate pair") from error


def parse_properties(text: str) -> dict[str, str]:
    """Read the UTF-8 Reader/Writer form of java.util.Properties, not JSON/INI."""
    result = {}
    logical = ""
    continuing = False
    for physical in re.split(r"\r\n|\r|\n", text):
        line = physical.lstrip(" \t\f") if continuing else physical
        if not continuing and (not line.lstrip(" \t\f") or line.lstrip(" \t\f").startswith(("#", "!"))):
            continue
        logical += line
        slashes = len(logical) - len(logical.rstrip("\\"))
        if slashes % 2:
            logical = logical[:-1]
            continuing = True
            continue
        continuing = False
        line = logical.lstrip(" \t\f")
        logical = ""
        escaped = False
        separator = len(line)
        for index, char in enumerate(line):
            if not escaped and (char in "=:" or char in " \t\f"):
                separator = index
                break
            escaped = not escaped if char == "\\" else False
        value_start = separator
        while value_start < len(line) and line[value_start] in " \t\f":
            value_start += 1
        if value_start < len(line) and line[value_start] in "=:" :
            value_start += 1
        while value_start < len(line) and line[value_start] in " \t\f":
            value_start += 1
        key = _unescape(line[:separator])
        result[key] = _unescape(line[value_start:])
    if continuing:
        raise InstallError("Incomplete properties continuation")
    return result


def _version(code: int) -> int:
    if isinstance(code, bool) or not isinstance(code, int) or not 1 <= code <= 2_147_483_647:
        raise InstallError("versionCode must be a positive 32-bit integer")
    return code


def _digest(archive: Path, expected: str) -> str:
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected):
        raise InstallError("Expected SHA-256 must contain 64 hexadecimal digits")
    if not archive.is_file() or archive.is_symlink() or archive.stat().st_size > MAX_ARCHIVE_BYTES:
        raise InstallError("Archive is missing, linked, or too large")
    digest = hashlib.sha256()
    with archive.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    actual = digest.hexdigest()
    if actual != expected.lower():
        raise InstallError("Archive SHA-256 does not match the verified release")
    return actual


@contextmanager
def _install_lock(data_dir: Path):
    data_dir.mkdir(parents=True, exist_ok=True)
    with (data_dir / ".install.lock").open("a+b") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def _pointer(data_dir: Path, name: str) -> Path | None:
    pointer = data_dir / name
    if not pointer.is_symlink():
        if pointer.exists():
            raise InstallError(f"{name} is not an installation pointer")
        return None
    try:
        target = pointer.resolve(strict=True)
        relative = target.relative_to((data_dir / "releases").resolve())
    except (OSError, ValueError, RuntimeError) as error:
        raise InstallError(f"{name} points outside installed releases") from error
    if len(relative.parts) != 2 or not relative.parts[0].isdigit() or relative.parts[1] != "GOSTEX":
        raise InstallError(f"{name} has an invalid release target")
    if not target.is_dir() or not (target / "bin/GOSTEX").is_file():
        raise InstallError(f"{name} release has no launcher")
    return target


def current_release(data_dir: Path) -> Path | None:
    return _pointer(Path(data_dir), "current")


def _replace_pointer(data_dir: Path, name: str, target: Path) -> None:
    temporary = data_dir / f".{name}-{uuid.uuid4().hex}"
    try:
        # Both operands must be canonical: macOS /var -> /private/var (or a
        # user-selected symlinked home/data directory) otherwise breaks relpath.
        temporary.symlink_to(os.path.relpath(target.resolve(), data_dir.resolve()), target_is_directory=True)
        os.replace(temporary, data_dir / name)
        _sync_dir(data_dir)
    finally:
        temporary.unlink(missing_ok=True)


def _sync_dir(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _activate(data_dir: Path, release: Path) -> None:
    try:
        old = current_release(data_dir)
    except InstallError:
        # A retained valid release can repair a damaged current launcher/pointer.
        if not (data_dir / "current").is_symlink():
            raise
        old = None
    if old == release.resolve():
        return
    if old is not None:
        _replace_pointer(data_dir, "previous", old)
    _replace_pointer(data_dir, "current", release)


def _check_zip_directory(archive: Path) -> None:
    # Bound central-directory allocation before ZipFile materializes ZipInfo objects.
    with archive.open("rb") as stream:
        size = archive.stat().st_size
        stream.seek(max(0, size - (65_535 + 22)))
        tail = stream.read(65_535 + 22)
    offset = tail.rfind(b"PK\x05\x06")
    if offset < 0 or len(tail) - offset < 22:
        raise InstallError("ZIP has no valid end record")
    _, disk, central_disk, disk_count, count, central_size, central_offset, comment_length = struct.unpack_from("<4s4H2LH", tail, offset)
    if disk or central_disk or disk_count != count or count > MAX_ENTRIES:
        raise InstallError("ZIP has too many entries or spans multiple disks")
    if count == 0xFFFF or central_size == 0xFFFFFFFF or central_offset == 0xFFFFFFFF:
        raise InstallError("ZIP64 is unnecessary for this bounded bundle format")
    if central_size > MAX_CENTRAL_DIRECTORY_BYTES or central_offset + central_size > size:
        raise InstallError("ZIP central directory is too large or invalid")
    if offset + 22 + comment_length != len(tail):
        raise InstallError("ZIP has an invalid comment or trailing data")


def _extract(archive: Path, stage: Path) -> None:
    _check_zip_directory(archive)
    try:
        with zipfile.ZipFile(archive) as bundle:
            entries = bundle.infolist()
            if len(entries) > MAX_ENTRIES:
                raise InstallError("ZIP has too many entries")
            seen = set()
            expanded = 0
            for entry in entries:
                name = entry.orig_filename
                if not name or "\x00" in name or "\\" in name or name.startswith("/"):
                    raise InstallError("ZIP contains an invalid absolute path")
                components = name.rstrip("/").split("/")
                if any(part in ("", ".", "..") for part in components):
                    raise InstallError("ZIP contains path traversal or an ambiguous path")
                normalized = str(PurePosixPath(*components))
                if normalized in seen:
                    raise InstallError("ZIP contains duplicate paths")
                seen.add(normalized)
                mode = entry.external_attr >> 16
                file_type = stat.S_IFMT(mode)
                if file_type not in (0, stat.S_IFREG, stat.S_IFDIR):
                    raise InstallError("ZIP links and special files are not supported")
                if file_type == stat.S_IFDIR and not entry.is_dir():
                    raise InstallError("ZIP directory has an invalid filename")
                if entry.flag_bits & 1 or entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                    raise InstallError("ZIP uses unsupported encryption or compression")
                if components[0] != "GOSTEX":
                    if len(components) == 1 and normalized in ROOT_ASSETS and not entry.is_dir():
                        # Release assets may be bundled for manual first install, never update bootstrap code.
                        expanded += entry.file_size
                        if expanded > MAX_EXPANDED_BYTES:
                            raise InstallError("Expanded bundle is too large")
                        continue
                    raise InstallError("ZIP must contain exactly one GOSTEX app image")
                expanded += entry.file_size
                if expanded > MAX_EXPANDED_BYTES:
                    raise InstallError("Expanded bundle is too large")
                destination = stage.joinpath(*components)
                if entry.is_dir():
                    destination.mkdir(parents=True, exist_ok=True)
                    destination.chmod(0o755)
                    continue
                destination.parent.mkdir(parents=True, exist_ok=True)
                written = 0
                with bundle.open(entry) as source, destination.open("xb") as output:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        written += len(chunk)
                        if written > entry.file_size or written > MAX_EXPANDED_BYTES:
                            raise InstallError("ZIP expands beyond its declared size")
                        output.write(chunk)
                    output.flush()
                    os.fsync(output.fileno())
                if written != entry.file_size:
                    raise InstallError("ZIP entry size does not match its contents")
                destination.chmod(0o755 if mode & 0o111 else 0o644)
    except (zipfile.BadZipFile, RuntimeError, OSError, NotImplementedError) as error:
        raise InstallError("Could not safely extract the app image") from error
    launcher = stage / "GOSTEX/bin/GOSTEX"
    runtime = stage / "GOSTEX/lib/runtime"
    if not launcher.is_file() or not runtime.is_dir():
        raise InstallError("Bundle is missing GOSTEX/bin/GOSTEX or its Java runtime")
    launcher.chmod(0o755)


def _install_archive(archive: Path, version_code: int, expected_sha256: str, data_dir: Path) -> Path:
    code = _version(version_code)
    archive = Path(archive)
    digest = _digest(archive, expected_sha256)
    releases = data_dir / "releases"
    if releases.is_symlink():
        raise InstallError("Release storage must not be a symbolic link")
    releases.mkdir(parents=True, exist_ok=True)
    target = releases / str(code)
    current = current_release(data_dir)
    if current is not None and code < int(current.parent.name):
        raise InstallError("Automatic downgrade is not allowed; use --rollback")
    if target.exists():
        if target.is_symlink() or not target.is_dir() or not (target / ".sha256").is_file():
            raise InstallError("Release directory already exists without trusted local metadata")
        if (target / ".sha256").read_text(encoding="ascii").strip() != digest:
            raise InstallError("This versionCode is already installed with different contents")
        if not (target / "GOSTEX/bin/GOSTEX").is_file() or not (target / "GOSTEX/lib/runtime").is_dir():
            raise InstallError("Existing app image is incomplete")
    else:
        stage = Path(tempfile.mkdtemp(prefix=f".install-{code}-", dir=releases))
        try:
            _extract(archive, stage)
            metadata = stage / ".sha256"
            metadata.write_text(digest + "\n", encoding="ascii")
            with metadata.open("rb") as stream:
                os.fsync(stream.fileno())
            _sync_dir(stage)
            os.replace(stage, target)
            _sync_dir(releases)
        finally:
            if stage.exists():
                shutil.rmtree(stage)
    release = target / "GOSTEX"
    _activate(data_dir, release)
    return release.resolve()


def install_archive(archive: Path, version_code: int, expected_sha256: str, data_dir: Path) -> Path:
    data_dir = Path(data_dir).resolve()
    with _install_lock(data_dir):
        return _install_archive(Path(archive), version_code, expected_sha256, data_dir)


def apply_pending(data_dir: Path) -> bool:
    data_dir = Path(data_dir).resolve()
    with _install_lock(data_dir):
        pending = data_dir / "pending.properties"
        if not pending.exists():
            return False
        if pending.is_symlink() or not pending.is_file() or pending.stat().st_size > MAX_PENDING_BYTES:
            raise InstallError("Pending update metadata is linked or too large")
        try:
            properties = parse_properties(pending.read_text(encoding="utf-8"))
            value = properties["versionCode"]
            if not re.fullmatch(r"[1-9][0-9]{0,9}", value):
                raise InstallError("Invalid pending versionCode")
            code = _version(int(value))
            if not properties["versionName"].strip():
                raise InstallError("Pending versionName is empty")
            archive = Path(properties["path"])
            updates = data_dir / "updates"
            if (updates.is_symlink() or not archive.is_absolute() or archive.is_symlink() or archive.name != f"{code}.zip"
                    or archive.resolve(strict=True).parent != updates.resolve(strict=True)):
                raise InstallError("Pending archive must be its own updates/<versionCode>.zip")
            _install_archive(archive, code, properties["sha256"], data_dir)
        except (KeyError, ValueError, OSError, UnicodeError) as error:
            raise InstallError("Pending update metadata could not be read") from error
        pending.unlink()
        _sync_dir(data_dir)
        return True


def rollback(data_dir: Path) -> Path:
    data_dir = Path(data_dir).resolve()
    with _install_lock(data_dir):
        previous = _pointer(data_dir, "previous")
        if previous is None:
            raise InstallError("No previous release is installed")
        _activate(data_dir, previous)
        # Do not immediately reapply the update the user just rolled back.
        pending = data_dir / "pending.properties"
        if pending.is_file() and not pending.is_symlink():
            os.replace(pending, data_dir / "pending.before-rollback.properties")
        return previous


def launch(data_dir: Path, arguments: list[str], use_previous: bool = False) -> None:
    if use_previous:
        rollback(data_dir)
    else:
        try:
            apply_pending(data_dir)
        except InstallError as error:
            print(f"GOSTEX update kept pending: {error}", file=sys.stderr)
    try:
        release = current_release(data_dir)
    except InstallError:
        release = rollback(data_dir)
    if release is None:
        raise InstallError("GOSTEX is not installed. Run gostex-install.py first.")
    flags = arguments if "--gaming-mode" in arguments else ["--gaming-mode", *arguments]
    try:
        executable = release / "bin/GOSTEX"
        os.environ["GOSTEX_EXECUTABLE"] = str(executable.resolve())
        os.execv(str(executable), [str(executable), *flags])
    except OSError as error:
        # exec itself failed (missing loader/launcher, permissions), so keep a usable prior image.
        if use_previous:
            raise InstallError("Could not execute the previous GOSTEX release") from error
        try:
            fallback = rollback(data_dir) / "bin/GOSTEX"
            os.environ["GOSTEX_EXECUTABLE"] = str(fallback.resolve())
            os.execv(str(fallback), [str(fallback), *flags])
        except (OSError, InstallError) as fallback_error:
            raise InstallError("Could not execute GOSTEX; inspect the installation or reinstall") from fallback_error


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Start the installed GOSTEX app image")
    parser.add_argument("--data-dir", type=Path, default=default_data_dir())
    parser.add_argument("--rollback", action="store_true", help="Switch to the retained previous release")
    options, flags = parser.parse_known_args(argv)
    if flags and flags[0] == "--":
        flags = flags[1:]
    try:
        launch(options.data_dir, flags, options.rollback)
    except (InstallError, OSError) as error:
        print(f"GOSTEX: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
