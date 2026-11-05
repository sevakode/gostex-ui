#!/usr/bin/env python3
"""Block known private content in an Android APK before a public release.

This narrow guard is not a comprehensive privacy, copyright or license audit.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[2]
MAX_CATALOG_BYTES = 1024 * 1024
MAX_ARTWORK_BYTES = 32 * 1024 * 1024
MAX_APPROVALS_BYTES = 2 * 1024 * 1024
ARTWORK_EXTENSIONS = frozenset({'.png', '.jpg', '.jpeg', '.webp', '.gif', '.avif'})
PRIVATE_ASSET_EXTENSIONS = frozenset({
    '.apk', '.aab', '.bin', '.cue', '.iso', '.cso', '.chd', '.sfc', '.smc', '.nes',
    '.gb', '.gbc', '.gba', '.nds', '.3ds', '.cia', '.srm', '.sav', '.zip', '.7z',
    '.rar', '.ovpn', '.pem', '.p12', '.jks', '.keystore',
})


def check_artwork(package: zipfile.ZipFile, manifest: Path | None) -> list[str]:
    # This checks a recorded inclusion decision and provenance for exact bytes.
    # It cannot establish third-party rights or determine legal permissibility.
    images = [item for item in package.infolist()
              if item.filename.startswith(('assets/media/', 'assets/art/')) and
              Path(item.filename.lower()).suffix in ARTWORK_EXTENSIONS]
    if not images:
        return []
    try:
        if manifest is None or manifest.stat().st_size > MAX_APPROVALS_BYTES:
            raise ValueError('missing or oversized artwork review')
        obj = json.loads(manifest.read_text(encoding='utf-8'))
        if not isinstance(obj, dict) or obj.get('version') != 1 or not isinstance(obj.get('approved'), list):
            raise ValueError('invalid artwork review')
        approved = {}
        for entry in obj['approved']:
            if not isinstance(entry, dict):
                raise ValueError('invalid artwork approval')
            path, digest = entry.get('path'), entry.get('sha256')
            basis, evidence = entry.get('inclusionBasis'), entry.get('evidence')
            if (not isinstance(path, str) or not path.startswith(('assets/media/', 'assets/art/')) or
                '..' in Path(path).parts or path in approved or not isinstance(digest, str) or
                not re.fullmatch(r'[a-f0-9]{64}', digest) or
                not isinstance(basis, str) or len(basis.strip()) < 12 or
                not isinstance(evidence, str) or len(evidence.strip()) < 12):
                raise ValueError('invalid artwork approval')
            approved[path] = digest
        if len({item.filename for item in images}) != len(images):
            raise ValueError('duplicate artwork')
        pending = 0
        for item in images:
            expected = approved.get(item.filename)
            if expected is None or item.file_size > MAX_ARTWORK_BYTES:
                pending += 1
                continue
            digest = hashlib.sha256()
            with package.open(item) as source:
                while chunk := source.read(1024 * 1024):
                    digest.update(chunk)
            if digest.hexdigest() != expected:
                pending += 1
        return [f'Artwork review is missing or does not match {pending} bundled image(s).'] if pending else []
    except (OSError, ValueError, TypeError, UnicodeError, zipfile.BadZipFile, RuntimeError):
        return ['Artwork review is missing or invalid.']


def check(apk: Path, license_paths: list[Path], artwork_manifest: Path | None = None) -> list[str]:
    issues = []
    try:
        if not any(path.is_file() and 32 <= path.stat().st_size <= 1_000_000 for path in license_paths):
            issues.append('Project license terms are missing or empty.')
    except OSError:
        issues.append('Project license terms cannot be read.')
    try:
        with zipfile.ZipFile(apk) as package:
            entries = package.infolist()
            catalogs = [entry for entry in entries if entry.filename == 'assets/catalog.json']
            if len(catalogs) != 1 or catalogs[0].file_size > MAX_CATALOG_BYTES:
                issues.append('APK catalog is missing, duplicated, or too large to inspect.')
            else:
                try:
                    catalog = json.loads(package.read(catalogs[0]))
                    if not isinstance(catalog, list):
                        raise ValueError('Unexpected catalog format')
                    if any(not isinstance(row, dict) or not isinstance(row.get('tree', ''), str) or
                           not isinstance(row.get('files', []), list) or row.get('tree', '') or row.get('files', [])
                           for row in catalog):
                        issues.append('APK catalog contains file references or invalid records.')
                    if any(isinstance(row, dict) and any(
                            isinstance(row.get(field), str) and row[field].startswith('media/')
                            for field in ('cover', 'scene')) for row in catalog):
                        issues.append('APK catalog references bundled game images.')
                except (ValueError, UnicodeError, zipfile.BadZipFile, RuntimeError):
                    issues.append('APK catalog cannot be safely parsed.')
            forbidden = sum(1 for entry in entries if entry.filename.startswith('assets/') and
                            Path(entry.filename.lower()).suffix in PRIVATE_ASSET_EXTENSIONS)
            if forbidden:
                issues.append(f'APK contains {forbidden} prohibited asset file(s).')
            game_images = sum(1 for entry in entries if entry.filename.startswith('assets/media/') and
                              Path(entry.filename.lower()).suffix in ARTWORK_EXTENSIONS)
            if game_images:
                issues.append(f'APK contains {game_images} bundled game image(s).')
            issues.extend(check_artwork(package, artwork_manifest))
    except (OSError, zipfile.BadZipFile, RuntimeError):
        issues.append('APK cannot be safely inspected.')
    return issues


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    args = parser.parse_args()
    license_paths = [ROOT / name for name in ('LICENSE', 'LICENSE.md', 'LICENSE.txt')]
    issues = check(args.apk, license_paths, ROOT / 'native-launcher/release/artwork-rights.json')
    if issues:
        for issue in issues:
            print('BLOCKED:', issue)
        return 1
    print('PASS: catalog references, prohibited assets, project license presence and recorded artwork review checked.')
    print('A separate provenance, rights and privacy review is still required.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
