import json
from pathlib import Path
import sys
import tempfile
import unittest

from capture_events import capture, logcat_command, package_uid, parse_event, SUBSYSTEM_CHANNELS, MEASUREMENT_CHANNELS, DURATION_CHANNELS


def line(message, pid=765, tag="xcertplay-usb"):
    return f"10-19 12:48:02.574 {pid:5d} 4470 W {tag}: {message}"


class CaptureEventsTest(unittest.TestCase):
    def test_occupancy_is_not_limited_like_seat_heat_and_does_not_become_a_boolean(self):
        for channel in ("FRONT_PRIMARY_OCCUPANCY_STATUS", "FRONT_SECONDARY_OCCUPANCY_STATUS"):
            for value in (-1, 0, 1, 2, 255):
                message = (f"C11 SUBSYSTEM module=SEATS channel={channel} "
                           f"status=OBSERVED_UNVERIFIED raw={value} receivedAgeMs=0")
                event = parse_event(line(message, tag="DiPlay-SUBSYSTEM"))
                self.assertEqual(value, event["rawValue"])
                self.assertFalse(event["semanticsVerified"])
                self.assertNotIn("occupied", event)

    def test_hood_bounds_and_separate_lock_codes_do_not_produce_a_boolean_lock_verdict(self):
        for channel, value in (("HOOD_STATUS", 0), ("HOOD_STATUS", 1), ("VEHICLE_LOCK_STATUS", 2),
                               ("REAR_LEFT_CHILD_LOCK_STATUS", 1), ("REAR_RIGHT_CHILD_LOCK_STATUS", -1)):
            message = f"C11 DOORS status=OBSERVED_UNVERIFIED channel={channel} raw={value} receivedAgeMs=0"
            event = parse_event(line(message, tag="DiPlay-DOORS"))
            self.assertEqual(value, event["rawValue"])
            self.assertFalse(event["semanticsVerified"])
            self.assertNotIn("locked", event)
        for invalid in (-1, 2):
            message = f"C11 DOORS status=OBSERVED_UNVERIFIED channel=HOOD_STATUS raw={invalid} receivedAgeMs=0"
            self.assertIsNone(parse_event(line(message, tag="DiPlay-DOORS")))

    def test_charge_target_limits_and_remaining_time_sentinel_match_oem_consumer(self):
        for channel, raw in (("CHARGE_TARGET_PERCENT", 50), ("CHARGE_TARGET_PERCENT", 100),
                             ("CHARGE_TIME_REMAINING", 0), ("CHARGE_TIME_REMAINING", 90)):
            message = f"C11 CHARGING status=OBSERVED_UNVERIFIED channel={channel} raw={raw} receivedAgeMs=0"
            self.assertEqual(raw, parse_event(line(message, tag="DiPlay-CHARGING"))["rawValue"])
        for channel, raw in (("CHARGE_TARGET_PERCENT", 49), ("CHARGE_TARGET_PERCENT", 101),
                             ("CHARGE_TIME_REMAINING", 16777215)):
            message = f"C11 CHARGING status=OBSERVED_UNVERIFIED channel={channel} raw={raw} receivedAgeMs=0"
            self.assertIsNone(parse_event(line(message, tag="DiPlay-CHARGING")))
        unknown = "C11 CHARGING status=UNAVAILABLE channel=CHARGE_TIME_REMAINING raw=UNKNOWN receivedAgeMs=0"
        self.assertIsNone(parse_event(line(unknown, tag="DiPlay-CHARGING"))["rawValue"])

    def test_logcat_filter_includes_every_new_module_tag_and_siri_diagnostics_are_allowlisted(self):
        command = logcat_command(["adb"], 12345)
        for tag in ("DiPlay-CHARGING", "DiPlay-DOORS", "DiPlay-SUBSYSTEM", "DiPlay-MEASUREMENTS"):
            self.assertIn(tag, command)
        messages = {
            "vehicle state received bytes=70 lane=HIGH": "vehicle_state_received",
            "vehicle state request=unknown lane=NORMAL": "vehicle_state_request",
            "vehicle state rejected=unsupported-command lane=HIGH": "vehicle_state_rejected",
            "vehicle state registration requested=3 available=2 unavailable=1 lane=HIGH": "vehicle_state_registration",
            "vehicle state control unavailable=1 lane=NORMAL": "vehicle_state_unavailable",
        }
        for message, expected in messages.items():
            self.assertEqual(expected, parse_event(line(message))["event"])
            self.assertIsNone(parse_event(line(message + " payload=private")))
        for invalid in ("vehicle state received bytes=262145 lane=HIGH",
                        "vehicle state request=private phrase lane=HIGH",
                        "vehicle state rejected=private reason lane=HIGH",
                        "vehicle state registration requested=3 available=3 unavailable=1 lane=HIGH"):
            self.assertIsNone(parse_event(line(invalid)))

    def test_all_subsystem_channels_are_filtered_and_module_mismatches_are_rejected(self):
        for module, channels in SUBSYSTEM_CHANNELS.items():
            for channel in channels:
                message = (f"C11 SUBSYSTEM module={module} channel={channel} "
                           "status=OBSERVED_UNVERIFIED raw=1 receivedAgeMs=60000")
                event = parse_event(line(message, tag="DiPlay-SUBSYSTEM"))
                self.assertEqual("c11_subsystem_observation", event["event"])
                self.assertEqual(module, event["module"])
                self.assertFalse(event["semanticsVerified"])
                self.assertFalse(event["writeControlAvailable"])
                self.assertIsNone(parse_event(line(message + " payload=private", tag="DiPlay-SUBSYSTEM")))
        base = "C11 SUBSYSTEM module=SEATS channel=DRIVER_SEAT_HEAT status=OBSERVED_UNVERIFIED raw=1 receivedAgeMs=1"
        for bad in (base.replace("module=SEATS", "module=LIGHTS"), base.replace("raw=1", "raw=4"),
                    base.replace("raw=1", "raw=-1"), base.replace("raw=1", "raw=UNKNOWN"),
                    base.replace("receivedAgeMs=1", "receivedAgeMs=60001"),
                    base.replace("OBSERVED_UNVERIFIED", "STALE")):
            self.assertIsNone(parse_event(line(bad, tag="DiPlay-SUBSYSTEM")))
        position = base.replace("SEATS", "WINDOWS_ROOF").replace("DRIVER_SEAT_HEAT", "SUNSHADE_POSITION")
        self.assertIsNone(parse_event(line(position.replace("raw=1", "raw=101"), tag="DiPlay-SUBSYSTEM")))
        unknown = base.replace("OBSERVED_UNVERIFIED", "UNAVAILABLE").replace("raw=1", "raw=UNKNOWN")
        self.assertIsNone(parse_event(line(unknown, tag="DiPlay-SUBSYSTEM"))["rawValue"])

    def test_wiper_notices_do_not_accept_speed_or_command_names(self):
        for channel in SUBSYSTEM_CHANNELS["WIPERS"]:
            for raw in (-1, 0, 1, 7):
                event = parse_event(line(f"C11 SUBSYSTEM module=WIPERS channel={channel} status=OBSERVED_UNVERIFIED raw={raw} receivedAgeMs=0", tag="DiPlay-SUBSYSTEM"))
                self.assertEqual(raw, event["rawValue"])
                self.assertFalse(event["writeControlAvailable"])
        for channel in ("FRONT_WIPER_SPEED", "FRONT_WIPER_COMMAND", "RAIN_LEVEL"):
            self.assertIsNone(parse_event(line(f"C11 SUBSYSTEM module=WIPERS channel={channel} status=OBSERVED_UNVERIFIED raw=1 receivedAgeMs=0", tag="DiPlay-SUBSYSTEM")))

    def test_smart_cleaning_and_ac_max_are_raw_codes_not_boolean_or_timers(self):
        for channel in ("HVAC_SMART_CLEAR_STATUS", "HVAC_AUTO_CLEAR_STATUS", "HVAC_AC_STATUS"):
            for raw in (-1, 0, 1, 2, 7):
                event = parse_event(line(f"C11 SUBSYSTEM module=CLIMATE channel={channel} status=OBSERVED_UNVERIFIED raw={raw} receivedAgeMs=0", tag="DiPlay-SUBSYSTEM"))
                self.assertEqual(raw, event["rawValue"])
                self.assertFalse(event["semanticsVerified"])
                self.assertNotIn("remainingSeconds", event)

    def test_sync_feedback_keeps_unknown_codes_without_an_on_off_verdict(self):
        for raw in (1, 2, 0, -1, 7):
            event = parse_event(line(f"C11 SUBSYSTEM module=CLIMATE channel=HVAC_SYNC_FEEDBACK status=OBSERVED_UNVERIFIED raw={raw} receivedAgeMs=0", tag="DiPlay-SUBSYSTEM"))
            self.assertEqual(raw, event["rawValue"])
            self.assertFalse(event["semanticsVerified"])
            self.assertFalse(event["writeControlAvailable"])

    def test_rear_displayed_temperature_requires_unscaled_eighteen_to_thirty_two(self):
        for raw in (-1, 17.9, 18, 20.5, 32, 32.1, 41):
            event = parse_event(line(f"C11 MEASUREMENT channel=REAR_HVAC_DISPLAY_TEMPERATURE status=OBSERVED_UNVERIFIED raw={raw} receivedAgeMs=0", tag="DiPlay-MEASUREMENTS"))
            self.assertEqual(18 <= raw <= 32, event is not None)

    def test_rear_climate_keeps_own_bounds_and_unknown_distribution_codes(self):
        for channel, max_value in (("REAR_HVAC_POWER_STATUS", 1), ("REAR_HVAC_AUTO_STATUS", 1), ("REAR_HVAC_FAN_LEVEL", 7)):
            for raw in (-1, 0, 1, 7, 8):
                event = parse_event(line(f"C11 SUBSYSTEM module=CLIMATE channel={channel} status=OBSERVED_UNVERIFIED raw={raw} receivedAgeMs=0", tag="DiPlay-SUBSYSTEM"))
                self.assertEqual(0 <= raw <= max_value, event is not None)
        self.assertIsNotNone(parse_event(line("C11 SUBSYSTEM module=CLIMATE channel=REAR_HVAC_AIR_DISTRIBUTION status=OBSERVED_UNVERIFIED raw=9 receivedAgeMs=0", tag="DiPlay-SUBSYSTEM")))

    def test_pm25_uses_categories_one_to_six_not_a_concentration_or_auto_cleaning_code(self):
        for raw in (-1, 0, 1, 3, 6, 7, 100):
            for channel in ("CABIN_PM25_LEVEL", "HVAC_AUTO_CLEAR_STATUS"):
                event = parse_event(line(f"C11 SUBSYSTEM module=CLIMATE channel={channel} status=OBSERVED_UNVERIFIED raw={raw} receivedAgeMs=0", tag="DiPlay-SUBSYSTEM"))
                self.assertEqual(channel == "HVAC_AUTO_CLEAR_STATUS" or 1 <= raw <= 6, event is not None)

    def test_seat_positions_have_percentage_bounds_separate_from_heat_levels(self):
        for channel in ("FRONT_LEFT_SEAT_SLIDE_POSITION", "FRONT_LEFT_SEAT_BASE_FRONT_POSITION", "FRONT_LEFT_SEAT_BACKREST_POSITION"):
            for raw in (-1, 0, 50, 100, 101):
                event = parse_event(line(f"C11 SUBSYSTEM module=SEATS channel={channel} status=OBSERVED_UNVERIFIED raw={raw} receivedAgeMs=0", tag="DiPlay-SUBSYSTEM"))
                self.assertEqual(0 <= raw <= 100, event is not None)

    def test_ac_lock_and_onboard_charger_keep_unknown_codes_without_boolean_verdicts(self):
        for channel in ("AC_PLUG_LOCK_STATUS", "ONBOARD_CHARGER_STATUS"):
            for raw in (-1, 0, 1, 2, 3):
                event = parse_event(line(f"C11 CHARGING status=OBSERVED_UNVERIFIED channel={channel} raw={raw} receivedAgeMs=0", tag="DiPlay-CHARGING"))
                self.assertEqual(raw, event["rawValue"])
                self.assertFalse(event["semanticsVerified"])
                self.assertFalse(event["writeControlAvailable"])

    def test_range_distances_reject_sentinel_without_restricting_selector_codes(self):
        for channel in ("CLTC_RANGE", "WLTC_RANGE", "RANGE_STANDARD"):
            for raw in (-1, 0, 3, 250):
                event = parse_event(line(f"C11 SUBSYSTEM module=ENERGY channel={channel} status=OBSERVED_UNVERIFIED raw={raw} receivedAgeMs=0", tag="DiPlay-SUBSYSTEM"))
                self.assertEqual(channel == "RANGE_STANDARD" or raw >= 0, event is not None)

    def test_power_tailgate_percentage_limits_do_not_restrict_status_codes(self):
        for raw in (-1, 0, 50, 100, 101):
            event = parse_event(line(f"C11 DOORS status=OBSERVED_UNVERIFIED channel=TAILGATE_POSITION raw={raw} receivedAgeMs=0", tag="DiPlay-DOORS"))
            self.assertEqual(0 <= raw <= 100, event is not None)
            status = parse_event(line(f"C11 DOORS status=OBSERVED_UNVERIFIED channel=POWER_TAILGATE_STATUS raw={raw} receivedAgeMs=0", tag="DiPlay-DOORS"))
            self.assertEqual(raw, status["rawValue"])

    def test_mirror_heat_is_not_limited_as_a_window_percentage(self):
        for channel in ("LEFT_MIRROR_HEAT_STATUS", "RIGHT_MIRROR_HEAT_STATUS"):
            for raw in (-1, 0, 1, 101):
                event = parse_event(line(f"C11 SUBSYSTEM module=WINDOWS_ROOF channel={channel} status=OBSERVED_UNVERIFIED raw={raw} receivedAgeMs=0", tag="DiPlay-SUBSYSTEM"))
                self.assertEqual(raw, event["rawValue"])
                self.assertFalse(event["semanticsVerified"])
        self.assertIsNone(parse_event(line("C11 SUBSYSTEM module=WINDOWS_ROOF channel=SUNSHADE_POSITION status=OBSERVED_UNVERIFIED raw=101 receivedAgeMs=0", tag="DiPlay-SUBSYSTEM")))

    def test_measurements_never_claim_verified_units_or_accept_nonfinite_and_private_data(self):
        for channel in MEASUREMENT_CHANNELS:
            raw = "20.5" if channel == "REAR_HVAC_DISPLAY_TEMPERATURE" else "60.0" if channel in DURATION_CHANNELS else "2.125"
            base = f"C11 MEASUREMENT channel={channel} status=OBSERVED_UNVERIFIED raw={raw} receivedAgeMs=0"
            event = parse_event(line(base, tag="DiPlay-MEASUREMENTS"))
            self.assertEqual(float(raw), event["rawValue"])
            self.assertFalse(event["unitsVerified"])
            for bad in (base + " payload=private", base.replace(raw, "1e309"),
                        base.replace(raw, "NaN"), base.replace(raw, "UNKNOWN"),
                        base.replace("OBSERVED_UNVERIFIED", "AVAILABLE"),
                        base.replace("receivedAgeMs=0", "receivedAgeMs=60001"),
                        base.replace(channel, "UNKNOWN")):
                self.assertIsNone(parse_event(line(bad, tag="DiPlay-MEASUREMENTS")))

    def test_duration_constraints_and_historical_consumption_alias(self):
        for channel in DURATION_CHANNELS:
            for raw in ("-1", "1.5", "2147483648"):
                self.assertIsNone(parse_event(line(f"C11 MEASUREMENT channel={channel} status=OBSERVED_UNVERIFIED raw={raw} receivedAgeMs=0", tag="DiPlay-MEASUREMENTS")))
        event = parse_event(line("C11 MEASUREMENT channel=PREVIOUS_TRIP_ENERGY status=OBSERVED_UNVERIFIED raw=12.5 receivedAgeMs=0", tag="DiPlay-MEASUREMENTS"))
        self.assertEqual("RECHARGE_TRIP_CONSUMPTION", event["channel"])

    def test_doors_keep_raw_codes_without_open_or_lock_assumptions(self):
        for channel in ("DRIVER_DOOR", "PASSENGER_DOOR", "REAR_LEFT_DOOR", "REAR_RIGHT_DOOR", "TAILGATE_STATUS"):
            for raw in (-2147483648, -1, 0, 1, 2, 2147483647):
                message = f"C11 DOORS status=OBSERVED_UNVERIFIED channel={channel} raw={raw} receivedAgeMs=0"
                event = parse_event(line(message, tag="DiPlay-DOORS"))
                self.assertEqual("c11_door_observation", event["event"])
                self.assertEqual(channel, event["channel"])
                self.assertEqual(raw, event["rawValue"])
                self.assertFalse(event["semanticsVerified"])
                self.assertFalse(event["writeControlAvailable"])
                self.assertNotIn("open", event)
                self.assertNotIn("locked", event)
        message = "C11 DOORS status=OBSERVED_UNVERIFIED channel=DRIVER_DOOR raw=1 receivedAgeMs=1"
        for bad in (message + " payload=private", message.replace("raw=1", "raw=2147483648"),
                    message.replace("raw=1", "raw=-2147483649"), message.replace("raw=1", "raw=01"),
                    message.replace("raw=1", "raw=UNKNOWN"), message.replace("DRIVER_DOOR", "HOOD"),
                    message.replace("OBSERVED_UNVERIFIED", "AVAILABLE"),
                    message.replace("OBSERVED_UNVERIFIED", "STALE"),
                    message.replace("receivedAgeMs=1", "receivedAgeMs=60001"),
                    message.replace("receivedAgeMs=1", "receivedAgeMs=UNKNOWN")):
            self.assertIsNone(parse_event(line(bad, tag="DiPlay-DOORS")))
        unknown = "C11 DOORS status=UNAVAILABLE channel=TAILGATE_STATUS raw=UNKNOWN receivedAgeMs=UNKNOWN"
        self.assertIsNone(parse_event(line(unknown, tag="DiPlay-DOORS"))["rawValue"])

    def test_charging_captures_only_raw_observations_without_inferred_boolean_states(self):
        for channel in ("CHARGE_STATUS", "AC_CONNECTION", "DC_CONNECTION"):
            for raw in (-2147483648, -1, 0, 1, 2, 2147483647):
                message = (f"C11 CHARGING status=OBSERVED_UNVERIFIED channel={channel} "
                           f"raw={raw} receivedAgeMs=60000")
                event = parse_event(line(message, tag="DiPlay-CHARGING"))
                self.assertEqual("c11_charging_observation", event["event"])
                self.assertEqual(raw, event["rawValue"])
                self.assertFalse(event["semanticsVerified"])
                self.assertFalse(event["writeControlAvailable"])
                self.assertNotIn("charging", event)
        message = "C11 CHARGING status=OBSERVED_UNVERIFIED channel=CHARGE_STATUS raw=1 receivedAgeMs=1"
        for bad in (message + " payload=private", message.replace("raw=1", "raw=2147483648"),
                    message.replace("raw=1", "raw=-2147483649"), message.replace("raw=1", "raw=01"),
                    message.replace("raw=1", "raw=UNKNOWN"), message.replace("CHARGE_STATUS", "UNKNOWN"),
                    message.replace("OBSERVED_UNVERIFIED", "AVAILABLE"),
                    message.replace("OBSERVED_UNVERIFIED", "STALE"),
                    message.replace("receivedAgeMs=1", "receivedAgeMs=60001"),
                    message.replace("receivedAgeMs=1", "receivedAgeMs=UNKNOWN")):
            self.assertIsNone(parse_event(line(bad, tag="DiPlay-CHARGING")))
        unknown = "C11 CHARGING status=UNAVAILABLE channel=DC_CONNECTION raw=UNKNOWN receivedAgeMs=UNKNOWN"
        self.assertIsNone(parse_event(line(unknown, tag="DiPlay-CHARGING"))["rawValue"])

    def test_hvac_target_is_a_bounded_reading_without_control_or_cabin_temperature_claim(self):
        for side in ("LEFT", "RIGHT"):
            for number in ("18.0", "20.5", "32.0"):
                message = f"C11 HVAC status=AVAILABLE side={side} celsius={number} receivedAgeMs=60000"
                event = parse_event(line(message, tag="DiPlay-HVAC"))
                self.assertEqual("c11_hvac_target", event["event"])
                self.assertEqual(float(number), event["celsius"])
                self.assertFalse(event["writeControlAvailable"])
                self.assertNotIn("interior", event)
        message = "C11 HVAC status=AVAILABLE side=LEFT celsius=20.5 receivedAgeMs=1"
        for bad in (message + " payload=private", message.replace("20.5", "1e309"),
                    message.replace("20.5", "17.9"), message.replace("20.5", "32.1"),
                    message.replace("20.5", "UNKNOWN"), message.replace("AVAILABLE", "STALE"),
                    message.replace("receivedAgeMs=1", "receivedAgeMs=60001"),
                    message.replace("receivedAgeMs=1", "receivedAgeMs=UNKNOWN"),
                    message.replace("side=LEFT", "side=DRIVER")):
            self.assertIsNone(parse_event(line(bad, tag="DiPlay-HVAC")))
        unknown = message.replace("AVAILABLE", "UNAVAILABLE").replace("20.5", "UNKNOWN")
        self.assertIsNone(parse_event(line(unknown, tag="DiPlay-HVAC"))["celsius"])

    def test_fan_zero_is_an_observed_level_without_inferred_power_state(self):
        for level in range(8):
            message = f"C11 HVAC_FAN status=OBSERVED level={level} receivedAgeMs=0"
            event = parse_event(line(message, tag="DiPlay-HVAC-FAN"))
            self.assertEqual(level, event["level"])
            self.assertFalse(event["powerStateInferred"])
            self.assertFalse(event["writeControlAvailable"])
        message = "C11 HVAC_FAN status=OBSERVED level=0 receivedAgeMs=1"
        for bad in (message + " private", message.replace("level=0", "level=8"),
                    message.replace("level=0", "level=-1"), message.replace("level=0", "level=00"),
                    message.replace("level=0", "level=UNKNOWN"),
                    message.replace("OBSERVED", "AVAILABLE"), message.replace("OBSERVED", "UNAVAILABLE"),
                    message.replace("receivedAgeMs=1", "receivedAgeMs=60001")):
            self.assertIsNone(parse_event(line(bad, tag="DiPlay-HVAC-FAN")))
        unknown = "C11 HVAC_FAN status=STALE level=UNKNOWN receivedAgeMs=60001"
        self.assertIsNone(parse_event(line(unknown, tag="DiPlay-HVAC-FAN"))["level"])

    def test_tpms_raw_retains_quantity_and_wheel_without_unit_or_health_inference(self):
        for wheel in ("FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT"):
            for quantity, value in (("pressureRaw", 0), ("pressureRaw", 4094),
                                    ("tempRaw", -50), ("tempRaw", -1), ("tempRaw", 150)):
                message = f"C11 TPMS status=AVAILABLE wheel={wheel} {quantity}={value} receivedAgeMs=0"
                event = parse_event(line(message, tag="DiPlay-TPMS"))
                self.assertEqual("c11_tpms_observation", event["event"])
                self.assertEqual(wheel, event["wheel"])
                self.assertEqual(quantity, event["quantity"])
                self.assertEqual(value, event["rawValue"])
                self.assertFalse(event["unitsVerified"])
                self.assertFalse(event["sensorHealthVerified"])
                self.assertNotIn("celsius", event)

    def test_tpms_invalid_known_sentinels_statuses_and_private_suffixes_are_dropped(self):
        message = "C11 TPMS status=AVAILABLE wheel=FRONT_LEFT pressureRaw=250 receivedAgeMs=1"
        invalid = [message + " payload=private", message.replace("FRONT_LEFT", "DRIVER"),
                   message.replace("250", "-1"), message.replace("250", "4095"),
                   message.replace("250", "0250"), message.replace("250", "UNKNOWN"),
                   message.replace("AVAILABLE", "STALE"), message.replace("pressureRaw", "psi"),
                   message.replace("receivedAgeMs=1", "receivedAgeMs=60001"),
                   message.replace("receivedAgeMs=1", "receivedAgeMs=UNKNOWN"),
                   message.replace("pressureRaw=250", "tempRaw=-51"),
                   message.replace("pressureRaw=250", "tempRaw=151")]
        for bad in invalid:
            self.assertIsNone(parse_event(line(bad, tag="DiPlay-TPMS")))
        unavailable = message.replace("AVAILABLE", "UNAVAILABLE").replace("250", "UNKNOWN")
        self.assertIsNone(parse_event(line(unavailable, tag="DiPlay-TPMS"))["rawValue"])

    def test_tpms_flags_reproduce_only_observed_oem_zero_checks_and_signed_raw_codes(self):
        message = ("C11 TPMS_FLAGS wheel=FRONT_LEFT pressureCheck=ZERO_FLAGS_OBSERVED "
                   "temperatureCheck=NONZERO_FLAG_OBSERVED leakRaw=0 leakAgeMs=1 "
                   "pressureRaw=0 pressureAgeMs=60000 temperatureRaw=-2147483648 temperatureAgeMs=0")
        event = parse_event(line(message, tag="DiPlay-TPMS-FLAGS"))
        self.assertEqual("c11_tpms_flags", event["event"])
        self.assertEqual(0, event["flags"]["LEAK"]["rawValue"])
        self.assertEqual(-2147483648, event["flags"]["TEMPERATURE"]["rawValue"])
        self.assertFalse(event["sensorHealthVerified"])
        self.assertFalse(event["nonzeroEnumVerified"])
        partial = message.replace("pressureCheck=ZERO_FLAGS_OBSERVED", "pressureCheck=UNKNOWN")
        partial = partial.replace("leakRaw=0 leakAgeMs=1", "leakRaw=UNKNOWN leakAgeMs=UNKNOWN")
        self.assertEqual("UNKNOWN", parse_event(line(partial, tag="DiPlay-TPMS-FLAGS"))["pressureCheck"])
        nonzero = message.replace("pressureCheck=ZERO_FLAGS_OBSERVED", "pressureCheck=NONZERO_FLAG_OBSERVED")
        nonzero = nonzero.replace("leakRaw=0", "leakRaw=2147483647")
        self.assertEqual(2147483647, parse_event(line(nonzero, tag="DiPlay-TPMS-FLAGS"))["flags"]["LEAK"]["rawValue"])

    def test_tpms_flags_reject_missing_inconsistent_stale_and_out_of_int32_data(self):
        message = ("C11 TPMS_FLAGS wheel=FRONT_LEFT pressureCheck=ZERO_FLAGS_OBSERVED "
                   "temperatureCheck=UNKNOWN leakRaw=0 leakAgeMs=1 "
                   "pressureRaw=0 pressureAgeMs=2 temperatureRaw=UNKNOWN temperatureAgeMs=UNKNOWN")
        invalid = [message + " payload=private", message.replace("FRONT_LEFT", "private-phone"),
                   message.replace("pressureCheck=ZERO_FLAGS_OBSERVED", "pressureCheck=UNKNOWN"),
                   message.replace("temperatureCheck=UNKNOWN", "temperatureCheck=ZERO_FLAGS_OBSERVED"),
                   message.replace("leakRaw=0", "leakRaw=UNKNOWN"),
                   message.replace("leakRaw=0", "leakRaw=1"),
                   message.replace("leakRaw=0", "leakRaw=2147483648"),
                   message.replace("leakRaw=0", "leakRaw=-2147483649"),
                   message.replace("leakAgeMs=1", "leakAgeMs=60001"),
                   message.replace("leakAgeMs=1", "leakAgeMs=UNKNOWN"),
                   message.replace("temperatureAgeMs=UNKNOWN", "temperatureAgeMs=31536000001")]
        for bad in invalid:
            self.assertIsNone(parse_event(line(bad, tag="DiPlay-TPMS-FLAGS")))

    def test_vehicle_observations_require_exact_tags_and_uid_capture_includes_all_modules(self):
        messages = {
            "DiPlay-HVAC": "C11 HVAC status=AVAILABLE side=LEFT celsius=20.0 receivedAgeMs=0",
            "DiPlay-HVAC-FAN": "C11 HVAC_FAN status=OBSERVED level=2 receivedAgeMs=0",
            "DiPlay-TPMS": "C11 TPMS status=AVAILABLE wheel=REAR_RIGHT tempRaw=-1 receivedAgeMs=0",
            "DiPlay-TPMS-FLAGS": ("C11 TPMS_FLAGS wheel=REAR_RIGHT pressureCheck=UNKNOWN "
                                  "temperatureCheck=UNKNOWN leakRaw=UNKNOWN leakAgeMs=UNKNOWN "
                                  "pressureRaw=UNKNOWN pressureAgeMs=UNKNOWN "
                                  "temperatureRaw=UNKNOWN temperatureAgeMs=UNKNOWN"),
        }
        command = logcat_command(["adb"], 1010123)
        for tag, message in messages.items():
            self.assertIn(tag, command)
            self.assertIsNotNone(parse_event(line(message, tag=tag)))
            self.assertIsNone(parse_event(line(message, tag="other-app")))
            self.assertIsNone(parse_event(line(message + "\nprivate", tag=tag)))
        self.assertIn("--uid=1010123", command)

    def test_exterior_subscription_and_first_delivery_are_separate_from_verified_values(self):
        for state in ("REGISTERED", "REJECTED", "STOPPED"):
            message = "C11 EXTERIOR subscription=" + state
            event = parse_event(line(message, tag="DiPlay-EXTERIOR"))
            self.assertEqual("c11_exterior_subscription", event["event"])
            self.assertEqual(state, event["state"])
            self.assertNotIn("celsius", event)
            self.assertIsNone(parse_event(line(message + " payload=private", tag="DiPlay-EXTERIOR")))
        event = parse_event(line("C11 EXTERIOR callback=FIRST_CHANGE", tag="DiPlay-EXTERIOR"))
        self.assertEqual("c11_exterior_first_callback", event["event"])
        self.assertNotIn("celsius", event)
        self.assertIsNone(parse_event(line("C11 EXTERIOR subscription=private-phone", tag="DiPlay-EXTERIOR")))

    def test_verified_exterior_keeps_celsius_and_separate_hal_and_event_ages(self):
        message = ("C11 EXTERIOR: source=CONNECTED status=AVAILABLE celsius=-12.5 "
                   "oemTimestampNanos=123000000 oemEventAgeMs=20 halDeliveryAgeMs=0 "
                   "freshnessPolicy=OEM_EVENT_AND_HAL_DELIVERY maxAgeMs=60000 permissionGranted=true")
        event = parse_event(line(message, tag="DiPlay-EXTERIOR"))
        self.assertEqual(-12.5, event["celsius"])
        self.assertEqual(20, event["oemEventAgeMs"])
        self.assertEqual(0, event["halDeliveryAgeMs"])
        self.assertEqual("CELSIUS", event["units"])
        self.assertEqual(0.0, parse_event(line(message.replace("-12.5", "0.0"),
                                             tag="DiPlay-EXTERIOR"))["celsius"])

    def test_exterior_parser_rejects_false_availability_staleness_and_sensitive_suffixes(self):
        message = ("C11 EXTERIOR: source=CONNECTED status=AVAILABLE celsius=12.5 "
                   "oemTimestampNanos=123000000 oemEventAgeMs=20 halDeliveryAgeMs=0 "
                   "freshnessPolicy=OEM_EVENT_AND_HAL_DELIVERY maxAgeMs=60000 permissionGranted=true")
        invalid = [message + " payload=private", message.replace("12.5", "NaN"),
                   message.replace("12.5", "1e309"), message.replace("12.5", "UNKNOWN"),
                   message.replace("CONNECTED", "CLOSED_OR_DISABLED"),
                   message.replace("permissionGranted=true", "permissionGranted=false"),
                   message.replace("oemTimestampNanos=123000000", "oemTimestampNanos=0"),
                   message.replace("oemTimestampNanos=123000000", "oemTimestampNanos=-1"),
                   message.replace("oemTimestampNanos=123000000", "oemTimestampNanos=9223372036854775808"),
                   message.replace("oemEventAgeMs=20", "oemEventAgeMs=60001"),
                   message.replace("halDeliveryAgeMs=0", "halDeliveryAgeMs=60001")]
        for candidate in invalid:
            self.assertIsNone(parse_event(line(candidate, tag="DiPlay-EXTERIOR")))
        unavailable = message.replace("AVAILABLE", "STALE").replace("12.5", "UNKNOWN")
        self.assertIsNone(parse_event(line(unavailable, tag="DiPlay-EXTERIOR"))["celsius"])

    def test_invalid_oem_timestamp_remains_diagnostic_without_a_temperature(self):
        message = ("C11 EXTERIOR: source=CONNECTED status=INVALID_VALUE celsius=UNKNOWN "
                   "oemTimestampNanos=-1 oemEventAgeMs=UNKNOWN halDeliveryAgeMs=0 "
                   "freshnessPolicy=OEM_EVENT_AND_HAL_DELIVERY maxAgeMs=60000 permissionGranted=true")
        event = parse_event(line(message, tag="DiPlay-EXTERIOR"))
        self.assertEqual("INVALID_VALUE", event["readingState"])
        self.assertEqual(-1, event["oemTimestampNanos"])
        self.assertIsNone(event["celsius"])

    def test_ambient_observations_stay_raw_and_do_not_become_celsius_or_sensor_health(self):
        for channel, key, number in (("INTERIOR", "interiorRawDouble", "23.5"),
                                     ("EXTERIOR_CORRECTED", "exteriorCorrectedRawDouble", "-12.25")):
            message = (f"C11 AMBIENT status=OBSERVED_UNVERIFIED channel={channel} "
                       f"{key}={number} receivedAgeMs=0")
            event = parse_event(line(message, tag="DiPlay-AMBIENT"))
            self.assertEqual(float(number), event["rawValue"])
            self.assertFalse(event["unitsVerified"])
            self.assertFalse(event["sensorValidityVerified"])
            self.assertNotIn("celsius", event)
            self.assertIsNone(parse_event(line(message + " payload=private", tag="DiPlay-AMBIENT")))
            self.assertIsNone(parse_event(line(message, tag="other-app")))

    def test_ambient_parser_rejects_nonfinite_unbounded_and_inconsistent_observations(self):
        message = ("C11 AMBIENT status=OBSERVED_UNVERIFIED channel=INTERIOR "
                   "interiorRawDouble=23.5 receivedAgeMs=1")
        invalid = [message.replace("23.5", value) for value in
                   ("NaN", "Infinity", "1e309", "9" * 100, "UNKNOWN", "private-phone")]
        invalid += [message.replace("interiorRawDouble", "exteriorCorrectedRawDouble"),
                    message.replace("OBSERVED_UNVERIFIED", "AVAILABLE"),
                    message.replace("OBSERVED_UNVERIFIED", "UNAVAILABLE"),
                    message.replace("receivedAgeMs=1", "receivedAgeMs=UNKNOWN"),
                    message.replace("receivedAgeMs=1", "receivedAgeMs=31536000001")]
        for candidate in invalid:
            self.assertIsNone(parse_event(line(candidate, tag="DiPlay-AMBIENT")))
        cleared = message.replace("OBSERVED_UNVERIFIED", "STALE").replace("23.5", "UNKNOWN")
        self.assertIsNone(parse_event(line(cleared, tag="DiPlay-AMBIENT"))["rawValue"])

    def test_sunshade_parser_keeps_raw_and_oem_coordinates_without_open_closed_claim(self):
        message = "C11 SUNSHADE status=AVAILABLE positionRaw=25 oemUiPosition=75 receivedAgeMs=0"
        event = parse_event(line(message, tag="DiPlay-SUNSHADE"))
        self.assertEqual(25, event["positionRaw"])
        self.assertEqual(75, event["oemUiPosition"])
        self.assertFalse(event["directionVerified"])
        self.assertFalse(event["writeControlAvailable"])
        self.assertNotIn("open", event)
        for candidate in (message + " payload=private", message.replace("25", "101"),
                          message.replace("75", "76"), message.replace("25", "UNKNOWN"),
                          message.replace("AVAILABLE", "STALE"),
                          message.replace("receivedAgeMs=0", "receivedAgeMs=31536000001")):
            self.assertIsNone(parse_event(line(candidate, tag="DiPlay-SUNSHADE")))
        cleared = "C11 SUNSHADE status=UNAVAILABLE positionRaw=UNKNOWN oemUiPosition=UNKNOWN receivedAgeMs=UNKNOWN"
        self.assertIsNone(parse_event(line(cleared, tag="DiPlay-SUNSHADE"))["positionRaw"])

    def test_uid_command_does_not_pin_process_and_keeps_replay_cursor(self):
        command = logcat_command(["adb", "-s", "test:5555"], 1010123, "10-19 12:48:02.574")
        self.assertIn("--uid=1010123", command)
        self.assertFalse(any("--pid" in value for value in command))
        self.assertEqual("10-19 12:48:02.574", command[command.index("-T") + 1])

    def test_package_uid_requires_one_exact_package(self):
        self.assertEqual(1010123, package_uid("package:com.example.test uid:1010123\n", "com.example.test"))
        for output in ("package:com.example.test.extra uid:1010123", "package:com.example.test uid:1\npackage:com.example.test uid:2"):
            with self.assertRaises(ValueError):
                package_uid(output, "com.example.test")

    def test_protocol_canvas_and_failure_events_are_distinct(self):
        event = parse_event(line("ultra reference failure reason=UI_SYNC_CHANNEL_TIMEOUT; rebuilding ordinary CarPlay"))
        self.assertEqual("UI_SYNC_CHANNEL_TIMEOUT", event["reason"])
        event = parse_event(line("ultra reference failure reason=FIRST_FRAME_TIMEOUT; rebuilding ordinary CarPlay"))
        self.assertEqual("ultra_fallback", event["event"])
        self.assertEqual("FIRST_FRAME_TIMEOUT", event["reason"])
        self.assertEqual("ui_sync_setup", parse_event(line("ultra channel: UI Sync SETUP received"))["event"])
        self.assertEqual("ui_sync_owner", parse_event(line("ultra channel: protocol owner attached"))["event"])
        self.assertTrue(parse_event(line("ultra output: canvasSubmitted=true physicalPresentation=unverified"))["submitted"])
        self.assertTrue(parse_event(line("ultra handshake: UI Sync negotiated=true; telemetry=false renderer=false"))["enabled"])

    def test_recovery_progress_accepts_only_fixed_states_and_bounded_scalar_fields(self):
        message = ("ultra recovery: reason=FIRST_FRAME_TIMEOUT resumed=true surfaceReady=true surfaceValid=true "
                   "canvasAttempts=9 canvasAccepted=2 output=LOCKING phase=PUBLISHING decoded=12 redraws=7 "
                   "compositions=14 unavailable=1 composed=9 publications=9 stale=0")
        event = parse_event(line(message))
        self.assertEqual("ultra_recovery_diagnostic", event["event"])
        self.assertTrue(event["surfaceReady"])
        self.assertEqual(9, event["canvasAttempts"])
        self.assertEqual("LOCKING", event["output"])
        for bad in (message + " private", message.replace("PUBLISHING", "secret-value"),
                    message.replace("LOCKING", "network-name"), message.replace("decoded=12", "decoded=-1"),
                    message.replace("decoded=12", "decoded=" + "9" * 20)):
            self.assertIsNone(parse_event(line(bad)))

    def test_cluster_stream_setup_is_separate_from_surface_and_activation(self):
        event = parse_event(line("airplay screen stream type=111 dataPort=12345"))
        self.assertEqual("screen_stream_setup", event["event"])
        self.assertEqual(111, event["streamType"])
        self.assertTrue(event["accepted"])
        self.assertNotIn("12345", json.dumps(event))
        event = parse_event(line("airplay screen stream type=111 dataPort=rejected"))
        self.assertFalse(event["accepted"])
        self.assertFalse(parse_event(line("cluster stream active=false", tag="DiPlay-Cluster"))["active"])
        self.assertTrue(parse_event(line("cluster stream active=true", tag="DiPlay-Cluster"))["active"])
        self.assertEqual("CREATED", parse_event(line("cluster surface created", tag="DiPlay-Cluster"))["state"])
        self.assertEqual(720, parse_event(line("cluster surface 720x720", tag="DiPlay-Cluster"))["width"])
        for message in ("cluster surface 9999x720", "cluster stream active=true private=secret"):
            self.assertIsNone(parse_event(line(message, tag="DiPlay-Cluster")))
        for message in ("airplay screen stream type=111 dataPort=65536", "airplay screen stream type=111 dataPort=12345 private=secret"):
            self.assertIsNone(parse_event(line(message)))

    def test_video_stats_keep_stream_identity_without_claiming_presentation(self):
        stats = ("video stats stream=111 rx=12.5fps shown=10.5fps maxGap=123ms kbps=456 recoveries=1 "
                 "touch2frame avg=-1ms max=0ms n=0 touchSendMax=0ms decode p50=3ms p90=5ms late=1 delay=20ms")
        event = parse_event(line(stats, tag="DiPlay-VideoStats"))
        self.assertEqual(111, event["streamType"])
        self.assertEqual(12.5, event["receivedFps"])
        self.assertEqual(10.5, event["submittedFps"])
        self.assertFalse(event["physicalPresentationVerified"])
        event = parse_event(line(stats.replace("12.5", "12,5").replace("10.5", "10,5"), tag="DiPlay-VideoStats"))
        self.assertEqual(12.5, event["receivedFps"])
        for altered in (stats + " private=secret", stats.replace("stream=111", "mirror=private-name"),
                        stats.replace("12.5fps", "9999.5fps")):
            self.assertIsNone(parse_event(line(altered, tag="DiPlay-VideoStats")))

    def test_first_frame_transport_and_decoder_queue_are_separate_typed_observations(self):
        event = parse_event(line("airplay screen first frame received type=111"))
        self.assertEqual("screen_first_frame_received", event["event"])
        event = parse_event(line("video decoder first submission type=111 width=720 height=720 composition=false mirror=false"))
        self.assertEqual("decoder_first_frame_queued", event["event"])
        self.assertEqual(111, event["streamType"])
        self.assertEqual(720, event["width"])
        self.assertFalse(event["physicalPresentationVerified"])
        for message in ("airplay screen first frame received type=111 private=secret",
                        "video decoder first submission type=111 width=9999 height=720 composition=false mirror=false",
                        "video decoder first submission type=111 width=720 height=720 composition=false mirror=private-name"):
            self.assertIsNone(parse_event(line(message)))

    def test_cluster_buffer_classification_never_contains_image_or_coordinates(self):
        for result, content in [("SUCCESS", "VARIED"), ("SUCCESS", "UNIFORM_LIGHT"), ("SUCCESS", "UNKNOWN"),
                                ("NO_DATA", "UNKNOWN"), ("TIMEOUT", "UNKNOWN"), ("ERROR", "UNKNOWN")]:
            event = parse_event(line(f"cluster buffer sample result={result} content={content}", tag="DiPlay-Cluster"))
            self.assertEqual("cluster_buffer_sample", event["event"])
            self.assertFalse(event["imageSaved"])
            self.assertFalse(event["physicalPresentationVerified"])
        for message in ("cluster buffer sample result=NO_DATA content=VARIED",
                        "cluster buffer sample result=SUCCESS content=VARIED coords=private",
                        "cluster buffer sample result=SUCCESS content=RGB(1,2,3)"):
            self.assertIsNone(parse_event(line(message, tag="DiPlay-Cluster")))

    def test_unrecognized_and_sensitive_messages_are_dropped_or_reduced_to_enums(self):
        secret = "private-phone-name location=51.123 password=secret"
        for message in (secret, "TRACE " + secret, "ultra channel: " + secret,
                        "ultra reference failure reason=" + secret + "; rebuilding ordinary CarPlay"):
            self.assertIsNone(parse_event(line(message)))
        event = parse_event(line("CarPlay transport stopped generation=1: " + secret))
        self.assertEqual("unclassified", event["reason"])
        self.assertNotIn(secret, json.dumps(event))
        self.assertIsNone(parse_event(line("airplay attachment VPN revoked", tag="other-app")))

    def test_socket_diagnostics_keep_category_without_stack_or_remote_identity(self):
        event = parse_event(line("airplay control closing reason=control I/O failed: Software caused connection abort activeStreams=[130, 110]"))
        self.assertEqual("connection_aborted", event["reason"])
        self.assertNotIn("activeStreams", event)
        event = parse_event(line("airplay attachment release reason=transport_error generation=1 active=true listeners=2"))
        self.assertEqual("transport_error", event["reason"])
        self.assertEqual(2, event["listeners"])
        self.assertIsNone(parse_event(line("\tat java.net.socketAccept(private details)")))

    def test_layout_relation_is_recorded_without_layout_id_or_resource_acceptance_claim(self):
        message = ("ultra handshake: script observation panelIndex=0 direction=received type=2 command=6 "
                   "layoutIdPresent=true layoutRelation=matchesRequest resourceAcceptance=unverified")
        event = parse_event(line(message))
        self.assertEqual("ui_sync_observation", event["event"])
        self.assertEqual("matchesRequest", event["layoutRelation"])
        self.assertEqual(6, event["command"])
        self.assertFalse(event["resourceAcceptanceVerified"])
        self.assertIsNone(parse_event(line(message + " layoutID=private-extra-data")))

    def test_vehicle_services_are_distinguished_from_ui_sync_and_payloads_are_dropped(self):
        event = parse_event(line("ultra handshake: UI Sync negotiated=true; vehicleStateNegotiated=true renderer=false"))
        self.assertTrue(event["vehicleStateNegotiated"])
        self.assertEqual("HIGH", parse_event(line("vehicle state channel opened lane=HIGH"))["lane"])
        self.assertEqual("writeRequest", parse_event(line("vehicle state answered=writeRequest lane=NORMAL"))["command"])
        self.assertIsNone(parse_event(line("vehicle state answered=writeRequest lane=NORMAL value=private")))

    def test_parked_observation_preserves_only_allowlisted_status_and_distinct_ages(self):
        message = ("C11 parked observation: status=CONNECTED permission=true reading=AVAILABLE "
                   "parked=true policy=CURRENT_HAL_SNAPSHOT oemAgeMs=123456 deliveryAgeMs=0")
        event = parse_event(line(message))
        self.assertEqual("c11_parked_observation", event["event"])
        self.assertEqual("CONNECTED", event["sourceStatus"])
        self.assertTrue(event["permissionGranted"])
        self.assertTrue(event["parked"])
        self.assertEqual(123456, event["oemEventAgeMs"])
        self.assertEqual(0, event["halDeliveryAgeMs"])
        self.assertEqual("CURRENT_HAL_SNAPSHOT", event["freshnessPolicy"])
        unknown = parse_event(line(message.replace("CONNECTED", "CLOSED_OR_DISABLED")
                                   .replace("permission=true", "permission=false")
                                   .replace("reading=AVAILABLE", "reading=UNKNOWN")
                                   .replace("parked=true", "parked=null")
                                   .replace("oemAgeMs=123456", "oemAgeMs=null")
                                   .replace("deliveryAgeMs=0", "deliveryAgeMs=null")))
        self.assertFalse(unknown["permissionGranted"])
        self.assertIsNone(unknown["parked"])
        self.assertIsNone(unknown["oemEventAgeMs"])
        self.assertIsNone(unknown["halDeliveryAgeMs"])

    def test_parked_observation_rejects_unrecognized_tokens_payloads_and_unbounded_ages(self):
        message = ("C11 parked observation: status=CONNECTED permission=true reading=AVAILABLE "
                   "parked=false policy=CURRENT_HAL_SNAPSHOT oemAgeMs=123 deliveryAgeMs=0")
        invalid = [message + " payload=private", message + " ", message + "\nprivate",
                   message.replace("CONNECTED", "private-phone"),
                   message.replace("permission=true", "permission=yes"),
                   message.replace("reading=AVAILABLE", "reading=private"),
                   message.replace("parked=false", "parked=0"),
                   message.replace("CURRENT_HAL_SNAPSHOT", "private-policy")]
        for age in ("-1", "+1", "01", "1.5", "1e3", "NaN", "31536000001", "9" * 1000, "private"):
            invalid.extend([message.replace("oemAgeMs=123", "oemAgeMs=" + age),
                            message.replace("deliveryAgeMs=0", "deliveryAgeMs=" + age)])
        for candidate in invalid:
            with self.subTest(candidate=candidate[:200]):
                self.assertIsNone(parse_event(line(candidate)))
        self.assertIsNone(parse_event(line(message, tag="other-app")))
        boundary = parse_event(line(message.replace("oemAgeMs=123", "oemAgeMs=31536000000")))
        self.assertEqual(31536000000, boundary["oemEventAgeMs"])

    def test_wire_capabilities_do_not_conflate_local_soc_with_siri_battery(self):
        message = ("vehicle state capabilities range=true climateZones=0 radio=false "
                   "highVoltageBattery=false localSocAvailable=true")
        event = parse_event(line(message))
        self.assertEqual("vehicle_state_capabilities", event["event"])
        self.assertTrue(event["range"])
        self.assertTrue(event["localSocAvailable"])
        self.assertFalse(event["highVoltageBattery"])
        self.assertEqual(0, event["climateZones"])
        for bad in (message + " payload=private", message.replace("climateZones=0", "climateZones=3"),
                    message.replace("radio=false", "radio=private")):
            self.assertIsNone(parse_event(line(bad)))

    def test_legacy_vehicle_status_reports_subscription_and_field_presence_without_values(self):
        start = parse_event(line("iap2 rx=0xa100 start-vehicle-status provider=true"))
        self.assertEqual("legacy_vehicle_status_subscription", start["event"])
        self.assertTrue(start["enabled"])
        self.assertTrue(start["provider"])
        stop = parse_event(line("iap2 rx=0xa102 stop-vehicle-status"))
        self.assertFalse(stop["enabled"])
        message = "iap2 tx=0xa101 vehicle-status range=227km battery=12.3% warning=null"
        sent = parse_event(line(message))
        self.assertEqual("legacy_vehicle_status_sent", sent["event"])
        self.assertTrue(sent["hasDisplayedBatteryPercentage"])
        self.assertTrue(sent["hasRange"])
        self.assertFalse(sent["phoneAcceptanceVerified"])
        self.assertFalse(sent["rangeWarningKnown"])
        self.assertNotIn("12.3", json.dumps(sent))
        self.assertNotIn("227", json.dumps(sent))
        for bad in (message + " private-payload", message.replace("227km", "99999km"),
                    message.replace("12.3%", "100.1%"), message.replace("12.3%", "NaN%"),
                    message.replace("null", "private")):
            self.assertIsNone(parse_event(line(bad)))

    def test_fatal_events_do_not_copy_thread_names_or_native_details(self):
        for tag, message, kind in (("AndroidRuntime", "FATAL EXCEPTION: private-thread", "java_fatal"),
                                   ("libc", "Fatal signal 11 (SIGSEGV), private-details", "native_fatal")):
            event = parse_event(line(message, tag=tag))
            self.assertEqual(kind, event["event"])
            self.assertNotIn("private", json.dumps(event))

    def test_channel_closures_and_successful_handoff_are_distinct(self):
        for message, expected in (
            ("airplay event read failed", "event_channel_read_error"),
            ("airplay event connection closed", "event_channel_closed"),
            ("airplay event accept failed", "event_channel_accept_error"),
            ("airplay event decrypt failed encrypted=123", "event_channel_decrypt_error"),
            ("tunneled iAP2 control failed", "iap_control_error"),
            ("Video: first frame rendered", "first_decoded_frame"),
            ("wireless handoff ready; closing Bluetooth bootstrap transport", "bluetooth_handoff"),
        ):
            self.assertEqual(expected, parse_event(line(message))["event"])
            self.assertIsNone(parse_event(line(message + " private-data")))
            self.assertIsNone(parse_event(line(message, tag="other-app")))

    def test_screen_close_does_not_export_error_messages(self):
        for reason, expected in (("peer EOF", "peer_eof"), ("Socket closed", "socket_closed"),
                                 ("private-name location=51 password=secret", "unclassified")):
            event = parse_event(line("screen stream ended type=110 reason=" + reason))
            self.assertEqual("screen_stream_closed", event["event"])
            self.assertEqual(110, event["streamType"])
            self.assertEqual(expected, event["reason"])
            self.assertNotIn("private", json.dumps(event))
        self.assertIsNone(parse_event(line("screen stream ended type=999 reason=peer EOF")))

    def test_close_trigger_contains_only_the_first_fixed_source_not_payload(self):
        message = "airplay session closing source=EVENT_IO"
        self.assertEqual("EVENT_IO", parse_event(line(message))["source"])
        self.assertEqual("session_close_trigger", parse_event(line(message))["event"])
        for bad in (message + " payload=private", message.replace("EVENT_IO", "private-phone")):
            self.assertIsNone(parse_event(line(bad)))

    def test_ui_requests_export_no_urls_or_parameters(self):
        for target in ("DEFAULT", "VIDEO_PLAYBACK", "MAP_CLUSTER", "OTHER", "INVALID"):
            self.assertEqual(target, parse_event(line(f"airplay UI request target={target}"))["target"])
        self.assertIsNone(parse_event(line("airplay UI request target=OTHER url=private")))
        self.assertIsNone(parse_event(line("airplay UI request target=MAP_CLUSTER url=maps:/private")))
        self.assertIsNone(parse_event(line("airplay UI request target=private")))

    def test_settings_stream_actions_are_requests_without_input_identifiers(self):
        base = ("ultra handshake: script observation panelIndex=0 direction=received type=2 command=1 "
                "layoutIdPresent=false layoutRelation=unavailable ")
        for action in ("NONE", "PRESENT", "DISMISS", "INVALID", "AMBIGUOUS"):
            message = base + f"settingsStreamAction={action} resourceAcceptance=unverified"
            event = parse_event(line(message))
            self.assertEqual(action, event["settingsStreamAction"])
            self.assertFalse(event["resourceAcceptanceVerified"])
            self.assertIsNone(parse_event(line(message + " input=private-settings")))
            if action != "NONE":
                self.assertIsNone(parse_event(line(message.replace("command=1 ", "command=6 "))))
                self.assertIsNone(parse_event(line(message.replace("type=2 ", "type=1 "))))
        self.assertEqual("UNOBSERVED", parse_event(line(base + "resourceAcceptance=unverified"))["settingsStreamAction"])
        self.assertIsNone(parse_event(line(base + "settingsStreamAction=private resourceAcceptance=unverified")))

    def test_real_subprocess_restarts_logcat_follows_new_pid_and_never_saves_raw_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake = root / "fake_adb.py"
            state = root / "state"
            fake.write_text(
                "import pathlib, sys, time\n"
                "args=sys.argv[1:]\n"
                "if '--help' in args: print('--uid'); sys.exit(0)\n"
                "if 'packages' in args: print('package:com.example.test uid:1010123'); sys.exit(0)\n"
                f"state=pathlib.Path({str(state)!r})\n"
                "n=int(state.read_text())+1 if state.exists() else 1\n"
                "state.write_text(str(n))\n"
                f"print({line('ultra channel: UI Sync SETUP received', pid=111)!r}, flush=True)\n"
                "print('raw-private-phone-password-location ' * 5000, flush=True)\n"
                "if n>1:\n"
                f" print({line('airplay attachment VPN revoked', pid=222)!r}, flush=True)\n"
                " time.sleep(10)\n"
            )
            output = root / "events.jsonl"
            capture([sys.executable, str(fake)], "com.example.test", output, seconds=2)
            events = [json.loads(value) for value in output.read_text().splitlines()]
            self.assertEqual({111, 222}, {event["pid"] for event in events if event["event"] == "process_observed"})
            self.assertEqual(1, sum(event["event"] == "ui_sync_setup" for event in events))
            self.assertTrue(any(event["event"] == "capture_gap" for event in events))
            self.assertEqual("capture_stopped", events[-1]["event"])
            self.assertNotIn("raw-private", output.read_text())
            with self.assertRaises(FileExistsError):
                capture([sys.executable, str(fake)], "com.example.test", output, seconds=1)

    def test_unsupported_uid_filter_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            fake = Path(directory, "fake.py")
            fake.write_text("print('logcat help without UID filtering')\n")
            output = Path(directory, "never-created.jsonl")
            with self.assertRaises(ValueError):
                capture([sys.executable, str(fake)], "com.example.test", output, 1)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
