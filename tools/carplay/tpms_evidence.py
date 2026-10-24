#!/usr/bin/env python3
"""Extract bounded OEM TPMS evidence and prepare a manual screen-correlation sheet.

Development-only, offline. Never connects to a car, reads app accounts, normalizes
units or treats Unity defaults as measurements. Inputs are a captured OEM APK and
optional structured output from capture_events.py, never arbitrary logcat text.
"""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import zipfile

WHEELS = ("FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT")
QUANTITIES = ("pressureRaw", "tempRaw")
UNITY_TABLE = "assets/UnityTable/Button_603.csv"
METADATA = "assets/bin/Data/Managed/Metadata/global-metadata.dat"
LABEL_PATH = "SZ{0}/Line/SZ/MyText_kPa{0}"
TIME = re.compile(r"\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}")
MAX_AGE = 365 * 24 * 60 * 60 * 1000


def metadata_label_evidence(data):
    if len(data) < 24:
        raise ValueError("Truncated metadata header")
    magic, version, offset, size, blob, blob_size = struct.unpack_from("<6I", data)
    if magic != 0xFAB11BAF or version != 27:
        raise ValueError("Only the captured metadata version 27 contract is supported")
    if size % 8 or offset < 24 or blob < offset + size or blob + blob_size > len(data):
        raise ValueError("Invalid metadata literal table bounds")
    matches = []
    for index in range(size // 8):
        length, relative = struct.unpack_from("<2I", data, offset + index * 8)
        if relative + length > blob_size:
            raise ValueError("Invalid metadata literal bounds")
        # Exact expected path only. No arbitrary Unity text or raw binary is emitted.
        if data[blob + relative:blob + relative + length] == LABEL_PATH.encode():
            matches.append(dict(index=index, labelPath=LABEL_PATH,
                                meaning="UI_LABEL_NAME_ONLY_NOT_RAW_CONVERSION"))
    return dict(version=version, sha256=hashlib.sha256(data).hexdigest(), literals=matches)


def extract_contract(apk):
    apk = Path(apk)
    with zipfile.ZipFile(apk) as archive:
        if archive.getinfo(UNITY_TABLE).file_size > 1_000_000 or archive.getinfo(METADATA).file_size > 20_000_000:
            raise ValueError("Unexpected evidence member size")
        table = archive.read(UNITY_TABLE)
        rows = list(csv.DictReader(io.StringIO(table.decode("utf-8-sig"))))
        selected = {}
        expected = {str(event) for event in range(603002, 603010)}
        for row in rows:
            event = row.get("EventId")
            if event not in expected:
                continue
            if event in selected or row.get("PageId") != "603":
                raise ValueError("Ambiguous OEM TPMS table identity")
            selected[event] = row
        if set(selected) != expected:
            raise ValueError("Captured OEM TPMS table does not match expected event identities")
        channels = []
        for wheel_index, wheel in enumerate(WHEELS):
            for quantity_index, quantity in enumerate(QUANTITIES):
                unity = 603002 + wheel_index + quantity_index * 4
                row = selected[str(unity)]
                expected_id = ("CK_MyText_SZ" if quantity_index == 0 else "CK_MyText_WD") + str(wheel_index)
                if row.get("id") != expected_id:
                    raise ValueError("Captured OEM wheel/control mapping changed")
                channels.append(dict(wheel=wheel, quantity=quantity,
                                     someIpEvent=3403 + wheel_index * 100 + quantity_index,
                                     unityEvent=unity, unityControl=expected_id,
                                     defaultIsMeasurement=False, unitsVerified=False))
        metadata = metadata_label_evidence(archive.read(METADATA))
    digest = hashlib.sha256()
    with apk.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return dict(apkSha256=digest.hexdigest(), tablePath=UNITY_TABLE,
                tableSha256=hashlib.sha256(table).hexdigest(), channels=channels,
                metadata=metadata, rawConversionVerified=False, physicalReadingsVerified=False)


def correlated_readings(events):
    """Latest valid structured record per channel, selected in capture order, not inferred time."""
    latest = {}
    for event in events:
        if not isinstance(event, dict) or event.get("event") != "c11_tpms_observation":
            continue
        wheel, quantity = event.get("wheel"), event.get("quantity")
        status, value, age = event.get("readingState"), event.get("rawValue"), event.get("receivedAgeMs")
        stamp = event.get("deviceTime")
        if (wheel not in WHEELS or quantity not in QUANTITIES or
                status not in ("AVAILABLE", "UNAVAILABLE", "STALE") or
                not isinstance(stamp, str) or not TIME.fullmatch(stamp) or
                age is not None and (type(age) is not int or not 0 <= age <= MAX_AGE)):
            continue
        bounds = (0, 4094) if quantity == "pressureRaw" else (-50, 150)
        if status == "AVAILABLE":
            if (type(value) is not int or not bounds[0] <= value <= bounds[1] or
                    age is None or age > 60000):
                continue
        elif value is not None:
            continue
        latest[wheel, quantity] = dict(wheel=wheel, quantity=quantity, rawValue=value,
                                       readingState=status, receivedAgeMs=age, deviceTime=stamp)
    return [latest[wheel, quantity] for wheel in WHEELS for quantity in QUANTITIES
            if (wheel, quantity) in latest]


def structured_events(path):
    with Path(path).open() as source:
        for line in source:
            if len(line) > 8192:
                continue
            try:
                yield json.loads(line)
            except (ValueError, TypeError):
                continue


def write_comparison(output, readings):
    latest = {(row["wheel"], row["quantity"]): row for row in readings}
    columns = ("wheel", "quantity", "rawValue", "readingState", "deviceTime", "receivedAgeMs",
               "oemDisplayedValue", "oemDisplayedUnit", "oemObservationDeviceTime", "comparisonStatus")
    with Path(output).open("x", newline="") as destination:
        writer = csv.DictWriter(destination, fieldnames=columns)
        writer.writeheader()
        for wheel in WHEELS:
            for quantity in QUANTITIES:
                row = latest.get((wheel, quantity), dict(wheel=wheel, quantity=quantity,
                                                        readingState="NOT_CAPTURED"))
                writer.writerow(dict(row, comparisonStatus="REQUIRES_SIMULTANEOUS_OEM_SCREEN"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--events", help="Structured capture_events.py JSONL only")
    parser.add_argument("--output", required=True, help="New evidence JSON; never overwritten")
    parser.add_argument("--comparison-csv", help="New manual comparison template; never overwritten")
    args = parser.parse_args()
    report = extract_contract(args.apk)
    readings = correlated_readings(structured_events(args.events)) if args.events else []
    report["latestRawReadings"] = readings
    report["comparisonStatus"] = "REQUIRES_SIMULTANEOUS_OEM_SCREEN"
    with Path(args.output).open("x") as destination:
        json.dump(report, destination, indent=2)
        destination.write("\n")
    if args.comparison_csv:
        write_comparison(args.comparison_csv, readings)


if __name__ == "__main__":
    main()
