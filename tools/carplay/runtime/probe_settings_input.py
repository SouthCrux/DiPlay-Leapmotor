#!/usr/bin/env python3
"""Disconnected SETTINGS-input contract probe; synthetic frames/peer, no OEM application."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
from probe_center_layout import LAYOUT, HERE, verify_resources, select_layout, video_nodes
from step_client import StepClient
import subprocess

INPUT = 'SyntheticVehicleSettings'


def settings_node(scene: dict) -> dict:
    nodes = [x for x in video_nodes(scene['scene']) if x['input']['identifier'] == INPUT]
    if len(nodes) != 1:
        raise ValueError('Expected exactly one original-controller Settings gauge')
    return nodes[0]


def probe(host: Path, resources: Path, expected: str) -> tuple[dict, dict]:
    manifest = verify_resources(resources, expected)
    inventory = json.loads(subprocess.run([str(host), str(resources), str(HERE/'bridge.luau'),
        str(HERE/'center_layout_inventory.luau')], capture_output=True, timeout=10, check=True).stdout)
    select_layout(inventory)
    trace = []
    with StepClient(host, resources, HERE/'bridge.luau', HERE/'settings_input_entry.luau') as vm:
        def tick(count=1): vm.request(2, bytes([count]))
        def send(value): vm.request(1, plistlib.dumps(value, fmt=plistlib.FMT_BINARY))
        def scene(): return json.loads(vm.request(3))
        tick()
        reset = plistlib.loads(vm.request(6)[0])
        if reset['type'] != 0: raise ValueError('Original reset missing')
        send(dict(type=1, ssn=reset['ssn'], psn=1, asn=reset['psn'], version=2, id='synthetic-settings-peer'))
        tick(8)
        replies = [plistlib.loads(x) for x in vm.request(6)]
        vm.request(5, b'\x01'); vm.request(8, b'\x04'); tick(10)
        replies += [plistlib.loads(x) for x in vm.request(6)]
        requests = [plistlib.loads(x['data']) for x in replies if x['type'] == 2]
        if not any(x['type'] == 5 and x['data'].get('layoutID') == LAYOUT for x in requests):
            raise ValueError('Original Wallpaper request absent')
        ack = max(x.get('psn', 0) for x in replies)
        sequence = 1
        def command(kind, data):
            nonlocal sequence
            sequence += 1
            send(dict(type=2, ssn=reset['ssn'], psn=sequence, asn=ack,
                data=plistlib.dumps(dict(type=kind, data=data), fmt=plistlib.FMT_BINARY)))
            tick(12)
            trace.append(dict(commandType=kind, simulatedPeer=True))
        for kind, data in [(9, dict(isTransitioning=True)), (3, dict(layoutID=LAYOUT)),
                           (6, dict(layoutID=LAYOUT, regions=[])), (9, dict(isTransitioning=False))]:
            command(kind, data)
        before = settings_node(scene())
        if before['effectiveOpacity'] != 0:
            raise ValueError('Settings gauge must initially be hidden')
        vm.request(7, plistlib.dumps(dict(action='offer', input=INPUT, token=101, pts=11.125), fmt=plistlib.FMT_BINARY))
        command(9, dict(isTransitioning=True))
        command(1, dict(presentStream=INPUT))
        vm.request(7, plistlib.dumps(dict(action='offer', input='iPhoneVideo', token=201, pts=12.0), fmt=plistlib.FMT_BINARY)); tick(12)
        vm.request(7, plistlib.dumps(dict(action='commit', input='iPhoneVideo', token=201), fmt=plistlib.FMT_BINARY))
        presented_scene = scene()
        presented = settings_node(presented_scene)
        if presented['effectiveOpacity'] != 1 or presented['input'] != dict(
                active=True, identifier=INPUT, token=101, pts=11.125):
            raise ValueError('Settings stream was not presented with the offered frame')
        vm.request(7, plistlib.dumps(dict(action='commit', input=INPUT, token=101), fmt=plistlib.FMT_BINARY))
        command(1, dict(dismissStream=INPUT))
        vm.request(7, plistlib.dumps(dict(action='offer', input='iPhoneVideo', token=202, pts=13.0), fmt=plistlib.FMT_BINARY)); tick(12)
        vm.request(7, plistlib.dumps(dict(action='commit', input='iPhoneVideo', token=202), fmt=plistlib.FMT_BINARY))
        command(9, dict(isTransitioning=False))
        dismissed = settings_node(scene())
        if dismissed['effectiveOpacity'] != 0:
            raise ValueError('Settings stream did not dismiss')
    verify_resources(resources, expected)
    report = dict(asset=inventory['asset'], display=inventory['display'],
        sourceModules=sum(x.endswith('.lua') for x in manifest['files']), archiveSha256=expected,
        originalSourcesUnchanged=True, input=dict(identifier=INPUT, kind=1, width=1650, height=720),
        initiallyHidden=before['effectiveOpacity'] == 0, presented=presented, dismissed=dismissed,
        trace=trace, hostSha256=hashlib.sha256(host.read_bytes()).hexdigest(),
        bridgeSha256=hashlib.sha256((HERE/'bridge.luau').read_bytes()).hexdigest(),
        entrySha256=hashlib.sha256((HERE/'settings_input_entry.luau').read_bytes()).hexdigest(),
        animationClockInput='iPhoneVideo', syntheticSource=True, simulatedPeer=True, simulatedLifecycle=True,
        oemApplicationIntegrated=False, liveEntryChanged=False, pixelsVerified=False,
        phoneAcceptanceVerified=False, physicalPresentation=False, fullUltraUI=False)
    return report, presented_scene


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
    print('Original Settings gauge created/presented/dismissed with synthetic source; no OEM UI or physical claim.')


if __name__ == '__main__': main()
