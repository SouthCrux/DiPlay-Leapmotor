"""Original-controller SETTINGS input proof; explicitly selected local host/resources only."""
import json
import os
import plistlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from probe_center_layout import HERE, verify_resources
from probe_settings_input import probe
from step_client import StepClient

DIGEST = 'ddf1317ef051f8ac251c9d892b5ea0651a75e14dd59e0f5ac2ff13b37729d504'


@unittest.skipUnless(os.environ.get('DIPLAY_ULTRA_TEST_HOST') and os.environ.get('DIPLAY_ULTRA_TEST_RESOURCES'),
                     'Explicit research host and original prepared resources required')
class SettingsInputTest(unittest.TestCase):
    def setUp(self):
        self.host = Path(os.environ['DIPLAY_ULTRA_TEST_HOST']).resolve(strict=True)
        self.resources = Path(os.environ['DIPLAY_ULTRA_TEST_RESOURCES']).resolve(strict=True)
        verify_resources(self.resources, DIGEST)

    def run_entry(self, body):
        with tempfile.TemporaryDirectory() as directory:
            entry = Path(directory)/'settings-kind-fixture.luau'
            entry.write_text(body)
            return subprocess.run([str(self.host), str(self.resources), str(HERE/'bridge.luau'), str(entry)],
                                  capture_output=True, timeout=10)

    def test_default_iphone_input_and_explicit_settings_are_distinct_types(self):
        run = self.run_entry('''
Offscreen.configureVideoInput("DefaultPhone",1920,720,1/30)
Offscreen.configureVideoInput("ExplicitPhone",1920,720,1/30,3)
Offscreen.configureVideoInput("SyntheticVehicleSettings",1650,720,1/30,1)
return require("APL.json").encode(CP.enumerateInputStreams())
''')
        self.assertEqual(0, run.returncode)
        streams = {x['identifier']: x for x in json.loads(run.stdout)}
        self.assertEqual(3, streams['DefaultPhone']['type'])
        self.assertEqual(3, streams['ExplicitPhone']['type'])
        self.assertEqual(1, streams['SyntheticVehicleSettings']['type'])
        self.assertEqual((1650, 720), (streams['SyntheticVehicleSettings']['width'], streams['SyntheticVehicleSettings']['height']))

    def test_settings_registered_first_cannot_own_the_phone_presentation_clock(self):
        run = self.run_entry('''
Offscreen.configureVideoInput("SettingsFirst",1650,720,1/30,1)
Offscreen.configureVideoInput("FirstPhone",1920,720,1/30,3)
Offscreen.configureVideoInput("SecondPhone",1920,720,1/30,3)
local before=CP.PTS
Offscreen.videoFrame(CP.createPlist({action="offer",input="SettingsFirst",token=1,pts=99}))
local settings=CP.PTS
Offscreen.videoFrame(CP.createPlist({action="offer",input="FirstPhone",token=1,pts=12}))
local phone=CP.PTS
Offscreen.videoFrame(CP.createPlist({action="offer",input="SecondPhone",token=1,pts=99}))
return require("APL.json").encode({before=before,settings=settings,phone=phone,secondPhone=CP.PTS})
''')
        self.assertEqual(0, run.returncode)
        self.assertEqual(dict(before=0, settings=0, phone=12, secondPhone=12), json.loads(run.stdout))

    def test_other_input_kinds_are_rejected_instead_of_advertising_unsupported_sources(self):
        for value in ('0', '2', '4', '-1', '1.5', '"1"', 'false'):
            with self.subTest(kind=value):
                run = self.run_entry(f'Offscreen.configureVideoInput("Unsupported",1650,720,1/30,{value})\nreturn "unexpected"')
                self.assertNotEqual(0, run.returncode)

    def test_original_settings_gauge_presents_offered_token_and_dismisses_without_oem_app(self):
        report, scene = probe(self.host, self.resources, DIGEST)
        self.assertEqual(193, report['sourceModules'])
        self.assertTrue(report['originalSourcesUnchanged'])
        self.assertTrue(report['initiallyHidden'])
        self.assertEqual(1, report['presented']['effectiveOpacity'])
        self.assertEqual(101, report['presented']['input']['token'])
        self.assertEqual(0, report['dismissed']['effectiveOpacity'])
        self.assertTrue(report['syntheticSource'] and report['simulatedPeer'])
        self.assertFalse(report['oemApplicationIntegrated'] or report['liveEntryChanged'] or report['pixelsVerified']
                         or report['phoneAcceptanceVerified'] or report['physicalPresentation'] or report['fullUltraUI'])
        self.assertFalse(scene['fullUltraUI'])

    def test_clear_retires_only_local_pixels_and_preserves_token_and_pts_watermarks(self):
        with tempfile.TemporaryDirectory() as directory:
            entry = Path(directory)/'clear-settings.luau'
            entry.write_text('''
Offscreen.configureVideoInput("Settings",1650,720,1/30,1)
Offscreen.configureVideoInput("iPhoneVideo",1920,720,1/30)
return "initialized"
''')
            def event(vm, action, stream='Settings', **fields):
                return vm.request(7, plistlib.dumps(dict(action=action, input=stream, **fields), fmt=plistlib.FMT_BINARY))
            for stale in ({'token': 1, 'pts': 11.0}, {'token': 2, 'pts': 10.0}):
                with self.subTest(stale=stale), StepClient(self.host, self.resources, HERE/'bridge.luau', entry) as vm:
                    event(vm, 'offer', token=1, pts=11.0)
                    event(vm, 'commit', token=1)
                    event(vm, 'offer', 'iPhoneVideo', token=10, pts=12.0)
                    event(vm, 'commit', 'iPhoneVideo', token=10)
                    state = event(vm, 'clear')
                    self.assertEqual(0, state['currentToken'])
                    self.assertEqual(0, state['pendingToken'])
                    self.assertTrue(state['flushed'])
                    self.assertEqual(10, event(vm, 'state', 'iPhoneVideo')['currentToken'])
                    with self.assertRaises(RuntimeError): event(vm, 'offer', **stale)
            with StepClient(self.host, self.resources, HERE/'bridge.luau', entry) as vm:
                event(vm, 'offer', token=1, pts=11.0)
                event(vm, 'clear')
                state = event(vm, 'offer', token=2, pts=12.0)
                self.assertFalse(state['flushed'])
                self.assertEqual(2, state['pendingToken'])

    def test_snapshot_before_the_original_root_exists_reports_unavailability_without_fake_pixels(self):
        with tempfile.TemporaryDirectory() as directory:
            entry = Path(directory)/'no-root.luau'
            entry.write_text('return "initialized"')
            with StepClient(self.host, self.resources, HERE/'bridge.luau', entry) as vm:
                report = json.loads(vm.request(3))
                self.assertEqual({'rootUnavailable': True}, report['scene'])
                self.assertFalse(report['fullUltraUI'])
                self.assertTrue(report['fixture'])


if __name__ == '__main__': unittest.main()
