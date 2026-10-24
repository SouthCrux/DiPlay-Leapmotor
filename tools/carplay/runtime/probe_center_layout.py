#!/usr/bin/env python3
"""Inspect and exercise the original reference Wallpaper with a simulated UI Sync peer.

Requires an explicitly pinned, source-only research directory. No phone, car, credentials,
resource signature verification or live-activation claim. Output is a capability contract,
protocol/frame trace and native scene, not a screenshot or physical presentation result.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import plistlib
import re
import subprocess

from step_client import StepClient

LAYOUT = '9E19BC4E-5251-5821-A0BA-26479A79F722'
HERE = Path(__file__).resolve().parent


def verify_resources(root: Path, expected: str) -> dict:
    if not re.fullmatch('[0-9a-f]{64}', expected):
        raise ValueError('Invalid selected archive digest')
    root = root.resolve(strict=True)
    manifest_path = root / 'research-input.json'
    if manifest_path.is_symlink() or manifest_path.stat().st_size > 1024 * 1024:
        raise ValueError('Invalid source manifest')
    manifest = json.loads(manifest_path.read_bytes())
    if manifest.get('archiveSha256') != expected or manifest.get('bytecodeExtracted') is not False:
        raise ValueError('Source preparation provenance differs')
    files = manifest.get('files')
    if not isinstance(files, dict) or not 1 <= len(files) <= 4096:
        raise ValueError('Invalid resource inventory')
    total = 0
    for name, digest in files.items():
        relative = PurePosixPath(name)
        if (relative.is_absolute() or not relative.parts or
                any(part in ('', '.', '..') for part in name.split('/')) or
                relative.suffix not in ('.lua', '.png', '.plist') or
                not isinstance(digest, str) or not re.fullmatch('[0-9a-f]{64}', digest)):
            raise ValueError('Unsafe source resource')
        path = root / name
        if any(parent.is_symlink() for parent in [path, *path.parents] if parent != root and root in parent.parents):
            raise ValueError('Linked source resource')
        size = path.stat().st_size
        total += size
        if size > 16 * 1024 * 1024 or total > 128 * 1024 * 1024:
            raise ValueError('Resource size limit')
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('Resource digest differs')
    for required in ('main.lua', 'r/PackageData.lua', 'LayoutController.lua', 'Workspace.lua', 'Info.plist'):
        if required not in files:
            raise ValueError('Missing original controller input')
    return manifest


def select_layout(inventory: dict) -> dict:
    if inventory.get('asset') != dict(identifier='44C9FA31EC', version=985, sdkVersion='19.0'):
        raise ValueError('Unknown reference resource profile')
    if inventory.get('display') != dict(id='Center_Display', role='centerConsole', size=[1920, 720], densityDpi=157):
        raise ValueError('Unknown output geometry or role')
    layouts = inventory.get('layouts')
    if not isinstance(layouts, list) or len(layouts) != 2 or len({x['id'] for x in layouts}) != 2:
        raise ValueError('Unexpected startup layout set')
    selected = next((x for x in layouts if x['id'] == LAYOUT), None)
    if selected is None or selected.get('layoutType') != 'primary' or selected.get('zones') != []:
        raise ValueError('Selected primary layout requires a different capability contract')
    overlay = next(x for x in layouts if x['id'] != LAYOUT)
    if overlay.get('layoutType') != 'overlay':
        raise ValueError('Unknown startup overlay')
    return selected


def video_nodes(scene: dict) -> list[dict]:
    result = []
    def visit(node, inherited=1.0):
        opacity = inherited * node['opacity'] * (not node['hidden'])
        if 'video' in node:
            result.append(dict(input=node['video'], effectiveOpacity=opacity))
        for child in node.get('children', []):
            visit(child, opacity)
    visit(scene)
    return result


def probe(host: Path, resources: Path, expected: str) -> tuple[dict, dict]:
    manifest = verify_resources(resources, expected)
    inventory_run = subprocess.run([str(host), str(resources), str(HERE/'bridge.luau'),
                                    str(HERE/'center_layout_inventory.luau')],
                                   capture_output=True, timeout=10, check=True)
    inventory = json.loads(inventory_run.stdout)
    selected = select_layout(inventory)
    trace = []
    with StepClient(host, resources, HERE/'bridge.luau', HERE/'center_wallpaper_entry.luau') as vm:
        def tick(count=1): vm.request(2, bytes([count]))
        def send(value): vm.request(1, plistlib.dumps(value, fmt=plistlib.FMT_BINARY))
        tick()
        reset = plistlib.loads(vm.request(6)[0])
        if reset['type'] != 0: raise ValueError('Original reset missing')
        send(dict(type=1, ssn=reset['ssn'], psn=1, asn=reset['psn'], version=2, id='fixture-peer'))
        tick(8)
        replies = [plistlib.loads(x) for x in vm.request(6)]
        # Simulated values, delivered through the explicit input ABI rather than the research
        # setter. The original entry declares the scene service with INVALIDVALUE at startup.
        vm.request(5, b'\x01'); vm.request(8, b'\x04'); tick(10)
        replies += [plistlib.loads(x) for x in vm.request(6)]
        requests = [plistlib.loads(x['data']) for x in replies if x['type'] == 2]
        if not any(x['type'] == 5 and x['data'].get('layoutID') == LAYOUT for x in requests):
            raise ValueError('Original selected-layout request missing')
        before = json.loads(vm.request(3))
        if not video_nodes(before['scene']) or any(x['effectiveOpacity'] != 0 for x in video_nodes(before['scene'])):
            raise ValueError('Unexpected initial video visibility')
        acknowledgment = max(x.get('psn', 0) for x in replies)
        commands = [(9, dict(isTransitioning=True)), (3, dict(layoutID=LAYOUT)),
                    (6, dict(layoutID=LAYOUT, regions=[])), (9, dict(isTransitioning=False))]
        for sequence, (kind, data) in enumerate(commands, 2):
            send(dict(type=2, ssn=reset['ssn'], psn=sequence, asn=acknowledgment,
                      data=plistlib.dumps(dict(type=kind, data=data), fmt=plistlib.FMT_BINARY)))
            tick(2)
            trace.append(dict(peerSequence=sequence, commandType=kind, simulatedPeer=True))
        event = dict(action='offer', input='iPhoneVideo', token=100, pts=11.125)
        vm.request(7, plistlib.dumps(event, fmt=plistlib.FMT_BINARY)); tick()
        scene = json.loads(vm.request(3))
        videos = video_nodes(scene['scene'])
        if len(videos) != 1 or videos[0] != dict(input=dict(active=True, identifier='iPhoneVideo', token=100, pts=11.125), effectiveOpacity=1):
            raise ValueError('Original video did not become visible at the offered frame')
        vm.request(7, plistlib.dumps(dict(action='commit', input='iPhoneVideo', token=100), fmt=plistlib.FMT_BINARY))
    verify_resources(resources, expected)
    report = dict(asset=inventory['asset'], display=inventory['display'],
                  selectedLayout=dict(id=selected['id'], name=selected['displayName'], zones=0),
                  startupLayouts=[dict(id=x['id'], name=x['displayName'], kind=x['layoutType']) for x in inventory['layouts']],
                  input=dict(identifier='iPhoneVideo', width=1920, height=720, frameDuration=1/30),
                  requiredLifecycle=['foreground ownership', 'uiControl.uiState.uiSceneState', 'UI Sync reset and layout transition'],
                  originalSourcesUnchanged=True, sourceModules=sum(x.endswith('.lua') for x in manifest['files']),
                  archiveSha256=expected, trace=trace, visibleVideo=videos,
                  simulatedPeer=True, simulatedLifecycle=True, explicitSceneInput=True, pixelsVerified=False,
                  signatureVerified=False, phoneAcceptanceVerified=False, physicalPresentation=False, fullUltraUI=False)
    return report, scene


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', type=Path, required=True)
    parser.add_argument('--resources', type=Path, required=True)
    parser.add_argument('--expected-archive-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--scene', type=Path, required=True)
    args = parser.parse_args()
    report, scene = probe(args.host.resolve(strict=True), args.resources.resolve(strict=True), args.expected_archive_sha256)
    args.output.write_text(json.dumps(report, indent=2)+'\n')
    args.scene.write_text(json.dumps(scene, indent=2)+'\n')
    print('Original Wallpaper requested and video visible in native scene; simulated peer/lifecycle only.')


if __name__ == '__main__': main()
