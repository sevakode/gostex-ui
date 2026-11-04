#!/usr/bin/env python3
"""Prepare a signed HTTPS release feed. Never prints or packages a private key."""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
from urllib.parse import urlparse


def value(raw):
    return raw.replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace('=', '\\=').replace(':', '\\:')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact', required=True, type=Path)
    parser.add_argument('--platform', required=True, choices=['android-arm64', 'linux-x64'])
    parser.add_argument('--base-url', required=True, help='HTTPS URL of this release asset directory')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--private-key', required=True, type=Path)
    parser.add_argument('--version-code', type=int)
    parser.add_argument('--version-name')
    args = parser.parse_args()
    version = dict(line.split('=', 1) for line in (Path(__file__).resolve().parents[2] / 'version.properties').read_text().splitlines() if '=' in line)
    code = args.version_code if args.version_code is not None else int(version['code'])
    name = args.version_name or version['name']
    if not 1 <= code <= 2_100_000_000 or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', name):
        parser.error('Use a positive Android-compatible code and three-part numeric version')
    base = urlparse(args.base_url)
    if base.scheme != 'https' or not base.hostname or base.username or base.password or base.query or base.fragment:
        parser.error('Release asset base must use HTTPS without credentials/query/fragment')
    if not args.artifact.is_file() or not args.private_key.is_file():
        parser.error('Artifact/private key file is missing')
    extension = '.apk' if args.platform == 'android-arm64' else '.zip'
    filename = f'gostex-{args.platform}-{name}{extension}'
    args.output.mkdir(parents=True, exist_ok=True)
    artifact = args.output / filename
    if args.artifact.resolve() != artifact.resolve():
        shutil.copyfile(args.artifact, artifact)
    digest = hashlib.sha256()
    with artifact.open('rb') as stream:
        for block in iter(lambda: stream.read(128 * 1024), b''):
            digest.update(block)
    fields = {'schema.version': '1', 'platform': args.platform, 'versionCode': str(code),
              'versionName': name, 'url': args.base_url.rstrip('/') + '/' + filename,
              'sha256': digest.hexdigest(), 'size': str(artifact.stat().st_size)}
    feed = args.output / f'{args.platform}.properties'
    feed.write_text(''.join(f'{key}={value(val)}\n' for key, val in fields.items()), encoding='utf-8')
    subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(args.private_key), '-out', str(feed) + '.sig', str(feed)], check=True)
    print(f'Prepared {feed.name}, detached signature and {artifact.name}; SHA256 {digest.hexdigest()}')


if __name__ == '__main__':
    main()
