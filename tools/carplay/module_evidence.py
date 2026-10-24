#!/usr/bin/env python3
"""Offline coverage of filtered JSONL captures; never connects to the vehicle.

Reports recorded observations, never current vehicle availability or validated semantics.
Protocol traffic is not attributed to Siri merely because it appears in the capture.
"""
import argparse
from collections import Counter
from datetime import datetime
import json
import math
from pathlib import Path

from capture_events import SUBSYSTEM_CHANNELS, MEASUREMENT_CHANNELS, MEASUREMENT_ALIASES, MEASUREMENT_MODULES, parse_event

MODULES = ("CHARGING", "DOORS_LOCKS", "WINDOWS_ROOF", "SEATS", "LIGHTS", "ENERGY",
           "WIPERS", "DRIVING", "WARNINGS", "CLIMATE")
MODULE_LABELS = dict(zip(MODULES, ("Carga", "Puertas y baúl", "Ventanillas, techo y espejos", "Asientos", "Luces",
                                  "Energía y consumo", "Limpiaparabrisas", "Conducción", "Avisos", "Climatización")))
SPECIAL_CHANNELS = {
    "c11_charging_observation": ("CHARGING", "DiPlay-CHARGING", "CHARGING",
                                  {"CHARGE_STATUS", "AC_CONNECTION", "DC_CONNECTION",
                                   "AC_PLUG_LOCK_STATUS", "ONBOARD_CHARGER_STATUS", "CHARGE_TIME_REMAINING", "CHARGE_TARGET_PERCENT"}),
    "c11_door_observation": ("DOORS_LOCKS", "DiPlay-DOORS", "DOORS",
                              {"DRIVER_DOOR", "PASSENGER_DOOR", "REAR_LEFT_DOOR", "REAR_RIGHT_DOOR", "TAILGATE_STATUS",
                               "TAILGATE_POSITION", "POWER_TAILGATE_STATUS", "HOOD_STATUS", "VEHICLE_LOCK_STATUS", "REAR_LEFT_CHILD_LOCK_STATUS", "REAR_RIGHT_CHILD_LOCK_STATUS"}),
}
MAX_FILE_BYTES = 32 * 1024 * 1024
MAX_LINE_BYTES = 8192
MAX_RECORDS = 100000
MAX_RECORDED_AGE_MS = 365 * 24 * 60 * 60 * 1000


def finite_number(value):
    if type(value) not in (int, float):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def normalize_observation(record):
    """Revalidate through the capture allowlist, then discard every other input field."""
    if not isinstance(record, dict):
        return None
    event, channel, stamp = record.get("event"), record.get("channel"), record.get("deviceTime")
    status, value, age = record.get("readingState"), record.get("rawValue"), record.get("receivedAgeMs")
    if not isinstance(event, str) or not isinstance(channel, str) or not isinstance(stamp, str) or len(stamp) != 18:
        return None
    try:
        # Calendar validation only; no year, chronological sorting or measurement time is inferred.
        datetime.strptime("2000-" + stamp, "%Y-%m-%d %H:%M:%S.%f")
    except ValueError:
        return None
    if age is not None and (type(age) is not int or not 0 <= age <= MAX_RECORDED_AGE_MS):
        return None
    if status not in ("UNAVAILABLE", "OBSERVED_UNVERIFIED", "STALE"):
        return None
    age_text = "UNKNOWN" if age is None else str(age)
    if event == "c11_measurement_observation":
        channel = MEASUREMENT_ALIASES.get(channel, channel)
        if channel not in MEASUREMENT_CHANNELS or value is not None and not finite_number(value):
            return None
        module = MEASUREMENT_MODULES[channel]
        tag = "DiPlay-MEASUREMENTS"
        message = f"C11 MEASUREMENT channel={channel} status={status} raw={value if value is not None else 'UNKNOWN'} receivedAgeMs={age_text}"
    elif event == "c11_subsystem_observation":
        module = record.get("module")
        if not isinstance(module, str) or channel not in SUBSYSTEM_CHANNELS.get(module, set()):
            return None
        if value is not None and (type(value) is not int or not -2147483648 <= value <= 2147483647):
            return None
        tag = "DiPlay-SUBSYSTEM"
        message = f"C11 SUBSYSTEM module={module} channel={channel} status={status} raw={value if value is not None else 'UNKNOWN'} receivedAgeMs={age_text}"
    elif event in SPECIAL_CHANNELS:
        module, tag, label, channels = SPECIAL_CHANNELS[event]
        if channel not in channels or value is not None and (
                type(value) is not int or not -2147483648 <= value <= 2147483647):
            return None
        message = f"C11 {label} status={status} channel={channel} raw={value if value is not None else 'UNKNOWN'} receivedAgeMs={age_text}"
    else:
        return None
    parsed = parse_event(f"{stamp} 1 1 I {tag}: {message}")
    if parsed is None:
        return None
    return dict(module=module, channel=channel, readingState=parsed["readingState"],
                rawValue=parsed["rawValue"], receivedAgeMs=parsed["receivedAgeMs"], deviceTime=stamp)


def expected_channels():
    result = {module: set(SUBSYSTEM_CHANNELS.get(module, set())) for module in MODULES}
    for module, _, _, channels in SPECIAL_CHANNELS.values():
        result[module].update(channels)
    for channel in MEASUREMENT_CHANNELS:
        result[MEASUREMENT_MODULES[channel]].add(channel)
    return result


def summarize(records, simulated=False):
    expected = expected_channels()
    channels = {(module, channel): dict(channel=channel, observations=0, valueChanges=0,
                 withdrawals=0, lastRecorded=None) for module in MODULES for channel in sorted(expected[module])}
    previous = {}
    commands, rejections = Counter(), Counter()
    boundaries, accepted, ignored = 0, 0, 0
    # Protocol keys are fixed; arbitrary strings never become output keys.
    known_commands = {"configRequest", "registerRequest", "writeRequest", "controlRequest",
                      "readRequest", "unregisterRequest", "unknown"}
    known_reasons = {"outer-dictionary", "plugin-id", "request-dictionary", "command-type", "transaction-type",
                     "register-values-type", "register-id-type", "write-values-type", "write-ids",
                     "control-values-type", "control-id-type", "unsupported-command", "inactive-channel"}
    for index, record in enumerate(records):
        if index >= MAX_RECORDS:
            raise ValueError("Capture exceeds supported record count")
        if not isinstance(record, dict):
            ignored += 1
            continue
        event = record.get("event")
        if event in ("capture_started", "capture_stopped", "capture_gap", "process_observed"):
            if event == "capture_started" and record.get("simulated") is True:
                simulated = True
            previous.clear()
            boundaries += 1
            continue
        if (event == "vehicle_state_request" and isinstance(record.get("command"), str) and
                record["command"] in known_commands and record.get("lane") in ("HIGH", "NORMAL")):
            commands[record["command"]] += 1
            continue
        if (event == "vehicle_state_rejected" and isinstance(record.get("reason"), str) and
                record["reason"] in known_reasons and record.get("lane") in ("HIGH", "NORMAL")):
            rejections[record["reason"]] += 1
            continue
        observation = normalize_observation(record)
        if observation is None:
            ignored += 1
            continue
        accepted += 1
        key = observation["module"], observation["channel"]
        stats = channels[key]
        stats["lastRecorded"] = {k: v for k, v in observation.items() if k not in ("module", "channel")}
        value = observation["rawValue"]
        if value is None:
            stats["withdrawals"] += 1
            previous.pop(key, None)
        else:
            stats["observations"] += 1
            if key in previous and previous[key] != value:
                stats["valueChanges"] += 1
            previous[key] = value
    modules = []
    for module in MODULES:
        rows = [channels[module, channel] for channel in sorted(expected[module])]
        observed = sum(row["observations"] > 0 for row in rows)
        changed = sum(row["valueChanges"] > 0 for row in rows)
        modules.append(dict(module=module, expectedChannels=len(rows), observedChannels=observed,
                            channelsWithChanges=changed,
                            status="VALUE_CHANGES_OBSERVED" if changed else "OBSERVATIONS_ONLY" if observed else "NOT_OBSERVED",
                            channels=rows))
    return dict(schemaVersion=1, inputKind="SIMULATED" if simulated else "STRUCTURED_CAPTURE_UNVERIFIED_ORIGIN",
                scope="RECORDED_OBSERVATIONS_ONLY_NOT_CURRENT_VEHICLE_STATE", physicalValidationVerified=False,
                unitsVerified=False, controlVerified=False, acceptedObservations=accepted,
                ignoredRecords=ignored, captureBoundaries=boundaries, modules=modules,
                phoneProtocol=dict(requests=dict(sorted(commands.items())), rejections=dict(sorted(rejections.items())),
                                   siriAcceptanceVerified=False, siriAttribution="NOT_ESTABLISHED"))


def structured_records(path):
    path = Path(path)
    if path.stat().st_size > MAX_FILE_BYTES:
        raise ValueError("Capture exceeds supported file size")
    with path.open("rb") as source:
        while line := source.readline(MAX_LINE_BYTES + 1):
            if len(line) > MAX_LINE_BYTES:
                while line and not line.endswith(b"\n"):
                    line = source.readline(MAX_LINE_BYTES + 1)
                yield None
                continue
            try:
                yield json.loads(line)
            except (ValueError, UnicodeError, RecursionError):
                yield None


def markdown(report):
    origin = "SIMULADO: datos inventados para probar la herramienta" if report["inputKind"] == "SIMULATED" else "Captura estructurada, origen no verificado"
    labels = {"NOT_OBSERVED": "Sin lecturas", "OBSERVATIONS_ONLY": "Lecturas registradas",
              "VALUE_CHANGES_OBSERVED": "Cambios observados"}
    lines = ["# DiPlay: cobertura de la captura", "", f"Origen: {origin}.",
             "Solo observaciones registradas; no acredita estado actual, unidades, controles ni aceptación de Siri.",
             "", "| Módulo | Canales observados | Canales con cambios | Estado |",
             "| --- | --- | --- | --- |"]
    for module in report["modules"]:
        lines.append(f"| {MODULE_LABELS[module['module']]} | {module['observedChannels']}/{module['expectedChannels']} | "
                     f"{module['channelsWithChanges']} | {labels[module['status']]} |")
    lines += ["", "Un cambio de código no demuestra su significado físico. Las interrupciones de captura",
              "y retiradas de valores cortan la comparación entre muestras.", ""]
    return "\n".join(lines)


def html_report(report):
    template = Path(__file__).with_name("module_report.html").read_text(encoding="utf-8")
    if template.count("__DIPLAY_REPORT_JSON__") != 1:
        raise ValueError("Incompatible report template")
    # Keep all input inside an inert JSON block; the page renders values with textContent.
    data = json.dumps(report, ensure_ascii=False, allow_nan=False)
    data = data.replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e")
    return template.replace("__DIPLAY_REPORT_JSON__", data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--events", required=True, help="Filtered capture_events.py JSONL")
    parser.add_argument("--output", required=True, help="New JSON report, never overwritten")
    parser.add_argument("--markdown", help="Optional new Markdown coverage table")
    parser.add_argument("--html", help="Optional new interactive offline review page")
    parser.add_argument("--simulated", action="store_true", help="Label synthetic replay output explicitly")
    args = parser.parse_args()
    try:
        outputs = [Path(args.output)] + ([Path(args.markdown)] if args.markdown else []) + ([Path(args.html)] if args.html else [])
        if any(path.exists() for path in outputs) or len({path.resolve() for path in outputs}) != len(outputs):
            raise ValueError("Output already exists or repeats")
        report = summarize(structured_records(args.events), simulated=args.simulated)
        with outputs[0].open("x", encoding="utf-8") as output:
            json.dump(report, output, indent=2, allow_nan=False)
            output.write("\n")
        if args.markdown:
            with Path(args.markdown).open("x", encoding="utf-8") as output:
                output.write(markdown(report))
        if args.html:
            with Path(args.html).open("x", encoding="utf-8") as output:
                output.write(html_report(report))
    except (OSError, ValueError, TypeError, OverflowError):
        parser.exit(1, "Could not build evidence; check filtered input, size limits and new output paths.\n")


if __name__ == "__main__":
    main()
