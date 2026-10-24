import copy
import json
from pathlib import Path
import tempfile
import unittest

from check_theme_contract import compare, contract, read_package


class ThemeContractTest(unittest.TestCase):
    def package(self):
        return dict(identifier="fixture", version=7, minimumSDKVersion="19.0", displays=[
            dict(id="Center_Display", type="centerConsole", size=[1920, 720], density=157),
            dict(id="Cluster_Display", type="cluster", size=[1920, 720], density=157),
        ])

    def negotiation(self):
        streams, panels = [], []
        for name, kind in [("Center_Display", 110), ("Cluster_Display", 111)]:
            streams.append(dict(uuid=name, type=kind, widthPixels=1920, heightPixels=720))
            panel = dict(uid=name, widthPixels=1920, heightPixels=720,
                         initialVideoStreams=[dict(uuid=name)])
            if kind == 111:
                panel["displayPlugins"] = [dict(uid=name, videoStreams=[name])]
            panels.append(panel)
        return dict(asset=dict(identifier="fixture", version=7, sdkVersion="19.0"), info=dict(
            displays=streams, displayPanels=panels, uiSyncInfo={}, hidDevices=[dict(displayUUID="Center_Display")]))

    def test_metadata_agreement_is_not_reported_as_runtime_or_signature_verification(self):
        report = compare(self.package(), self.negotiation())
        self.assertTrue(report["metadataMatches"])
        self.assertEqual(1, report["declaredDisplayPluginCount"])
        self.assertEqual([], report["topologyWarnings"])
        for field in ("rendererVerified", "phoneAcceptanceVerified", "signatureVerified"):
            self.assertFalse(report[field])

    def test_breaks_in_identity_canvas_plugin_controls_or_descriptor_are_detected(self):
        original = self.negotiation()
        mutations = [lambda n: n["asset"].update(version=8),
                     lambda n: n["info"]["displays"][0].update(uuid="wrong"),
                     lambda n: n["info"]["displays"][0].update(widthPixels=2560),
                     lambda n: n["info"]["displayPanels"][1]["displayPlugins"][0].update(videoStreams=[]),
                     lambda n: n["info"]["hidDevices"][0].update(displayUUID="wrong"),
                     lambda n: n["info"].pop("uiSyncInfo")]
        for mutate in mutations:
            candidate = copy.deepcopy(original)
            mutate(candidate)
            self.assertFalse(compare(self.package(), candidate)["metadataMatches"])

    def test_duplicate_metadata_and_ambiguous_or_invalid_geometry_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "duplicate.json")
            path.write_text('{"identifier":"one","identifier":"two"}')
            with self.assertRaises(ValueError):
                read_package(path)
        package = self.package()
        package["displays"][1]["id"] = "Center_Display"
        with self.assertRaises(ValueError):
            contract(package)
        package = self.package()
        package["displays"][0]["size"] = [True, 720]
        with self.assertRaises(ValueError):
            contract(package)

    def test_selected_center_matches_without_claiming_topology_or_resource_acceptance(self):
        negotiation = self.negotiation()
        negotiation['info']['displays'] = negotiation['info']['displays'][:1]
        negotiation['info']['displayPanels'] = negotiation['info']['displayPanels'][:1]
        report = compare(self.package(), negotiation, ('centerConsole',))
        self.assertTrue(report['metadataMatches'])
        self.assertEqual(0, report['declaredDisplayPluginCount'])
        self.assertEqual(1, len(report['topologyWarnings']))
        self.assertFalse(report['topologyAcceptanceVerified'])
        self.assertFalse(report['phoneAcceptanceVerified'])
        self.assertEqual(['centerConsole'], report['selectedRoles'])
        self.assertFalse(compare(self.package(), negotiation)['metadataMatches'])
        self.assertFalse(compare(self.package(), self.negotiation(), ('centerConsole',))['metadataMatches'])
        negotiation['info']['displayPanels'][0]['properties'] = ['showsInstruments']
        self.assertFalse(compare(self.package(), negotiation, ('centerConsole',))['metadataMatches'])
        for roles in [(), ('cluster',), ('unknown',), ('centerConsole', 'centerConsole')]:
            with self.assertRaises(ValueError): contract(self.package(), roles)

    def test_composed_center_requires_an_explicit_exact_plugin_binding(self):
        negotiation = self.negotiation()
        negotiation['info']['displays'] = negotiation['info']['displays'][:1]
        negotiation['info']['displayPanels'] = negotiation['info']['displayPanels'][:1]
        roles = ('centerConsole',)
        self.assertFalse(compare(self.package(), negotiation, roles, compose_center=True)['metadataMatches'])
        panel = negotiation['info']['displayPanels'][0]
        panel['displayPlugins'] = [dict(uid='Center_Display', videoStreams=['Center_Display'])]
        report = compare(self.package(), negotiation, roles, compose_center=True)
        self.assertTrue(report['metadataMatches'])
        self.assertEqual(1, report['declaredDisplayPluginCount'])
        self.assertEqual([], report['topologyWarnings'])
        self.assertFalse(report['phoneAcceptanceVerified'])
        self.assertFalse(report['topologyAcceptanceVerified'])
        self.assertFalse(compare(self.package(), negotiation, roles)['metadataMatches'])
        panel['displayPlugins'][0]['videoStreams'] = ['Cluster_Display']
        self.assertFalse(compare(self.package(), negotiation, roles, compose_center=True)['metadataMatches'])


if __name__ == "__main__":
    unittest.main()
