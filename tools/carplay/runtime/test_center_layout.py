"""Selected-layout and prepared-resource boundary tests; no hardware or private resource dependency."""
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest

from probe_center_layout import LAYOUT, probe, select_layout, verify_resources


class CenterContractTest(unittest.TestCase):
    def inventory(self):
        return dict(asset=dict(identifier='44C9FA31EC', version=985, sdkVersion='19.0'),
                    display=dict(id='Center_Display', role='centerConsole', size=[1920, 720], densityDpi=157),
                    layouts=[dict(id=LAYOUT, displayName='Wallpaper', layoutType='primary', zones=[]),
                             dict(id='overlay', displayName='Overlay', layoutType='overlay', zones=[])])

    def test_reference_primary_is_selected_without_inventing_an_instrument_layout(self):
        self.assertEqual(LAYOUT, select_layout(self.inventory())['id'])
        for field, replacement in [('role', 'cluster'), ('size', [2560, 1176])]:
            data = self.inventory(); data['display'][field] = replacement
            with self.assertRaises(ValueError): select_layout(data)

    def test_different_resources_zones_and_ambiguous_layouts_need_a_new_contract(self):
        mutations = [lambda d: d['asset'].update(version=986),
                     lambda d: d['layouts'][0].update(zones=[{'instrument': 'speed'}]),
                     lambda d: d['layouts'].append(copy.deepcopy(d['layouts'][0])),
                     lambda d: d['layouts'][1].update(layoutType='primary')]
        for mutate in mutations:
            data = self.inventory(); mutate(data)
            with self.assertRaises(ValueError): select_layout(data)

    def test_pinned_prepared_sources_reject_changes_and_unsafe_or_linked_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = {}
            for name in ['main.lua', 'r/PackageData.lua', 'LayoutController.lua', 'Workspace.lua', 'Info.plist']:
                path = root/name; path.parent.mkdir(exist_ok=True); path.write_bytes(b'fixture')
                files[name] = hashlib.sha256(path.read_bytes()).hexdigest()
            manifest = dict(archiveSha256='0'*64, bytecodeExtracted=False, files=files)
            def save(): (root/'research-input.json').write_text(json.dumps(manifest))
            save(); self.assertEqual(manifest, verify_resources(root, '0'*64))
            with self.assertRaises(ValueError): verify_resources(root, '1'*64)
            (root/'main.lua').write_bytes(b'changed')
            with self.assertRaises(ValueError): verify_resources(root, '0'*64)
            (root/'main.lua').write_bytes(b'fixture')
            for name in ['../outside.lua', '/outside.lua', 'opaque.luac', 'r//asset.lua']:
                manifest['files'][name] = '0'*64; save()
                with self.assertRaises(ValueError): verify_resources(root, '0'*64)
                del manifest['files'][name]
            (root/'alias.lua').symlink_to(root/'main.lua')
            manifest['files']['alias.lua'] = files['main.lua']; save()
            with self.assertRaises(ValueError): verify_resources(root, '0'*64)


@unittest.skipUnless(os.environ.get('DIPLAY_ULTRA_TEST_HOST') and os.environ.get('DIPLAY_ULTRA_TEST_RESOURCES'),
                     'Explicit research host and original prepared resources required')
class OriginalCenterTest(unittest.TestCase):
    def test_original_controller_requests_wallpaper_and_applies_video_visibility(self):
        report, scene = probe(Path(os.environ['DIPLAY_ULTRA_TEST_HOST']), Path(os.environ['DIPLAY_ULTRA_TEST_RESOURCES']),
                              'ddf1317ef051f8ac251c9d892b5ea0651a75e14dd59e0f5ac2ff13b37729d504')
        self.assertEqual(193, report['sourceModules'])
        self.assertTrue(report['originalSourcesUnchanged'])
        self.assertTrue(report['explicitSceneInput'])
        self.assertEqual([9, 3, 6, 9], [x['commandType'] for x in report['trace']])
        self.assertEqual(1, report['visibleVideo'][0]['effectiveOpacity'])
        self.assertFalse(report['phoneAcceptanceVerified'] or report['physicalPresentation'] or report['fullUltraUI'])
        self.assertFalse(scene['fullUltraUI'])


if __name__ == '__main__': unittest.main()
