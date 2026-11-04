#!/usr/bin/env python3
"""Install a locally downloaded and checksum-verified Linux GOSTEX app image.

Run on the target Linux computer; uses only Python's standard library and user files.
Keep bootstrap.py beside this installer. No downloaded shell commands are evaluated.
"""
from __future__ import annotations

import argparse
import importlib.util
import os
from pathlib import Path
import platform
import sys
import tempfile


def _bootstrap_module():
    source = Path(__file__).resolve().with_name("bootstrap.py")
    if not source.is_file() or source.is_symlink():
        raise RuntimeError("Keep the reviewed bootstrap.py next to gostex-install.py")
    spec = importlib.util.spec_from_file_location("gostex_bootstrap", source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module, source


def _atomic_write(destination: Path, payload: bytes, mode: int) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}-", dir=destination.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        temporary.chmod(mode)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def _xdg(name: str, fallback: Path) -> Path:
    value = os.environ.get(name, "")
    return Path(value) if value and Path(value).is_absolute() else fallback


def _desktop_value(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")


def _exec_value(value: str) -> str:
    # Desktop Entry Exec has its own quoting, it is not parsed by a shell.
    quoted = value.replace("\\", "\\\\").replace('"', '\\"').replace("`", "\\`").replace("$", "\\$").replace("%", "%%")
    return '"' + quoted + '"'


def install_local(archive: Path, version_code: int, sha256: str, version_name: str = "") -> Path:
    bootstrap, source = _bootstrap_module()
    home = Path.home()
    data_base = _xdg("XDG_DATA_HOME", home / ".local/share")
    data = data_base / "gostex"
    wrapper = home / ".local/bin/gostex-launch"
    release = bootstrap.install_archive(archive, version_code, sha256, data)
    # The bootstrap from this explicitly invoked installer is the trusted copy.
    # ZIP-contained bootstrap/installer files never replace it during an update.
    installed_bootstrap = data / "bootstrap.py"
    _atomic_write(installed_bootstrap, source.read_bytes(), 0o644)
    launch_script = ("#!/usr/bin/env python3\n"
        "# Stable GOSTEX entry point, preserved across app-image updates.\n"
        "import os, sys\n"
        "interpreter = sys.executable\n"
        f"os.execv(interpreter, [interpreter, {str(installed_bootstrap)!r}, "
        f"'--data-dir', {str(data)!r}, *sys.argv[1:]])\n")
    _atomic_write(wrapper, launch_script.encode("utf-8"), 0o755)
    icon = data_base / "icons/hicolor/scalable/apps/gostex.svg"
    icon_source = Path(__file__).resolve().with_name("gostex.svg")
    if icon_source.is_file() and not icon_source.is_symlink():
        _atomic_write(icon, icon_source.read_bytes(), 0o644)
    else:
        # A source wordmark fallback is usable when only the two Python assets were copied.
        _atomic_write(icon, b'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256"><path fill="#101216" d="M0 0h256v256H0z"/><text x="128" y="140" text-anchor="middle" fill="#CCFF00" font-family="sans-serif" font-weight="bold" font-size="43">GOSTEX</text></svg>\n', 0o644)
    desktop = data_base / "applications/gostex.desktop"
    content = ("[Desktop Entry]\nType=Application\nVersion=1.0\nName=GOSTEX\n"
        "Comment=Your games and apps, together\nComment[es]=Tus juegos y aplicaciones, juntos\n"
        "Comment[ru]=Ваши игры и приложения вместе\n"
        f"Exec={_desktop_value(_exec_value(str(wrapper)))} --gaming-mode\n"
        f"TryExec={_desktop_value(str(wrapper))}\nIcon={_desktop_value(str(icon))}\n"
        "Terminal=false\nStartupNotify=true\nStartupWMClass=GOSTEX\nCategories=Game;AudioVideo;\n")
    _atomic_write(desktop, content.encode("utf-8"), 0o644)
    if version_name:
        _atomic_write(data / "installed-version.txt", (version_name + "\n").encode("utf-8"), 0o644)
    print(f"Installed GOSTEX {version_name or version_code}: {release}")
    print(f"Stable launcher: {wrapper}")
    print(f"Desktop entry: {desktop}")
    print("Steam Desktop: Games > Add a Non-Steam Game > GOSTEX. Leave Proton compatibility disabled.")
    print("Steam Input: D-pad → arrow keys; A → Enter; B → Escape; Y → F1.")
    return release


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--version-name", default="")
    options = parser.parse_args(argv)
    if platform.system() != "Linux":
        parser.error("Run the installer on Linux/Steam Deck with its Linux app-image ZIP")
    try:
        install_local(options.archive, options.version_code, options.sha256, options.version_name)
    except Exception as error:
        print(f"GOSTEX installation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
