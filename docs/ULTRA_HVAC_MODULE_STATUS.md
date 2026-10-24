# C11 HVAC module: implementation and external review evidence

## Offline follow-up, 2026-10-21

The captured `AirManager` distinguishes raw recirculation event1010 from automatic
cycle event1310. The1010 consumer saves `isCycleState` but suppresses its direct
Binder callback while `isCycleAuto` is set; the1310 branch emits a synthesized
callback under1010 or replays the stored cycle state when auto is cleared.
Consequently the Binder1010 enum cannot simply be treated as the raw SomeIP1010
enum. The exact consumers are in the pinned development fallback source at
lines2878–2903 and3657–3680, with subscriptions at213 and482. No decoder,
subscription or climate capability was added from this evidence alone.

Likewise1003 updates `isACMax` and follows separate mode branches; in automatic
HVAC mode the consumer returns before updating `mACState` (lines2578–2647).
A cached scalar or an event name alone is therefore insufficient to expose
compressor ON/OFF or a verified Siri A/C control. The authorized write route and
independent physical confirmation remain pending. These findings came from
previously captured development sources; no vehicle command was sent.

2026-10-20. HVAC / A/C here means cabin climate, not adaptive cruise control.
This file separates observed vehicle data, static OEM contracts, and untested routes.
No new vehicle action, permission change or firmware modification was performed for this review.

## Current implementation and physical evidence

* Targets: `LeapmotorSomeIpClimateDecoder.kt` decodes events 11205 FRONT_LEFT and
  11206 FRONT_RIGHT on service 0x9900 / instance 0x9901 / group 0x9903, exact eight-byte
  little-endian double, accepted OEM consumer range 18..32 Celsius. The cache is
  receipt-based with independent sides, epoch retirement and 60-second silence withdrawal.
  A physical RIGHT target20 was captured. Historical LEFT absence was not conclusive:
  an early family logger suppressed later ON_CHANGE fields inside one second; the
  trailing-batch diagnostic fix now preserves their latest states.
* Measured temperatures: events1221 interior and1016 corrected exterior use separate
  eight-byte double decoders/caches. Physical values25.0 /14.5 were observed. OEM voice
  code appends degrees without conversion; Celsius, sensor health/invalid finite values
  and physical sensor scope are not fully established. They remain OBSERVED_UNVERIFIED,
  not target substitutes. See `ULTRA_AMBIENT_TEMPERATURES.md`.
* Standard Android HVAC property358614275: real UID2000 shell has CONTROL_CAR_CLIMATE
  before and after reading. Config is RW Float ON_CHANGE, range16..32, areas49/68.
  Both physical GETs returned area0, value16, status0, timestamp0, identityMatches=false.
  This is an invalid area-specific target observation, consistent with a HAL placeholder.
  A status of AVAILABLE alone is insufficient. No setter was invoked.
* DiPlay normal user10 lacks CONTROL_CAR_CLIMATE; merely granting this permission
  would not repair the already measured shell HAL result. Existing write adapter remains
  disabled and no functional climate/Siri write capability is registered.
* Step is still unverified. Stock SystemUI resource half-degree UI is not proof that
  this Unity/OEM path accepts half degrees; HAL16..32 differs from OEM consumer18..32.

Physical shell evidence: `.private/c11/ultra-20261020/climate-shell-read-physical.json`.
OEM sources: `.private/c11/ultra-20261020/AirManager-climate-readonly-fallback.java`
(hereafter AirManager) and `hvac-module-review/AirControlManager-fallback.java` in the
same private directory (hereafter AirControlManager).

## New independently implemented increment: passive fan level

`shared/.../vehicle/leapmotor/LeapmotorSomeIpFan.kt` adds a decoder and transport-owned
cache for event1008. AirManager registers it as valueType1 at lines183–187. Its consumer
at lines2759–2781 accepts only0..7 before updating mWindNum, binder1008, BLE airLevel,
strCarAirWind and Unity101009. The dispatcher requires exactly four bytes for valueType1
and ByteUtils decodes int32LE. No RPM/percentage/compressor/power state is inferred.
Unknown, malformed, delayed, future, out-of-order or retired-source data cannot become zero.
Silence over60s withdraws the level. Receipt age is not an OEM origin timestamp.

The module itself does not open a source. The existing passive subscriber now includes
event 1008 and owns its cache lifecycle. Delivery remains physically unvalidated until
a new capture observes event 1008. No new iPhone
capability is justified by this cache alone.

Integration implemented after root delegated the two central Telemetry files:

1. TelemetryService: import LeapmotorSomeIpFanDecoder; append LEVEL_EVENT to the
   existing0x9900/0x9901/0x9903 subscription, and accept it in the bounded callback filter.
   Keep exact four-byte validation in decoder; keep existing payload maximum8.
2. Telemetry: own LeapmotorSomeIpFanState(SystemClock::elapsedRealtime) plus DiagnosticBatch.
   Clear/reset at every existing teardown; beginSource per actual binding; retain epoch
   in that binding's Messenger receiver. Pass service/instance/group/event/payload/receivedAt
   through receive. On accepted changes log the latest cached reading via DiagnosticBatch,
   using the current-binding guard and the existing trailing1s emission behavior.
3. Diagnostic report must read cache only. Suggested bounded line:
   `C11 HVAC_FAN status=(UNAVAILABLE|OBSERVED|STALE) level=(UNKNOWN|0..7) receivedAgeMs=(UNKNOWN|int)`.
   Do not log AVAILABLE as physical acceptance, powerON or freshness from an OEM timestamp.
4. Capture parser allowlist belongs to the wheels agent. Three additional Robolectric
   integration tests cover trailing batches, missing flag constituents, invalid/silent fan
   observations and same-connection retired callbacks. No UI, Settings or global activation
   was changed here. Root delegated parser work to
   the wheels agent; that work is separate from the Kotlin integration.

Seven focused JUnit tests were added for wire shape/identity/range, all real levels,
malformed withdrawal, already silent receipt, clock rollback, out-of-order/future time,
source replacement and never-started acceptance. Gradle was intentionally not run because
root coordinates full CI. Source review and scoped whitespace check passed. Subsequent coordinated CI passed
3,508 JUnit tests, zero failures/errors, one pre-existing skip.

## Other state contracts: known and deliberately incomplete

| Event | Evidence | Remaining meaning |
| --- | --- | --- |
|1001 HVAC power|AirManager144–151 registration;2427–2466 consumer uses value1 as mAirConditionerOpen=true and updates airPower/strCarAirSwitch|Full finite invalid/enumeration semantics unproven; do not equate every non1 to trustworthy OFF|
|1008 fan|Registration183–187;2759–2781 exact0..7 consumer|New local module above; no live capture yet|
|1011 airflow mode|Registration173–177;2714–2727 stores raw+1 to mWindMode|No complete outlet/layout enum established|
|1012 front defrost|Registration188–192;2784–2830 stores mFDefrostState;0 restores ordinary status;1/2 special handling|Complete invalids/mode distinctions not established|
|1013 rear defrost|Registration193–197;2832–2855 forwards raw to Settings and Unity|Full accepted state enum not established|
|1002/1003 AC/mode|Registration153–167 and consumer2474 onward|Multiple flags/combinations; no complete trustworthy compressor state derived|

These are state event IDs, not write request IDs. An enum with missing semantics should
remain raw/unavailable rather than be advertised as a functioning climate switch.

## Static write contracts: exact IDs and confirmation caveat

The verified temperature SDK IDs in this captured build are **1409 and1410**, not110x.
AirManager audioControl switch1160–1165 maps1409 to labelL251 and1410 toL22a;
lines1370–1404 parse integer18..32 and multiply by2 for syncMainTemp/syncViceTemp.
AirControlManager1044–1100 /1355–1390 divide the doubled UI value by2 and send decimal
Celsius text via MainService.sendAudioEventToQnx with1409/1410 respectively.
Power syncAirOpen560–589 uses1402. Fan syncWind1393–1417 uses1407 and permits1..7;
its write domain is different from read1008 allowing0..7. Do not invent fan0 as powerOFF.
These are code-verified only; no physical setter, request acknowledgment or applied
readback has been demonstrated. The OEM manager updates some local cache/UI before
sending, so its local callback is not proof of applied vehicle state. A future temperature
transaction must confirm a new independent11205/11206 observation for the intended
physical side, within a bounded timeout, and must not report Siri success on send alone.

## Access policies: technical checks versus engineering scope

* CarControlImpl.java82–107 requires normal permission
  `com.leapmotor.carcontrol.CAR_CONTROL_SERVICE`, then real getPackagesForUid(callingUID)
  containing prefixcom.leapmotor orcom.android.systemui. This is a measured source guard,
  not proof of a general OEM licensing policy. Shell and current DiPlay identity do not match.
* CarControlExternalImpl-fallback.java142–236 applies a separate explicit package allowlist.
  It namescom.leapmotor.autosdk.server, but root's pm path for user10 returned no path;
  availability in other users/builds is not established. No usable API/schema was captured.
* CustomSceneService is exported and returns a real ICustomSceneInterface Binder, but
  its anonymous Binder onTransact accepts exactly the first callingUID package
  com.leapmotor.scene before dispatch. Shell/DiPlay are rejected; handlerTask is not an
  open generic command endpoint. Exact guard excerpt is saved in
  `.private/c11/ultra-20261020/CustomSceneService-caller-guard-review.java`.
* CONTROL_CAR_CLIMATE is signature|privileged for standard CarProperty. Shell legitimately
  holds it; actual area identity/timestamp validation still fails, as above.
* Captured SomeIP wrappers demonstrate subscriber and publisher/service-offer/notify.
  They do not demonstrate a separate client request API. Not offering the OEM service or
  masquerading its identity is our current engineering scope to avoid service collision;
  it is not evidence that the OEM protocol forbids all independent client implementations.
* User authorization covers development and vehicle work, excluding personal files and
  passwords. That owner authorization does not automatically satisfy Android UID/package
  checks, nor does this review authorize modifying firmware or extracting credentials.

## Resolved routes versus unexplored possibilities

Resolved for the captured code: CarShell provider hardcodes window/activity/SurfaceFlinger
logs, notHVAC; strcontrol broadcast sends only STR event7009; AiSpeechService bind returns
null and ignores command extras; SpeechReceiver SPEECH is TTS/mic coordination;
CustomSceneReceiver custom.scene/settings updates vocabulary/voice configuration.
No supported exported set-temperature action was found in these reviewed entries.
This is a bounded source review, not proof that no OEM integration exists anywhere.

Unresolved: an OEM-supported installed broker in another firmware/user; admission of a
legitimate DiPlay client to an OEM broker; an official SomeIP request SDK/service schema;
a real VHAL bridge for HVAC; Apple fan/power service contract and actual Siri acceptance;
OEM temperature step and safety/invalid semantics. None should be described as disproven.

## Falsifiable next experiments

1. Fan: subscribe only1008 through the existing passive owner. Change the OEM fan control
   manually while stationary; compare observed0..7 against screen settings and source epoch.
   No packet manipulation or write request is needed. If silent, do not substitute Settings.
2. Temperature confirmation: manually change physical LEFT thenRIGHT target and correlate
   only fresh11205/11206 observations, after the logger batching fix. Establish side mapping
   independently of DRIVER/PASSENGER and detect whether values update on each supported step.
3. Broker admission: only if an actual installed broker/API is identified, verify manifest,
   callingUID authorization and the exact request schema first. A read-only binder handshake
   can test admission before any1409/1410 request. If denied, require OEM admission or a
   supported broker build; do not claim adding a normal permission removes package guards.
4. VHAL: reconsider only after evidence of firmware/broker change; repeat GET to verify
   requested49/68 area identity, nonzero elapsed-realtime origin timestamp and agreement with
   actual OEM target. Another identical HAL probe without a change is not progress.
5. One end-to-end setter can follow only after authorized transport, range/step, real readback
   and side mapping are established. Success means requested side's new observed target,
   not sendAudioEventToQnx return, optimistic manager callback or an Ultra label on iPhone.
