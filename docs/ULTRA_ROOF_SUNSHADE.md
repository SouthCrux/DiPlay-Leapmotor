# Leapmotor C11 roof sunshade

Implemented: a passive roof-curtain position decoder, source-owned cache and bounded diagnostic
capture. Nothing sends roof commands or advertises a Siri/Ultra roof control.

## Curtain and glass are different

OEM `BaseControlManager` registers both events as `SubEvent(..., 1)`, meaning exactly four-byte
little-endian integers through `SomeIpSubManageImpl` and `ByteUtils.bytesToInt`:

| Signal | Event | OEM consumer |
| --- | --- | --- |
| Roof curtain / sunshade | 2801, `RZCU_SS_PositionSts` | `getSunshade()`, `sunshadePosition`, BLE `rollo`, Unity 327006 |
| Roof glass / skylight | 2800, `RZCU_SR_PositionSts` | `getSkylight()` |

Only **2801** is subscribed, using the existing passive source: service `0x9900`, instance
`0x9901`, configured group `0x9903`. The callback has no group argument; the Messenger group is
the single configured subscription group for that service, not separately observed wire metadata.

The curtain consumer accepts 0..100 and sends `100 - position` to Unity status 327006. Diagnostics
preserve both `positionRaw` and `oemUiPosition`; neither endpoint is labelled OPEN/CLOSED until
physical movement, direction and endpoint mapping are observed on this car. The independent
configuration flags `Sunshade` and `Skylight` must not be substituted for each other.

## Cache and diagnostic contract

Each source connection starts a new epoch. Teardown clears both representations. Retired-source
callbacks are ignored. Malformed, out-of-range, future, delayed or out-of-order current-source
events withdraw the old value. Sixty seconds without a curtain event withdraws it independently
of battery, climate, tires or ambient-temperature activity. This is a local silence policy;
OEM measurement timestamp and notification cadence remain unknown.

`LeapmotorTelemetry.diagnosticReport()` reads memory and never starts a source. Log tag
`DiPlay-SUNSHADE`, at most once per second:

```text
C11 SUNSHADE status=AVAILABLE positionRaw=25 oemUiPosition=75 receivedAgeMs=0
```

Missing values use `UNKNOWN`; statuses are `UNAVAILABLE`, `AVAILABLE`, `STALE`. No payload,
identifier, location or arbitrary text is logged.

## Write route identified; authorization remains blocked

OEM code distinguishes the curtain SDK request `IVI_ROLLO_POSITION=1105` from glass request
`IVI_SUNROOF_POSITION=1104`. `BaseControlManager.audioControl(1105, text)` checks
`persist.sys.lp.config.sunshade == 1`, parses an integer 0..100, and forwards it to
`MainService.sendAudioEventToQnx`. The glass path checks `Skylight` separately.

The local Unity path is different: button 327002 passes 100 to `ControlManager.syncSkyWindow`,
327003 passes 0, and slider 327007 passes `100 - UI value`. That legacy CAN helper divides the
number by ten before publishing event 1105. These are different interfaces and encodings; do
not apply the CAN division to the SDK request or infer physical endpoint direction from them.

The SDK route remains behind `ICarControl` server checks: `CAR_CONTROL_SERVICE` permission plus
OEM package-prefix validation. DiPlay is outside that package policy. No authorized third-party
broker or granted privileged window-control route has been demonstrated. The captured SomeIP
JNI exposes publisher offers/notifications and subscriptions, not an authorized client-request
API. No OEM application name, service identity or namespace is impersonated.

`ROLLOMOVECMD` 1117 / notification 7117, anti-pinch 2806, initialization 2804, movement 2814,
switch-error 2812 and thermal protection 2810 are symbols only in the currently inspected
contract. Their enums, validity and actionable interlocks are not proven. **STOP is UNKNOWN**;
no command encoder or action implementation is installed. Position readback alone does not
authorize motion or enable Siri controls.

## Evidence and remaining validation

Primary local sources: captured CarControl APK, `BaseControlManager` static initializer /
`onMessageInteger` / `audioControl` / `onClickEvent`, `ControlManager.syncSkyWindow`,
`CanEvent`, `ControlEvent`, `UnityButtonId`, `SomeIpService.SomeIpInit`, `SomeIpSubManageImpl`,
`ByteUtils`, and `CarControlImpl.onTransact`. Private fallback source and evidence inventory are
under `.private/c11/ultra-20261020/`.

Physical validation on 2026-10-20 received event 2801 with raw 0 / OEM UI 100;
`persist.sys.lp.config.sunshade` is 1. No motion was performed. Full required CI
plus diagnostics passed with 3,469 JUnit tests, zero failures/errors and one
pre-existing skip; 33 Python tests passed.

Required next evidence: user-observed curtain direction
and endpoints, capability configuration, verified STOP and interlock semantics, and a legitimate
authorized broker. Software tests cover decoding, inversion, malformed payloads, glass rejection,
epoch fencing, invalidation, silence and Messenger teardown/replacement. No physical command
test has been performed by this implementation.

## Dedicated module review: remaining state signals

The independent decoder/cache is `shared/.../vehicle/leapmotor/LeapmotorSomeIpSunshade.kt`.
Transport ownership stays in `LeapmotorTelemetryService`; the decoder neither starts a service
nor sends commands. A dedicated static review on 2026-10-20 confirmed this boundary:

| Curtain state | Event | Primary evidence | Implementation decision |
| --- | --- | --- | --- |
| Position | 2801 | `BaseControlManager` subscription type 1, four-byte integer consumer and range check | Decoded and physically observed |
| Initialization | 2804 | `CanEvent.OPCODE_RZCU_SS_AutoInitializationSts` constant | No proven subscription type or enum; not decoded |
| Anti-pinch | 2806 | `CanEvent.OPCODE_RZCU_SS_AntiPinch` constant | No proven subscription type or enum; not decoded |
| Switch state | 2808 | `CanEvent.OPCODE_RZCU_SS_SWSts` constant | No proven subscription type or enum; not decoded |
| Thermal protection | 2810 | `CanEvent.OPCODE_RZCU_SS_ThermalProtection` constant | No proven subscription type or enum; not decoded |
| Switch error | 2812 | `CanEvent.OPCODE_RZCU_SS_SWError` constant | No proven subscription type or enum; not decoded |
| Movement | 2814 | `CanEvent.OPCODE_RZCU_SS_Movement` constant | No proven subscription type or enum; not decoded |

Numeric symbols alone do not establish integer encoding, valid ranges or whether zero means
healthy. The corresponding glass events use different identifiers and cannot supply missing
curtain semantics. The glass consumer for 2800 also lacks the curtain's explicit range check
and inversion, so the curtain decoder must continue rejecting it.

### Notification silence is an evidence gap

`SubInfo.SubEvent(2801, 1)` sets the OEM payload type, not an ON_CHANGE/periodic contract.
`SomeipSubInfo` carries service, instance, group and event arrays, with no notification cadence.
The OEM `SomeIpPool` caches values without a measurement timestamp; its delayed callback reads
the same cache. Likewise, `BaseControlManager` posts a three-second Java handler task after
position receipt. Neither operation proves a new roof measurement or a periodic wire event.

Consequently the current sixty-second expiry can hide an unchanged valid position if the
producer is ON_CHANGE. That is a presentation limitation, not evidence that the curtain stopped
working. Keeping an indefinitely AVAILABLE value would instead hide source failure. This review
does not change expiry until there is a verified source-health/notification contract; unrelated
battery, climate or tire messages must not refresh the curtain's measurement age.

### Next physical evidence, when coordinated

First capture a stationary curtain for more than sixty seconds while retaining the bounded
position diagnostics. If it expires, compare with OEM position and source availability; silence
alone cannot distinguish ON_CHANGE from loss of the roof producer. Then, with the owner operating
the OEM controls, observe partial travel and both endpoints to establish raw/UI direction.
Do not trigger anti-pinch or obstruction deliberately. Record any naturally occurring state
changes only after establishing their payload contract. A future writer also needs verified
STOP, error/interlock semantics, authorization and readback before it can expose a control.

This review added documentation only. It did not expand subscriptions, move the roof, access the
car, or run a new build; the CI counts above refer to the earlier implementation.

## Offline follow-up: STOP, namespaces and next calibration

The follow-up review found no additional evidence sufficient to label physical OPEN/CLOSED,
normalize movement/interlock enums or implement a writer. The current position decoder/cache
therefore remains unchanged. No navigation Kotlin, subscriptions, APK, car state or permissions
were modified, and no ADB/Gradle command was run.

### Additional static facts, not a STOP contract

`ControlEvent` also defines `IVI_ROLLO_POSITIONSETREQ=1111`, alongside position1105 and
`IVI_ROLLOMOVECMD=1117`. `CanUtils.getCanIdByEvent()` groups these legacy control events under
CAN identifier834 (`0x342`). This establishes only dispatch grouping: it supplies neither a bit
layout nor valid movement values, STOP, acknowledgements or actuator interlocks. Notification7117
is likewise only a symbol in the inspected sources. Do not generate a frame from this grouping.

The integer namespace matters: separate captured classes assign1111 to
`QnxControlEvent.IVI_ADAS_CLARION_CALLSTS` and `QnxIVIEvent.IVI_BLTIR_PRESS`. A bare integer or the
word POSITIONSETREQ is not sufficient to select an operation or infer roof behavior.

The captured Android car API offers another read-only inventory candidate, independently of the
previously rejected OEM Binder path:

| Standard property/area symbol | Decimal ID | Current evidence boundary |
| --- | --- | --- |
| WINDOW_POS | 322964416 | Standard property symbol, not demonstrated on this HAL |
| WINDOW_MOVE | 322964417 | Standard property symbol, no demonstrated roof STOP/value contract |
| WINDOW_LOCK | 320867268 | Standard property symbol, not a proven curtain interlock |
| WINDOW_ROOF_TOP_1 | 65536 | Area symbol, not a demonstrated curtain/glass assignment |
| WINDOW_ROOF_TOP_2 | 131072 | Area symbol, not a demonstrated curtain/glass assignment |

The captured `PropertyHalServiceIds` maps all three properties' read/write access to
`android.car.permission.CONTROL_CAR_WINDOWS`. No actual grant to the current development caller,
exposed configuration, usable GET, physical area mapping or broker is established. A future
read-only inventory can inspect configurations and existing legitimate permission status; it must
not assume a privileged route exists. As with climate, a missing/unusable GET alone cannot prove
that every possible write implementation is absent. It still supplies no basis to expose control.

### Minimal future physical session

Coordinate one stationary session with the owner using only the normal OEM curtain controls;
DiPlay remains a passive observer. Do not operate the separate glass roof for this test.

1. Record the starting curtain position visually, with current `Sunshade` capability configuration,
   source connection epoch and the bounded2801 diagnostic. Retain only event IDs, scalar positions,
   relative receipt times and the owner's physical state labels, not screen or vehicle identifiers.
2. Observe at least90 seconds stationary before moving. Record any sixty-second expiry separately
   from the owner's unchanged visible position. Other telemetry traffic is not curtain liveness.
3. Use the OEM control toward one visible endpoint. Correlate `positionRaw` and `100-positionRaw`
   with the observed endpoint; repeat toward the other. Do not name0/100 OPEN/CLOSED until both
   directions are established for the same source and this actual car. Restore the owner's initial
   curtain position afterward.
4. A partial-travel/stop observation is optional and uses only an already known OEM stop interaction.
   If that interaction is unknown, omit it. A stationary2801 value after release proves only observed
   position, not a numeric STOP command. Do not deliberately obstruct the curtain or induce protection.
5. Do not subscribe to2804/2806/2808/2810/2812/2814 merely to guess their payloads. First obtain an
   exact OEM subscription/consumer or an admitted protocol definition for each; only then consider
   bounded passive capture of naturally occurring changes. A list of integer symbols is insufficient.

A separate read-only HAL inventory, if an actually authorized caller is available, should record
configuration presence/type/access/areas/change mode/ranges and GET property identity, exact area,
status, value and timestamp. Compare any exposed roof area against owner-operated curtain movement
while keeping glass state unchanged. Treat permission denial, unsupported config, placeholder GET
and a working curtain GET as distinct outcomes. This session sends no property SET or QNX/CAN request.

Acceptance of normalization requires the observed physical mapping to agree in both directions,
without borrowing glass semantics. Acceptance of future control additionally requires a verified
request interface/namespace, legitimate authorization, STOP, interlocks and independent readback;
the calibration above alone does not satisfy those control prerequisites.

### Source locations for this follow-up

All are previously captured development sources under
`.private/c11/20261016T005916Z/decompiled/`:

- `carcontrol/sources/com/leapmotor/can/external/ControlEvent.java`: roof1105/1111/1117 constants.
- `carcontrol/sources/com/leapmotor/can/CanUtils.java`: `getCanIdByEvent()` grouping to834.
- `carcontrol/sources/com/leapmotor/carcontrol/event/QnxControlEvent.java` and
  `carcontrol/sources/com/leapmotor/can/external/QnxIVIEvent.java`: separate1111 namespaces.
- `android-car/sources/android/car/VehiclePropertyIds.java` and `VehicleAreaWindow.java`:
  standard property and roof-area symbols.
- `car-service/sources/com/android/car/hal/PropertyHalServiceIds.java`: window permission mapping.

The existing `BaseControlManager-sunshade-fallback.java` and `ControlManager-fallback.java`
continue to support the position inversion and distinct SDK-versus-legacy encoding already
reported above. This follow-up does not claim a new vehicle measurement or control capability.
