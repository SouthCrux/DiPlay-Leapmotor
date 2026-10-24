#!/usr/bin/env python3
"""Capture allowlisted DiPlay events by app UID, across process restarts.

No raw log lines, adb error output, phone identity, location or payloads are saved.
Does not connect ADB, launch/install apps, or modify the device. Requires logcat --uid.
"""
import argparse
import collections
import json
import math
from pathlib import Path
import re
import selectors
import subprocess
import time


HEADER = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+([^: ]+)\s*: (.*)$")
FAILURES = r"UI_SYNC_NOT_NEGOTIATED|UI_SYNC_CHANNEL_TIMEOUT|UI_SYNC_CHANNEL_CLOSED|BINDING_MISMATCH|RENDERER_FAILURE|FIRST_FRAME_TIMEOUT"
RELEASES = r"wired_replacement|wired_attach_failed|wireless_replacement|wireless_attach_failed|detach|wireless_owner_detach|service_destroyed|transport_error"
CLOSE_SOURCES = r"OWNER_CLOSED|CONTROL_EOF|CONTROL_IO|CONTROL_DECRYPT|EVENT_EOF|EVENT_IO|EVENT_DECRYPT|EVENT_SETUP_FAILED|SCREEN_CLOSED|IAP_FAILED|RENDERER_FAILED"
# Retain useful old ON_CHANGE ages, but reject unbounded numeric strings or impossible uptime.
PARKED_MAX_AGE_MS = 365 * 24 * 60 * 60 * 1000
PARKED_STATUS = r"CONNECTED|CONNECTING|CLOSED_OR_DISABLED"
PARKED_READING = r"UNKNOWN|AVAILABLE|NOT_EXPOSED|ACCESS_DENIED|INVALID_CONFIG|UNAVAILABLE|ERROR|STALE|INVALID_VALUE"
PARKED_AGE = r"null|0|[1-9][0-9]{0,10}"
VEHICLE_AGE = r"UNKNOWN|0|[1-9][0-9]{0,10}"
VEHICLE_NUMBER = r"UNKNOWN|-?[0-9]{1,18}(?:\.[0-9]{1,18})?(?:[Ee][+-]?[0-9]{1,3})?"
VEHICLE_INT = r"UNKNOWN|0|-?[1-9][0-9]{0,9}"
WHEEL = r"FRONT_LEFT|FRONT_RIGHT|REAR_LEFT|REAR_RIGHT"
TPMS_CHECK = r"UNKNOWN|ZERO_FLAGS_OBSERVED|NONZERO_FLAG_OBSERVED"
SUBSYSTEM_CHANNELS = {
    "WINDOWS_ROOF": {"LEFT_MIRROR_HEAT_STATUS", "RIGHT_MIRROR_HEAT_STATUS", "FRONT_LEFT_WINDOW_POSITION", "FRONT_RIGHT_WINDOW_POSITION", "REAR_LEFT_WINDOW_POSITION",
                     "REAR_RIGHT_WINDOW_POSITION", "GLASS_ROOF_STATUS", "SUNSHADE_POSITION"},
    "SEATS": {"FRONT_LEFT_SEAT_SLIDE_POSITION", "FRONT_LEFT_SEAT_BASE_FRONT_POSITION", "FRONT_LEFT_SEAT_BACKREST_POSITION", "DRIVER_SEAT_HEAT", "PASSENGER_SEAT_HEAT", "REAR_LEFT_SEAT_HEAT", "REAR_RIGHT_SEAT_HEAT",
              "DRIVER_SEAT_VENT", "PASSENGER_SEAT_VENT", "REAR_LEFT_SEAT_VENT", "REAR_RIGHT_SEAT_VENT",
              "FRONT_PRIMARY_OCCUPANCY_STATUS", "FRONT_SECONDARY_OCCUPANCY_STATUS"},
    "LIGHTS": {"MAIN_LAMP_FEEDBACK", "LOW_BEAM_STATUS", "HIGH_BEAM_STATUS", "REAR_FOG_STATUS", "LEFT_TURN_SWITCH", "RIGHT_TURN_SWITCH",
               "REAR_LEFT_READING_LIGHT_STATUS", "REAR_RIGHT_READING_LIGHT_STATUS"},
    "ENERGY": {"CLTC_RANGE", "WLTC_RANGE", "RANGE_STANDARD"},
    "WIPERS": {"FRONT_WIPER_MAINTENANCE_OFF_FAILURE", "FRONT_WIPER_MAINTENANCE_ON_FAILURE", "FRONT_WIPER_START_FAILURE", "REAR_WIPER_MAINTENANCE_ON_FAILURE", "REAR_WIPER_MAINTENANCE_OFF_FAILURE", "REAR_WIPER_START_FAILURE"},
    "DRIVING": {"GEAR", "READY_STATUS", "BRAKE_CONTROLLER_STATUS"},
    "WARNINGS": {"FRONT_PRIMARY_BELT", "FRONT_SECONDARY_BELT", "REAR_LEFT_BELT", "REAR_MIDDLE_BELT", "REAR_RIGHT_BELT"},
    "CLIMATE": {"HVAC_SMART_CLEAR_STATUS", "HVAC_SYNC_FEEDBACK", "REAR_HVAC_POWER_STATUS", "REAR_HVAC_FAN_LEVEL", "REAR_HVAC_AIR_DISTRIBUTION", "REAR_HVAC_AUTO_STATUS", "CABIN_PM25_LEVEL", "HVAC_AUTO_CLEAR_STATUS", "HVAC_POWER_STATUS", "HVAC_AUTO_STATUS", "HVAC_AC_STATUS", "HVAC_AIR_DISTRIBUTION",
                "HVAC_FRONT_DEFROST", "HVAC_REAR_DEFROST", "HVAC_RECIRCULATION_STATUS",
                "HVAC_RECIRCULATION_AUTO_STATUS"},
}
MEASUREMENT_ALIASES = {'CURRENT_TRIP_ENERGY': 'CURRENT_TRIP_CONSUMPTION', 'PREVIOUS_TRIP_DISTANCE': 'RECHARGE_TRIP_DISTANCE', 'PREVIOUS_TRIP_ENERGY': 'RECHARGE_TRIP_CONSUMPTION', 'TRIP_A_ENERGY': 'TRIP_A_CONSUMPTION'}
DURATION_CHANNELS = {"CURRENT_TRIP_DURATION", "RECHARGE_TRIP_DURATION", "TRIP_A_DURATION"}
MEASUREMENT_CHANNELS = {"CURRENT_TRIP_DISTANCE", "CURRENT_TRIP_CONSUMPTION", "RECHARGE_TRIP_DISTANCE",
                        "RECHARGE_TRIP_CONSUMPTION", "TRIP_A_DISTANCE", "TRIP_A_CONSUMPTION", "VEHICLE_SPEED", "REAR_HVAC_DISPLAY_TEMPERATURE"} | DURATION_CHANNELS
MEASUREMENT_MODULES = {channel: ("DRIVING" if channel == "VEHICLE_SPEED" else
                                 "CLIMATE" if channel == "REAR_HVAC_DISPLAY_TEMPERATURE" else "ENERGY")
                       for channel in MEASUREMENT_CHANNELS}
ERRORS = {"Socket closed": "socket_closed", "Software caused connection abort": "connection_aborted",
          "Connection reset": "connection_reset", "Connection reset by peer": "connection_reset",
          "Broken pipe": "broken_pipe", "Read timed out": "read_timeout"}
CHANNEL_EVENTS = {
    "airplay event connection closed": "event_channel_closed",
    "airplay event read failed": "event_channel_read_error",
    "airplay event accept failed": "event_channel_accept_error",
    "tunneled iAP2 control failed": "iap_control_error",
    "Video: first frame rendered": "first_decoded_frame",
    "wireless type-130 tunnel data stream accepted": "iap_tunnel_accepted",
    "wireless handoff ready; closing Bluetooth bootstrap transport": "bluetooth_handoff",
    "wireless handoff timed out waiting for tunnel iAP2 readiness": "handoff_deadline",
}


def optional_integer(value):
    return None if value == "UNKNOWN" else int(value)


def valid_receipt(value, age):
    """Only local receipt age; no OEM timestamp or physical acceptance claim."""
    return ((age is None or 0 <= age <= PARKED_MAX_AGE_MS) and
            (value is None or age is not None and age <= 60000))


def tpms_zero_check(*values):
    if any(value is None for value in values):
        return "UNKNOWN"
    return "ZERO_FLAGS_OBSERVED" if all(value == 0 for value in values) else "NONZERO_FLAG_OBSERVED"


def parse_event(line):
    """Extract fixed metadata and bounded vehicle observations. Never return log text."""
    match = HEADER.fullmatch(line.rstrip("\r\n"))
    if not match:
        return None
    stamp, pid, tid, tag, message = match.groups()
    event = None
    if tag == "AndroidRuntime" and message.startswith("FATAL EXCEPTION:"):
        event = dict(event="java_fatal")
    elif tag == "libc" and message.startswith("Fatal signal "):
        event = dict(event="native_fatal")
    elif tag == "DiPlay-SUBSYSTEM":
        m = re.fullmatch(r"C11 SUBSYSTEM module=([A-Z_]{1,30}) channel=([A-Z0-9_]{1,40}) "
                         r"status=(UNAVAILABLE|OBSERVED_UNVERIFIED|STALE) raw=(" + VEHICLE_INT + r") "
                         r"receivedAgeMs=(" + VEHICLE_AGE + r")", message)
        if m:
            value, age = optional_integer(m[4]), optional_integer(m[5])
            if (m[2] not in SUBSYSTEM_CHANNELS.get(m[1], set()) or
                    (m[3] == "OBSERVED_UNVERIFIED") != (value is not None) or
                    not valid_receipt(value, age) or
                    value is not None and not -2147483648 <= value <= 2147483647):
                return None
            if value is not None and ((m[1] == "SEATS" and m[2].endswith(("_HEAT", "_VENT")) and not 0 <= value <= 3) or
                    (m[1] in ("WINDOWS_ROOF", "SEATS") and m[2].endswith("_POSITION") and not 0 <= value <= 100) or
                    (m[1] == "ENERGY" and m[2] in ("CLTC_RANGE", "WLTC_RANGE") and value < 0) or
                    (m[2] == "CABIN_PM25_LEVEL" and not 1 <= value <= 6) or
                    (m[2] in ("REAR_HVAC_POWER_STATUS", "REAR_HVAC_AUTO_STATUS") and value not in (0, 1)) or
                    (m[2] == "REAR_HVAC_FAN_LEVEL" and not 0 <= value <= 7)):
                return None
            event = dict(event="c11_subsystem_observation", module=m[1], channel=m[2], readingState=m[3],
                         rawValue=value, receivedAgeMs=age, semanticsVerified=False, writeControlAvailable=False)
    elif tag == "DiPlay-MEASUREMENTS":
        m = re.fullmatch(r"C11 MEASUREMENT channel=([A-Z0-9_]{1,40}) "
                         r"status=(UNAVAILABLE|OBSERVED_UNVERIFIED|STALE) raw=(" + VEHICLE_NUMBER + r") "
                         r"receivedAgeMs=(" + VEHICLE_AGE + r")", message)
        if m:
            value, age = None if m[3] == "UNKNOWN" else float(m[3]), optional_integer(m[4])
            channel = MEASUREMENT_ALIASES.get(m[1], m[1])
            if (channel not in MEASUREMENT_CHANNELS or
                    (m[2] == "OBSERVED_UNVERIFIED") != (value is not None) or
                    not valid_receipt(value, age) or value is not None and (not math.isfinite(value) or
                    channel in DURATION_CHANNELS and (not value.is_integer() or not 0 <= value <= 2147483647) or
                    channel == "REAR_HVAC_DISPLAY_TEMPERATURE" and not 18 <= value <= 32)):
                return None
            event = dict(event="c11_measurement_observation", channel=channel, readingState=m[2],
                         rawValue=value, receivedAgeMs=age, unitsVerified=False,
                         semanticsVerified=False, writeControlAvailable=False)
    elif tag == "DiPlay-CHARGING":
        m = re.fullmatch(r"C11 CHARGING status=(UNAVAILABLE|OBSERVED_UNVERIFIED|STALE) "
                         r"channel=(CHARGE_STATUS|AC_CONNECTION|DC_CONNECTION|AC_PLUG_LOCK_STATUS|ONBOARD_CHARGER_STATUS|CHARGE_TIME_REMAINING|CHARGE_TARGET_PERCENT) "
                         r"raw=(" + VEHICLE_INT + r") receivedAgeMs=(" + VEHICLE_AGE + r")", message)
        if m:
            value, age = optional_integer(m[3]), optional_integer(m[4])
            if ((m[1] == "OBSERVED_UNVERIFIED") != (value is not None) or
                    not valid_receipt(value, age) or
                    value is not None and not -2147483648 <= value <= 2147483647):
                return None
            if value is not None and ((m[2] == "CHARGE_TARGET_PERCENT" and not 50 <= value <= 100) or
                                      (m[2] == "CHARGE_TIME_REMAINING" and value == 0xffffff)):
                return None
            event = dict(event="c11_charging_observation", channel=m[2], readingState=m[1],
                         rawValue=value, receivedAgeMs=age, semanticsVerified=False,
                         writeControlAvailable=False)
    elif tag == "DiPlay-DOORS":
        m = re.fullmatch(r"C11 DOORS status=(UNAVAILABLE|OBSERVED_UNVERIFIED|STALE) "
                         r"channel=(DRIVER_DOOR|PASSENGER_DOOR|REAR_LEFT_DOOR|REAR_RIGHT_DOOR|TAILGATE_STATUS|TAILGATE_POSITION|POWER_TAILGATE_STATUS|HOOD_STATUS|VEHICLE_LOCK_STATUS|REAR_LEFT_CHILD_LOCK_STATUS|REAR_RIGHT_CHILD_LOCK_STATUS) "
                         r"raw=(" + VEHICLE_INT + r") receivedAgeMs=(" + VEHICLE_AGE + r")", message)
        if m:
            value, age = optional_integer(m[3]), optional_integer(m[4])
            if ((m[1] == "OBSERVED_UNVERIFIED") != (value is not None) or
                    not valid_receipt(value, age) or
                    value is not None and not -2147483648 <= value <= 2147483647):
                return None
            if m[2] == "HOOD_STATUS" and value is not None and value not in (0, 1):
                return None
            if m[2] == "TAILGATE_POSITION" and value is not None and not 0 <= value <= 100:
                return None
            event = dict(event="c11_door_observation", channel=m[2], readingState=m[1],
                         rawValue=value, receivedAgeMs=age, semanticsVerified=False,
                         writeControlAvailable=False)
    elif tag == "DiPlay-HVAC":
        m = re.fullmatch(r"C11 HVAC status=(UNAVAILABLE|AVAILABLE|STALE) side=(LEFT|RIGHT) "
                         r"celsius=(" + VEHICLE_NUMBER + r") receivedAgeMs=(" + VEHICLE_AGE + r")", message)
        if m:
            value = None if m[3] == "UNKNOWN" else float(m[3])
            age = optional_integer(m[4])
            if ((m[1] == "AVAILABLE") != (value is not None) or not valid_receipt(value, age) or
                    value is not None and (not math.isfinite(value) or not 18 <= value <= 32)):
                return None
            event = dict(event="c11_hvac_target", side=m[2], readingState=m[1], celsius=value,
                         receivedAgeMs=age, writeControlAvailable=False)
    elif tag == "DiPlay-HVAC-FAN":
        m = re.fullmatch(r"C11 HVAC_FAN status=(UNAVAILABLE|OBSERVED|STALE) "
                         r"level=(UNKNOWN|[0-7]) receivedAgeMs=(" + VEHICLE_AGE + r")", message)
        if m:
            value, age = optional_integer(m[2]), optional_integer(m[3])
            if (m[1] == "OBSERVED") != (value is not None) or not valid_receipt(value, age):
                return None
            event = dict(event="c11_hvac_fan", readingState=m[1], level=value,
                         receivedAgeMs=age, powerStateInferred=False, writeControlAvailable=False)
    elif tag == "DiPlay-TPMS":
        m = re.fullmatch(r"C11 TPMS status=(UNAVAILABLE|AVAILABLE|STALE) wheel=(" + WHEEL + r") "
                         r"(pressureRaw|tempRaw)=(" + VEHICLE_INT + r") "
                         r"receivedAgeMs=(" + VEHICLE_AGE + r")", message)
        if m:
            value, age = optional_integer(m[4]), optional_integer(m[5])
            bounds = (0, 4094) if m[3] == "pressureRaw" else (-50, 150)
            if ((m[1] == "AVAILABLE") != (value is not None) or not valid_receipt(value, age) or
                    value is not None and not bounds[0] <= value <= bounds[1]):
                return None
            event = dict(event="c11_tpms_observation", wheel=m[2], quantity=m[3], readingState=m[1],
                         rawValue=value, receivedAgeMs=age, unitsVerified=False,
                         sensorHealthVerified=False)
    elif tag == "DiPlay-TPMS-FLAGS":
        m = re.fullmatch(r"C11 TPMS_FLAGS wheel=(" + WHEEL + r") "
                         r"pressureCheck=(" + TPMS_CHECK + r") temperatureCheck=(" + TPMS_CHECK + r") "
                         r"leakRaw=(" + VEHICLE_INT + r") leakAgeMs=(" + VEHICLE_AGE + r") "
                         r"pressureRaw=(" + VEHICLE_INT + r") pressureAgeMs=(" + VEHICLE_AGE + r") "
                         r"temperatureRaw=(" + VEHICLE_INT + r") temperatureAgeMs=(" + VEHICLE_AGE + r")", message)
        if m:
            values = [optional_integer(m[index]) for index in (4, 6, 8)]
            ages = [optional_integer(m[index]) for index in (5, 7, 9)]
            if (any(value is not None and not -2147483648 <= value <= 2147483647 for value in values) or
                    not all(valid_receipt(value, age) for value, age in zip(values, ages)) or
                    m[2] != tpms_zero_check(*values[:2]) or m[3] != tpms_zero_check(values[2])):
                return None
            event = dict(event="c11_tpms_flags", wheel=m[1], pressureCheck=m[2], temperatureCheck=m[3],
                         flags={name: dict(rawValue=value, receivedAgeMs=age)
                                for name, value, age in zip(("LEAK", "PRESSURE", "TEMPERATURE"), values, ages)},
                         sensorHealthVerified=False, nonzeroEnumVerified=False)
    elif tag == "DiPlay-AMBIENT":
        numeric = r"UNKNOWN|-?[0-9]{1,18}(?:\.[0-9]{1,18})?(?:[Ee][+-]?[0-9]{1,3})?"
        m = re.fullmatch(r"C11 AMBIENT status=(UNAVAILABLE|OBSERVED_UNVERIFIED|STALE) "
                         r"channel=(INTERIOR|EXTERIOR_CORRECTED) "
                         r"(interiorRawDouble|exteriorCorrectedRawDouble)=(" + numeric + r") "
                         r"receivedAgeMs=(UNKNOWN|0|[1-9][0-9]{0,10})", message)
        if m:
            expected_key = "interiorRawDouble" if m[2] == "INTERIOR" else "exteriorCorrectedRawDouble"
            value = None if m[4] == "UNKNOWN" else float(m[4])
            age = None if m[5] == "UNKNOWN" else int(m[5])
            observed = m[1] == "OBSERVED_UNVERIFIED"
            if (m[3] != expected_key or (value is not None and not math.isfinite(value)) or
                    observed != (value is not None) or (observed and age is None) or
                    (age is not None and age > PARKED_MAX_AGE_MS)):
                return None
            event = dict(event="c11_ambient_observation", channel=m[2], readingState=m[1],
                         rawValue=value, receivedAgeMs=age, unitsVerified=False,
                         sensorValidityVerified=False)
    elif tag == "DiPlay-SUNSHADE":
        m = re.fullmatch(r"C11 SUNSHADE status=(UNAVAILABLE|AVAILABLE|STALE) "
                         r"positionRaw=(UNKNOWN|0|[1-9][0-9]{0,2}) "
                         r"oemUiPosition=(UNKNOWN|0|[1-9][0-9]{0,2}) "
                         r"receivedAgeMs=(UNKNOWN|0|[1-9][0-9]{0,10})", message)
        if m:
            raw = None if m[2] == "UNKNOWN" else int(m[2])
            ui = None if m[3] == "UNKNOWN" else int(m[3])
            age = None if m[4] == "UNKNOWN" else int(m[4])
            available = m[1] == "AVAILABLE"
            if (available != (raw is not None and ui is not None) or
                    (not available and (raw is not None or ui is not None)) or
                    (available and (age is None or not 0 <= raw <= 100 or ui != 100 - raw)) or
                    (age is not None and age > PARKED_MAX_AGE_MS)):
                return None
            event = dict(event="c11_sunshade_observation", readingState=m[1], positionRaw=raw,
                         oemUiPosition=ui, receivedAgeMs=age, directionVerified=False,
                         writeControlAvailable=False)
    elif tag == "DiPlay-EXTERIOR":
        subscription = re.fullmatch(r"C11 EXTERIOR subscription=(REGISTERED|REJECTED|STOPPED)", message)
        if subscription:
            event = dict(event="c11_exterior_subscription", state=subscription[1])
        elif message == "C11 EXTERIOR callback=FIRST_CHANGE":
            event = dict(event="c11_exterior_first_callback")
        numeric = r"UNKNOWN|-?[0-9]{1,18}(?:\.[0-9]{1,18})?(?:[Ee][+-]?[0-9]{1,3})?"
        age_pattern = r"UNKNOWN|0|[1-9][0-9]{0,10}"
        m = re.fullmatch(r"C11 EXTERIOR: source=(CONNECTED|CONNECTING|CLOSED_OR_DISABLED) "
                         r"status=(" + PARKED_READING + r") celsius=(" + numeric + r") "
                         r"oemTimestampNanos=(UNKNOWN|0|-?[1-9][0-9]{0,18}) "
                         r"oemEventAgeMs=(" + age_pattern + r") halDeliveryAgeMs=(" + age_pattern + r") "
                         r"freshnessPolicy=OEM_EVENT_AND_HAL_DELIVERY maxAgeMs=60000 "
                         r"permissionGranted=(true|false)", message)
        if m:
            value = None if m[3] == "UNKNOWN" else float(m[3])
            timestamp = None if m[4] == "UNKNOWN" else int(m[4])
            oem_age = None if m[5] == "UNKNOWN" else int(m[5])
            delivery_age = None if m[6] == "UNKNOWN" else int(m[6])
            available = m[2] == "AVAILABLE"
            if (available != (value is not None) or
                    (value is not None and not math.isfinite(value)) or
                    (timestamp is not None and not -9223372036854775808 <= timestamp <= 9223372036854775807) or
                    any(age is not None and age > PARKED_MAX_AGE_MS for age in (oem_age, delivery_age)) or
                    (available and (m[1] != "CONNECTED" or m[7] != "true" or timestamp is None or timestamp <= 0 or
                                    oem_age is None or delivery_age is None or
                                    oem_age > 60000 or delivery_age > 60000))):
                return None
            event = dict(event="c11_exterior_temperature", sourceStatus=m[1], readingState=m[2],
                         celsius=value, oemTimestampNanos=timestamp, oemEventAgeMs=oem_age,
                         halDeliveryAgeMs=delivery_age, freshnessPolicy="OEM_EVENT_AND_HAL_DELIVERY",
                         maxAgeMs=60000, permissionGranted=m[7] == "true", units="CELSIUS")
    elif tag == "DiPlay-VideoStats":
        m = re.fullmatch(
            r"video stats(?: stream=(110|111))? rx=([0-9]{1,4}[.,][0-9])fps shown=([0-9]{1,4}[.,][0-9])fps "
            r"maxGap=([0-9]{1,7})ms kbps=([0-9]{1,9}) recoveries=([0-9]{1,7}) "
            r"touch2frame avg=-?[0-9]{1,10}ms max=[0-9]{1,10}ms n=[0-9]{1,10} touchSendMax=[0-9]{1,10}ms"
            r"(?: decode p50=[0-9]{1,10}ms p90=[0-9]{1,10}ms late=[0-9]{1,10}(?: delay=[0-9]{1,10}ms)?)?", message)
        if m:
            rx, shown = float(m[2].replace(",", ".")), float(m[3].replace(",", "."))
            if rx > 1000 or shown > 1000:
                return None
            event = dict(event="screen_video_stats", streamType=int(m[1] or 110), receivedFps=rx,
                         submittedFps=shown, maxGapMs=int(m[4]), kbps=int(m[5]), recoveries=int(m[6]),
                         physicalPresentationVerified=False)
    elif tag == "DiPlay-Cluster":
        if m := re.fullmatch(r"cluster buffer sample result=(SUCCESS|NO_DATA|TIMEOUT|ERROR) "
                            r"content=(UNIFORM_DARK|UNIFORM_LIGHT|UNIFORM_OTHER|VARIED|UNKNOWN)", message):
            if m[1] != "SUCCESS" and m[2] != "UNKNOWN":
                return None
            event = dict(event="cluster_buffer_sample", result=m[1], content=m[2],
                         imageSaved=False, physicalPresentationVerified=False)
        elif m := re.fullmatch(r"cluster stream active=(true|false)", message):
            event = dict(event="cluster_stream_active", active=m[1] == "true", streamType=111)
        elif message in ("cluster surface created", "cluster surface destroyed"):
            event = dict(event="cluster_surface", state="CREATED" if message.endswith("created") else "DESTROYED")
        elif m := re.fullmatch(r"cluster surface ([1-9][0-9]{0,3})x([1-9][0-9]{0,3})", message):
            if not 1 <= int(m[1]) <= 8192 or not 1 <= int(m[2]) <= 8192:
                return None
            event = dict(event="cluster_surface_size", width=int(m[1]), height=int(m[2]))
    elif tag == "xcertplay-usb":
        recovery = re.fullmatch(
            r"ultra recovery: reason=(" + FAILURES + r") resumed=(true|false) surfaceReady=(true|false) surfaceValid=(true|false) "
            r"canvasAttempts=(\d{1,19}) canvasAccepted=(\d{1,19}) "
            r"output=(UNAVAILABLE|WAITING|NO_SURFACE|LOCKING|LOCK_FAILED|DRAWING|SIZE_MISMATCH|POSTING|POST_FAILED|SUBMITTED) "
            r"phase=(WAITING|COMPOSING|DRAWING|PUBLISHING|IDLE) decoded=(\d{1,19}) redraws=(\d{1,19}) "
            r"compositions=(\d{1,19}) unavailable=(\d{1,19}) composed=(\d{1,19}) publications=(\d{1,19}) stale=(\d{1,19})", message)
        if recovery:
            keys = ("reason", "resumed", "surfaceReady", "surfaceValid", "canvasAttempts", "canvasAccepted", "output", "phase",
                    "decoded", "redraws", "compositions", "unavailable", "composed", "publications", "stale")
            values = recovery.groups()
            event = dict(event="ultra_recovery_diagnostic")
            for key, value in zip(keys, values):
                event[key] = value == "true" if key in ("resumed", "surfaceReady", "surfaceValid") else (
                    int(value) if key not in ("reason", "output", "phase") else value)
            return dict(deviceTime=stamp, pid=int(pid), tid=int(tid), **event)
        if message in CHANNEL_EVENTS:
            event = dict(event=CHANNEL_EVENTS[message])
        elif m := re.fullmatch(r"airplay session closing source=(" + CLOSE_SOURCES + r")", message):
            event = dict(event="session_close_trigger", source=m[1])
        elif m := re.fullmatch(r"airplay UI request target=(DEFAULT|VIDEO_PLAYBACK|MAP_CLUSTER|OTHER|INVALID)", message):
            event = dict(event="ui_request", target=m[1])
        elif m := re.fullmatch(r"screen stream ended type=(110|111) reason=(.*)", message):
            event = dict(event="screen_stream_closed", streamType=int(m[1]),
                         reason="peer_eof" if m[2] == "peer EOF" else ERRORS.get(m[2], "unclassified"))
        elif re.fullmatch(r"airplay event decrypt failed encrypted=[0-9]{1,10}", message):
            event = dict(event="event_channel_decrypt_error")
        elif m := re.fullmatch(r"airplay screen first frame received type=(110|111)", message):
            event = dict(event="screen_first_frame_received", streamType=int(m[1]))
        elif m := re.fullmatch(r"video decoder first submission type=(110|111) width=([1-9][0-9]{0,3}) "
                              r"height=([1-9][0-9]{0,3}) composition=(true|false) mirror=(true|false)", message):
            if int(m[2]) > 8192 or int(m[3]) > 8192:
                return None
            event = dict(event="decoder_first_frame_queued", streamType=int(m[1]), width=int(m[2]),
                         height=int(m[3]), composition=m[4] == "true", mirror=m[5] == "true",
                         physicalPresentationVerified=False)
        elif m := re.fullmatch(r"airplay screen stream type=(110|111) dataPort=(rejected|[1-9][0-9]{0,4})", message):
            if m[2] != "rejected" and int(m[2]) > 65535:
                return None
            event = dict(event="screen_stream_setup", streamType=int(m[1]), accepted=m[2] != "rejected")
        elif message in ("ultra channel: UI Sync SETUP received", "ultra channel: protocol owner attached"):
            event = dict(event="ui_sync_setup" if "SETUP" in message else "ui_sync_owner")
        elif message == "airplay attachment VPN revoked":
            event = dict(event="vpn_revoked")
        elif m := re.fullmatch(r"ultra reference failure reason=(" + FAILURES + r"); rebuilding ordinary CarPlay", message):
            event = dict(event="ultra_fallback", reason=m[1])
        elif m := re.fullmatch(r"ultra output: canvasSubmitted=(true|false) physicalPresentation=unverified", message):
            event = dict(event="canvas_submission", submitted=m[1] == "true")
        elif m := re.fullmatch(r"ultra handshake: UI Sync negotiated=(true|false); (?:telemetry|vehicleStateNegotiated)=(true|false) renderer=(true|false)", message):
            event = dict(event="ui_sync_negotiated", enabled=m[1] == "true", vehicleStateNegotiated=m[2] == "true")
        elif m := re.fullmatch(
                r"C11 parked observation: status=(" + PARKED_STATUS + r") permission=(true|false) "
                r"reading=(" + PARKED_READING + r") parked=(true|false|null) policy=(CURRENT_HAL_SNAPSHOT) "
                r"oemAgeMs=(" + PARKED_AGE + r") deliveryAgeMs=(" + PARKED_AGE + r")", message):
            oem_age = None if m[6] == "null" else int(m[6])
            delivery_age = None if m[7] == "null" else int(m[7])
            if any(age is not None and age > PARKED_MAX_AGE_MS for age in (oem_age, delivery_age)):
                return None
            event = dict(event="c11_parked_observation", sourceStatus=m[1], permissionGranted=m[2] == "true",
                         readingState=m[3], parked=None if m[4] == "null" else m[4] == "true",
                         freshnessPolicy=m[5], oemEventAgeMs=oem_age, halDeliveryAgeMs=delivery_age)
        elif m := re.fullmatch(r"vehicle state capabilities range=(true|false) climateZones=([0-2]) radio=(true|false) "
                               r"highVoltageBattery=(true|false) localSocAvailable=(true|false)", message):
            event = dict(event="vehicle_state_capabilities", range=m[1] == "true", climateZones=int(m[2]),
                         radio=m[3] == "true", highVoltageBattery=m[4] == "true", localSocAvailable=m[5] == "true")
        elif m := re.fullmatch(r"iap2 rx=0xa100 start-vehicle-status provider=(true|false)", message):
            event = dict(event="legacy_vehicle_status_subscription", enabled=True, provider=m[1] == "true")
        elif message == "iap2 rx=0xa102 stop-vehicle-status":
            event = dict(event="legacy_vehicle_status_subscription", enabled=False)
        elif m := re.fullmatch(r"iap2 tx=0xa101 vehicle-status range=([0-9]{1,5})km "
                               r"battery=([0-9]{1,3}(?:\.[0-9]{1,16})?)% warning=(true|false|null)", message):
            if int(m[1]) > 65535 or not 0 <= float(m[2]) <= 100:
                return None
            # Deliberately discard numeric readings: sent fields are not Siri acceptance.
            event = dict(event="legacy_vehicle_status_sent", hasRange=True, hasDisplayedBatteryPercentage=True,
                         rangeWarningKnown=m[3] != "null", phoneAcceptanceVerified=False)
        elif m := re.fullmatch(r"vehicle state channel opened lane=(HIGH|NORMAL)", message):
            event = dict(event="vehicle_state_channel", lane=m[1])
        elif m := re.fullmatch(r"vehicle state answered=(configRequest|registerRequest|writeRequest|controlRequest) lane=(HIGH|NORMAL)", message):
            event = dict(event="vehicle_state_answer", command=m[1], lane=m[2])
        elif m := re.fullmatch(r"vehicle state received bytes=([0-9]{1,6}) lane=(HIGH|NORMAL)", message):
            if int(m[1]) > 262144:
                return None
            event = dict(event="vehicle_state_received", bytes=int(m[1]), lane=m[2])
        elif m := re.fullmatch(r"vehicle state request=(configRequest|registerRequest|writeRequest|controlRequest|readRequest|unregisterRequest|unknown) lane=(HIGH|NORMAL)", message):
            event = dict(event="vehicle_state_request", command=m[1], lane=m[2])
        elif m := re.fullmatch(r"vehicle state rejected=(outer-dictionary|plugin-id|request-dictionary|command-type|transaction-type|register-values-type|register-id-type|write-values-type|write-ids|control-values-type|control-id-type|unsupported-command|inactive-channel) lane=(HIGH|NORMAL)", message):
            event = dict(event="vehicle_state_rejected", reason=m[1], lane=m[2])
        elif m := re.fullmatch(r"vehicle state registration requested=([0-9]{1,4}) available=([0-9]{1,4}) unavailable=([0-9]{1,4}) lane=(HIGH|NORMAL)", message):
            requested, available, unavailable = map(int, m.group(1, 2, 3))
            if requested > 8192 or available + unavailable != requested:
                return None
            event = dict(event="vehicle_state_registration", requested=requested, available=available,
                         unavailable=unavailable, lane=m[4])
        elif m := re.fullmatch(r"vehicle state (write|control) unavailable=([0-9]{1,4}) lane=(HIGH|NORMAL)", message):
            if int(m[2]) > 8192:
                return None
            event = dict(event="vehicle_state_unavailable", operation=m[1], count=int(m[2]), lane=m[3])
        elif m := re.fullmatch(r"ultra handshake: script observation panelIndex=(\d{1,2}) direction=(sent|received) type=(\d{1,10}) command=(none|\d{1,10}) layoutIdPresent=(true|false) layoutRelation=(requested|matchesRequest|differsFromRequest|noRequestObserved|unavailable)(?: settingsStreamAction=(NONE|PRESENT|DISMISS|INVALID|AMBIGUOUS))? resourceAcceptance=unverified(?: observationLimitReached=true)?", message):
            if m[7] not in (None, "NONE") and (m[3] != "2" or m[4] != "1"):
                return None
            event = dict(event="ui_sync_observation", panelIndex=int(m[1]), direction=m[2],
                         type=int(m[3]), command=None if m[4] == "none" else int(m[4]),
                         layoutIdPresent=m[5] == "true", layoutRelation=m[6],
                         settingsStreamAction=m[7] or "UNOBSERVED", resourceAcceptanceVerified=False)
        elif m := re.fullmatch(r"airplay attachment release reason=(" + RELEASES + r") generation=(\d{1,10}) active=(true|false) listeners=(\d{1,3})", message):
            event = dict(event="attachment_release", reason=m[1], generation=int(m[2]),
                         active=m[3] == "true", listeners=int(m[4]))
        elif m := re.fullmatch(r"CarPlay transport stopped generation=(\d{1,10}): (.*)", message):
            event = dict(event="transport_stopped", generation=int(m[1]),
                         reason=ERRORS.get(m[2], "unclassified"))
        elif message.startswith("airplay control closing reason=control I/O failed: "):
            reason = message.split("failed: ", 1)[1].split(" activeStreams=", 1)[0]
            event = dict(event="control_io_closed", reason=ERRORS.get(reason, "unclassified"))
        elif message == "Existing Wi-Fi network lost; restarting wireless session":
            event = dict(event="wifi_network_lost")
        elif message == "Existing Wi-Fi interface or address changed; restarting wireless session":
            event = dict(event="wifi_link_changed")
    if event is not None:
        event.update(deviceTime=stamp, pid=int(pid), tid=int(tid))
    return event


def package_uid(output, package):
    matches = re.findall(r"^package:" + re.escape(package) + r" uid:(\d+)\s*$", output, re.MULTILINE)
    if len(matches) != 1:
        raise ValueError("Cannot identify the exact installed package UID")
    return int(matches[0])


def logcat_command(base, uid, since=None):
    # UID, unlike PID, remains the same when Android restarts the application.
    return base + ["logcat", "--uid=" + str(uid), "-v", "threadtime", "-T", since or "1",
                   "-s", "xcertplay-usb", "AndroidRuntime", "libc", "DiPlay-AMBIENT", "DiPlay-SUNSHADE",
                   "DiPlay-EXTERIOR", "DiPlay-HVAC", "DiPlay-HVAC-FAN", "DiPlay-TPMS", "DiPlay-TPMS-FLAGS",
                   "DiPlay-CHARGING", "DiPlay-DOORS", "DiPlay-SUBSYSTEM", "DiPlay-MEASUREMENTS",
                   "DiPlay-Cluster", "DiPlay-VideoStats"]


def capture(base, package, output, seconds):
    help_result = subprocess.run(base + ["logcat", "--help"], capture_output=True, timeout=10)
    if help_result.returncode or b"--uid" not in help_result.stdout + help_result.stderr:
        raise ValueError("Device logcat must support UID filtering; capture was not started")
    result = subprocess.run(base + ["shell", "pm", "list", "packages", "-U", "--user", "10", package],
                            capture_output=True, timeout=10)
    if result.returncode:
        raise ValueError("Cannot query the installed package UID")
    uid = package_uid(result.stdout.decode("utf-8", errors="replace"), package)
    deadline, since = time.monotonic() + seconds, None
    recent = collections.OrderedDict()
    processes = set()
    with Path(output).open("x", encoding="utf-8") as destination:
        def emit(event):
            destination.write(json.dumps(event, sort_keys=True) + "\n")
            destination.flush()
        emit(dict(event="capture_started", uid=uid, durationSeconds=seconds,
                  rawLogsSaved=False, physicalPresentationVerified=False))
        try:
            while time.monotonic() < deadline:
                process = subprocess.Popen(logcat_command(base, uid, since), stdout=subprocess.PIPE,
                                           stderr=subprocess.DEVNULL)
                selector = selectors.DefaultSelector()
                selector.register(process.stdout, selectors.EVENT_READ)
                pending = b""
                oversized = False
                try:
                    while time.monotonic() < deadline:
                        if not selector.select(timeout=min(1, max(0, deadline - time.monotonic()))):
                            continue
                        chunk = process.stdout.read1(8192)
                        if not chunk:
                            break
                        pending += chunk
                        while b"\n" in pending:
                            line, pending = pending.split(b"\n", 1)
                            if not oversized and len(line) <= 65536:
                                event = parse_event(line.decode("utf-8", errors="replace"))
                                if event:
                                    since = event["deviceTime"]
                                    key = json.dumps(event, sort_keys=True)
                                    if key not in recent:
                                        recent[key] = None
                                        if len(recent) > 4096:
                                            recent.popitem(last=False)
                                        if event["pid"] not in processes:
                                            processes.add(event["pid"])
                                            emit(dict(event="process_observed", pid=event["pid"]))
                                        emit(event)
                            oversized = False
                        if len(pending) > 65536:
                            pending = b""
                            oversized = True
                finally:
                    selector.close()
                    if process.poll() is None:
                        process.terminate()
                    try:
                        process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=3)
                    process.stdout.close()
                if time.monotonic() < deadline:
                    # Do not label this a vehicle disconnect: ADB/logcat can end independently.
                    emit(dict(event="capture_gap", logcatExit=process.returncode))
                    time.sleep(min(1, max(0, deadline - time.monotonic())))
        finally:
            emit(dict(event="capture_stopped", observedProcesses=len(processes)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.shihab.diplay.hudtest")
    parser.add_argument("--seconds", type=int, default=1800)
    parser.add_argument("--output", required=True, help="New JSONL file; existing files are never overwritten")
    args = parser.parse_args()
    if not 1 <= args.seconds <= 43200:
        parser.error("--seconds must be between 1 and 43200")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+", args.package):
        parser.error("Invalid package name")
    try:
        capture([args.adb, "-s", args.serial], args.package, args.output, args.seconds)
    except KeyboardInterrupt:
        return
    except (OSError, ValueError, subprocess.SubprocessError):
        # ADB error output can contain device/network details. Keep it out of saved/user output.
        parser.exit(1, "Capture could not complete; check ADB availability, UID support and output path.\n")


if __name__ == "__main__":
    main()
