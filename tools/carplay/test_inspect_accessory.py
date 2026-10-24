import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile, ZipInfo
import warnings

from inspect_accessory import inspect, source_report


class AccessoryInspectionTest(unittest.TestCase):
    def test_lexer_ignores_comments_and_string_contents(self):
        report = source_report('''-- CP.fake()
            --[=[ CP.alsoFake() ]=]
            local text = "CP.fakeString()"
            local long = [=[ CP.fakeLong() ]=]
            local CP = CP or require "CPMock"
            local layer = CP.DPLayer.create()
            layer.mode = CP.DPLayer.BLEND.NORMAL
            self._nativeLayer:setBounds(bounds)
            self._nativeLayer.opacity = 0.5
        ''')
        self.assertEqual([r['symbol'] for r in report['cpReferences']],
                         ['CP.DPLayer.create', 'CP.DPLayer.BLEND.NORMAL'])
        self.assertEqual(['CPMock'], [r['module'] for r in report['requires']])
        self.assertEqual(['setBounds'], [r['name'] for r in report['nativeLayerMethods']])
        self.assertEqual(['opacity'], [r['name'] for r in report['nativeLayerProperties']])

    def test_archive_inventory_and_external_dependencies(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'fixture.zip'
            with ZipFile(path, 'w') as archive:
                archive.writestr('APL/BridgeVersion.lua', 'return {semver = "1.4.0"}')
                archive.writestr('main.lua', 'require "APL.BridgeVersion"; require("CPMock"); CP.setRootLayer(layer)')
                archive.writestr('image.png', b'fixture')
            report = inspect(path)
            self.assertEqual('1.4.0', report['bridgeVersion'])
            self.assertEqual(['CPMock'], report['externalModules'])
            self.assertEqual(['setRootLayer'], report['cpRootSymbols'])
            self.assertFalse(report['signatureVerified'])
            self.assertFalse(report['scriptsExecuted'])
            with self.assertRaises(ValueError):
                inspect(path, '0' * 64)

    def test_unsafe_duplicate_and_symlink_entries_are_rejected(self):
        for names in [['../main.lua'], ['/main.lua'], ['x\\main.lua'], ['main.lua', 'main.lua']]:
            with self.subTest(names=names), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / 'fixture.zip'
                with warnings.catch_warnings(), ZipFile(path, 'w') as archive:
                    warnings.simplefilter('ignore', UserWarning)
                    for name in names:
                        archive.writestr(name, 'return {}')
                with self.assertRaises(ValueError):
                    inspect(path)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'fixture.zip'
            with ZipFile(path, 'w') as archive:
                entry = ZipInfo('link.lua')
                entry.external_attr = 0o120777 << 16
                archive.writestr(entry, 'target')
            with self.assertRaises(ValueError):
                inspect(path)


if __name__ == '__main__':
    unittest.main()
