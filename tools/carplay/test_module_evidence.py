import json
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
import re

from module_evidence import expected_channels, html_report, markdown, normalize_observation, structured_records, summarize


def record(channel="CHARGE_STATUS", raw=0, **changes):
    return dict(dict(event="c11_charging_observation", channel=channel, readingState="OBSERVED_UNVERIFIED",
                     rawValue=raw, receivedAgeMs=0, deviceTime="10-23 12:00:00.000"), **changes)


class ModuleEvidenceTest(unittest.TestCase):
    def test_cli_replays_full_synthetic_capture_and_preserves_existing_outputs(self):
        here = Path(__file__).resolve().parent
        with tempfile.TemporaryDirectory() as directory:
            output, table, page = Path(directory, "report.json"), Path(directory, "report.md"), Path(directory, "report.html")
            command = [sys.executable, str(here / "module_evidence.py"), "--events",
                       str(here / "fixtures/modules-simulated.jsonl"), "--output", str(output),
                       "--markdown", str(table), "--html", str(page)]
            completed = subprocess.run(command, capture_output=True, timeout=10)
            self.assertEqual(0, completed.returncode)
            report = json.loads(output.read_text())
            self.assertEqual("SIMULATED", report["inputKind"])
            self.assertEqual(91, sum(module["observedChannels"] for module in report["modules"]))
            self.assertIn("SIMULADO", table.read_text())
            self.assertNotIn("__DIPLAY_REPORT_JSON__", page.read_text())
            self.assertIn('id="report-data"', page.read_text())
            before = output.read_bytes(), table.read_bytes(), page.read_bytes()
            self.assertNotEqual(0, subprocess.run(command, capture_output=True, timeout=10).returncode)
            self.assertEqual(before, (output.read_bytes(), table.read_bytes(), page.read_bytes()))

    def test_html_embeds_inert_json_and_cannot_close_its_data_block(self):
        report = summarize([])
        report["testText"] = "</script><script>unwanted()</script>"
        page = html_report(report)
        self.assertNotIn(report["testText"], page)
        data = re.search(r'<script type="application/json" id="report-data">(.*?)</script>', page, re.S)[1]
        self.assertEqual(report, json.loads(data))

    def test_empty_capture_keeps_all_ten_modules_missing_and_never_assumes_zero(self):
        report = summarize([])
        self.assertEqual(10, len(report["modules"]))
        self.assertEqual(91, sum(len(channels) for channels in expected_channels().values()))
        self.assertEqual("CLIMATE", report["modules"][-1]["module"])
        self.assertTrue(all(module["status"] == "NOT_OBSERVED" for module in report["modules"]))
        self.assertTrue(all(row["lastRecorded"] is None for module in report["modules"] for row in module["channels"]))
        self.assertFalse(report["physicalValidationVerified"])

    def test_recorded_zero_counts_as_observation_and_changes_do_not_validate_meaning(self):
        report = summarize([record(raw=0), record(raw=0), record(raw=1)], simulated=True)
        module = report["modules"][0]
        row = next(row for row in module["channels"] if row["channel"] == "CHARGE_STATUS")
        self.assertEqual(3, row["observations"])
        self.assertEqual(1, row["valueChanges"])
        self.assertEqual("VALUE_CHANGES_OBSERVED", module["status"])
        self.assertEqual("SIMULATED", report["inputKind"])
        self.assertEqual("SIMULATED", summarize([dict(event="capture_started", simulated=True)])["inputKind"])
        self.assertFalse(report["physicalValidationVerified"])
        self.assertFalse(report["controlVerified"])
        self.assertIn("no acredita estado actual", markdown(report))

    def test_gaps_restarts_and_withdrawals_break_transition_comparisons(self):
        records = [record(raw=0), dict(event="capture_gap"), record(raw=1),
                   dict(event="process_observed", pid=5), record(raw=0),
                   record(raw=None, readingState="STALE", receivedAgeMs=60001), record(raw=1)]
        report = summarize(records)
        row = next(row for row in report["modules"][0]["channels"] if row["channel"] == "CHARGE_STATUS")
        self.assertEqual(0, row["valueChanges"])
        self.assertEqual(1, row["withdrawals"])
        self.assertEqual(2, report["captureBoundaries"])
        report = summarize([record(), record(raw=None, readingState="UNAVAILABLE")])
        row = next(row for row in report["modules"][0]["channels"] if row["channel"] == "CHARGE_STATUS")
        self.assertIsNone(row["lastRecorded"]["rawValue"])

    def test_routes_cover_energy_and_speed_in_separate_modules_and_drop_private_fields(self):
        sample = record("CURRENT_TRIP_ENERGY", 2.125, event="c11_measurement_observation",
                        private="phone-location-password")
        speed = record("VEHICLE_SPEED", 0.0, event="c11_measurement_observation")
        seat = record("DRIVER_SEAT_HEAT", 2, event="c11_subsystem_observation", module="SEATS")
        rear = record("REAR_HVAC_DISPLAY_TEMPERATURE", 20.5, event="c11_measurement_observation")
        report = summarize([sample, speed, seat, rear])
        modules = {module["module"]: module for module in report["modules"]}
        self.assertEqual(1, modules["ENERGY"]["observedChannels"])
        self.assertEqual(1, modules["DRIVING"]["observedChannels"])
        self.assertEqual(1, modules["SEATS"]["observedChannels"])
        self.assertEqual(1, modules["CLIMATE"]["observedChannels"])
        self.assertNotIn("phone-location-password", json.dumps(report))
        self.assertFalse(report["unitsVerified"])

    def test_malformed_or_unbounded_structured_data_never_becomes_evidence(self):
        invalid = [None, "raw log", record(raw=True), record(raw=2147491918), record(raw=10**5000),
                   record(receivedAgeMs=True), record(receivedAgeMs=10**5000), record(deviceTime="private"),
                   record(deviceTime="99-99 99:99:99.999"), record(event=[]), record(channel=[]),
                   record(raw=None), record(readingState="STALE"), record(receivedAgeMs=60001),
                   record("CURRENT_TRIP_ENERGY", float("inf"), event="c11_measurement_observation"),
                   record("CURRENT_TRIP_ENERGY", 10**5000, event="c11_measurement_observation"),
                   record("DRIVER_SEAT_HEAT", 4, event="c11_subsystem_observation", module="SEATS"),
                   record("DRIVER_SEAT_HEAT", 2, event="c11_subsystem_observation", module="LIGHTS"),
                   record("CHARGE_TARGET_PERCENT", 49), record("CHARGE_TIME_REMAINING", 0xffffff),
                   record("HOOD_STATUS", 2, event="c11_door_observation")]
        self.assertTrue(all(normalize_observation(row) is None for row in invalid))
        self.assertEqual(0, summarize(invalid)["acceptedObservations"])

    def test_phone_protocol_counts_do_not_claim_siri_acceptance_or_echo_unknown_text(self):
        report = summarize([dict(event="vehicle_state_request", command="configRequest", lane="HIGH"),
                            dict(event="vehicle_state_rejected", reason="unsupported-command", lane="NORMAL"),
                            dict(event="vehicle_state_request", command="private Siri phrase", lane="HIGH"),
                            dict(event="vehicle_state_request", command=[], lane="HIGH")])
        self.assertEqual({"configRequest": 1}, report["phoneProtocol"]["requests"])
        self.assertEqual({"unsupported-command": 1}, report["phoneProtocol"]["rejections"])
        self.assertFalse(report["phoneProtocol"]["siriAcceptanceVerified"])
        self.assertNotIn("private Siri phrase", json.dumps(report))

    def test_bounded_reader_skips_oversize_invalid_and_deeply_nested_records(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "events.jsonl")
            path.write_text("private\n" + "x" * 9000 + "\n" + "[" * 2000 + "]" * 2000 + "\n" + json.dumps(record()) + "\n")
            report = summarize(structured_records(path))
            self.assertEqual(1, report["acceptedObservations"])
            self.assertEqual(3, report["ignoredRecords"])


if __name__ == "__main__":
    unittest.main()
