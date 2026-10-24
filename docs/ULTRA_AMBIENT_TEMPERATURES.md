# Measured temperatures and sunshade: iPhone integration contracts

## Owner-directed local exterior readout, 2026-10-22

Compact follow-up: the C11 host now uses one transparent row containing battery
percentage and a short localized exterior-temperature label. Receipt age remains
in diagnostics/accessibility, not the visible row. The conventional header is
48dp rather than96dp, still scaled with the driver's font. Complete APK
`c8b2ffb6` was installed preserving data. Full CI passed3,637 registered JUnit
tests, zero failures/errors,46 environment skips. Compact/full/RTL large-font
layout checks passed; those are development checks, not changes to the owner's
Spanish language or font preferences. Physical visual acceptance is pending
the owner's quick check. Screenshots: `common/build/reports/c11-compact-header/`.

Physical follow-up: installed complete APK `ba07118c` shows13 °C beside60%
battery on the real centre screen, with receipt-age labels12 and13 minutes.
The observation therefore remains visible past the previous60s cutoff.
Evidence: `.private/c11/ultra-20261022/exterior-fixed-visible-stable.png`.
Full CI passed3,636 JUnit tests, zero failures/errors,46 environment skips.

The owner requested a visible result independent of Ultra stability and confirmed
that this car's exterior display uses Celsius (approximately15 degrees outdoors).
A fresh passive event1016 returned13.0, with receipt ages1ms and1001ms. This is
an actual reported value, not a default or a value copied from the owner's estimate.
Evidence: `.private/c11/ultra-20261022/vehicle-readings-check-01.jsonl`.

The local Android vehicle card also presents this reported exterior value,
including during conventional CarPlay and when battery data is absent. It reads the existing telemetry lease;
it does not add vehicle commands or an iPhone service. The underlying observation
remains `OBSERVED_UNVERIFIED`: the owner's Celsius confirmation supports this
development car's local presentation, not a newly established OEM unit/health
contract for every firmware. The UI labels the field as reported by the car.
Missing, non-finite and values outside the local presentation guard of -50..80
are hidden; zero remains valid. The strict freshness cache still withdraws after
60 seconds. The local display separately retains the last valid exterior event
for the lifetime of its connected source. Diagnostics/accessibility retain how
many minutes ago it was received; the compact driving row omits that text.
UI refreshes do not renew that age.
Source loss/replacement, an invalid exterior event or clock rollback clears it.
This is reception age, not sensor measurement age, and is not exported to the
iPhone as fresh temperature. No sensor-health claim or OEM sentinel specification
is inferred. Conventional C11 projection reserves a48dp strip (scaled with font,
capped to one third of the window) through the same aspect-preserving geometry
used for video and touch, so visibility no longer requires accidental letterboxing.

Native compact/full, measured C11, high-density and Arabic screenshots at large
font are under `common/build/reports/ultra-battery-ui/exterior-*.png`.
The retained-observation cases are in `retained-exterior-*.png` in that directory.
The owner selected four-wheel pressure as the next increment. Its unit binding
and live values must be checked separately; this temperature change does not
establish tyre pressures or warning thresholds.

The local protocol references define **measured interior temperature separately
from the HVAC target**. They also establish Apple's exterior-temperature rendering
path, but do not provide its VehicleState wire descriptor. No Apple sunshade
control contract has been established. This is a local source review dated
2026-10-20, not an iPhone or vehicle acceptance result.

## Interior temperature

The development reference
`.private/c11/ultra-20261017/livi-vehicle_state.rs:495–534` describes:

| Field | Proven reference contract |
| --- | --- |
| Climate accessory | Type `0x01000001` |
| Per-zone temperature service | Type `0x11000002` |
| Measured/current temperature | Type `0x31000019`, Float format `9`, Celsius units `1082` |
| Target temperature | Different type: `0x31000017` |
| Physical association | `vehicleLayoutKey`, type `0x36000065` |
| Measured-value ownership | Car-owned: `writable=false`, `oemWritable=true` |

Instance IDs `521`/`535` hold current readings in that example; `520`/`534` hold
targets. Instance IDs are local allocations, not global Apple type identifiers.
The reference's initial `21°C`, limits and `0.5°C` step are example data, not
evidence of Leapmotor sensor accuracy, resolution or current temperature.

The current `UltraClimateBackend.Snapshot` carries only `targetsCelsius`.
Transporting a target does not implement the measured-temperature characteristic.
A later extension can supply fresh, optional measured values independently of
whether target writes are authorized, and reject phone writes to those readings.
It must preserve the real sensor's physical scope: **one global cabin sensor does
not justify advertising separate driver/passenger sensors**. Missing, stale or
revoked observations must become unavailable, without defaults or inferred values.

This contract alone does not establish what the iPhone displays or which Siri
questions it answers. Those outcomes require actual protocol and phone validation.

## Exterior temperature

The local Apple reference `apple-VehicleDataUpdater.lua:2084–2086` maps the
`outsideTemperature` data identifier to
`environmentalConditions.exteriorConditions.temperature`.
The extracted runtime's `Layout.lua:1936–1948` and
`MeasurementLabelGauge.lua:198–210` implement an outside-temperature label;
example `PackageData.lua` files contain its layout entries. These development
resources are under `/private/tmp/diplay-ultra-runtime-resources/`; copies of the
updater and package metadata are in `.private/c11/ultra-20261017/`.

That proves a rendering route, not a complete wire service or its placement in
the deployed C11 layout. The inspected LIVI VehicleState source provides no
environmental-conditions accessory/service/characteristic numeric descriptor.
`livi-ident.rs:159` enables legacy iAP2 `outside_temperature`, but does not establish
the update parameter's encoding, scaling or invalid-value semantics. The current
`Iap2VehicleStatus` implementation does not encode outside temperature.

A verified, fresh OEM Celsius observation can support a local Android display.
Publishing it to the iPhone requires the actual VehicleState descriptor or the
exact legacy iAP2 encoding and validation. Do not substitute engine/battery
temperature types or treat the Lua property path as an OPACK descriptor. OEM
read permissions and decoders are a separate integration layer; their existence
does not fill this Apple protocol gap.

The parallel OEM source review identified `TMS_InsideTemp` event `1221` and
`TMS_OutsideTemp_Corrected` event `1016` in `AirManager-local.java` (development
extraction under `/private/tmp/`, subscription lines 198–205). Both use an
eight-byte little-endian double payload. That establishes raw observations only:
units, sentinels and sensor validity have not been verified. Such values must
remain `OBSERVED_UNVERIFIED`, without a degree symbol or an `AVAILABLE` measured
temperature claim. Events `11205`/`11206` are HVAC targets and remain separate.

## Sunshade / roof

No sunshade, sunblind or sunroof control definition was found in the inspected
LIVI VehicleState reference or extracted Apple runtime Lua modules. There is no
proven service/command identifier, position unit/range, or open/close/stop
semantics in this corpus. This does not establish that Apple never supports it.

A real, authorized OEM backend may support a local DiPlay control after its state,
actuation and stop behavior are verified. An iPhone Ultra or Siri control still
requires a proven Apple contract. Do not advertise a new functional vehicle app
or invented wire capability from the existence of an Android actuator alone.

## Next validation

1. Obtain real interior/exterior observations with units, freshness and physical
   scope; keep them separate from HVAC targets.
2. Implement measured interior transport only for verified scope, testing
   missing/stale/revoked data and read-only enforcement.
3. Keep exterior iPhone publication gated until its exact wire schema is known.
4. Validate OEM sunshade state/control/stop independently; keep Apple mapping
   absent until primary evidence supplies it.

## Implemented source and physical observations, 2026-10-20

The current passive subscriber and independent caches received interior 25.0 and
corrected exterior 14.5 on the C11. Source replacement, malformed values, finite
checks and per-field silence withdraw readings. A diagnostic batching fix preserves
later fields without needing another ON_CHANGE event.

OEM `SettingsManager` forwards event 1221 unchanged to cabin state and binder 3205;
`AirManager` forwards event 1016 unchanged to the exterior binder value. The captured
SpeechAdapter `CarControl.queryInformation` reads binder 3205/1016 and appends
`度` (degrees) without conversion. This establishes measured temperature usage,
but does not prove Celsius or the sensors' finite-invalid and health semantics.
Both remain `OBSERVED_UNVERIFIED` diagnostics.

An independent Android reader requests the normal
`android.car.permission.CAR_EXTERIOR_ENVIRONMENT` permission. It validates the
exact global Float property configuration, permission, status, original OEM event
timestamp and HAL delivery age; zero timestamps never become fresh readings.
Its source is shared by explicit telemetry owners, with no Bluetooth startup.
The read-only continuous subscription uses 1 Hz and is removed on lease teardown.

On this firmware the permission is granted, subscription registers and a callback
arrives, but GETs still return timestamp zero and remain STALE. Neither a callback
nor a successful GET is proof of a physical temperature. No Android exterior value
is currently suitable for presentation, and no iPhone capability was added.

The standard property's Celsius contract and elapsed-realtime timestamp semantics
come from [Android 12 Vehicle HAL types](https://android.googlesource.com/platform/hardware/interfaces/+/refs/tags/android-12.0.0_r1/automotive/vehicle/2.0/types.hal)
and [CarPropertyValue](https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-12.0.0_r1/car-lib/src/android/car/hardware/CarPropertyValue.java).
Those standard units cannot be applied to the separate raw SomeIP events by inference.

Full required CI plus diagnostics passed: 3,469 JUnit tests, zero failures/errors,
one pre-existing skip; 33 Python tests. The installed APK and evidence are recorded
in the [current status](LEAPMOTOR_C11_ULTRA_STATUS.md).


## Offline validity review after the native TPMS trace

A further local consumer/SDK review found three named candidates in captured
`CanEvent.java`: `TMS_InsideTempSensorSCOrOC=1215`,
`TMS_OutsideTempSensoSCOrOC=1216`, and `TMS_OutsideTempVD=1014` (lines1845–1854).
These are catalog entries, not a proven enum, valueType, subscription or binding
of validity to measured events1221/1016. Their names alone do not authorize
mapping zero to healthy, one to fault, or invalidating a corrected exterior
observation. No new sensor-health decoder was added.

The exact previously established chain still ends in generic degrees: measured
inside1221 is forwarded to binder3205; corrected exterior1016 to binder1016;
the OEM speech consumer appends `度`. The Unity native TPMS consumer was traced
separately and does not establish ambient units. Generic translation key28_2
contains Celsius symbols, but has no demonstrated binding to these two ambient
sensor branches. Likewise, the native `OnChangeUnitType` method on the TPMS/health
page changes km/mi odometer labels, not measured-temperature units.

No RAW normalization, Celsius claim, finite-sentinel rule or new phone capability
was implemented from this review. The next minimal physical evidence is the OEM
**measured** inside/outside readout with its visible unit and device time during
a fresh passive capture, separate from HVAC setpoints. A naturally later distinct
reading can cross-check an offset/scale without manipulating a sensor. Missing or
stale data remains absent. No car access, new library installation or Android
source changes were performed for this offline review.


The 2026-10-21 follow-up also checked a misleading numeric match: the two uses of
1014 in `SettingsManager-temperature-review.java:7046–7054` send a **vehicle bind /
unbind control** through `sendCarControlEventToQnx`. They are a different control
namespace, not consumers of SomeIP `TMS_OutsideTempVD` state1014. That occurrence
must not be used to assign the temperature-validity enum. The new Java-to-Unity
TPMS bridge review found no conversion there, but supplies no additional ambient
Celsius or sensor-validity evidence. Both measured ambient fields remain RAW and
unverified; no decoder changes were justified.
