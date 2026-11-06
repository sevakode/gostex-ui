import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

MODULE = Path(__file__).resolve().parents[1] / 'tools/release/carry_forward_linux.py'
spec = importlib.util.spec_from_file_location('carry_forward_linux', MODULE)
carry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(carry)


class CarryForwardLinuxTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.keys = tempfile.TemporaryDirectory()
        cls.private = Path(cls.keys.name) / 'private.pem'
        cls.public = Path(cls.keys.name) / 'public.der'
        subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048',
                        '-out', str(cls.private)], check=True, capture_output=True)
        subprocess.run(['openssl', 'pkey', '-in', str(cls.private), '-pubout', '-outform', 'DER',
                        '-out', str(cls.public)], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.keys.cleanup()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.source, self.dest = self.root / 'old', self.root / 'new'
        self.source.mkdir()
        self.archive = self.source / 'gostex-linux-x64-0.33.4.zip'
        self.archive.write_bytes(b'synthetic linux archive')
        for name in carry.EXTRAS:
            (self.source / name).write_bytes(('synthetic ' + name).encode())
        self.fields = {'schema.version': '1', 'platform': 'linux-x64', 'versionCode': '100004',
                       'versionName': '0.33.4', 'url': 'https://github.com/owner/project/releases/download/build-4/' + self.archive.name,
                       'sha256': hashlib.sha256(self.archive.read_bytes()).hexdigest(),
                       'size': str(self.archive.stat().st_size)}
        self.sign()

    def tearDown(self):
        self.tmp.cleanup()

    def sign(self, suffix=''):
        text = ''.join(f'{key}={value}\n' for key, value in self.fields.items()) + suffix
        feed = self.source / carry.FEED
        feed.write_text(text)
        subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(self.private), '-out',
                        str(feed) + '.sig', str(feed)], check=True, capture_output=True)

    def run_carry(self):
        return carry.carry(self.source, self.dest, 'owner/project', 'build-4', self.public)

    def test_exact_assets_copied_and_immutable_url_retained(self):
        names = self.run_carry()
        self.assertEqual(7, len(names))
        for name in names:
            self.assertEqual((self.source / name).read_bytes(), (self.dest / name).read_bytes())
        self.assertIn('/releases/download/build-4/', (self.dest / carry.FEED).read_text())

    def test_modified_feed_or_signature_is_rejected(self):
        (self.source / carry.FEED).write_text('tampered')
        with self.assertRaises(subprocess.CalledProcessError): self.run_carry()
        self.sign()
        (self.source / (carry.FEED + '.sig')).write_bytes(b'fake')
        with self.assertRaises(subprocess.CalledProcessError): self.run_carry()

    def test_wrong_archive_size_or_digest_rejected(self):
        self.archive.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'size'): self.run_carry()
        self.archive.write_bytes(b'x' * int(self.fields['size']))
        with self.assertRaisesRegex(ValueError, 'checksum'): self.run_carry()

    def test_wrong_origin_schema_and_duplicate_fields_rejected(self):
        for key, value in [('url', self.fields['url'].replace('owner/project', 'other/project')),
                           ('url', self.fields['url'].replace('/build-4/', '/latest/')),
                           ('platform', 'android-arm64'), ('versionCode', '0')]:
            old = self.fields[key]; self.fields[key] = value; self.sign()
            with self.assertRaises(ValueError): self.run_carry()
            self.fields[key] = old
        self.sign('size=4\n')
        with self.assertRaises(ValueError): self.run_carry()

    def test_missing_or_symlink_asset_and_overwrite_rejected(self):
        extra = self.source / carry.EXTRAS[0]
        extra.unlink()
        with self.assertRaises(ValueError): self.run_carry()
        extra.symlink_to(self.archive)
        with self.assertRaises(ValueError): self.run_carry()
        extra.unlink(); extra.write_bytes(b'valid')
        self.dest.mkdir(); (self.dest / carry.FEED).write_bytes(b'existing')
        with self.assertRaises(ValueError): self.run_carry()

    def test_deck_installer_is_required(self):
        (self.source / 'gostex-deck-install.py').unlink()
        with self.assertRaisesRegex(ValueError, 'gostex-deck-install.py'):
            self.run_carry()

    def test_older_origin_is_verified_before_copying(self):
        def fake_download(repo, tag, name, destination):
            self.assertEqual(('owner/project', 'build-4', self.archive.name), (repo, tag, name))
            (destination / name).write_bytes(self.archive.read_bytes())
        with mock.patch.object(carry, 'download_origin', side_effect=fake_download):
            carry.carry(self.source, self.dest, 'owner/project', 'build-9', self.public)
        self.assertEqual(self.archive.read_bytes(), (self.dest / self.archive.name).read_bytes())
        self.dest.rename(self.root / 'first-copy')
        with mock.patch.object(carry, 'download_origin', side_effect=subprocess.CalledProcessError(1, 'gh')):
            with self.assertRaises(subprocess.CalledProcessError):
                carry.carry(self.source, self.dest, 'owner/project', 'build-9', self.public)


if __name__ == '__main__':
    unittest.main()
