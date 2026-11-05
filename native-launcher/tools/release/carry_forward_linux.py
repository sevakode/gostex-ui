#!/usr/bin/env python3
"""Copy an existing verified Linux release unchanged into an Android-only release."""
import argparse
import hashlib
import importlib.util
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.parse import urlsplit

NATIVE = Path(__file__).resolve().parents[2]
KEY = NATIVE / 'release-runtime/src/main/resources/gostex-release-public.der'
BOOTSTRAP = NATIVE / 'tools/deck/bootstrap.py'
EXTRAS = ('bootstrap.py', 'gostex-install.py', 'gostex.svg')
FEED = 'linux-x64.properties'
FIELDS = {'schema.version', 'platform', 'versionCode', 'versionName', 'url', 'sha256', 'size'}


def source_file(directory: Path, name: str, maximum: int) -> Path:
    path = directory / name
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= maximum:
        raise ValueError(f'Missing or invalid required Linux asset: {name}')
    return path


def parse_properties(text: str) -> dict[str, str]:
    # The release producer writes exactly seven simple physical lines. Reject
    # duplicates before parsing to avoid ambiguity between different clients.
    lines = text.splitlines()
    keys = [line.split('=', 1)[0] for line in lines if '=' in line]
    if len(lines) != len(FIELDS) or len(keys) != len(FIELDS) or set(keys) != FIELDS:
        raise ValueError('Unexpected Linux feed format')
    spec = importlib.util.spec_from_file_location('gostex_bootstrap_parser', BOOTSTRAP)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    parsed = module.parse_properties(text)
    if set(parsed) != FIELDS:
        raise ValueError('Unexpected Linux feed fields')
    return parsed


def verify_archive(archive: Path, size: int, expected_digest: str) -> None:
    if archive.stat().st_size != size:
        raise ValueError('Linux archive size does not match its signed feed')
    digest = hashlib.sha256()
    with archive.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    if digest.hexdigest() != expected_digest.lower():
        raise ValueError('Linux archive checksum does not match its signed feed')


def download_origin(repo: str, tag: str, name: str, destination: Path) -> None:
    subprocess.run(['gh', 'release', 'download', tag, '--repo', repo, '--dir', str(destination), '--pattern', name],
                   check=True, stdout=subprocess.DEVNULL)


def carry(source: Path, destination: Path, repo: str, source_tag: str, public_key: Path = KEY) -> list[str]:
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
        raise ValueError('Invalid repository name')
    if not re.fullmatch(r'build-[0-9]+', source_tag):
        raise ValueError('The previous release must use a versioned build tag')
    feed = source_file(source, FEED, 16 * 1024)
    signature = source_file(source, FEED + '.sig', 4096)
    with tempfile.TemporaryDirectory(prefix='gostex-linux-key-') as temporary:
        pem = Path(temporary) / 'public.pem'
        subprocess.run(['openssl', 'pkey', '-pubin', '-inform', 'DER', '-in', str(public_key), '-out', str(pem)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(pem), '-signature', str(signature), str(feed)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    props = parse_properties(feed.read_text(encoding='utf-8'))
    if props['schema.version'] != '1' or props['platform'] != 'linux-x64':
        raise ValueError('Wrong Linux feed schema or platform')
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', props['versionName']):
        raise ValueError('Invalid Linux version name')
    if not props['versionCode'].isdigit() or not 0 < int(props['versionCode']) <= 2_100_000_000:
        raise ValueError('Invalid Linux version code')
    if not props['size'].isdigit() or not 0 < int(props['size']) <= 1024 ** 3:
        raise ValueError('Invalid Linux archive size')
    if not re.fullmatch(r'[a-fA-F0-9]{64}', props['sha256']):
        raise ValueError('Invalid Linux archive digest')
    name = f"gostex-linux-x64-{props['versionName']}.zip"
    url = urlsplit(props['url'])
    parts = url.path.split('/')
    prefix = ['', *repo.split('/'), 'releases', 'download']
    if (url.scheme != 'https' or url.hostname != 'github.com' or url.username or url.password or
        url.port not in (None, 443) or url.query or url.fragment or len(parts) != 7 or
        parts[:5] != prefix or not re.fullmatch(r'build-[0-9]+', parts[5]) or parts[6] != name):
        raise ValueError('Linux feed must reference a versioned release in the same repository')
    archive = source_file(source, name, 1024 ** 3)
    verify_archive(archive, int(props['size']), props['sha256'])
    # Later Android releases may carry a Linux feed pointing to an older tag.
    # Check that immutable origin still exists and serves the signed bytes.
    if parts[5] != source_tag:
        with tempfile.TemporaryDirectory(prefix='gostex-linux-origin-') as temporary:
            origin = Path(temporary)
            download_origin(repo, parts[5], name, origin)
            verify_archive(source_file(origin, name, 1024 ** 3), int(props['size']), props['sha256'])
    names = [FEED, FEED + '.sig', name, *EXTRAS]
    files = [source_file(source, item, 1024 ** 3 if item == name else 1024 * 1024) for item in names]
    destination.mkdir(parents=True, exist_ok=True)
    if any((destination / item).exists() for item in names):
        raise ValueError('Refusing to overwrite existing release assets')
    for item in files:
        shutil.copyfile(item, destination / item.name)
    return names


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--destination', required=True, type=Path)
    parser.add_argument('--repo', required=True)
    parser.add_argument('--source-tag', required=True, help='Tag whose assets were downloaded as the source')
    args = parser.parse_args()
    try:
        copied = carry(args.source, args.destination, args.repo, args.source_tag)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f'Cannot preserve the prior Linux release: {error}')
        return 1
    print(f'Preserved {len(copied)} verified Linux release assets unchanged.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
