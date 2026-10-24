# CarPlay development diagnostics

## Capturing a coordinated car trial

`capture_events.py` follows the installed app's Android UID, so a process restart
does not leave capture pinned to an obsolete PID. Use an already connected ADB
device and a new output file:

```sh
python3 tools/carplay/capture_events.py \
  --adb /path/to/Android/sdk/platform-tools/adb \
  --serial DEVICE_SERIAL \
  --seconds 1800 \
  --output /private/tmp/diplay-trial-events.jsonl
```

The default package is `com.shihab.diplay.hudtest` for Android user 10. The tool
requires device support for `logcat --uid` and stops if that filter is unavailable.
It does not connect ADB, install or launch an app, or alter device settings. Stop
with Ctrl-C. Duration is bounded to 12 hours and existing output is never replaced.

Only enumerated protocol/fallback/network events, timestamps, process/thread IDs,
bounded counters and strictly validated vehicle observations are saved. Raw log messages, stack traces, remote identities,
location, credentials and payloads are discarded. Fatal events retain only their
Java/native category. An unrecognized transport error becomes `unclassified`.

`process_observed` identifies a PID that emitted a recognized event; it does not
prove the exact process start time. `capture_gap` means the ADB log stream ended,
not that CarPlay disconnected. On stream restart the tool requests replay from
the last recognized device timestamp and suppresses bounded recent duplicates.
Log buffer eviction, device restart/clock changes and disconnected ADB can still
leave gaps. This is a connected-host capture, not an on-device flight recorder.

The vehicle allowlist includes `DiPlay-HVAC` (left/right target temperature,
18–32 Celsius), `DiPlay-HVAC-FAN` (observed level 0–7), `DiPlay-TPMS` (pressure
and temperature RAW by physical wheel), and `DiPlay-TPMS-FLAGS` (three signed
integer OEM flags per wheel). These supplement ambient, exterior and sunshade
observations. A line with extra text, an unknown enum, invalid range, inconsistent
availability or stale present value is discarded in full. Known stale/unavailable
states retain a null value, never the last good reading. Ages describe local
receipt only, except the exterior source's explicitly separate HAL/event ages.

TPMS pressure RAW 0–4094 and temperature RAW −50–150 follow OEM validity bounds;
they do not establish pressure units, Celsius or sensor health. Flags reproduce
only the OEM zero check: pressure requires observed zero leak and pressure flags;
temperature requires its observed zero flag. Missing constituents remain unknown,
and nonzero codes retain RAW values without guessed severity or meanings. Fan
level zero does not imply HVAC power off. No captured reading establishes working
climate controls, Siri acceptance or phone-side UI presentation.

Older versions of this public capture omitted the HVAC/TPMS tags. Their silence
cannot demonstrate absence of vehicle data. Use a new capture with the updated
allowlist and the app's corrected trailing diagnostic batch before comparing
readings against the OEM screen.

## Review all local modules offline

The capture now includes `DiPlay-CHARGING`, `DiPlay-DOORS`, `DiPlay-SUBSYSTEM`
and `DiPlay-MEASUREMENTS`, as well as bounded received/rejected vehicle-protocol
diagnostics. Older capture filters omitted these four tags; their silence cannot
establish that a module failed to receive data.

After one capture, produce coverage for all ten modules without another ADB call:

```sh
python3 tools/carplay/module_evidence.py \
  --events /private/tmp/diplay-trial-events.jsonl \
  --output /private/tmp/diplay-module-evidence.json \
  --markdown /private/tmp/diplay-module-evidence.md \
  --html /private/tmp/diplay-module-evidence.html
```

The report lists expected, observed and changing channels, withdrawals and the
last **recorded** observation. It does not establish current vehicle state, physical
semantics, units, working controls or Siri acceptance. Comparisons follow capture
order and restart after missing data, stream gaps or observed process boundaries.
Zero is a recorded value, not a substitute for missing data. Unknown/private fields
are discarded. Input is limited to 32 MiB and 100,000 records; oversized/malformed
lines are ignored. Outputs are new files and are never overwritten.

The optional HTML page is self-contained and works offline in a browser. Its ten
module selectors show the corresponding channel readings and expandable record
details. Missing/withdrawn values are labelled explicitly; zero is never used as
an empty-state placeholder. The simulated source label stays visible. There are
no vehicle controls, remote resources or network requests in the page.

Phone requests and rejection reasons are counted separately. A configuration
request may be normal CarPlay initialization; it is not automatically a Siri query.
The original filtered JSONL retains timestamps for a later coordinated query.

`fixtures/modules-simulated.jsonl` is invented offline test data covering all 91
channels. It is explicitly marked simulated, and that mark carries into the report
even without the CLI flag. Example:

```sh
python3 tools/carplay/module_evidence.py \
  --events tools/carplay/fixtures/modules-simulated.jsonl --simulated \
  --output /private/tmp/diplay-modules-simulated.json \
  --markdown /private/tmp/diplay-modules-simulated.md
```

See the [generated example](../../docs/LEAPMOTOR_C11_CAPTURE_REVIEW_EXAMPLE.md)
and [current local module status](../../docs/LEAPMOTOR_C11_LOCAL_MODULES.md).

## Center composition experiment

`UltraThemeBinding.composeMainDisplay` explicitly binds the implemented reference
center compositor to a `displayPlugin` with that same panel/video identity. The
packaged development resource selection enables it; legacy descriptor-only
bindings retain their previous topology. No additional panel or instrument
capability is advertised. The setting only matters while the Ultra probe is active.

This tests a specific hypothesis from the pinned LIVI reference: a plug-in-bearing
panel triggers Ultra and receives its theme layout. The reference itself uses a
cluster, so it does not prove that an iPhone accepts a center-only configuration.
Opening UI Sync, accepting resources/layout and presenting physical output remain
separate live checks. The existing timeout/fallback is retained. A plug-in may
change the phone's video content; a black video stream is not proof of failure or
proof of working composition.

Compare the generated experiment against original package metadata with:

```sh
python3 tools/carplay/check_theme_contract.py PACKAGE_JSON \
  shared/build/reports/ultra-theme/center-composed.plist \
  --center-only --compose-center
```

`metadataMatches` only validates the selected identities and geometry. The report
also counts declared plug-ins and warns when the reference Ultra trigger is absent.
It never infers phone acceptance or authenticates the reference package.

Local tool checks:

```sh
python3 -m unittest discover -s tools/carplay -p 'test_*.py' -v
```

## Offline camera-buffer previews

`camera_frame.py` converts explicitly selected, tightly packed 8-bit NV12/NV21/
I420/YV12 to a PNG hypothesis preview using only Python's standard library.
Geometry, matrix and quantization range are mandatory; none is detected.
A real SDK capture requires its manifest and matching SHA-256, while `--synthetic`
labels invented fixtures explicitly. Output directories are exclusive and never
overwritten. PNG validity does not verify the physical camera format.

See [camera capture and conversion](../../docs/LEAPMOTOR_C11_CAMERAS.md) for the
command and pending vehicle trial. Run the converter checks with:

```sh
python3 -m unittest discover -s tools/carplay -p 'test_camera_frame.py'
```
