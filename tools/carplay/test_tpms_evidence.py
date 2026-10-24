import csv
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from tpms_evidence import (LABEL_PATH, METADATA, UNITY_TABLE, correlated_readings,
                           extract_contract, metadata_label_evidence, structured_events,
                           write_comparison)


def metadata(label=LABEL_PATH):
    blob = label.encode()
    return struct.pack("<8I", 0xFAB11BAF, 27, 24, 8, 32, len(blob), len(blob), 0) + blob


def table():
    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(("id", "PageId", "EventId", "Value", "Desc"))
    for offset in range(8):
        kind = "SZ" if offset < 4 else "WD"
        writer.writerow((f"CK_MyText_{kind}{offset % 4}", 603, 603002 + offset,
                         "0|0", "static OEM description"))
    return output.getvalue()


def record(**changes):
    return dict(dict(event="c11_tpms_observation", wheel="FRONT_LEFT", quantity="pressureRaw",
                     readingState="AVAILABLE", rawValue=250, receivedAgeMs=0,
                     deviceTime="10-20 12:48:02.574"), **changes)


class TpmsEvidenceTest(unittest.TestCase):
    def test_exact_unity_label_proves_only_label_path_not_pressure_conversion(self):
        evidence = metadata_label_evidence(metadata())
        self.assertEqual(27, evidence["version"])
        self.assertEqual(LABEL_PATH, evidence["literals"][0]["labelPath"])
        self.assertEqual("UI_LABEL_NAME_ONLY_NOT_RAW_CONVERSION", evidence["literals"][0]["meaning"])
        self.assertEqual([], metadata_label_evidence(metadata("private-phone"))["literals"])

    def test_invalid_metadata_bounds_or_version_fail_before_literal_extraction(self):
        original = metadata()
        for candidate in (original[:4], original[:30],
                          original[:4] + struct.pack("<I", 24) + original[8:],
                          original[:12] + struct.pack("<I", 7) + original[16:],
                          original[:28] + struct.pack("<I", 999999) + original[32:]):
            with self.assertRaises(ValueError):
                metadata_label_evidence(candidate)

    def test_apk_mapping_and_initial_defaults_never_become_live_values(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory, "oem.apk")
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(UNITY_TABLE, table())
                archive.writestr(METADATA, metadata())
            evidence = extract_contract(apk)
            self.assertEqual(8, len(evidence["channels"]))
            self.assertEqual(3403, evidence["channels"][0]["someIpEvent"])
            self.assertEqual(603002, evidence["channels"][0]["unityEvent"])
            self.assertEqual(3704, evidence["channels"][-1]["someIpEvent"])
            self.assertEqual(603009, evidence["channels"][-1]["unityEvent"])
            self.assertTrue(all(not row["defaultIsMeasurement"] for row in evidence["channels"]))
            self.assertFalse(evidence["physicalReadingsVerified"])
            self.assertFalse(evidence["rawConversionVerified"])
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(UNITY_TABLE, table().replace("CK_MyText_SZ0", "changed"))
                archive.writestr(METADATA, metadata())
            with self.assertRaises(ValueError):
                extract_contract(apk)

    def test_capture_order_withdrawal_replaces_last_good_without_inferred_measurement_time(self):
        readings = correlated_readings([record(), record(rawValue=None, readingState="STALE", receivedAgeMs=60001)])
        self.assertEqual(1, len(readings))
        self.assertIsNone(readings[0]["rawValue"])
        self.assertEqual("STALE", readings[0]["readingState"])
        readings = correlated_readings([record(quantity="tempRaw", rawValue=-1)])
        self.assertEqual(-1, readings[0]["rawValue"])

    def test_structured_input_drops_invalid_or_private_fields_without_exporting_them(self):
        invalid = [None, "private", record(rawValue=True), record(rawValue=4095), record(rawValue=-1),
                   record(quantity="psi"), record(wheel="DRIVER"), record(readingState="HEALTHY"),
                   record(receivedAgeMs=60001), record(receivedAgeMs=None), record(deviceTime="private"),
                   record(rawValue=None), record(rawValue=250, readingState="STALE")]
        self.assertEqual([], correlated_readings(invalid))
        event = record()
        event["private"] = "password-location-phone"
        self.assertNotIn("private", json.dumps(correlated_readings([event])))

    def test_manual_correlation_template_has_eight_channels_and_no_invented_oem_values(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory, "compare.csv")
            write_comparison(output, correlated_readings([record()]))
            with output.open() as source:
                rows = list(csv.DictReader(source))
            self.assertEqual(8, len(rows))
            self.assertEqual("250", rows[0]["rawValue"])
            self.assertEqual("NOT_CAPTURED", rows[1]["readingState"])
            self.assertTrue(all(not row["oemDisplayedValue"] and not row["oemDisplayedUnit"] for row in rows))
            self.assertTrue(all(row["comparisonStatus"] == "REQUIRES_SIMULTANEOUS_OEM_SCREEN" for row in rows))
            with self.assertRaises(FileExistsError):
                write_comparison(output, [])

    def test_jsonl_reader_skips_malformed_and_oversize_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory, "events.jsonl")
            source.write_text("private\n" + "x" * 9000 + "\n" + json.dumps(record()) + "\n")
            self.assertEqual(1, len(correlated_readings(structured_events(source))))


if __name__ == "__main__":
    unittest.main()
