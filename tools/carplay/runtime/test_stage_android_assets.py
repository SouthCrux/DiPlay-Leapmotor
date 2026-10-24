"""Synthetic packaging fixtures; no original resources, phone or credentials needed."""
import hashlib
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest.mock import patch

import stage_android_assets as assets


def sha(data):
    return hashlib.sha256(data).hexdigest()


class StagedAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='ultra-stage-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        self.output = self.root / 'assets'
        self.files = {name: b'return {}' for name in assets.REQUIRED if name.endswith('.lua')}
        self.files['r/image.png'] = b'synthetic png bytes'
        self.files['Info.plist'] = plistlib.dumps(dict(CFBundleIdentifier='44C9FA31EC',
            AccessoryContentVersion=985, CFBundleVersion='985', MinimumSDKVersion='19.0'),
            fmt=plistlib.FMT_BINARY)
        self.write_files()
        self.write_manifest()

    def write_files(self):
        for name, data in self.files.items():
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

    def write_manifest(self, change=None):
        manifest = dict(archiveSha256='a' * 64, sourceModules=4, bridgeVersionRequired='1.4.0',
            bytecodeExtracted=False, scriptsExecuted=False, signatureVerified=False,
            files={name: sha(data) for name, data in self.files.items()})
        if change:
            change(manifest)
        data = plistlib.dumps(manifest, fmt=plistlib.FMT_BINARY)
        (self.source / assets.MANIFEST).write_bytes(data)
        self.identity = assets.ResourceIdentity('a' * 64, sha(data), 4)

    def stage(self):
        return assets.stage(self.source, self.output, expected=self.identity)

    def verify(self):
        return assets.verify(self.output, expected=self.identity)

    def assert_rejected_cleanly(self):
        with self.assertRaises((ValueError, OSError)):
            self.stage()
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.root.glob('.ultra-stage-*')))
        self.assertTrue((self.source / 'main.lua').exists())

    def test_stage_copies_only_verified_inputs_and_current_repo_code(self):
        # An unrelated source file must never be copied, opened or parsed.
        (self.source / 'unlisted.private').write_text('synthetic excluded sentinel')
        result = self.stage()
        self.assertFalse(result['phoneAcceptanceVerified'])
        self.assertEqual(self.verify(), result)
        package = self.output / assets.PREFIX
        self.assertEqual((assets.HERE / 'bridge.luau').read_bytes(), (package / 'bridge.luau').read_bytes())
        self.assertEqual((assets.HERE / 'center_wallpaper_entry.luau').read_bytes(),
                         (package / 'center_wallpaper_entry.luau').read_bytes())
        self.assertFalse(any(path.name == 'unlisted.private' for path in self.output.rglob('*')))
        selection = plistlib.loads((package / 'selection.plist').read_bytes())
        self.assertEqual(assets.PROFILE['layoutId'], selection['layoutId'])
        (self.source / 'main.lua').write_text('modified after staging')
        self.assertEqual(self.verify(), result)

    def test_existing_output_is_preserved(self):
        self.output.mkdir()
        sentinel = self.output / 'keep'
        sentinel.write_text('retained')
        with self.assertRaises(ValueError):
            self.stage()
        self.assertEqual('retained', sentinel.read_text())
        self.assertEqual([], list(self.root.glob('.ultra-stage-*')))

    def test_manifest_digest_checked_before_parsing_and_cannot_be_replaced(self):
        selected = self.identity
        self.files['main.lua'] = b'return "replacement"'
        self.write_files()
        self.write_manifest()
        self.identity = selected
        with patch.object(assets.plistlib, 'loads', side_effect=AssertionError('must not parse')):
            self.assert_rejected_cleanly()

    def test_resource_digest_and_selected_metadata_are_required(self):
        (self.source / 'main.lua').write_text('tampered')
        self.assert_rejected_cleanly()
        self.write_files()
        self.files['Info.plist'] = plistlib.dumps(dict(CFBundleIdentifier='wrong',
            AccessoryContentVersion=985, CFBundleVersion='985', MinimumSDKVersion='19.0'),
            fmt=plistlib.FMT_BINARY)
        self.write_files()
        self.write_manifest()
        self.assert_rejected_cleanly()

    def test_source_only_flags_and_module_types_are_strict(self):
        for change in (lambda m: m.update(bytecodeExtracted=True),
                       lambda m: m.update(scriptsExecuted=True),
                       lambda m: m.update(signatureVerified=True),
                       lambda m: m.update(sourceModules='4'),
                       lambda m: m.update(bridgeVersionRequired='9.9.9')):
            with self.subTest(change=change):
                self.write_manifest(change)
                self.assert_rejected_cleanly()

    def test_unsafe_resource_names_and_leaf_links_rejected(self):
        for name in ('../outside.lua', '/absolute.lua', './other.lua', 'r//other.lua',
                     'r/a.luac', 'r/a.pk8', 'r/é.lua'):
            with self.subTest(name=name):
                self.write_manifest(lambda m: m['files'].update({name: 'b' * 64}))
                self.assert_rejected_cleanly()
        self.write_manifest()
        outside = self.root / 'sentinel'
        outside.write_bytes(self.files['r/image.png'])
        image = self.source / 'r/image.png'
        image.unlink()
        image.symlink_to(outside)
        self.assert_rejected_cleanly()
        self.assertEqual(self.files['r/image.png'], outside.read_bytes())

    def test_parent_links_and_missing_required_inputs_rejected(self):
        parent = self.source / 'r'
        outside = self.root / 'outside'
        parent.rename(outside)
        parent.symlink_to(outside, target_is_directory=True)
        self.assert_rejected_cleanly()
        parent.unlink()
        outside.rename(parent)
        del self.files['Workspace.lua']
        self.write_manifest()
        self.assert_rejected_cleanly()

    def test_staged_tree_rejects_extra_files_empty_directories_and_links(self):
        self.stage()
        extra = self.output / 'identity.pk8'
        extra.write_text('synthetic sentinel')
        with self.assertRaises(ValueError):
            self.verify()
        extra.unlink()
        extra.mkdir()
        with self.assertRaises(ValueError):
            self.verify()
        extra.rmdir()
        extra.symlink_to(self.source / 'main.lua')
        with self.assertRaises(ValueError):
            self.verify()
        extra.unlink()
        self.verify()

    def test_staged_code_descriptor_and_resource_tampering_rejected(self):
        self.stage()
        package = self.output / assets.PREFIX
        for relative in ('bridge.luau', 'center_wallpaper_entry.luau', 'resources/main.lua'):
            with self.subTest(relative=relative):
                path = package / relative
                original = path.read_bytes()
                path.write_bytes(b'tampered')
                with self.assertRaises(ValueError):
                    self.verify()
                path.write_bytes(original)
        descriptor = package / 'selection.plist'
        selected = plistlib.loads(descriptor.read_bytes())
        selected['width'] = 1280
        descriptor.write_bytes(plistlib.dumps(selected, fmt=plistlib.FMT_BINARY))
        with self.assertRaises(ValueError):
            self.verify()

    def test_oversized_files_manifest_and_inventory_are_bounded(self):
        self.files['main.lua'] = bytes(assets.MAX_FILE + 1)
        self.write_files()
        self.write_manifest()
        self.assert_rejected_cleanly()
        self.files['main.lua'] = b'return {}'
        self.write_files()
        self.write_manifest(lambda m: m.update(files={f'r/{i}.png': 'b' * 64 for i in range(4097)}))
        self.assert_rejected_cleanly()
        oversized = bytes(1024 * 1024 + 1)
        (self.source / assets.MANIFEST).write_bytes(oversized)
        self.identity = assets.ResourceIdentity('a' * 64, sha(oversized), 4)
        self.assert_rejected_cleanly()

    def test_partial_failure_removes_only_its_own_temp_directory(self):
        unrelated = self.root / '.ultra-stage-unrelated'
        unrelated.mkdir()
        sentinel = unrelated / 'keep'
        sentinel.write_text('retained')
        with patch.object(assets, 'verify', side_effect=ValueError('synthetic verify failure')):
            with self.assertRaises(ValueError):
                self.stage()
        self.assertFalse(self.output.exists())
        self.assertEqual([unrelated], list(self.root.glob('.ultra-stage-*')))
        self.assertEqual('retained', sentinel.read_text())

    def test_cli_reference_identity_rejects_synthetic_fixture(self):
        with self.assertRaises(ValueError):
            assets.stage(self.source, self.output)


if __name__ == '__main__':
    unittest.main()
