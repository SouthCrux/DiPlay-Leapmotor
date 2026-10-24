#!/usr/bin/env python3
"""Exercise a local CP battery label using original raster-gauge modules.

Produces snapshots and the exact referenced PNG subset for the Android compositor.
No phone connection, layout advertisement, vehicle writes, or production activation.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import plistlib

from probe_center_layout import verify_resources
from step_client import StepClient

HERE = Path(__file__).resolve().parent
ARCHIVE = 'ddf1317ef051f8ac251c9d892b5ea0651a75e14dd59e0f5ac2ff13b37729d504'


def inspect_scene(scene: dict, available: bool, glyph_count: int) -> set[str]:
    root = scene['scene']
    assert root['name'] == 'LocalCompositionWrapper'
    assert root['bounds']['size'] == dict(width=320, height=120)
    base, overlay = root['children']
    assert base['name'] == 'SimulatedMainBackground'
    assert overlay['name'] == 'LocalBatteryOverlay'
    assert overlay['hidden'] is not available
    assert overlay['bounds']['size'] == dict(width=280, height=80)
    references: set[str] = set()
    visible_glyphs = 0

    def multiply(left, right):
        a, b, c, d, x, y = left
        A, B, C, D, X, Y = right
        return (a*A+c*B, b*A+d*B, a*C+c*D, b*C+d*D, a*X+c*Y+x, b*X+d*Y+y)

    def geometry(node, parent):
        bounds = node['bounds']
        origin, size = bounds['origin'], bounds['size']
        position, anchor, t = node['position'], node['anchorPoint'], node['transform']
        local = multiply((1, 0, 0, 1, position['x'], position['y']),
                         tuple(t[k] for k in ('a', 'b', 'c', 'd', 'tx', 'ty')))
        local = multiply(local, (1, 0, 0, 1, -anchor['x']*size['width']-origin['x'],
                                 -anchor['y']*size['height']-origin['y']))
        world = multiply(parent, local)
        a, b, c, d, x, y = world
        corners = [(a*X+c*Y+x, b*X+d*Y+y)
                   for X in (origin['x'], origin['x']+size['width'])
                   for Y in (origin['y'], origin['y']+size['height'])]
        return world, corners

    def visit(node, visible=True, parent=(1, 0, 0, 1, 0, 0), parent_node=None, parent_corners=None):
        nonlocal visible_glyphs
        visible = visible and not node['hidden'] and node['opacity'] > 0
        world, corners = geometry(node, parent)
        texture = node.get('texture')
        if texture:
            references.add(texture['path'])
            visible_glyphs += visible
            if visible:
                # Atlas images deliberately extend outside their glyph portals. Check each whole
                # clipped portal, rather than merely accepting any changed ink within the overlay.
                assert parent_node['maskToBounds'] is True
                assert all(24 <= x <= 296 and 24 <= y <= 96 for x, y in parent_corners), parent_corners
        for child in node['children']:
            visit(child, visible, world, node, corners)

    visit(root)
    assert visible_glyphs == glyph_count, (visible_glyphs, glyph_count)
    assert references == {'r/Center_Display/Fonts/default/default.png'}
    return references


def probe(host: Path, resources: Path, output: Path) -> dict:
    verify_resources(resources, ARCHIVE)
    output.mkdir(parents=False, exist_ok=False)
    references: set[str] = set()
    with StepClient(host, resources, HERE / 'bridge.luau',
                    HERE / 'local_battery_indicator_entry.luau') as vm:
        for name, value, glyphs in [('invalid', None, 0), ('75', 75, 3),
                                    ('0', 0, 2), ('100', 100, 4), ('lost', None, 0), ('restored', 75, 3)]:
            vm.request(9, plistlib.dumps({} if value is None else {'chargePercent': value},
                                      fmt=plistlib.FMT_BINARY))
            vm.request(2, b'\x02')
            scene = json.loads(vm.request(3))
            references.update(inspect_scene(scene, value is not None, glyphs))
            scene.update(localAccessoryIndicator=True, simulatedValues=True,
                         phoneConnected=False, iphoneWidget=False, physicalPresentation=False)
            (output / f'{name}.json').write_text(json.dumps(scene, indent=2) + '\n')
    for name in references:
        target = output / 'resources' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((resources / name).read_bytes())
    verify_resources(resources, ARCHIVE)
    report = dict(originalGenericGauge=True, originalSourcesUnchanged=True,
                  sourceModules=193, cases=['invalid', '75', '0', '100', 'lost', 'restored'],
                  invalidPolicy='hide entire local indicator; no fabricated value',
                  glyphCounts=dict(invalid=0, percent75=3, percent0=2, percent100=4, lost=0, restored=3),
                  outputSize=[320, 120], localRegion=[20, 20, 280, 80],
                  fullGlyphPortalMarginPixels=4, logicalFontScale=0.5,
                  backgroundRGB=[16, 32, 48], textures=sorted(references),
                  snapshotsVerified=True, pixelsVerified=False, simulatedValues=True,
                  phoneConnected=False, iphoneWidget=False, physicalPresentation=False)
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True, type=Path)
    parser.add_argument('--resources', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(probe(args.host.resolve(strict=True), args.resources.resolve(strict=True), args.output)))
