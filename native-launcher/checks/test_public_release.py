import json
import hashlib
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile

from check_public_release import check


class PublicReleaseCheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.license = self.dir / 'LICENSE'
        self.license.write_text('Synthetic license terms for test only.\n')
        self.apk = self.dir / 'test.apk'

    def tearDown(self):
        self.tmp.cleanup()

    def package(self, catalog, extras=()):
        with zipfile.ZipFile(self.apk, 'w') as apk:
            if catalog is not None:
                apk.writestr('assets/catalog.json', catalog)
            for name, contents in extras:
                apk.writestr(name, contents)

    def check(self):
        return check(self.apk, [self.license])

    def test_empty_and_metadata_only_catalogs_pass(self):
        for catalog in ([], [{'id': 'metadata', 'tree': '', 'files': []}]):
            self.package(json.dumps(catalog))
            self.assertEqual([], self.check())

    def test_personal_tree_and_files_block_without_exposing_values(self):
        for row in ({'tree': 'private-synthetic-path', 'files': []},
                    {'tree': '', 'files': ['private-synthetic-file']}):
            self.package(json.dumps([row]))
            issues = self.check()
            self.assertTrue(issues)
            self.assertNotIn('private-synthetic', str(issues))

    def test_missing_license_and_prohibited_assets_block(self):
        self.package('[]', [('assets/games/game.sfc', b'synthetic')])
        self.license.unlink()
        issues = self.check()
        self.assertEqual(2, len(issues))

    def test_missing_malformed_and_duplicate_catalog_block(self):
        for catalog in (None, '{bad', '{}', '[{"files":"bad"}]'):
            self.package(catalog)
            self.assertTrue(self.check())
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            self.package('[]', [('assets/catalog.json', '[]')])
        self.assertTrue(self.check())

    def test_invalid_archive_and_oversized_catalog_block(self):
        self.apk.write_bytes(b'invalid')
        self.assertTrue(self.check())
        self.package(' ' * (1024 * 1024 + 1))
        self.assertTrue(self.check())

    def test_artwork_requires_specific_review_for_exact_bytes(self):
        image = b'synthetic image, not a real artwork'
        name = 'assets/art/synthetic.png'
        self.package('[]', [(name, image)])
        manifest = self.dir / 'artwork-rights.json'
        self.assertTrue(check(self.apk, [self.license], manifest))
        record = {'path': name, 'sha256': hashlib.sha256(image).hexdigest(),
                  'inclusionBasis': 'Synthetic owned test image', 'evidence': 'Test-only provenance record'}
        manifest.write_text(json.dumps({'version': 1, 'approved': [record]}))
        self.assertEqual([], check(self.apk, [self.license], manifest))
        self.package('[]', [(name, image + b' modified')])
        self.assertTrue(check(self.apk, [self.license], manifest))
        record['evidence'] = ''
        manifest.write_text(json.dumps({'version': 1, 'approved': [record]}))
        self.assertTrue(check(self.apk, [self.license], manifest))

    def test_game_images_block_even_with_artwork_approval(self):
        image = b'synthetic image'
        name = 'assets/media/synthetic.png'
        manifest = self.dir / 'artwork-rights.json'
        record = {'path': name, 'sha256': hashlib.sha256(image).hexdigest(),
                  'inclusionBasis': 'Synthetic owned test image', 'evidence': 'Test-only provenance record'}
        manifest.write_text(json.dumps({'version': 1, 'approved': [record]}))
        self.package('[]', [(name, image)])
        self.assertTrue(any('bundled game image' in issue for issue in check(self.apk, [self.license], manifest)))
        self.package(json.dumps([{'tree': '', 'files': [], 'cover': 'media/synthetic.png'}]))
        self.assertTrue(any('references bundled game images' in issue for issue in self.check()))

    def test_duplicate_artwork_and_duplicate_approval_block(self):
        image = b'test artwork'
        name = 'assets/art/synthetic.png'
        manifest = self.dir / 'artwork-rights.json'
        record = {'path': name, 'sha256': hashlib.sha256(image).hexdigest(),
                  'inclusionBasis': 'Synthetic owned test image', 'evidence': 'Test-only provenance record'}
        manifest.write_text(json.dumps({'version': 1, 'approved': [record, record]}))
        self.package('[]', [(name, image)])
        self.assertTrue(check(self.apk, [self.license], manifest))
        manifest.write_text(json.dumps({'version': 1, 'approved': [record]}))
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            self.package('[]', [(name, image), (name, image)])
        self.assertTrue(check(self.apk, [self.license], manifest))


if __name__ == '__main__':
    unittest.main()
