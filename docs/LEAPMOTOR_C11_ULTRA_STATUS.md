# CarPlay Ultra: implementation gap and observed session

**Clarification: existing upstream iAP2 battery path:** the earlier phrase
"battery service missing" applies only to the experimental AirPlay
VehicleStatePrototype description; it must not be interpreted as absence of
battery delivery to the phone. Upstream commits c49cfba4/195b2672 already implement
the generic iAP2 EV identification/status path behind the BYD battery option.
The C11 host reuses that sender with LeapmotorTelemetry.provider instead of the
BYD-specific property reader. Current identification enables it for C11
independently of the BYD checkbox, and physical logs confirm SOC/range updates.
The owner's hypothesis that the prior partial Siri answer used this older path
is plausible but unproven; lack of readRequest on the separate AirPlay channel
does not exclude Siri consuming iAP2 state. Compare historical identification,
capabilities, source readiness and updates rather than assuming a new battery
service is necessary. Do not change the C11 profile to BYD to test the theory.

**Owner scope correction, native Siri only:** the owner explicitly rejected an
additional iPhone app. The SiriKit companion probe below is shelved and must not
be installed or extended as the proposed solution. Continue investigation in
DiPlay's existing CarPlay vehicle-state/iAP2 integration. Current inspection
confirms that the native description exposes trip-computer range only, not a
traction-battery SOC service; range delivery alone does not prove Siri access.
The local reference contains an electric-engine accessory and a range visibility
characteristic absent from this minimal prototype, but neither is proven to be
the cause of failed voice queries. Do not advertise a zero/fake power reading,
copy fuel-level characteristics for EV SOC, or claim an unverified schema works.
Native Siri support in this DiPlay integration remains unresolved in both modes.

**SiriKit companion probe prepared, 2026-10-24:** the owner authorized a minimal
iPhone prototype. `tools/siri-probe` now contains an app, power-intent extension,
shared opt-in fixture store, generated Xcode project and physical test matrix.
It deliberately uses only simulated 50%/180km for `C11 de prueba` during a
ten-minute activation; there is no real-telemetry network bridge. The core
compiled and passed13 CLI checks; plists/project/scheme validation and Android
CI passed. Full Xcode/iOS SDK and XCTest are absent. The owner has a free Apple
account; Apple's capability table excludes Siri from that membership. No iOS
build, signing, installation, Siri invocation, lock-screen behavior or either
CarPlay mode has been verified. See `tools/siri-probe/README.md` and private
`ultra-20261022/siri-probe-preparation-20261024.json`. A supported signing team
and full Xcode are required before physical testing. Native Siri is unresolved.

**Actual reboot follow-up, boot2820:** after the owner made the car available
again, revision43 was still installed. Opening the host automatically replaced
the boot2819 route with boot2820/local:131/display2/mode2. The session log confirms
the 720x720 cluster stream request, `stream active=true`, and the first received
frame of stream111. Owner visual confirmation is pending. No APK reinstall or
manual route renewal was needed. A passive startup capture also recorded channels
in nine of the ten prepared modules (75 channels with raw observations; meanings
and sentinels are not thereby validated). The current trip was 0km and its raw
consumption was -1; revision42/43 correctly exclude that from the visible field.
Evidence: `ultra-20261022/cluster-resume-boot2820.jsonl`,
`modules-boot2820-report.json`, and the follow-up in
`cluster-reboot-verification-20261024.json`.

**Revision43 installed and recovery verified, 2026-10-24:** CI passed (3,733 tests, zero failures/errors, 46 skipped). The old boot2810 route was deliberately restored with DiPlay stopped; after launching the installed app, it automatically remeasured the panel and saved boot2819/display2/mode2. This verifies recovery from a stale route without rebooting the vehicle. Physical map visibility remains for a later owner check. Siri remains unresolved. Evidence: `ultra-20261022/cluster-reboot-verification-20261024.json`. Work stopped at the owner’s request for the day.

**Cluster reboot regression, 2026-10-24:** the owner reported the second map
missing in both modes. The stored C11 route was bound to boot2810 while the car
was on boot2819. Fresh `dumpsys display` still identified physical `local:131`
as logical display2, mode2, 1920x720. The stale-boot guard therefore rejected a
real, available panel. The route was renewed from that measurement. Revision43
adds automatic off-main-thread measurement through already-approved local ADB
when the host starts without a valid route. It accepts exactly one physical
`local:131` base record, cross-checks the current DisplayManager geometry/mode
and boot count, and atomically replaces the route. It never requests approval
in the background or falls back to passenger/HDMI. A live session that had
started without the panel is reconnected to request its stream. Tests cover a
new boot and logical ID, ambiguous/wrong identities, geometry, rotation,
privacy, missing ADB data, and a boot change during measurement.

The simultaneous Siri report remains unresolved: the filtered trace includes
vehicle-state configuration/capability exchange and iAP2 delivery of range179km
and SOC40%, but no subsequent range-read request on that observed channel.
This confirms local data availability, not Siri acceptance or a working voice
query in either mode.

**Local ADB authorization confirmed, 2026-10-24:** the approval dialog initially
closed itself: runtime service.adb.tcp.port was unset although persist.adb.tcp.port
was5555, so UsbDebuggingActivity registered its USB-disconnect receiver and denied
the network key. `adb tcpip5555` restored the same existing TCP port; a subsequent
key offer showed the real Android confirmation dialog. The owner accepted it.
Read-only dumpsys verification now finds the current DiPlay public key in both
user_keys and the persistent keystore. No private key was exported. The permission
blocker is resolved; physical hotspot recovery remains untested. Private evidence:
`ultra-20261022/adb-authorization-check-20261024.json` and the follow-up in
`wifi-recovery-auth-block-20261023.json`.

**Installation checkpoint, 2026-10-24:** owner made the car available and requested
installation. `DiPlay-C11-0.2.20-c11.20261023-units-dev.apk` (SHA-256
`b5e6a2bd129e86b95fd0ba4d3317f640f5f041045790205caaa67298aebcde34`)
installed successfully for user10; CarPlayHostActivity was opened. Version40 /
`0.2.20-c11.20261023-hud-test` includes axle-unit placement and the C11 update
channel. Owner visual acceptance remains pending. This installation alone does
not fix or validate the local-ADB authorization/recovery dependency below.

**Units/version correction prepared, 2026-10-23:** owner requested Delante(kPa)
and Detrás(kPa); native sidebar now places the unit in both localized axle labels
and removes the footer. Required CI passed after adding update-channel coverage.
Candidate `DiPlay-C11-0.2.20-c11.20261023-units-dev.apk` is prepared but not
installed: owner reports the vehicle is no longer available. Version40 /
`0.2.20-c11.20261023-hud-test` identifies this fork without claiming the latest
upstream merge. HEAD remains1b1ac5f2; the full current upstream merge is pending.
C11 builds now withdraw cached official-update offers, skip upstream background
checks and explain the separate APK distribution in About. Ordinary profiles
retain the existing updater.

Wi-Fi follow-up found a concrete recovery blocker: the platform legacy tethering
call fails on Android12 and local ADB fallback returns NOT_APPROVED. WRITE_SETTINGS
is granted; both idle-shutdown settings remain disabled. This does not establish
why the hotspot initially went off. No radio reset, AP reconfiguration or ADB-key
authorization was performed. Current DiPlay key approval and physical recovery
validation remain pending until the owner makes the car available again. Evidence:
`ultra-20261022/wifi-recovery-auth-block-20261023.json` and
`c11-units-version-verification.json`. The offline high-reasoning climate review is
`climate-communication-review-20261023.md`; it found passive power/SYNC opportunities
but no new admitted actuator route for DiPlay.

**Wheel temperatures added offline, 2026-10-23:** Clara now includes each wheel's
existing QNX temperature below its pressure. The local cache already receives
events1113–1116; this change binds those fields to the conventional sidebar,
preserving independent validity/freshness and pressure-only fallback. Compact,
full and RTL renders were inspected; mandatory CI passed 3,653 tests with zero
failures/errors and46 environment skips. Latest prepared candidate:
`DiPlay-C11-sidebar-clara-tyre-temp-dev-20261023.apk`, SHA-256
`408067bd20ec8bf0ebf51c86f6f87547b76cd772ceeda6b8d27500c724d7fd3d`.
It includes the Clara icon work below. Prepared offline, then installed successfully
for user10 after the owner made the car available. The host was opened directly.
New receipt at device time10-23 07:03:50.970 reports233/228/228/231 kPa and
19/19/19/19 °C. Owner visual acceptance of the new layout remains pending.

**Sidebar Clara prepared offline, 2026-10-23:** the owner selected A · Clara
from the three local sidebar mockups. The implementation adds Lucide outline
icons beside the existing labels, a route icon on the separate range row and
soft separators. Compact/full/RTL renders at enlarged text were inspected.
Required CI passed 3,653 tests with zero failures/errors and 46 environment skips.
Signed candidate `DiPlay-C11-sidebar-clara-dev-20261023.apk`, SHA-256
`f686afc72ec9923ba193217471832dd144ecb1220edf17cd8749553091a73c8e`,
is archived privately with its verification JSON. It is **not installed**;
the owner requested work without further car checks. No vehicle access occurred
for this change. Telemetry, viewport width, audio and navigation behavior remain
as in the preceding candidate.

**Conventional CarPlay Siri trial, 2026-10-23:** the owner confirmed the new
four-wheel pressure sidebar works. Candidate `7f9af9e1` then enabled the existing
read-only VehicleState range protocol independently of Ultra. Full required CI
passed (3,653 tests, zero failures/errors, 46 environment skips); installation
succeeded. The iPhone selected `vehicleStateProtocol`, opened NORMAL/HIGH lanes
and received configResponse with range=true. iAP2 also reported real 220 km and
49.1%. The owner nevertheless heard that the vehicle was unavailable. This is a
failed Siri acceptance test, not a validated voice integration. No
registerRequest was observed in the filtered capture; that capture does not
record unknown or rejected commands, so absence is not proof that no request
arrived. Do not treat channel opening as a working Siri query or promise that
switching to Ultra fixes it. Private evidence: `ultra-20261022/siri-range-verification.json`
and `siri-range-current-capture.json`.

A documented alternative is an iPhone Intents extension implementing
`INGetCarPowerLevelStatusIntentHandling`; Apple routes car power/range requests
to that app handler. This repository currently has no iOS app/extension. Its
existence does not establish this CarPlay session's native routing failure or
guarantee a new extension would work here. A Shortcut bridge is another proposed
test route, not implemented or physically validated. Keep the owner's earlier
partial Ultra range reply as an observation, not proof of a complete contract.
Reference: https://developer.apple.com/documentation/intents/ingetcarpowerlevelstatusintenthandling

**Connection reliability follow-up:** physical readback shows both saved/active
hotspot timeout mechanisms still disabled: `mTimeoutEnabled=false` and
`mBridgedModeOpportunisticsShutdownTimeoutEnabled=false`. The AP retains its2.4
and5GHz instances. Do not reapply or alter the old idle-band fix blindly. Saved
hotspot recovery is enabled. Current failed attempts were Bluetooth RFCOMM
failures before iAP authentication, not AP shutdown. No competing AutoKit process
was present. When the owner confirmed the iPhone was nearby, the old process
already recovered at21:00:23–21:00:30 (attempt22). Do not attribute that recovery
to the later reset.

A manual Bluetooth reset was attempted after that observation. Shell return
codes/state transitions were unreliable; app was stopped and native Settings
was opened. Final verified state is adapter ON (`enabled:true`, `state:ON`,
`mEnable:true`, global `bluetooth_on=1`). Pairings were preserved. The shell reset
script has been retired to prevent reuse. DiPlay is reopened; the new process
reached authenticated/active CarPlay at21:02:12–21:02:13 (attempt3). No automatic
Bluetooth radio reset is implemented or proposed. Private diagnostic:
`ultra-20261022/current-hotspot-state.json`.

A source gap was found: hotspot recovery stopped when the projection Activity
was covered, even while its foreground connection service remained alive.
Candidate change also starts/stops the existing opted-in recovery with
`DiPlaySessionService`, preserving serialized startup and cancellation on explicit
disconnect, task removal and service destruction. Tests exercise service-only
recovery, repeated start commands and explicit disconnect. Installed `cd958d50` (`DiPlay-C11-hotspot-background-dev-20261023.apk`) and
launched DiPlay. Mandatory CI passed3,644 tests with zero failures/errors and46
environment skips. The owner cannot perform the hotspot-off background-recovery
test now and explicitly deferred it; do not claim physical recovery validated.
Evidence: `ultra-20261022/hotspot-background-verification.json`. Keep the
owner-confirmed audio controls and cards.

**Current physical baseline, owner-confirmed:** audio controls now work perfectly
with native Bluetooth ownership after build `07a16fec`; cluster cards are also
accepted. Preserve both. The Siri wheel button works. Siri vehicle queries remain
unverified/unsuccessful despite observed iAP battery/range delivery. Priorities:
real wheel data; vehicle queries through Siri in conventional CarPlay; authorized
HVAC writes and actual response verification; real radio/other vehicle control;
hotspot/reconnection reliability; independent passenger playback. Full Ultra
presentation remains separate experimental work, not a proven prerequisite.
The current implementation enables VehicleStateProtocol only with the Ultra
handshake; its prototype exposes range, not a verified SOC/outdoor schema.
Separating that protocol from the Ultra UI is a research path, not yet a working
Siri integration in conventional CarPlay. Apple also documents an iPhone SiriKit
vehicle power-level intent; do not promise all Siri commands can be supported by
an Android-only APK change.

**Owner confirmation and media-control follow-up:** owner confirms both cluster
cards now look correct after the right-alignment build `0e707116`. Keep their
geometry. Play/pause remains faulty even with Bluetooth audio selected; AutoKit
works with the owner's Bluetooth audio/controls path and is the behavioral
reference. The pending fix prevents DiPlay from creating a competing MediaSession
or requesting focus when car Bluetooth audio owns the session, and passes native
media keys through the foreground host instead of retransmitting CarPlay HID.
Keep metadata observation for the UI and retain the working Siri wheel button.
Installed candidate `DiPlay-C11-native-bt-controls-dev-20261023.apk`, SHA-256
`07a16fec`. Full mandatory CI passed: 3,642 tests, zero failures/errors,
46 environment skips. Actual Android user10 media-button target is now
`com.android.bluetooth/BluetoothMediaBrowserService`; no DiPlay MediaSession is
present. Owner subsequently confirmed audio control works perfectly. Evidence:
`ultra-20261022/native-bt-controls-verification.json`.

The owner clarifies that Siri battery/range/temperature questions are integration
tests for more complex future requests, not the immediate feature priority. Do
not promise these are supported: iAP battery/range delivery is observed, Siri
querying those values is not; exterior temperature remains a local readout.

**Cards aligned to the right after the owner's reference photo:** owner says the
heights are right, but both cards must move right like the original placement.
Installed and launched `DiPlay-C11-cards-right-dev-20261023.apk`, SHA-256 `0e707116`.
Both right edges now use a2% inset; vertical margins remain15% and route totals
remain capped at80% tile width. Full mandatory CI passed; compact/full/RTL
large-font local renders checked. Physical acceptance remains pending. The
Bluetooth-only audio trial is still applied; continuous playback and Siri audio
have not yet been confirmed by the owner. Evidence:
`ultra-20261022/cards-right-verification.json`.

**Audio and Siri follow-up after inset15:** owner reports the wheel Siri button
works, but music control, Siri battery/range answers and card placement still need
work. Do not mark these complete. Await the owner's new photo before moving cards
again. Physical iAP logs prove the phone requests `0xA100` and receives `0xA101`
every30s with actual49.6% battery and222km range. This proves status delivery,
not a successful Siri response.

Correct an earlier diagnosis: normal high-quality AAC uses stream102; experimental
buffered music uses103. `main_buffered_audio=false` is verified and stream102 is
expected. Do not remove102 to enforce that setting. The previous inference that
102 showed buffered mode still enabled was wrong.

`dumpsys audio` identifies competing focus requests from OEM
`com.android.bluetooth.a2dpsink.A2dpSinkStreamHandler`, DiPlay's
`CarPlayMediaKeys` and `AudioFocusCoordinator`. A reversible configuration trial is
now applied with readback: `car_bluetooth_audio=true`,
`disable_media_key_audio_focus=true`, `audio_focus_enabled=false`, and buffered
music remains false. DiPlay was restarted. The trial intentionally selects the
existing car Bluetooth audio route and suppresses CarPlay output advertisement;
owner must confirm continuous playback, wheel controls and Siri microphone/voice
still work. It is not physically validated yet. Evidence (private):
`ultra-20261022/current-playback-state.json` and `apply-sidebar-settings.py`.

Apple documents a separate iPhone SiriKit power-level intent:
https://developer.apple.com/documentation/intents/ingetcarpowerlevelstatusintent
This is a research lead, not proof that a companion app is required for this
particular CarPlay firmware/iOS session. Do not promise a Siri battery fix merely
because iAP delivery works or manufacture vehicle data to obtain a response.

**Additional owner-requested card adjustment:** installed `f0947c75`
(`DiPlay-C11-inset15-dev-20261023.apk`) and launched DiPlay. Vertical margins
increase from12% to15%: maneuver moves down22px and route totals move up22px
in the720px navigation tile. Full mandatory CI passed, and compact/full/RTL
large-font local renders were checked. Physical acceptance remains pending.
Private evidence: `ultra-20261022/inset15-verification.json`.

**Owner feedback and local follow-up, 2026-10-23:** the owner accepts the right
vehicle sidebar. They report the maneuver/arrival cards are lost in the physical
cluster's edge fade and audio starts then stops even from the on-screen play
button. Local follow-up moves both cards 12% inside the vertical tile edges and
keeps the totals 10% inside the horizontal edges. The existing map center remains
unchanged. Real vehicle-reported range in km is added below battery percentage;
it clears with the vehicle snapshot and is not calculated from battery percent.
The UI follow-up was subsequently installed after the owner requested continuation. APK
`DiPlay-C11-range-inset-dev-20261023.apk`, SHA-256 `90b3b19c`, has a valid signature.
Full mandatory CI passed: 3,641 tests, zero failures/errors, 46 environment skips.
Compact/full/RTL large-font local renders were checked. Evidence:
`ultra-20261022/range-inset-verification.json`.

Buffered media was enabled (`main_buffered_audio=true`), and physical logs show
repeated type102 AAC media stream creation/closure. This is a hypothesis for the
playback problem, not a proved cause. At the owner's explicit request the saved
setting is now false, with readback verified. The failed previous audio-focus
trial was reverted (`audio_focus_enabled=true`,
`disable_media_key_audio_focus=false`). After stopping the test package to reload
preferences, ADB became offline and the launch command failed `error: closed`;
The owner later requested continuation; the candidate installed successfully and
DiPlay launched. Saved buffered audio remains false after installation. Stable sound
and the adjusted physical card positions await the owner’s check. The earlier
background-only restriction applied while the other session owned the screen. Prior Siri range response is a positive owner observation;
its initial error remains unexplained, and broader Siri battery support is not
claimed.

**Conventional CarPlay sidebar candidate, 2026-10-23:** complete APK `d7f9dd22`
is installed and launched. C11 now reserves a right-hand local vehicle sidebar,
negotiates the remaining full-height viewport and uses that same geometry for
video and touches. Its default dock is driver-side; the installed test instance
is explicitly set to that edge. No receipt-age text appears in the sidebar.
Pressure remains unavailable and reads “Sin datos”. C11 maneuver banners are
centered at the top of the existing bounded navigation tile, with route totals
at its bottom; the map safe-area center is unchanged.

Full mandatory CI passed: 3,641 tests, zero failures/errors, 46 environment skips.
Compact/full/RTL large-font local renders were checked. These validate local
layout, not physical acceptance. The owner is checking the car now.

New wheel evidence: owner confirms next/previous work but single play/pause
behaves as a double press, and the assistant fails. OEM logs confirm event4114
press/release; DiPlay's new process confirms receipt with one active listener.
The first complete live click now works without an initial idle notification;
cached/held startup presses remain rejected. Actual assistant opening is pending.
Phone metadata can now register C11 media controls without waiting for a local
audio decoder. As a reversible play/pause trial, both competing audio-focus
requests are disabled in the test instance; no physical fix is claimed yet.

The existing hotspot recovery was off and its control hidden on C11. The existing
ADB control is now exposed in Connection and enabled in the test instance, with
WRITE_SETTINGS granted. Both hotspot idle timers were already disabled; the
reported new disconnection cause remains unresolved. User authorizes resetting
the entire test app configuration if needed. Private evidence:
`ultra-20261022/sidebar-carplay-verification.json`.

**Conventional CarPlay priority, compact follow-up:** complete APK `c8b2ffb6`
was installed preserving data. The C11 readout is one transparent line with
battery percentage and exterior temperature; age is kept in diagnostics, and
the reserved header is reduced from96dp to48dp. Full CI passed3,637 JUnit tests,
zero failures/errors,46 environment skips. The owner will provide the quick
visual acceptance check; do not claim the new layout physically accepted yet.
Ultra was disabled through Settings and read back as `handshake=false`.
The missing cluster route was measured again for boot2810, physical `local:131`,
logical display2, mode2,1920x720, and restored with readback. Overlay permission
was enabled through Android Settings for DiPlay HUD Test only and confirmed
`SYSTEM_ALERT_WINDOW: allow`. The owner subsequently supplied physical photos confirming cluster navigation
with Google Maps and Waze. Maneuver and route-total positions still require correction;
other apps have not been validated. Spanish language/font settings were not changed.

**Exterior display fixed and physically checked, 2026-10-22:** complete APK
`ba07118c` was installed preserving data, with its installed hash verified.
The real centre screen shows **13 °C beside 60% battery**, including receipt-age
labels12 and13 minutes: the observation stays visible beyond the former60s
cutoff. The strict freshness cache and iPhone data are unchanged. A separate
local last-received observation clears on source loss or invalid input; the
temperature can render without battery data. Conventional C11 video reserves
a header through the same video/touch layout. Full CI passed3,636 JUnit tests,
zero failures/errors,46 environment skips; compact/full/Arabic large-font
screenshots were checked. Private evidence:
`ultra-20261022/exterior-visible-fix-verification.json` and
`exterior-fixed-visible-stable.png`. The hotspot was off after the car returned;
it was enabled through Android's existing settings, preserving its credentials.
No wheel-pressure display or Ultra recovery is claimed by this correction.

**Visible telemetry priority, 2026-10-22:** the owner paused further Ultra recovery
work in favor of a useful local readout, with four-wheel pressure next. A fresh
OEM exterior event returned13.0; the owner confirmed Celsius on this car. Complete
APK `26ea6f10` adds reported exterior temperature beside battery, including in
conventional CarPlay. It was installed preserving data and its hash verified.
After the other SystemUpdate session stopped, the app was launched and Ultra
disabled through its UI for conventional-CarPlay validation. Connection attempts
failed before video, so physical readout visibility remains unverified. The OEM
launcher pressure widget was captured (231/228/228/231 kPa, FL/FR/RL/RR); DiPlay
received no TPMS events in these captures and has no validated pressure indicator.
Full CI passed3,632 JUnit, zero failures/errors
and46 environment skips;38 capture tests passed. Compact/full, C11, high-density and
Arabic large-font screenshots were checked. The60s silence-withdrawal policy and
unverified OEM sensor-health contract remain documented in
[the local exterior readout](ULTRA_AMBIENT_TEMPERATURES.md).

**Physical follow-up, 2026-10-22:** the owner authorized installing complete APK
`c469c29a`; data was preserved and its installed hash verified. The owner reports
better speed and visible vehicle battery, but the hang recurred. Accepted Canvas
submission recovered once before a later `FIRST_FRAME_TIMEOUT` and fallback.
The improvement is partial. Available logs do not confirm the reported AP shutdown:
the point-in-time AP state is `StartedState` and both inspected idle shutdown flags
are false. Network configuration was not changed. Filtered evidence and limits are
in [the physical follow-up](ULTRA_BLACK_SCREEN_DIAGNOSIS.md).

**Recovery update, 2026-10-22:** the complete installed APK `02ad2563` connects
wirelessly, but returning from DiPlay to projection produced `FIRST_FRAME_TIMEOUT`
and a conventional-CarPlay fallback. The local correction retains the last decoded
image and recomposes on host/protocol changes without requiring a new phone frame.
It preserves the phone timestamp and activation/input fencing. Two real-JNI
regressions recover after a native input flush and an initially unavailable scene.
The car is excluded from this iteration at the owner's request while another
session uses SystemUpdate; physical recovery and the Vehicle panel remain unverified.
Full CI passed3,625 JUnit tests with zero failures/errors and46 environment skips;
the separate real-JNI regression run passed9 tests without skips. Complete APK
`c469c29a` is prepared locally and has not been installed.
See [the recovery evidence and limits](ULTRA_BLACK_SCREEN_DIAGNOSIS.md).

**Local Vehicle increment, 2026-10-21:** the explicit C11 Ultra path now supplies
the real read-only vehicle Settings image producer, with battery summary/detail
and Back. Input is tied to an accepted Canvas, exact source token and current
host activation. Gesture ownership survives connection replacement; CANCEL,
stale images and ambiguous geometry cannot trigger a local action. Geometry
traversal does not hold the pause/touch monitor. Full CI passed3,599 JUnit tests,
zero failures/errors and one existing skip;126 Python tests passed. The archived
APK `DiPlay-Ultra-vehicle-panel-touch-dev-20261021.apk` has SHA-256
`bc964a64c156414eb7490435c4a655b01d6a8af7423b25d7fe44a306ca9f4151`.
No new APK has been installed for this increment. The
Vehicle-icon callback and a visible/dismissible phone panel remain unverified.

TPMS follow-up proved the Java bridge does not scale/offset received values;
active unit binding remains unresolved. HVAC follow-up distinguishes raw
recirculation1010 from automatic1310 and their synthesized Binder callbacks.
No new climate or roof write capability is enabled.

**Latest installation, 2026-10-21:** at the owner's subsequent explicit request,
the previous fork was uninstalled without retaining data and the stability
candidate `14450bee` was installed. The installed APK hash was verified. No old
preferences, cluster route or experimental opt-ins were restored; the app remains
closed. Camera/focus and reconnection recovery still need physical validation.
This supersedes earlier installation restrictions below.

**Current update, 2026-10-21:** stability takes precedence over new integration.
The owner reports connection loss on camera/focus transitions and a black screen
on reconnection. Apple Maps does show turns and a simplified map in the cluster;
the requested perspective route view remains incomplete. Menu duplication is
reported but not yet localized or reproduced. The recovery candidate is local,
not installed, and physical recovery remains unverified.

The requested clean reinstall of e6ced9da completed while the owner subsequently
asked to postpone installation until stability is fixed. Prior fork data was
erased, defaults were retained without restoring the cluster route or opt-ins,
and the app remains closed. Do not install another candidate before resolving
and validating stability. Older installation statements below are historical.

Status on 2026-10-19: **real UI Sync channel, script owner, matching layout reply
and Canvas submission observed; useful full Ultra integration remains incomplete**.
The iPhone showed CarPlay Ultra. The user saw ordinary CarPlay, small and slow,
without new widgets or multiple displays. Resource provisioning and a physical
Ultra interface with live vehicle values have not been verified.

**Current priority, revised explicitly by the owner:** integrate the Leapmotor,
including Siri temperature controls, real battery information, navigation on the
instrument display, visible vehicle widgets, and independent passenger entertainment.
Graphic refinement must not prevent work on those functions. Each control must
confirm the actual vehicle response; mock values and receipt ACKs are not completion.

**Car coordination, 2026-10-20:** the owner made the car available again and authorized
installation and diagnostics. The render-lock correction described below is installed.
The bounded car capture has stopped; other agents worked locally.
Normal projection keeps the official system-bar style and Bluetooth
auto-launch remains off. Display metadata confirmed main 2560×1440, passenger
1920×720 and cluster 1920×720; the resource canvas is a separate 1920×720 contract.

## Vehicle entry: local input contract verified, 2026-10-20

The owner clarified that the failing entry is **Vehículo inside CarPlay Ultra**;
radio is not enabled and was excluded from this work. The selected resource's
Settings overlay requires a local SETTINGS input; the live entry still supplies
only iPhoneVideo. This missing source is proven, but the actual Vehicle-icon
callback has not yet been correlated with that overlay.

The bridge now supports explicit SETTINGS1 alongside default phone3, without
declaring a new live source. Only the first phone input drives the presentation
clock. With the original controller and a synthetic source/peer, the Settings
gauge was created, presented with its own token and dismissed/unsubscribed.
Four dedicated tests and four center regression tests passed. This is protocol
and native-scene evidence, **not an OEM app, rendered Settings pixels or a working
Vehicle app**. Bounded observations now distinguish present/dismiss request shapes
without retaining input identifiers. Routing remains unchanged.

Latest prepared APK, **not installed**:
`DiPlay-Ultra-vehicle-settings-contract-dev-20261020.apk`, SHA-256
`e6ced9da96f674ec31962332ea7178b90609b96a92965929c01f1c1f31df72c7`.
Full required CI plus diagnostics passed3,563 JUnit, zero failures/errors and one
existing skip;37 capture,12 asset-packaging,7 TPMS and60 LeapMoto Python tests passed.
The original-header flag is verified false. Installed version remains510df783;
no car access, installation or radio change occurred in this increment.
See [Settings contract and remaining integration](ULTRA_VEHICLE_SETTINGS_CONTRACT.md).

## Previous local candidate; car released, 2026-10-20

The owner asked to close DiPlay before the car left with another driver. The fork
was force-stopped for user10 and absence of its process verified. Work continues
locally; no active car capture or installation is planned while it is away.

Prepared, **not installed**: `DiPlay-Ultra-map-hvac-diagnostic-dev-20261020.apk`,
SHA-256 `afc974ec0e16d147585d401e6bd7246fbd4f3d9da6c7256e0683a3293dfbfa62`.
Full required CI plus diagnostics passed3,561 JUnit tests, no failures/errors and
one existing skip;36 capture,7 TPMS evidence and60 LeapMoto Python tests passed.
The original header resource is
verified false. This includes the legibility/dimensions/watchdog changes below and
a debug-only one-shot buffer-content classification. It does not save images or
establish that a map is physically visible. The exact known map request is now
classified as MAP_CLUSTER without changing command routing. The shell-only HVAC
diagnostic observes matching asynchronous negative errors for at most500ms after
its single setter attempt; silence never confirms success. It was not executed
on the car and does not enable Siri. Installed version remains510df783.

The owner's latest attached AMap image establishes the desired visual direction:
perspective route, vehicle position below and large next maneuver/distance above.
See [visual target and data boundaries](ULTRA_CLUSTER_MAP_GAP.md#referencia-visual-elegida-por-el-propietario).

The TPMS native consumer was traced to text already processed upstream; its local
unit-change handler affects km/mi. Global kPa/Celsius labels do not establish the
RAW conversion or prefab binding, so no normalization was added. Ambient validity
IDs remain catalog candidates without payload/enum contracts. Roof follow-up
identified separate command namespaces and potential standard HAL inventory,
but no admitted control, endpoint mapping or STOP. The module documents preserve
these findings and the minimal future OEM comparison; no vehicle actuation occurred.

## Instrument guidance physically validated; map remains pending

The owner confirmed turn instructions on the instrument display in installed510df783,
but found them small and reported no simplified 3D route map. This is the first
owner-confirmed useful cluster guidance path. It does not resolve the center black-screen
report or establish full Ultra integration.

Prepared, not installed: `DiPlay-Ultra-cluster-legibility-map-dev-20261020.apk`, SHA-256
`2a50aebfeb75c3c95d4f595385e8c87be13477c6bc3cc1ae4589859cb3676539`.
It removes a second BYD crop inside the C11 tile (default card150x58 →396x150),
gives the111 decoder its own720x720 configuration and includes the separately tested
first-Canvas watchdog. Full required CI plus diagnostics:3,540 JUnit, no failures/errors,
one existing skip; capture35Python; four native large-font/RTL screenshots inspected.

Physical history proves SETUP111 and decoder throughput often16–18fps, with no
presentation/content guarantee. [Map gap and next discrimination](ULTRA_CLUSTER_MAP_GAP.md)
records why no geographic or 3D map is claimed yet.

## Current installed correction and unresolved physical report, 2026-10-20

The owner reports an unusable session: black screen, slow transitions, closures,
fallback to ordinary CarPlay and no instrument-display content. This report takes
priority. See [diagnosis and evidence](ULTRA_BLACK_SCREEN_DIAGNOSIS.md).

The previous installed APK was verified as e75604. Its cluster route still referred
to boot2791 while the car was on2793. The physical destination was measured again
(local131/display2/mode2/1920x720/rotation0), the route renewed with backup/readback,
and overlay permission remains allowed. This corrects a demonstrated routing block;
physical map/guidance output and future-boot provisioning remain unverified.

Installed and hash-verified `DiPlay-Ultra-render-lock-original-header-dev-20261020.apk`:
`510df7839c18136d191b7ac221da62520f0492fd6d8ab993d7b11b4b8fdd6571`.
Full required CI plus diagnostics passed3,523 JUnit tests, no failures/errors,
one pre-existing skip. Three native contextual-card screenshots (compact/full/Arabic,
font1.5) were inspected; capture32Python and TPMS evidence7Python passed.
The packaged transparent-header resource is false: the original bar is retained
for diagnosis, with transparent artifacts archived separately.

Canvas publication no longer holds the host-state lock. Activation/attachment/epoch
are revalidated before establishing output readiness; borrowed surfaces still have
serialized ownership. A slow surface teardown can still wait for an in-flight Canvas.
The installed build also includes the contextual battery fallback, fan/TPMS subscriptions
and the explicit shell SET diagnostic, which has **not** been physically executed.
No HVAC/Siri control is enabled.

The first brief real connection negotiated Ultra, opened UI Sync and decoded a frame,
then ended with OWNER_CLOSED. The owner reported being far from the car, so distance
is a competing explanation and this is not assigned to a new defect. The later owner
check confirmed turn guidance, but not the map. Neither this observation nor CI proves
that black screens, closures or frame smoothness are fixed.

## Current local module work and functional reference, 2026-10-20

The owner supplied an [Aston Martin experience reference](ULTRA_ASTON_MARTIN_USER_REFERENCE.md).
Its claims are recorded as user-provided aspirations, not verified official specifications.
For the C11, preserve OEM instruments and use the verified map region as a
[contextual cockpit](ULTRA_CONTEXTUAL_COCKPIT.md): guidance, vehicle battery and other cards.
A startup maintenance sequence is requested for filters, brakes and coolant; remaining
life requires verified vehicle data or an applicable schedule and known service baseline.
Unknown history remains unknown. Bluetooth auto-launch stays disabled.

Independent HVAC, TPMS and roof reviews have completed their local increments.
Fan event1008 and twelve raw TPMS flag channels are now wired into the existing passive
subscriber, session-owned caches, bounded diagnostics and strict public capture.
The capture parser passed32 Python tests; full required Android CI plus diagnostics
passed3,508 JUnit tests, zero failures/errors and one pre-existing skip.
The new `DiPlay-Ultra-vehicle-modules-transparent-dev-20261020.apk` is archived,
SHA-256 `2bc9f9a988a991915bf6a679fbf573897be340cecc58aba9c0239958b657d235`,
**not installed**. It retains the transparent-header build option. No new physical
readings or commands are claimed. Roof position2801 remains the only proven registered
shade state; endpoint direction and control are unresolved.

The self-contained [HVAC xhigh handoff](ULTRA_HVAC_XHIGH_REVIEW.md) includes physical HAL
results, corrected SDK IDs1409/1410, access checks and assumptions to challenge.
The [independent xhigh review](ULTRA_HVAC_XHIGH_FINDINGS.md) completed locally,
with no car access or code changes. It traced OEM SDK commands inside notify15001,
identified BinderPool5 as an uncharacterized candidate and proposed three falsifiable
experiments. No new transport is proven admitted.
An invalid HAL GET has not established that SET is impossible.

## Prepared transparent-header variant, 2026-10-20

`DiPlay-Ultra-transparent-header-dev-20261020.apk`, SHA-256
`b270993cee05042a0a801c77384adf9e1aa06e78dda18105335af1b9fb93e75f`,
is built and archived, **not installed**. Its packaged header resource is true.
Full required CI plus diagnostics passed: 3,492 JUnit tests, zero failures/errors,
one pre-existing skip. Five native screenshots were inspected, covering compact,
full, measured C11, Arabic RTL and high density with large fonts. Header alpha,
opaque exclusions/bottom and unchanged video pixels are tested. This is a separate
wallpaper-backed development variant; physical OEM wallpaper remains unverified.
The last ADB check showed the car offline. See [transparent-header contract](ULTRA_TRANSPARENT_HEADER.md).

LeapMoto (pronounced LipMoto) now has a Responses API adapter and event-driven trip
reassessment, in addition to energy assessment. All 60 offline Python tests passed
and their command is included in CI. No OpenAI account, real providers, Android
voice UI, car telemetry feed or navigation receiver is connected yet. Current
scope is EV navigation; vehicle actions remain deferred. The owner supplied seven
charging-app references, recorded without assuming API access.

## Previous cockpit-ready installation, 2026-10-20

Installed `DiPlay-Ultra-cockpit-ready-dev-20261020.apk`, SHA-256
`e75604beeaa5bab97268ba2ab01ef4d8a736d5eba6fbaf3b132b995c6055c670`,
verified against the installed package. Full required CI plus diagnostics passed:
3,488 JUnit tests, zero failures/errors, one pre-existing skip; lint and debug builds.
The count includes the home module's tests. Manual launch returned OK.

The semantic card can now appear without alternate-screen map video; window
retirement clears both guidance and active-video state. For this C11 build only,
unset content defaults to MAP_WITH_CUSTOM_CARD, retaining every explicit valid
selection and other vehicle defaults. Four default/persistence regressions cover
that choice; no new Settings layout or main-screen geometry change was made.

The current boot 2791 route was measured and renewed, hash readback matched and
overlay permission is allowed. Actual next-turn presentation still requires an
owner navigation route and visual acceptance. A future car boot invalidates this
measured route until reprovisioned; automatic physical identity discovery is not
implemented. [Cockpit contract and limits](ULTRA_COCKPIT_NAVIGATION.md).

The new shell climate probe ran physically with permission, but received invalid
area 0 and timestamp 0 for both target areas. No physical target read or setter
was verified; Climate/Radio/Vehicle and Siri actuation remain incomplete. The
standard and inspected exported OEM routes do not currently provide a functional
writer for DiPlay. Details below distinguish the measured failure from permissions.

The assistant remains lower priority than functional vehicle integration; the owner
later explicitly resumed navigation-only development as LeapMoto. Its current local
progress and missing live integrations are recorded above.

## Earlier ambient/exterior installed increment, 2026-10-20

Installed `DiPlay-Ultra-exterior-listener-dev-20261020.apk`, SHA-256
`85bb3b5fae2722f6b974cbe7cedb7bcfe8bc33176b59edcd985f7a29bf5a2f69`,
verified against the installed package. Full required CI plus diagnostics passed:
3,469 JUnit tests, zero failures/errors, one pre-existing skip; 33 Python tests.
No settings, battery-card or system-bar layout changes were added.

Passive OEM observations delivered interior **25.0**, corrected exterior **14.5**,
and sunshade position **raw 0 / OEM UI 100**. OEM speech consumers treat the two
temperatures as measured degrees without conversion; Celsius, finite sentinels
and sensor validity remain unverified. These values stay raw diagnostics, without
invented Celsius/provider/Siri support. See [temperature contracts](ULTRA_AMBIENT_TEMPERATURES.md).

The normal Android exterior permission is granted. Its independent source and
1 Hz subscription connect, register and receive a callback, but validated GETs
still return OEM timestamp zero. These readings remain STALE and unpublishable.
The experiment establishes that enabling the continuous subscription alone does
not supply a valid exterior observation on this firmware.

A diagnostic batching bug suppressed later fields in an ON_CHANGE batch. Logging
now emits the latest pending state after the rate-limit window, with cancellation
on source replacement. Earlier missing-field logs are not evidence that the cache
never received those fields. Tests cover all four passive signal families.

Sunshade equipment configuration is 1 and event 2801 was physically received.
No roof movement was performed. Opening, closing, STOP, physical endpoint direction,
interlocks and a legitimate write route remain unverified or blocked. No Siri roof
capability is advertised. See [sunshade evidence](ULTRA_ROOF_SUNSHADE.md).

The owner's battery-car icon/color bands and outside-temperature/weather placement
ideas are recorded in the plan, with reserve SOC unknown. No T03 reserve threshold
is imported into the C11. This increment is telemetry groundwork; Vehicle, Climate,
Radio, full Ultra presentation and end-to-end Siri controls remain incomplete.

### Owner iPhone validation, later on 2026-10-20

The owner reported Ultra opens more responsively, but Climate, Radio and Vehicle
settings do not function; outside temperature is absent and styling is unsatisfactory.
The shared photo preview shows a broad upper black band and a very small local SOC
indicator. The card occupies existing free space outside the video; it does not
itself reserve that band. A coherent layout remains work to do.

A bounded 360-second app capture recorded one UI Sync negotiation, both VehicleState
lanes, config/register replies, a matching layout reply, first decoded video and
Canvas submission. No session-close, crash or fallback event was captured. A parallel
filtered network observation recorded no full AP shutdown or bridged idle timeout
in this interval. Saved idleShutdown was inspected as false. Network peer changes
cannot be attributed to the phone without identity evidence. These observations
are a short physical test, not proof of long-term stability or functioning Ultra apps.

The advertised capability snapshot is range=true, localSocAvailable=true,
climateZones=0, radio=false, highVoltageBattery=false. It explains why the new
functional vehicle controls are not available despite the Ultra negotiation.
A new cabin observation was 27.5 raw; outside was stale under the local silence
policy. Android exterior GETs still had timestamp zero. No writes, settings changes
or installation occurred during this session. Both captures are stopped.

### Cockpit candidate and climate access investigation

A later source increment fixes semantic-turn visibility without map video and
retires both video-active and guidance state on window replacement. C11 defaults
to the custom turn card when no valid explicit content selection exists; explicit
MAP and other vehicle defaults are preserved. See [cockpit navigation](ULTRA_COCKPIT_NAVIGATION.md).

Physical inspection found cluster enabled, content unset and the route file from
boot 2758 while the car was at boot 2791. The measured current firmware, local:131,
display 2 / mode 2 / 1920×720 / rotation 0 match the existing contract. The route
was renewed for boot 2791 with a previous-file backup and exact hash readback;
overlay permission is already allowed. A later boot still requires fresh route
provisioning because public Display APIs cannot disambiguate both equal-size panels.

The shell-only climate probe verified UID 2000 and CONTROL_CAR_CLIMATE before/after
GETs. Standard target property 358614275 advertises Float RW ON_CHANGE areas 49/68,
range 16..32, but both physical reads return area 0, value 16, timestamp 0. These
are invalid target observations; no setter was called and no climate capability
was enabled. Permission alone does not make this firmware HAL functional.

Static inspection closed three additional routes: CarShell's DumpsysProvider only
runs predefined diagnostic dumps, strcontrol only dispatches STR event 7009, and
CustomSceneService requires calling package com.leapmotor.scene. No reachable
physical HVAC writer for shell/DiPlay was established. Climate needs an actually
authorized OEM broker or a functional HAL bridge, not additional generic probes.

## Earlier telemetry and idle-band increment, 2026-10-20

Installed APK: `DiPlay-Ultra-telemetry-idle-band-dev-20261020.apk`, SHA-256
`5bbda42e874430245a3617bc6e41b6449f07bc069d2b32fa966fdde9ee83bb61`,
verified against the installed package. Full required CI passed: 3,439 registered
JUnit tests, zero failures/errors, one pre-existing skip; lint and debug builds.
All 26 Python tool/capture tests passed. No battery-card or system-bar changes
were added in this increment; the owner deferred further styling.

Six physical SoftApManager observations identify the periodic hotspot failure:
`Instance idle timout` precedes AP-DISABLED and then hostapd/interface failure.
The general timeout is false, but the independent bridged-instance opportunistic
timeout was true. The manual shell helper `HotspotIdleBandRepairMain` changes only
that boolean through an opaque platform config copy, checks round-trip equality,
refuses non-restorable configs or a detected concurrent change and verifies readback. It
does not extract credentials, store config parcels, or start with Bluetooth.
The helper returned APPLIED; the active manager and stored flag now report false.
A 390-second physical observation completed without AP-DISABLED, hostapd death or
the idle-timeout failure recurring. No iPhone session was established during this
window, so full CarPlay stability remains unverified. The active flag remained
false afterward. Manual rollback uses `restore-idle-shutdown`.

The existing passive SomeIP subscriber now includes verified target-temperature
and direct-TPMS event IDs. Physical capture received a right-side HVAC target of
20.0 Celsius; the initial log showed the left side unavailable (the batching issue
above prevents treating that log as proof of missing delivery). This is a read-only observation, not
a Siri command confirmation. The device property `persist.sys.lp.config.TPMS` is
0, matching the OEM direct-TPMS branch; no wheel values have yet been captured.
Pressure/temperature remain explicitly RAW with units unverified, separate field
expiry, source epochs and no iPhone capability announcement or UI controls.
See [wheel telemetry](ULTRA_WHEELS_TELEMETRY.md).

The owner's battery query to Siri returned an error but an autonomy estimate.
Existing range transport can explain that response; it does not verify the
missing modern high-voltage battery schema. Climate currently has only a future
authorized temperature path, not power/compressor controls; the real permission
and OEM write route remain absent. Vehicle, Climate and Radio apps are not
functional. Radio still lacks a production C11 backend.

Official [0.2.21](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.21)
was downloaded and its published checksum verified, without installation. An
isolated comparison found 81 commits and 16 overlapping modified paths. Selective
stability imports are recommended; the release does not supply the missing C11
Ultra integration or repair this bridged idle timer. The review and physical
evidence are stored privately under the development reports for 2026-10-20.

## Earlier overnight local increment, 2026-10-20 (historical)

The owner confirmed that the visible 12% card matches the car's traction battery.
Clima, Radio and an unidentified third app did not open. That is not a successful
full Ultra interface. Later local changes have not been installed in the car.
The car was released before the overnight development and has not been accessed
since. Bluetooth auto-launch remains off and the Master system-bar style remains.

Physical logs show several periodic cuts with hostapd AP-DISABLED on wlan3 and
wlan2 before the AirPlay socket failures, followed by AP recovery. For example,
20:31:14.771 AP-DISABLED precedes 20:31:15.328 control failure. The initiating
component is unresolved. Observed mTimeoutEnabled=false does not support blaming
the framework idle timeout. The app process remained the same in these captures;
this evidence establishes connection loss, not a Java/native app fatal.

Implemented and locally verified in the new batch:

- A retired screen callback cannot close its replacement. Audio feedback and
  metadata retirement are scoped to the AirPlay session. Stale host-UI/command
  callbacks cannot open HOME over a replacement session.
- Teardown records its first fixed source before sockets are closed, separating
  the initiating channel from later cleanup errors. Control stream acquisition
  failures now also enter cleanup. UI requests export only a fixed target enum;
  arbitrary URLs are not recorded. P observation transitions now reach Logcat:
  their previous absence there was a logging gap, not proof that P was unavailable.
- The local battery card has a translated heading, larger SOC and proportional
  cyan fill. No charging/alarm state is inferred. Six native-graphics screenshots
  cover compact/full, RTL, high density and measured C11 geometry at 100%/12%,
  font scale 1.5. They enforce complete text and unchanged CarPlay picture pixels.
- The navigation overlay fences each window and Surface, detaches the decoder
  before removing the window and cleans partially failed openings. This does not
  claim to fix the separate main-screen focus issue after OEM camera transitions.
- An explicit CarProperty climate factory requires legitimate permission,
  opt-in, firmware, physical seat mapping and verified increments. Revocation
  removes capabilities and retires manager ownership. No real broker is registered.
- The verified DAB radio protocol shares one Vehicle State owner with range and
  climate, including mixed registration, updates and confirmed controls. Twenty
  radio tests include two encrypted simulated-receiver paths. No production
  Engine radio backend or C11 tuner is connected; FM is not relabelled as DAB.

The full CI command passed: 3,416 JUnit tests registered, zero failures/errors,
one pre-existing skip, lint and debug builds. All 24 Python capture/tool tests
passed. The APK's 3,073 assets and stripped native library match the staged inputs.
The exact APK also passed the isolated Android arm64 packaged-resource/decoder/
composition/adoption smoke, with simulated peer/data and generated video. No
physical presentation or iPhone function is claimed; the emulator was closed.
Candidate: `DiPlay-Ultra-overnight-dev-20261020.apk`, SHA-256
`54ce6128ecd0a9a9430dc28cdff3f9d609795e86c63f2f7467ce5947d5de50e0`.
Evidence is in `.private/c11/ultra-20261020/overnight-integration/` and
`overnight-integration-verification.json`. The closing verification ran after the
requested 08:00 local deadline. No later candidate was installed in the car.

Real Siri climate operation, the Radio app, independent passenger playback,
physical P freshness and the full Ultra interface remain unverified. The cause
of hotspot shutdown still needs identification. See [radio](ULTRA_RADIO_INTEGRATION.md)
and [multiple displays](ULTRA_MULTIDISPLAY_NEXT.md) for the implemented boundaries.

## Earlier parallel integration, 2026-10-19 (historical)

The existing C11 SOC/range cache now feeds both the local CP
`highVoltageBattery` script contract and an Android `UltraBatteryCard` attached
to `CarPlayHostActivity`. The card displays cached SOC, treats 0% as valid,
and hides on missing/stale data. It occupies only verified spare space outside
the CarPlay picture, excludes system bars and controls, and hides when there is
no room or the visible Ultra host is not ready. It does not resize the picture,
alter the 193 resource scripts, or create an Apple widget zone. The selected
Wallpaper still has no widget zones. Physical presentation of this new card has
not been tested; the previous disconnected raster-label fixture remains separate.
The CP identifiers are not a verified Apple wire schema for Siri battery queries.

An explicit climate broker registry supplies independently owned session
backends to `/info`, SETUP and Vehicle State. Peer opt-out, session closure and
broker revocation release access. Later `/info` cannot reacquire after opt-out;
a new connection may select the capability again. Revoked leases block cached
reads and late success independently of whether the broker closes its backend.
The registry is empty by default: neither the climate privilege nor the real
C11 temperature increment has been established. No real climate capability is
enabled merely by this integration.

Passenger video has an explicit per-session Android destination with firmware/
boot-bound physical identity proof. Real AirPlay session boundaries replace
routing, parked-source, item and request ownership even when the controller is
reused. Old callbacks, resource replies, selections and P reads cannot restore
availability in the replacement session. Lost destinations and wrong-display
activities cannot redirect playback to the main display. No passenger route has
been provisioned in the car and C11 video remains disabled; independent iPhone
passenger UI, DRM compatibility and physical playback remain unverified.

The [read-only P source](C11_PARKED_SOURCE.md) now has a diagnostic observation
lease tied to the actual AirPlay session in the visible, manually enabled Ultra
host. Pause, end, replacement and destruction close it. It uses optional platform
`android.car` and the normal `CAR_POWERTRAIN` permission from this firmware.
The integration explicitly uses `CURRENT_HAL_SNAPSHOT`: a successful synchronous
HAL get has a local delivery time/TTL separate from the unchanged OEM ON_CHANGE
timestamp. Zero/future timestamps, failed/slow reads, stalled delivery and loss
of access leave P unknown. The Activity logs only allowlisted state and ages;
the capture tool rejects appended payloads. This is observation only: it does
not feed video availability, and actual C11 HAL behavior still needs validation.

The combined increment passed the full Android CI command: 3,370 JUnit tests
registered, zero failures/errors and one pre-existing skip, plus lint and debug
builds. All 20 Python capture/tool tests passed. Five native Robolectric
screenshots cover compact/full windows, large fonts, Arabic RTL, high density
and the measured C11 mdpi geometry. They verify complete text and unchanged
CarPlay picture pixels; the vehicle values and picture in these tests are simulated.
Four screenshots, including C11, compact, RTL and high density, were also visually
reviewed. See the [widget layout evidence](LEAPMOTOR_C11_ULTRA_WIDGET_LAYOUTS.md).

Candidate: `DiPlay-Ultra-battery-parked-dev-20261019.apk`, SHA-256
`67eb0baeef8f5c69733126f513526a462cec1602b4c852db4051c43e96f957f5`.
Its 3,073 staged assets and packaged stripped native library were verified.
The native runtime/resources are unchanged from the previous emulator-tested
candidate; this increment was verified with unit/native-graphics tests, not a
new emulator smoke. Evidence is under
`.private/c11/ultra-20261019/battery-parked-integration/` and
`battery-parked-integration-verification.json`. No car access or installation
took place. Siri climate control, independent passenger playback and the full
Ultra interface remain unverified on the car/iPhone.

### Previously verified local candidate (historical)

Joint verification passed: 3,336 JUnit tests registered, zero failures/errors and
one pre-existing skip; 62 Python tests passed. The final arm64 APK executed the
packaged-resource/decoder/composition/host-adoption smoke in an isolated Android
emulator, including battery-source loss and retirement. Its 3,073 staged assets
and merged/stripped native library were verified against the candidate.

Six separate battery-label scenes were rendered by that APK: invalid, 75%, 0%,
100%, lost and restored. Complete glyph bounds have at least four pixels of
margin; missing data leaves no visible label and restoration reproduces 75%.
The first fixture clipped the percent sign; its frame/scale were corrected and
regression coverage now rejects that clipping. This label remains a disconnected
fixture, not an activated layout or an iPhone widget.

Candidate: `DiPlay-Ultra-parallel-integration-dev-20261019.apk`, SHA-256
`e81dec3fd89d42c824c4c127eafd6b78faaef18b9b89e84b31f2717e9c9c1d6c`.
Local evidence is retained under `.private/c11/ultra-20261019/parallel-integration/`
and `parallel-integration-verification.json`. The emulator was closed afterward.
There has been no new car access, install, iPhone test or SystemUpdate action.

## Historical dependency review, 2026-10-18 (priority superseded above)

### Central negotiation and session lifetime, 2026-10-19

An explicit center-only resource binding now generates one video stream/panel
with matching HID controls, no instrument capability and no inherited cluster.
This is validated against the selected package metadata and remains an unverified
phone topology. Existing C11 settings do not select it automatically. New bounded
diagnostics distinguish local declarations, UI Sync negotiation and sent/received
layout kinds; incoming UUIDs can be correlated with the last request without
exporting identifiers. None of these observations declares resource acceptance.
See [the resource negotiation evidence](ULTRA_RESOURCE_NEGOTIATION.md).

A fresh generic Apple catalog query still lists resource 985. Metadata inspection
of the Single variant also found both center and cluster; no resource was changed.
The actual iPhone acceptance/provision contract remains unverified.

The default JNI/CLI research lifetime retains its 4,096-request cap. An explicit
session-owned JNI entry removes only that cumulative counter while preserving
memory/operation/message and four-VM limits. Tests verify 8,192 requests, default
research retirement, the operation CPU deadline and a mixed-policy VM ceiling.
Android arm64 executed more than 5,000 operations before original Wallpaper
negotiation and generated 1920×720 AVC composition/pixel checks, then retired its
pipeline. This does not establish hours of endurance or physical presentation.
Full Android CI passed; no car access or installation took place.

The goal is working CarPlay Ultra in DiPlay/C11 with a real iPhone, synchronized
phone video/local UI, and verified vehicle data as that scope is added. The first
milestone is **one real Ultra scene visible and reproducible after reconnect**.
It does not require every gauge or every display to be completed first.

The dependency inventory is maintained in
[`tools/carplay/ultra_plan.json`](../tools/carplay/ultra_plan.json). States distinguish
real car/phone evidence, isolated research evidence, partial implementations,
pending integration, unresolved contracts and work deferred until the first scene.
No completion percentage is assigned: the size and feasibility of the unresolved
resource/display contracts are not known well enough to justify one.

| Branch | Established | Still required for the first scene |
| --- | --- | --- |
| Enlace/negotiation | Real CarPlay and UI Sync reset/reset-complete | A real layout request/response driven by an integrated renderer |
| Resources/layout | Inspected source/image package and matching resource IDs | Acceptance/provision contract, valid display geometry and a minimal viable layout |
| Scripts in the app | Persistent native host, JNI/Kotlin protocol integration, debug packaging and isolated Android ART execution | Real app/session lifecycle, necessary CP subset and capabilities for a selected layout |
| Synchronized composition | Latched decoder buffers, source-clock frame transactions and video/local-layer composition tested with generated AVC | Real app/session lifecycle, live phone timing, sustained performance and physical presentation |
| First real scene | No successful real Ultra presentation yet | End-to-end evidence, reconnect and recovery to the normal screen behavior |
| Vehicle data/additional displays | Prior SOC/range reads and navigation work | Deferred until the first scene; then validate each signal/widget and each display role |

The selected minimal-layout capability matrix and integration contract is in
[`ULTRA_MINIMUM_CENTER_CONTRACT.md`](ULTRA_MINIMUM_CENTER_CONTRACT.md), covering
the reference Wallpaper center output, required graphics/video operations,
mandatory services/states, resource acceptance evidence, Android host mechanism
and single owner of UI Sync sequencing. It establishes a disconnected candidate,
not a live-installable layout. A resource-delivery implementation is
conditional on the real session needing it; a download prompt is not itself a
required success criterion. Cache/provisioning must be distinguished from transfer.

The real reset-only Kotlin channel handler now has an explicit development
factory for an exclusive script protocol owner. The original-script runtime has
an in-process JNI/Kotlin adapter and optional debug native packaging. The Android
offscreen compositor and decoded-frame coordinator now have a shared optional
panel owner with the script protocol. The actual app/session/surface lifecycle
remains unconnected. Joining it into a visual session is part of the remaining work. The
normal app does not install the incomplete script owner.

### Combined protocol/composition owner, 2026-10-19

`UltraComposedPanelSession` serializes message delivery, script ticks and outbox
draining with frame composition. Router closure retires retained pixels and the
VM together, waiting for active composition. Late frames are closed; a protocol
failure also closes composition. A new connection must construct a new owner,
without inherited source-time/token state. The decoded debug pipeline uses this
owner; no production registration or car installation was performed.

Actual JNI fixtures join a routed timestamped opacity command, its receipt, the
offered video source time and the resulting scene token/opacity. Polling without
video does not apply the future change. Further cases cover a disconnect during
drawing, a protocol failure and replacement with a restarted clock. These commands
are a test fixture, not an original OEM transition or a real iPhone session.
### Optional decoder and connection lifecycle, 2026-10-19

The engine now has an explicit, default-absent renderer factory keyed by AirPlay
control-session identity. Main screen data and UI Sync resolve the same owner;
session closure and UI Sync disablement retire it. Alternate screen and audio
keep their existing routes. Closed sessions cannot keep a newly created owner.

`UltraVideoPipeline` combines the real Android decoder, GL input, bounded frame
worker and combined panel. It stops publication before closing, retains the input
surface until actual codec release, drains composition and then retires VM/GL.
GL errors notify this owner without destroying a destination still used by a
codec. `UltraSurfaceOutput` supports explicit host-surface replacement and ignores
late destroy callbacks, with exact selected-layout dimensions and no ownership of
framework surfaces. The decoded Android debug pipeline uses this lifecycle.

The Activity still does not install the renderer factory, choose resources/layout,
bind its view callbacks or implement experimental failure fallback. There is no
live Ultra activation. Same-control-session replacement of a closed experimental
UI Sync pipeline requires a reconnect rather than silently reusing native state.

### JNI and protocol integration

The selected reference Wallpaper now has a reproducible host contract/protocol
probe and a debug Android original-controller composition check. Under an
explicitly simulated peer and UI lifecycle, the original controller requested
that layout, accepted a completed transition containing its layout ID, and made
its phone video visible. Real generated AVC at 1920×720 passed the Android
compositor's four-quadrant pixel checks and full pipeline retirement. Hashes of
all 193 original source modules on Android matched the prepared manifest.
The host suite passed 27 tests and full Android CI passed. This is offscreen
original-controller evidence, not iPhone acceptance or a live Ultra scene.

The shared host implementation builds as a desktop JNI library and an Android
arm64 shared library. Kotlin tests execute the actual desktop library: state
persists across routed messages, the original scripts complete reset and emit a
layout request under an explicitly simulated lifecycle, failed VMs are retired,
and opaque handles cannot alias replacement sessions. Separate encrypted
loopback tests verify exclusive ownership and retirement on stream replacement.
These tests connect to neither an iPhone nor a vehicle.

`-PultraNativeDirectory=/absolute/path/to/native` adds locally built native
libraries to debug packaging only. The switch does not include Apple resources
or register the script owner at app startup. A fresh Android 35 emulator loaded
the debug entry point from that APK and its packaged JNI library via `app_process`;
the original scripts completed reset, requested a layout and exported a 6,573-byte
research scene. The peer/lifecycle were simulated, the APK was not installed and
no physical display was opened. The existing emulator and car were not targeted.
The real app's resource/session lifecycle and live video/composition remain
pending. The native embedding
retains the research limits (four VMs, 4,096 requests per VM, bounded memory and
per-operation CPU deadline); its polling cadence is not a synchronized video
clock. Build/test instructions are in [the runtime notes](../tools/carplay/runtime/README.md).

The integrated debug path also passed the exported scene directly to the Android
compositor under ART and wrote a 1920x720 PNG. That image is black: the original
scene has no bound phone video and no visible non-video content in this simulated
state. This establishes plumbing through the renderer, not a visible Ultra
layout or synchronized presentation. The isolated emulator was closed after the
test; no app was installed or vehicle connection used.

### Decoded-video integration, 2026-10-19

An optional SurfaceTexture destination now associates actual latched buffers with
the exact iPhone-origin timestamp registered by the decoder. Copied SDR frame
leases and a bounded latest-frame worker feed a serialized JNI transaction:
offer, tick, snapshot, render and commit. Deferred/stale frames cannot replace the
current frame; flush and rendering failures retire the affected state. With a
configured input, the script frame clock advances from offered video rather than
from protocol polling. The original center program requires the input name
`iPhoneVideo`.

The isolated Android 35 emulator performed real AVC encoding/decoding of generated
quadrants, matched the latched origin, verified orientation/colors and combined
video with a local yellow layer through the native frame transaction. Three
subsequent complete process cycles passed after synchronized GL shutdown was
added. This verifies an integration path with generated video, not a live Ultra
layout or physical presentation. See [ADR-003](adr/003-ultra-video-frame-ownership.md).

A separate disconnected probe ran the unmodified original center controller,
completed reset and requested its default Wallpaper layout. Future metadata was
held until the offered frame window reached it. The program ignored the region
for an unchanged Wallpaper layout because no next layout was staged; this does
not demonstrate a visible OEM layout transition.

App/session lifecycle, resource acceptance, supported graphics for a selected live
layout, performance and JNI lifetime budgets remain activation gates. The JNI
4,096-request research budget is not suitable for a sustained live session. Normal
startup still does not install the experimental renderer.

### Owner's connection preferences and reported disconnects

The owner requested no automatic launch when the iPhone connects by Bluetooth.
The next mobile debug beta resets the old Bluetooth-launch opt-in once, before
either the receiver or Settings reads it. Ordinary builds preserve their existing
choice. Manual opening/connection remains available; a later explicit opt-in is
not overwritten repeatedly. The car's installed preference has not been changed.

The owner also reported intermittent disconnections. No new car/iPhone logs were
captured because car access remains paused. There is no evidence yet establishing
their cause, and the Bluetooth preference change is not claimed to fix them.

Minimal UI lifecycle/capability metadata may be required to construct a layout;
that dependency does not authorize inventing speed, battery or control values.
The current reference cluster fails on unavailable cruise-control controllers,
so its feasibility must be resolved before treating it as the first layout.
The center program starting with 172 modules and a compatibility override is
research evidence, not complete CP 1.4.0 compatibility or proof of a C11 theme.

Protocol receipt and visual presentation need separate evidence. In the inspected
original ClusterControlSession, command acknowledgment is queued before the data
handler; LayoutController subsequently applies transitions according to video PTS.
An ACK therefore must not be used as proof that drawing finished. The existing
Kotlin probe remains conservative and reset-only. A future integrated session
must preserve the original transport semantics while exposing separate applied-
state/frame/presentation observations, and must not claim rendering it cannot do.

The ordinary main-branch display style remains a constraint. Do not re-enable the
reference 1920x720 geometry over the driver's normal 2560x1176 session merely to
repeat the already-proven reset exchange. Car access remains paused until the
owner indicates availability; resource/layout analysis and local integration
work can proceed without it.

**Research update, later on 2026-10-17:** the earlier feasibility review below is
superseded in part. LIVI now publishes executable vehicle-state and UI Sync
implementations, and Apple's asset catalog supplies an inspectable accessory
package including Lua sources. There is a concrete route to a prototype without
first obtaining the complete MFi specification. This does not establish that
the current DiPlay receiver implements Ultra or that these resources fit the C11.
See [the updated findings](#reassessment-with-new-public-code-and-apple-resources).

## Current evidence

The current private car-session capture contains repeated decoded iAP2 CarPlay
availability notifications with `wired=true wireless=true themeAssets=true`, both
before and after the wireless handoff. This is the phone's advertised availability
metadata, not proof of a working Ultra session. Its exact relationship to Ultra
has not been established on this phone.

The same session's AirPlay `/info` requests list the three conventional navigation
alternate-screen URLs: map, instruction card, and combined instrument-cluster
navigation. The receiver advertises its primary screen and, when configured, its
conventional alternate navigation screen. No independently rendered passenger
Ultra interface is observed.

The raw capture remains private because it contains phone and location data.

## Code paths that explain the gap

| Layer | Present | Missing or unverified |
| --- | --- | --- |
| Availability parsing | `Iap2CarPlayMessages.availability()` decodes the optional theme-assets group; `carPlayAvailabilityDiagnostic()` logs it | No theme-assets negotiation follows from the availability flag |
| iAP2 session start | Inspected reference asset descriptor supplied during identification/session start; bound-panel build installed | No C11-specific resource package or local renderer is validated |
| AirPlay display declaration | Primary type 110 and optional alternate type 111; handshake probe adds diagnostic panel/plugin declarations | No verified Ultra screen model, passenger-role negotiation or two independent Ultra layouts |
| Vehicle data | Verified SOC/range read from C11 services and conventional iAP2 EV status reporting; modern range prototype retained internally and excluded from the handshake probe | Real-iPhone acceptance of the modern range service is pending; battery schema and widget binding remain unresolved; speed is still only a static candidate |
| Local UI and composition | Android hosts CarPlay video and a bounded navigation-tile overlay; a UI Sync reset probe is implemented | Phone opened UI Sync and sent reset/reset-complete; no verified Ultra renderer/compositor or C11-specific assets |

The optional asset ID/version codec is not an asset package, asset-transfer
implementation or proof of compatibility. Supplying an invented identifier or
adding another Android Surface would not complete these missing layers.

## Next investigation gates

1. Establish the actual theme/vehicle asset exchange and compatibility requirements
   from a reproducible reference or the applicable vehicle-integration specification.
   Determine what resources the iPhone expects and how a receiver supplies them.
2. Establish the Ultra display/state/composition contracts. Keep the full center,
   full passenger and bounded instrument-cluster navigation tile as distinct roles.
3. Implement a controlled opt-in prototype only when those contracts are concrete;
   retain the working conventional CarPlay path as the recoverable baseline.
4. Verify two independently rendered Ultra screens before claiming multi-screen
   support; verify actual widget values before claiming Ultra vehicle integration.

Apple's [next-generation CarPlay architecture presentation](https://developer.apple.com/videos/play/wwdc2024/10111/)
describes remote/local rendering, vehicle resources and synchronization. The
[vehicle-integrator CarPlay page](https://developer.apple.com/carplay/) directs
integration work to MFi. These are architecture references, not a complete
implementation of the required contracts in this repository.

The steering-wheel assistant menu, left-side CarPlay
dock, hotspot recovery and Leapmotor branding are receiver improvements. Their
validation must not be counted as Ultra negotiation or rendering validation.

## Earlier full-implementation feasibility review

The owner requested full Ultra implementation and confirmed that no MFi Ultra
documentation, SDK or authorized vehicle asset package is available locally.
A search of the available Mac application/developer/download locations did not
find CarPlay Simulator, CarPlaySDK or a vehicle asset package.

The public reference projects reviewed describe useful reverse-engineered
structures, but do not supply a complete working Ultra receiver:

- [carplayd's simulator analysis](https://github.com/lvalen91/carplayd/blob/main/docs/APPLE_BEHAVIORAL_REFERENCE.md)
  distinguishes the modern VDC/CARP characteristic plane from conventional iAP2
  vehicle status. Recovered symbols/schema names do not define all wire messages,
  sequencing or device acceptance requirements.
- [carlink_linux's capability reference](https://github.com/lvalen91/carlink_linux/blob/main/docs/CARPLAY_CAPABILITIES.md)
  identifies candidate `uiSync`, `vehicleStateProtocol` and file-transfer feature
  tokens, with evidence/confidence tags and unresolved items. A token declaration
  obligates an implementation; it is not itself that implementation.
- [MHI2 research closeout](https://github.com/harman-f/mhi2_altscreen_carplay/blob/main/docs/research/CARPLAY_CLUSTER_ULTRA_CLOSEOUT_2026-10-03.md)
  validates conclusions about conventional alternate-screen navigation and defers
  its local compositor work. It does not demonstrate an Ultra implementation.

Apple's architecture presentation establishes that the local renderer consumes
vehicle-specific image/script packages transferred from the phone, verifies their
authenticity, and synchronizes local/remote content using a dedicated per-display
UI Sync channel. It directs access to technical documentation to MFi. This explains
the required components but does not specify their complete wire contracts.

The remaining blocking inputs are a sufficient asset-transfer/authentication
contract with compatible resources, UI Sync framing/timing semantics, and the
modern vehicle-state protocol contract. A reproducible compatible reference
session or the applicable integration documentation could establish them. The
current observations do not justify fabricating asset identities, capability
claims, packet layouts, or an "Enable Ultra" setting.

No Ultra protocol behavior, firmware changes or auxiliary car applications were
installed as part of that feasibility review. Full Ultra implementation remains
unfulfilled; that review was not an implementation milestone.

## Reassessment with new public code and Apple resources

The earlier conclusion correctly describes DiPlay's observed conventional
session. It does not establish that the missing protocols cannot be recovered.
In particular, the assessment of public resources was incomplete: the sources
below provide message implementations and real assets, beyond recovered names.

### LIVI: reproducible implementation reference

The inspected revision is
[`4bcd1335c2daedba40640e6dc432cd2ed7acfaa4`](https://github.com/f-io/LIVI/commit/4bcd1335c2daedba40640e6dc432cd2ed7acfaa4),
committed on 2026-10-17. It introduces manufacturer themes and dashboard layouts.
This research inspected source; it did not run LIVI or independently reproduce
its iPhone results.

| Component | Concrete public implementation | Remaining C11 work |
| --- | --- | --- |
| Modern vehicle state | [`vehicle_state.rs`](https://github.com/f-io/LIVI/blob/4bcd1335c2daedba40640e6dc432cd2ed7acfaa4/native/livi-helperd/crates/livi-cp/src/vehicle_state.rs) describes services/characteristics, handles configuration, subscriptions, reads/writes and notifications using OPACK, with separate high/normal channels and unit fixtures | Port a minimal read-only service using verified C11 SOC/range; omit unavailable speed and every unsupported action |
| Layout synchronization | [`ui_sync.rs`](https://github.com/f-io/LIVI/blob/4bcd1335c2daedba40640e6dc432cd2ed7acfaa4/native/livi-helperd/crates/livi-cp/src/ui_sync.rs) implements reset/completion, sequence state, acknowledgments, layout selection, palette, appearance and focus messages | Validate ordering and recovery with our iPhone; integrate an actual local compositor before claiming synchronized rendering |
| Panel negotiation | [`info.rs`](https://github.com/f-io/LIVI/blob/4bcd1335c2daedba40640e6dc432cd2ed7acfaa4/native/livi-helperd/crates/livi-cp/src/info.rs) supplies `displayPanels` and `displayPlugins` | Separate physical destinations, panel identities and video streams; its extra virtual panel is not an independent passenger interface |
| Channel routing | [`stack.rs`](https://github.com/f-io/LIVI/blob/4bcd1335c2daedba40640e6dc432cd2ed7acfaa4/native/livi-helperd/crates/livi-cp/src/stack.rs) accepts proposed features and opens distinct encrypted type-130 data channels after pair verification | Route, replace and close channels by identity/stream ID; preserve iAP and the conventional navigation path |
| Theme discovery | [`theme_catalog.rs`](https://github.com/f-io/LIVI/blob/4bcd1335c2daedba40640e6dc432cd2ed7acfaa4/native/livi-helperd/bin/livi-core/src/theme_catalog.rs) queries Apple's catalog and reads package geometry, layouts and palettes | Establish which resources our phone accepts and a suitable C11 resource model |

The upstream [climate screenshot](https://github.com/f-io/LIVI/blob/4bcd1335c2daedba40640e6dc432cd2ed7acfaa4/docs/media/climate/climate_01.png)
shows iPhone-rendered climate controls. It is evidence supplied by LIVI's author,
not validation on our hardware. LIVI's dashboard draws its own gauges; these
files do not demonstrate a complete Apple script renderer on Android.

The channel implementations derive keys from the pair-verify shared secret,
which DiPlay already has. LIVI also implements a separate MFi4 authentication
flow. Whether our iPhone requires that flow for the intended configuration
must be established from a capture; neither universal BAA compatibility nor
universal MFi4 necessity has been demonstrated here.

### Apple asset package inspected directly

Using the catalog request published in LIVI, a read-only request to Apple's
`https://gdmf.apple.com/v2/assets` for `com.apple.MobileAsset.CarDDPAssets`
returned four entries. The downloaded
[Apple package](https://updates.cdn-apple.com/MobileAssets2026/mobileassets/140-40687/F88F06C5-4B15-445E-B43D-36412420D116/com_apple_MobileAsset_CarDDPAssets/4b4e7d405c673385c6360679fc05b4e4179fb5ac.zip)
has content ID `44C9FA31EC`, version 985, minimum SDK 19.0 and a declared size
of 50,870,896 bytes, matching the download. Its SHA-256 is
`872c2a33ea16b481da6515f5c19c5ea736a23ed4ae5be97d6994ac2649cdb385`.

The outer archive contains phone-side package/theme JSON, an accessory archive,
a detached signature and a certificate chain. The accessory archive contains
**193 Lua source files, 193 compiled Lua files, one plist and 2,875 PNGs**.
It includes layout coordination, vehicle-data subscriptions, speed/charge/range
definitions and Lua wrappers for layers, fonts, video and cluster control.
No downloaded script or binary was executed or installed.

The detached SHA-256 signature verifies against the included leaf public key
with `openssl dgst`. Verification of the supplied chain against Apple's Root
CA G3 fails at the current time because the signing leaf expired on 2026-09-22.
The same chain verifies at the explicit reference date 2026-09-01.
This is an inspectable research resource, not proof of current-time acceptance
by an iPhone. The catalog JWS and the receiver's asset acceptance rules were
not independently verified.

These are manufacturer resources, not a Leapmotor asset package. The inspected
phone manifest contains a center-console role and a cluster role; it does not
establish a passenger role. The Lua code references a native `CP` bridge,
including layer creation, timing and output streams. A static scan finds 52
distinct `CP.*` identifiers; the archive is not a standalone Android renderer.

### Concrete reverse-engineering and implementation sequence

1. **Capture feature negotiation.** Record the features proposed by the iPhone
   and accepted by DiPlay while selecting Ultra. Compare authentication,
   `/info`, SETUP and channel creation against the conventional session.
2. **Port the smallest modern vehicle-state slice.** Implement bounded OPACK
   parsing, independent encrypted channels and the reference configuration /
   subscription exchange with fixtures. Populate only verified battery/range
   values. Keep the prototype opt-in and do not expose HVAC writes or substitute
   a speed value.
3. **Validate against the actual iPhone.** Confirm it requests configuration,
   subscribes and renders an actual value. Test channel replacement, teardown
   and reconnection without colliding with the existing type-130 iAP tunnel.
4. **Recover the renderer boundary.** Map the accessory Lua wrappers to the
   native `CP` API and identify the required subset of layers, time sources,
   vehicle-data callbacks and cluster-control operations. Apple's Simulator /
   CarPlaySupport binaries can help recover behavior missing from the scripts;
   the [published iOS extraction notes](https://github.com/harman-f/mhi2_altscreen_carplay/blob/main/docs/research/IOS27_SENDER_LIFECYCLE.md)
   provide reproducible binary provenance. No such binary was newly acquired
   in this iteration.
5. **Validate panel composition and UI Sync separately.** First reproduce one
   layout transition and fallback in a desktop harness. A passenger interface
   still requires its own demonstrated negotiation and input routing; reusing
   the cluster stream does not meet the two-independent-screen objective.

The state/channel prototype is now a justified next implementation step.
Full Ultra, custom C11 assets, local rendering and the passenger interface
remain unimplemented; they are no longer grounds for declaring the entire
research path exhausted.

There is also a specific Simulator extraction route. The pinned
[carplayd extraction report](https://github.com/lvalen91/carplayd/blob/4eb254499aa00f456aea91d00755cf150c7d18a4/docs/23_APPLE_AUTHORITATIVE_SPEC.md)
identifies `iap2messages-internal.i2mspecarchive` and
`mfi4authmessages-internal.i2mspecarchive` in the CarPlay Simulator's frameworks.
They are keyed archives whose object references can be resolved to recover
message types, groups and constraints. Its
[vehicle-data analysis](https://github.com/lvalen91/carplayd/blob/4eb254499aa00f456aea91d00755cf150c7d18a4/docs/APPLE_BEHAVIORAL_REFERENCE.md#4-vehicle-data-model)
also identifies `VDCSchema-External.json` and vehicle configuration resources.
Those are useful targets for a local extractor and cross-reference analysis,
complementing LIVI's message implementation. The carplayd snapshot is unchanged
from the earlier review; it is not a second independent live Ultra capture.

## Diagnostic change from this investigation

`AirPlayFeatureDiagnostics` now records recognized proposed features separately
from enabled features at session SETUP. Unknown values become counts, and no
arbitrary peer string enters this summary. It distinguishes an absent feature
list from a malformed container or an empty list. This observes `uiSync`,
`vehicleStateProtocol` and `fileTransfer` without enabling them.

Five codec/diagnostic tests and a saved-report test verify this behavior and
preserve the existing payload filters. The complete AGENTS.md check command,
plus the workflow's diagnostics-module targets, passed locally in 2 min 45 s:
3,045 tests, zero failures/errors and one skipped. Public-tree and whitespace
checks also passed. This build has not been installed on the car.

The stored ADB endpoint was unreachable during this iteration. A new live
Ultra-selection capture is pending the current car address/access; no conclusion
about the current iPhone's feature proposal is inferred from that failure.

## Offline protocol prototype, resumed session on 2026-10-17

`Opack` implements bounded decoding and encoding, including scalar references,
signed integers, binary data and open containers. It rejects trailing records,
duplicate dictionary keys, invalid UTF-8, truncation and excessive byte, nesting,
value or reference counts. The reference register-response fixture comes from
the pinned LIVI source above.

`VehicleStatePrototype` implements the nested plugin envelope, configuration,
registration and changed-value notifications. It uses `VehicleStatusProvider`
and describes only the reference trip-computer remaining range, in kilometres
and rounded miles. Writes and controls receive explicit errors. Replies retain
the request lane; updates use the high-priority lane. Source loss clears the
subscriptions and returns `SourceLost`, requiring a future channel owner to
close both channels rather than retain a stale reading. Wire invalidation
semantics have not yet been verified.

The pinned LIVI implementation does **not** describe a high-voltage-battery SOC
service. Apple's Lua names that service, but the characteristic wire identifiers
and constraints have not been recovered here. Consequently SOC is intentionally
absent from this prototype despite being available from C11 telemetry.

This harness is internal and is not connected to `/info`, feature selection,
SETUP or sockets. It cannot enable Ultra. Encrypted stream ownership, replacement,
teardown, real-iPhone validation, battery schema, asset handling, local rendering,
UI Sync and independent passenger composition remain pending. No APK or asset
has been installed during this resumed work.

## Duplex channel implementation and independent Leapmotor audit

`VehicleStateChannel` adds an isolated duplex TCP transport following the pinned
LIVI `iap_tunnel.rs`. Each connection derives its two directional keys from the
pair-verify secret and SETUP seed. Authenticated frames contain bounded 32-byte
APTransportPackage headers and OPACK bodies. Synchronous packages receive an
empty reply with the same token before delivery; outgoing protocol messages use
asynchronous command packages. Reply packages are not passed to the protocol.

`VehicleStateChannels` owns the normal/high-priority lanes by UUID and exact
stream ID. A replacement closes its predecessor; stale IDs cannot send or tear
down the replacement. Unknown UUIDs never acquire an endpoint, and stream 1 is
reserved for the existing iAP tunnel. Seeds and stream IDs cannot be reused
within an owner session. Both channel keys/counters and lifecycle are separate
from conventional CarPlay. The owner still requires integration into the live
serialized session loop; these classes do not advertise or enable Ultra.

Seven new loopback/codec tests exercise packet bytes, fragmented encrypted
traffic, independent lanes, multi-frame payloads, acknowledgments, replacement,
exact teardown, invalid lengths, authentication failure and replay rejection.
They validate local behavior against the reference, not acceptance by an iPhone.

The owner supplied an independent cross-session Leapmotor audit dated
2026-10-17, with 31 source references, a 20-symbol OEM CSV and an 11-artifact
contract-search manifest. All 31 source-file hashes match the supplied manifest.
The report and manifests are archived privately with their verification results.
Its SOC service/instance/group/event and little-endian double consumer agree with
our C11 integration. It identifies additional speed, gear, charge and climate
candidates, but does not validate their live semantics or Apple wire mappings.
Its CLTC/WLTC candidates do not replace our separately observed range source.

The audit also records historical Android display destinations and manual
connector diagrams; their variant/firmware applicability remains qualified.
It finds no modern Apple battery schema, Ultra negotiation contract or CP/Lua
renderer in its bounded corpus. These negative search results do not establish
absence from all firmware. No new OEM signal is enabled from symbol names alone.

The complete AGENTS.md command plus the workflow diagnostics targets passed
after the transport change: `BUILD SUCCESSFUL in 2m 46s`, 3,065 JUnit tests,
zero failures/errors and one skipped. Public-tree and whitespace checks pass.
The authenticated diagnostic APK was archived privately, checked against both
selected runtime assets and the installed signing certificate, and updated
successfully in car user 10. A copy of the preceding installed APK was retained.
The ordinary debug APK was then rebuilt and verified to exclude `offline-mfi`.
The car build includes feature-proposal diagnostics; the prototype channels
remain unregistered with live SETUP. Installation does not validate Ultra.

## Live feature proposal captured after the diagnostic update

The previously stored ADB address became reachable. On reconnection after the
diagnostic update, the real iPhone proposed all three research features:
`uiSync`, `vehicleStateProtocol` and `fileTransfer`. The recognized proposal also
included `hevc`, `iAPChannel`, `viewAreas`, `altScreen`, `cornerMasks`,
`videoPlayback` and `mainBuffered`; six other proposal values were counted without
logging their contents. The enabled features remained exactly `iAPChannel`,
`viewAreas` and `altScreen`. Observed stream SETUP types were 110, 111 and 130.

This is direct session evidence that the phone proposes the candidate features;
it does not demonstrate their activation, an Ultra-rendered widget or independent
passenger output. The owner's selection of a specific Ultra option has not been
confirmed, so this capture must not be described as a before/after Ultra-toggle
experiment. The encrypted prototype is still disconnected from live SETUP.

Raw logs and a summary are stored privately. The collector was stopped after
capturing the proposal; no key export or OEM vehicle writes were performed.
Next: integrate the prototype behind an explicit experimental gate, supply the
reference vehicle-state description, and observe actual channel/configuration
requests before claiming any phone-side acceptance of the range service.

## Runtime integration and local UI Sync work

The range prototype is now connected to `/info`, feature selection and verified
session SETUP. **Advanced > CarPlay Ultra prototype (experimental)** is shown
only for the C11 profile, defaults off and requests a driver-controlled reconnect.
It advertises only the read-only trip-computer range service. The media handler
must have a vehicle source, and an endpoint requires completed pair verification
and the phone's negotiated `vehicleStateProtocol` feature.

`VehicleStateRuntime` serializes configuration, registration and notifications,
bounds ingress and asynchronous egress, and shares one protocol state between
the two lanes. Source loss or a failed channel resets both state lanes while
leaving the iAP/video path independently owned. Exact stream-ID teardown is
forwarded without treating a non-iAP type-130 stream as the iAP tunnel. Updates
wait until the high-priority socket connects; they are not marked delivered
while that endpoint is only listening.

`UiSyncProtocol` implements the pinned reset/completion exchange, bounded nested
OPACK/plist decoding, panel isolation, sequence acknowledgments, layout requests,
palette/appearance changes and focus messages. A visual command is acknowledged
only after its compositor callback accepts it. Without a renderer it does not
grant focus or request layout changes. This core remains disconnected from live
advertising: the C11 build does **not** enable `uiSync` or `fileTransfer`.

The found LIVI `livi-runtime/src/file_transfer.rs` reassembles album artwork on
the iAP2 file-transfer session; it is not evidence of the AirPlay `fileTransfer`
asset/update channel. No guessed resource-transfer protocol has been advertised.

`tools/carplay/inspect_accessory.py` inventories ZIP contents and lexes Lua without
extracting/executing scripts. It reports native CP references, module imports,
wrapper layer methods/properties and archive/module hashes, with path, expansion
and duplicate-entry limits. On the private Apple archive it reads 193 Lua
modules, 52 CP root symbols and a required bridge version of **1.4.0**. The only
external Lua module reference is `CPMock`, used by the scripts' tooling fallback;
its absence alone is not a runtime compatibility result. A digest check is not
reported as signature verification. Three synthetic Python tests cover the
lexer, dependency inventory and unsafe archive entries.

Installation and real-iPhone acceptance of this runtime change must be recorded
separately from the earlier proposal-only capture. Local tests and generated
screenshots do not establish that the phone accepts the range service.

Final local validation after the late-channel correction: complete AGENTS.md and
diagnostics-module checks passed in 2 min 51 s; 3,075 JUnit tests, zero failures
or errors, one skipped. The three Python inspector tests also pass. Compact,
full and Arabic RTL screenshots cover 1.5x text at xhdpi; toggling uses the same
persisted opt-in consumed by the host. The ordinary APK is verified to exclude
private authentication assets. This new runtime APK has not been installed.

## Runtime installation checkpoint

The authenticated runtime APK was subsequently installed successfully in the
existing test package for car user 10. Its signing certificate matches the
preceding installed APK, which was saved privately for rollback. Both packaged
authentication assets match the explicitly selected local inputs. The read-only
range prototype was enabled through Advanced settings.

The resumed complete AGENTS.md and diagnostics-module checks passed again
(incremental build, 3,075 JUnit tests, zero failures/errors, one skipped).
The ordinary debug APK was rebuilt and verified to exclude private authentication
assets; public-tree and whitespace checks passed.

The connection attempt stopped before negotiation because the head unit's
Bluetooth was off. The owner is going to the car to enable Bluetooth and connect
the iPhone. Phone-side feature selection, channel requests and range-service
acceptance remain pending; this installation does not validate Ultra.

The owner subsequently requested reverting the transparent top status bar.
The C11-specific transparency and fullscreen-layout override has been removed;
the host uses its original system-bar/insets policy again.
The authenticated correction was installed successfully, and the complete
AGENTS.md plus diagnostics checks passed (3,071 tests, zero failures/errors,
one skipped). The ordinary APK was rebuilt without private authentication assets.

The owner clarified that the priority is actual Ultra activation and rendering,
not isolated vehicle-data acceptance. Further range-protocol validation is
deferred. Vehicle data alone cannot deliver the requested interface; the next
work must address compatible assets, display negotiation and synchronized
composition before expanding telemetry integration.

## Handshake-first probe

The C11 opt-in now selects a separate `ultraHandshake` mode. It uses a new saved
`handshake` preference (default off); the old range preference cannot enable it.
The Settings control and all translations describe session negotiation, with a
driver-controlled reconnect. No new vehicle signal has been implemented.

Following the pinned LIVI `info.rs` no-theme panel topology, `/info` includes
panels corresponding to the existing video destinations and one diagnostic
plug-in panel without a video stream. The real video destinations keep their
original declarations and have no plug-in attached. These local diagnostic IDs
are not Apple asset IDs, a vehicle theme, or an independent passenger interface.
`uiSyncInfo` is supplied, and `uiSync` is selected only if the phone proposes it
and the media layer can serve it. In this mode `vehicleStateProtocol` is excluded
even if an older range configuration is present. `fileTransfer` remains excluded.

The UI Sync endpoint uses the pinned UUID and existing authenticated duplex
framing after pair verification. It handles panel-scoped reset/reset-complete
exchanges without a vehicle-data provider. It never requests a theme/layout,
grants focus, or acknowledges a visual transition without a renderer. Replacement,
exact teardown, stale callbacks and seed/stream-ID reuse are bounded separately
from the existing iAP tunnel. It logs the negotiation stages without logging new
arbitrary peer values.

Live success requires observing the phone offer, selected feature, authenticated
channel SETUP and peer reset/completion exchange. Advertising panels or opening
a listening socket alone does not meet that criterion. If the phone does not
progress, investigate that handshake and its resource/authentication requirements
before returning to vehicle data. Local loopback tests cannot establish phone
acceptance or an Ultra UI.

Final local validation passed: complete AGENTS.md plus diagnostics-module checks,
3,077 JUnit tests, zero failures/errors and one skipped. Six handshake tests cover
opt-in topology, proposal selection, media capability gating, pair-verification
requirements, encrypted reset/completion without a vehicle provider, channel
replacement and exact teardown. Compact, full and Arabic RTL control screenshots
were regenerated and inspected at 1.5x text and xhdpi density.

The authenticated handshake APK was installed successfully in car user 10 after
checking its signature and selected runtime assets. The preceding status-bar
rollback APK remains available privately. The new handshake preference was enabled
through Advanced settings and verified saved; the obsolete `vehicle_state` key
is not consumed by this mode. The ordinary source-only APK was rebuilt and checked
to exclude private authentication assets. Phone-side acceptance remains separate
from this installation and local validation.

The first attempts with the installed handshake mode failed at Bluetooth RFCOMM
(`BluetoothSocket.readAll`, peer read failure) before AirPlay `/info` or feature
SETUP. No UI Sync request/reset has been observed in this run. This is an
unreached handshake, not an Ultra rejection. The owner was asked to confirm that
the iPhone is present, unlocked and has Bluetooth/Wi-Fi active. Resolve transport
and capture negotiation before resuming any vehicle-data integration.

## Connected baseline: UI Sync selected but no channel requested

After the owner connected the iPhone, the installed no-theme handshake probe
reached AirPlay SETUP. The phone proposed `uiSync`, `vehicleStateProtocol` and
`fileTransfer`. The receiver selected `iAPChannel`, `viewAreas`, `altScreen` and
`uiSync`, and `/info` supplied three panels plus `uiSyncInfo`. The data protocols
remained excluded as intended.

The observed requests opened only iAP type 130 stream ID 1, alternate video type
111 and main video type 110. No UI Sync type-130 channel, reset or completion was
observed in the captured interval. The `negotiated=true` diagnostic here records
the receiver's feature selection; it is not proof that the phone activated Ultra.
The ordinary video session proceeded. The owner reports iOS 27, Ultra already
selected in the iPhone's CarPlay options, and no new download prompt. The absence
of a prompt alone does not establish acceptance, rejection or a missing download.

The next controlled difference is the resource announcement. The pinned LIVI
`livi-runtime/src/ident.rs` places the real catalog identifier in child parameter
6 of the wired or wireless CarPlay transport component; its `bringup.rs` supplies
the theme SDK and ID/version in CarPlayStartSession. DiPlay previously omitted
all of those at runtime. This is a concrete implementation difference and a
candidate cause of the missing next step, not a proven cause.

An optional validated `Iap2ThemeAsset` now carries those values consistently
through both transports and wireless identification roles. Ordinary endpoints
default to no descriptor. The C11 handshake opt-in selects the inspected Apple
reference resource `44C9FA31EC`, version 985, SDK 19.0; that SDK is resource metadata,
not a claim about the owner's iOS version. The UI and translations identify this
as a reference-resource test. No manufacturer binaries/scripts are bundled or
executed, no C11 theme compatibility is claimed, and no mutual-authentication
support is invented. Vehicle-data protocols remain excluded.

This next experiment intentionally changes the announcement first. It does not
yet bind the downloaded theme's display names/layouts to the compositor, implement
the asset-transfer channel, or claim a complete handshake. Those requirements
must be established from the phone's next requests. An active session must not
be interrupted to install the next APK without driver consent.

The descriptor APK is prepared privately and **not installed**. Its signature
matches the preceding car-test APK and both runtime authentication assets match
the explicitly selected local inputs. Complete AGENTS.md and diagnostics checks
passed in 2 min 46 s: 3,081 tests, zero failures/errors, one skipped. Seventeen
targeted transport tests passed, including the four new descriptor tests. The
updated compact/full/Arabic screenshots were rendered and inspected. The ordinary
APK was then rebuilt without private authentication assets. Installing this next
experiment requires driver consent to interrupt/reconnect the now-active session.

## Descriptor experiment installed and rolled back

With explicit owner consent, the verified descriptor APK was installed and
CarPlay restarted. Both Bluetooth-bootstrap and Wi-Fi-tunnel identification and
authentication were accepted. The phone advertised theme-assets availability,
and the receiver again selected the offered `uiSync` feature and supplied its
diagnostic panels.

During the captured interval (18:24:02–18:25:04 on the car's log clock), the phone
opened iAP type 130 stream ID 1 and alternate video type 111. It did **not** request
main video type 110 or the UI Sync endpoint, and no reset/completion was observed.
The primary screen remained black. This is a changed response to the descriptor,
not proof of Ultra activation, package download, package acceptance or rejection.

The preceding no-theme handshake APK was reinstalled successfully to recover
ordinary CarPlay. The descriptor APK remains a private failed experiment and must
not be presented as the working car build. The staged source still contains the
experimental descriptor path; its live result is qualified by this section.
Next investigate the resource exchange and matching panel/theme declarations,
not vehicle data. Captures and an experiment/rollback manifest remain private.

Reinstalling the no-theme APK alone did not immediately restore main video.
The experimental handshake switch was then disabled through Settings, and the
Reconnect now action applied the recovery. At 18:28:03 the phone again requested
main video type 110 alongside iAP/alternate video. A screen capture confirmed the
ordinary CarPlay image was restored. The current car build is the no-theme
handshake APK with its experimental opt-in **off**. The descriptor candidate is
not the installed/working car build. The possible contribution of phone-side
retained state is unproven; no claim about a successful resource download follows
from this recovery.

## Theme/panel consistency investigation

Before the bound-panel live test, the working car build used the probe off. Following
the failed descriptor-only experiment, source and resource inspection established
a structural inconsistency in that candidate. Apple's inspected phone package
defines `Center_Display` and `Cluster_Display`, each 1920×720 at density 157. The
candidate announced the asset but kept conventional stream IDs/canvases and a
synthetic plug-in panel. The pinned LIVI themed path instead binds the resource's
display names consistently across video, panels, the cluster plug-in and controls.
This is a demonstrated metadata mismatch; it is not a proven root cause of the
phone's withheld main video.

| Declaration | Descriptor-only experiment | New staged binding |
| --- | --- | --- |
| Main video | Conventional UUID, 2560×1176 | `Center_Display`, 1920×720 |
| Alternate video | Conventional UUID, 1280×720 | `Cluster_Display`, 1920×720 |
| Local plug-in | Synthetic extra panel without video | `Cluster_Display`, bound to that logical video stream |
| HID and live commands | Conventional display identities | Same names as the resource/streams |
| Alternate physical output | Ordinary navigation route | Discarded at media boundary for this probe |

`UltraThemeBinding` now configures the descriptor, logical canvases and panel
identities together. A bound configuration with different or missing geometry is
rejected. HID declarations, view-area commands and keyframe routing share the
selected identities; primary-screen options are selected by role rather than a
hard-coded UUID. Conventional cluster URLs and map zoom are rejected for the
themed probe instead of being sent into a theme's composition channel.

This is still a reset-only handshake host without a renderer. The alternate
stream is accepted into a no-output sink, and physical cluster presentation/task
routing is suppressed for this mode. It must not display unrendered vehicle
gauges or grant focus/layout acknowledgments. The regular main video path remains
available for the experiment, but its delivery by the phone is unverified.

`tools/carplay/check_theme_contract.py` compares a generated negotiation plist
against phone-package JSON without executing scripts. On a reconstruction of the
failed advertisement it detects six identity/canvas/control mismatches. On the
new generated advertisement it reports zero. The comparison uses the inspected
package metadata SHA-256
`084b6fddc2f56a034094f284d425b1046688ac727fcc15dccff675983b629511`.
It explicitly does not claim signature verification, renderer support or phone
acceptance. Both generated plists and comparison reports are retained locally.

This remains separate from resource transfer/authentication and UI composition.
Apple's [architecture description](https://developer.apple.com/videos/play/wwdc2024/10111/)
requires authenticated vehicle resources, local rendering and per-display UI Sync;
matching metadata alone does not implement those pieces. Vehicle-data work remains
deferred until the phone's handshake behavior is established.

The bound-panel candidate passed the complete AGENTS.md plus diagnostics checks
in 2 min 47 s: 3,086 JUnit tests, zero failures/errors, one skipped. Six Python
inspector tests pass. The private authenticated APK is archived as
`diplay-ultra-bound-panels.apk`; its signature matches the working test package and
its two runtime assets match the explicitly selected local inputs. The ordinary
APK was rebuilt without private authentication assets. Public-tree and whitespace
checks pass.

Initially this candidate could not be installed: at the first live-test attempt, ADB
reported the stored car endpoint offline; reconnecting timed out and no mDNS
endpoint was discovered. The Mac remains on the same subnet. The owner was asked
to restore the car connection. At that point the car still used the previous
no-theme handshake APK with the experimental switch off. The subsequent live result
below supersedes that connectivity blocker.

## Bound-panel live test: UI Sync reset exchange observed

After the owner restored availability, ADB reconnected and the authenticated
`diplay-ultra-bound-panels.apk` was installed for car user 10. The experimental
switch was enabled through Settings and CarPlay started. This is now the installed
car build, with the probe on; the previous no-theme APK remains the rollback.

The car log records the following sequence on 2026-10-17:

- 18:53:10: the receiver selected the phone-offered `uiSync`; `/info` declared
  two bound panels and no modern vehicle-state protocol.
- 18:53:11: the phone requested iAP data stream 1, alternate video 111, main
  video 110, and a second type-130 data stream, ID 3, for UI Sync.
- 18:53:11.620: a UI Sync reset was received and answered.
- 18:53:11.705: reset-complete was received and the receiver queued its reply.

This is the first observed real UI Sync channel/reset exchange in these probes.
It goes beyond selecting a feature token. It does not prove that the phone
consumed the final queued reply, accepted/downloaded resources, or enabled a
complete Ultra UI. No renderer or resource-transfer implementation was added.

Through 18:54:07 no UI Sync closure was logged. Video statistics continued,
and a screenshot confirmed the ordinary CarPlay clock/weather interface on the
main screen, with the opaque system status bar preserved. The physical alternate
output remains suppressed for this probe. No recovery reinstall was needed.
Private evidence is retained in `bound-panels-session.log`,
`bound-panels-screen.png` and `bound-panels-verification.json`.

The aligned candidate succeeded where the descriptor-only candidate did not;
that observation supports the consistency fix without isolating every phone-side
state variable. Next work is resource exchange/authentication and local UI
composition. Vehicle-data integration remains deferred until a usable Ultra
session is established.

## Post-reset observation and renderer boundary

The next private build, `diplay-ultra-post-reset.apk`, adds bounded structural
UI Sync diagnostics and is now installed with the probe enabled. It changes no
negotiated feature, visual acknowledgment policy or vehicle-data path.
`UiSyncProbeDiagnostics` emits at most 32 distinct structural observations per
channel, recording local panel index, numeric message/command kinds, readiness
and reply count. It records no peer strings, identifiers, routing fields or
message bodies. Repeated frames are deduplicated. Channel replacement starts a
new diagnostic budget.

On the car's clock, 19:01:04–19:02:43, the phone again opened main/alternate video,
iAP stream 1 and UI Sync stream 3. It sent reset and reset-complete for
`Cluster_Display` (sorted panel index 1); the receiver queued one reply to each.
No command frame, diagnostic-limit event or UI Sync closure was observed in that
interval. This establishes neither consumption of the last reply nor resource
transfer/acceptance. A screenshot confirms ordinary CarPlay remains visible and
the status bar opaque. The opt-in remains on; physical alternate output remains
suppressed. The preceding bound-panel APK is the immediate rollback.

Direct inspection of the existing Apple accessory archive further identifies the
renderer boundary in `LayoutController.lua`: the host sends layout requests
(command 5) and layout-change permission (17); incoming zone/layout transitions
and metadata use commands 1, 2, 3, 4, 6 and 9. Metadata carries presentation times
and is consumed against video-frame presentation windows. These meanings come
from the inspected script, not from observed commands in this capture. The
current reset-only runtime intentionally sends no layout request or permission
without a renderer. The observed silence after reset therefore does not show
that a package is unavailable or that the iPhone refused Ultra.

The archive's native `CP` bridge and its layer/video/timing operations remain
unimplemented on Android. A suitable next implementation is an offscreen resource
host/compositor that loads a validated package, stages one real layout, and
preserves video/transition presentation times. Only once that path can apply a
transition should it request the layout or acknowledge it. This is distinct from
the AirPlay asset-transfer contract, which remains unidentified in the inspected
references. The existing `Iap2FileTransferReceiver` and LIVI's similarly named
file are album-artwork receivers and cannot establish that contract. No guessed
`fileTransfer` capability was enabled.

Validation of this change: full AGENTS.md CI plus diagnostics tasks passed;
3,089 JUnit tests with zero failures/errors and one skipped. Six Python inspector
tests pass. An encrypted-channel test confirms an unrendered layout remains
unanswered while its structural kind is logged without its layout identifier;
additional tests cover bounded output and noninterference. The authenticated
APK's signature and selected local assets were verified. The ordinary output APK
was rebuilt and checked to contain no private authentication assets. Private
session, screenshot and build verification are retained separately.


## 2026-10-18: live reconnection and original display restored

With the car and iPhone available again, a fresh wireless attempt authenticated
and opened CarPlay. At car time 08:52:57 the iPhone repeated UI Sync reset and
reset-complete on panel index 1. Only those two structural message kinds were
observed; this is transport evidence, not Ultra visual activation.

The owner reported that the interface was reduced with excessive space above it
and requested the official main-branch display style. The current official
`upstream/main` was fetched at `6d3b8a8ce927cb4b115b8c3881f17619433e93b1`.
DiPlayActivity's fullscreen, interface-density and render functions and
CarPlayHostActivity's fullscreen, content rectangle and video-layout functions
match that revision. The active reference-theme probe instead forced 1920x720,
which letterboxed inside the normal 2560x1176 viewport.

The probe was switched off through its existing Advanced control and its
Reconnect now action was applied. The new session reports default size,
2560x1176, 100% resolution, and UI Sync negotiation false. A fresh screenshot
confirms CarPlay fills the available area below the opaque original status bar
without the extra black top/bottom letterbox. No APK was installed, no personal
files or passwords were consulted, and the main-branch screen behavior remains
unchanged. Research can continue offscreen; re-enabling this reference geometry
on the driver's normal session would reintroduce the reported display problem.

## 2026-10-18: offscreen source host progress

A source-built Luau 0.743 host executes the original wrappers with bounded
resource access, a 128 MiB VM ceiling and a persistent CPU deadline. The initial
15-module scene was executed on DiPlay arm64 and rasterized by Android Canvas
into a PNG without opening any physical display or replacing the installed app.
The current wrapper fixture adds original Thread/Path/ShapeLayer execution
(19 modules), and the compositor adds path fills/strokes, contour trimming and
linear/radial/conical gradients. Ellipse and arc paths are converted to cubic
segments. Arc and conical-gradient conventions still need native-reference
comparison; these are research implementations.

The bridge normally reports an incomplete version. An explicit disconnected
`--research-allow-incomplete-cp14` override allows the unmodified center main.lua
to start with a simulated UI lifecycle: 172 modules run on the Mac and DiPlay arm64 and produce
a 17-node scene without phone video. This is not a complete CP bridge or a
working Ultra dashboard. The original cluster entry point progresses past path
construction but fails when its cruise-control gauge requires vehicle controllers
that the disconnected host does not expose. No speed, battery or cruise-control
values were invented to make that entry point pass.

The live Java UI Sync handler remains reset-only. Masks, filters, decoration,
video-texture binding, resource acceptance/transfer and synchronization to real
video presentation timestamps are still required before live visual commands can
be applied and acknowledged. See ADR-002 and tools/carplay/runtime/README.md.

The updated 19-module fixture was also executed and rasterized successfully on
DiPlay arm64. The resulting PNG was inspected for nested layers, the selected
resource image and the filled/stroked ellipse. No physical display was opened.
The required complete CI command passed in 3m24s: 3,101 JUnit tests, zero
failures/errors, one skipped. This includes 12 native-graphics compositor tests.
Ten runtime/preparation tests and six resource-inspector tests passed separately;
public-tree and whitespace checks passed. Those checks validate these development
changes, not full Ultra activation.


## 2026-10-18: original protocol session runs offscreen

The research host now implements native binary property-list creation and parsing
for CP.createPlist/parsePlist, preserving nested binary data as immutable userdata.
It supports finite real timestamps and signed integers within Luau's exact numeric
range. Inputs, outputs, aggregate expanded payload, object count and recursion are
bounded. Independent Python plistlib vectors cover nested messages, Unicode,
32/64-bit reals, negative values, and refused malformed or excessive input.

The new protocol_probe.luau executes all original center scripts unchanged, with
an explicitly simulated peer and UI lifecycle. Actual bplist reset/reset-complete
messages pass through the original ClusterControlSession. The program then sends
request-layout (5), allow-layout-change (17), and give-focus (7), in that order.
The selected center layout is 9E19BC4E-5251-5821-A0BA-26479A79F722, palette blue.
This ran on both the Mac and DiPlay arm64 (172 original modules). The initial
foreground lifecycle also exercises a reset/retry before the successful session.
No real phone or physical screen is attached to this protocol fixture.

The Android bounded plist reader previously refused real-valued fields and signed
eight-byte integers. It now preserves finite 32/64-bit presentation times and
signed values such as appearanceMode=-1, while refusing nonfinite values. These
changes retain the existing structural limits and do not enable a live renderer.
The installed app and its normal 2560x1176 CarPlay session were not replaced.

Live UI Sync remains reset-only. Connecting this original session to the iPhone
still requires a persistent script host, safe native transport integration, video
texture/presentation-time binding, and transition application in the compositor.
The cluster reference still requires unsupported vehicle controllers; the fixture
does not fabricate live C11 speed or battery values to bypass that requirement.


## 2026-10-18: persistent host and video clock preparation

A new explicit --research-step-stream mode keeps one Luau VM and the original
center session alive across bounded binary IPC requests. Incoming messages,
coroutine ticks, outgoing-message drains and scene snapshots are separate calls.
There is no arbitrary-source command. Invalid requests terminate the process;
queue sizes, record sizes, request count, per-step CPU time and VM memory remain
bounded. The Python client adds response deadlines and process cleanup.

Before the owner's request to stop accessing the car for its restart, this
persistent flow was verified offscreen on DiPlay arm64: 172 original modules,
12 successful IPC responses, reset/reset-complete followed by appearance, focus
and layout requests as the simulated lifecycle changed. This used a simulated
peer and does not establish a live Ultra session.

After that request, all further work has been local; no car connection, APK
installation or screen operation was performed. The decoder now has an optional
release-timing callback that preserves the iPhone's original frame timestamp
through both paced and unpaced decoding. Reordered output, absent timestamps,
duplicate codec timestamps, bounded eviction and codec reset are handled without
guessing an origin. Mirrors and parked output do not emit the release event.
The callback is unset in the normal app path and does not change its layout,
pacing or resolution.

Local Robolectric tests exercise both decoder output paths, the paced path and
isolation from a failing timing observer. Release to a Surface is not proof of
physical presentation. The time callback is not yet attached to the script host
or used to acknowledge visual changes. Video texture ownership, a shared frame
clock, compositor transition application and live phone integration remain.

Validation after the offline video-timing changes: the full AGENTS.md and
diagnostics checks passed in 2m51s (3,110 JUnit tests, zero failures/errors,
one skipped). Eighteen research runtime/preparation/protocol tests passed
separately, including persistent-session CPU deadline, bounded IPC, and original
script integration. Public-tree and whitespace checks passed. An earlier full
run encountered a connection reset in the existing backup-server socket stress
test; the repeated full run passed without changing backup code.

## 2026-10-19: live baseline and host-visibility dependency

The owner made the car and iPhone available again. The source-only debug APK was
upgraded in place for Android user 10, preserving application data. Its existing
authentication provider reached MfiReady and the iPhone reached WirelessActive.
Normal CarPlay remained 2560x1176. The experimental handshake preference was off,
and this connection reported UI Sync not negotiated. This is a verified normal
CarPlay baseline, not an Ultra activation. The owner also reported that returning
from the car's camera requires reopening DiPlay; that fault remains undiagnosed.

Subsequent work resumed local prerequisites rather than further car changes.
`UltraHostVisibility` now provides a connection-owned mailbox for complete
resumed/surface-ready host snapshots. Callbacks store only the latest effective
foreground state and do not enter JNI or acquire the panel's composition lock.
`UltraComposedPanelSession` consumes pending state under its VM transaction before
protocol delivery, polling or composition. Retiring an owner invalidates pending
and late callbacks; replacement connections start with a fresh mailbox. Failure
before frame handoff releases the offered frame before retiring the owner.

This is one part of the lifecycle dependency. Activity callbacks are not wired
yet, and local foreground state neither supplies vehicle scene state nor establishes
the phone's layout/focus response. The original-center debug fixture now supplies its
simulated host state through the same pipeline mailbox, but still explicitly
simulates the separate vehicle scene state. No new APK was installed for this
dependency, and no camera-return behavior was changed.

Three mailbox tests and three additional real JNI tests passed locally. They
cover surface loss/host pause, conflation, retired callbacks, application at
protocol/frame boundaries, callback progress during a blocked composition, and
frame cleanup when the VM is already unavailable. Full Android CI passed in
2m57s: 3,151 tests, zero failures/errors, one skipped, plus lint and debug builds.
Public-tree and whitespace checks passed. Resource acceptance, the verified vehicle
scene mapping, phone layout/focus response, Activity activation/fallback, sustained performance and physical
Ultra presentation remain separate open gates.

## 2026-10-19: explicit local vehicle scene service

Inspection of the original controller corrected the earlier interpretation of
`uiSceneState`: `Workspace.lua` identifies it as vehicle-requested, and
`VehicleDataUpdater.lua` reads it from the local CP service. It is not a phone
permission signal. The phone's UI Sync layout/focus response is a separate
contract. The minimum-center contract and dependency plan now distinguish these
inputs instead of treating local scene state as an iPhone grant.

The selected entry declares only the required scene service before loading the
original controller. Its initial reading is INVALIDVALUE. New bounded opcode 8
accepts the selected package's enum 0–4 or invalidates with 255; it refuses an
undeclared service and invalid values terminate the VM. The old research setter
is still separate for older research fixtures. No speed, battery, gear or other
vehicle schema is added, and nothing in this input API sends a scene value to
the iPhone.

The optional panel/pipeline now explicitly selects its own scene mailbox,
created per connection rather than shared between owners. Callbacks record enum
or null without entering JNI; protocol/composition workers apply pending local
state inside the VM transaction. Close invalidates late callbacks. Missing/stale
data must be reported as null by the future source, independently of host
visibility. No C11 scene source, mapping, freshness timer or automatic inference
has been enabled; verifying that mapping remains an open dependency.

Five scene tests cover missing versus explicit NONE, source loss, retired
callbacks, the actual JNI state route, independence from host foreground,
undeclared-service rejection and invalid native values. The 28-test host suite
also passes. The original Wallpaper probe now uses opcode 8 and still requests
its selected layout and applies video visibility with simulated scene/peer input;
193 original source modules remain unchanged. This is a local contract check,
not live C11 state, iPhone acceptance or physical Ultra output. Native builds for
Mac JNI, Android arm64 JNI and the desktop host were rebuilt successfully.

The first full Android CI passed in 2m52s. After tightening per-connection
construction, a full run hit the pre-existing SettingsBackupServerTest socket
reset. The repeated full CI passed in 2m36s without modifying backup code:
3,156 tests, zero failures/errors, one skipped, plus lint and debug builds.
Seven standalone contract tests, public-tree and whitespace checks also passed.
The new input route was executed in the desktop host/Mac JNI; Android arm64 was
compiled. This dependency was not installed in the car; the normal session and
Bluetooth startup policy were not changed. Selection of a validated local source
or scene policy remains open; a direct physical ignition mapping is not assumed
to be a protocol requirement.

## 2026-10-19: explicit reference-center accessory UI policy

The scene-source dependency now has a bounded local-policy option for the
selected Wallpaper. `REFERENCE_CENTER_ACCESSORY` explicitly requests its
ACCESSORY_MODE UI; it is not an assertion about physical ignition or driving.
The policy starts withdrawn, requires a connection-local request plus resumed
host and ready surface, suspends when either host condition is absent, and
forgets the request on retirement. The alternative raw scene-service mode is
mutually exclusive and cannot overwrite the policy. Ordinary startup selects
neither mode.

Foreground and scene travel as one snapshot into the VM transaction. The
pipeline restricts policy selection to Center_Display/iPhoneVideo at 1920x720;
the future installer must separately verify resource/layout identity. Wrong
geometry is rejected in the constructor cleanup path before opening GL/decoder.
This does not establish resource acceptance, UI Sync focus or phone topology.

The original workspace preserves its active scene in the background, so merely
changing local CP values cannot suppress output. The owner now consumes/drops
inactive-policy frames without composition. Completed pipeline output also
requires the same activation token captured before composition. Pause/resume
or withdrawal during drawing invalidates that output, even if active again
when drawing finishes. Host-surface detach still owns any publication already
executing; this policy neither closes/reopens the Activity nor fixes camera
return focus. Decoder/GL continue draining until the connection is retired.

The original-center debug fixture now uses the policy request instead of direct
scene enum injection. New JNI tests execute the original controller with a
simulated peer, verify its selected-layout request, and exercise inactive-frame
ownership. Unit tests cover all readiness gates, explicit withdrawal, callback
conflation, new connections and invalidated activation tokens. Seven policy
tests and fourteen native-runtime tests pass, including rejection/VM cleanup
before an incompatible pipeline opens a decoder. Final full CI passed in 39s:
3,165 tests, zero failures/errors, one skipped, plus lint and debug builds. An
earlier full run timed out waiting for an existing audio test's start callback;
that test passed alone in 3s, then the full suite passed without audio changes.
Public-tree, whitespace and dependency-graph checks passed. No car installation,
Activity selection or normal CarPlay geometry/startup change was performed for
this dependency. Verified resource selection/loading and Activity lifetime and
fallback are the next implementation dependencies; phone acceptance remains
unverified.

## 2026-10-19: verified resource loading for the optional Android runtime

Parallel work produced the loader and synthetic tests while the primary agent
integrated native runtime lifetime, original-controller/image access and full
verification. `UltraPreparedResources` pins the selected prepared archive and
binary manifest, checks every listed Lua/PNG/plist hash and copied Info.plist
identity, then creates a private session copy. Only manifest-listed files are
read; no bytecode, credentials or other input-directory files are copied. Source
files are preserved. Safe-path/regular-file checks, file/count/aggregate limits
and source-only flags apply before a VM starts. This is an explicitly selected
development source input, not resource import from the iPhone or signature trust.

The reference manifest is 348,629 bytes. Initial real-package tests exposed that
the network plist reader's 256KiB cap was too small. A separate local-manifest
reader now allows at most 1MiB while retaining object/depth budgets. Network
decoding keeps its 256KiB cap; a regression test verifies that separation.

The snapshot is single-use. `openRuntime` transfers its retirement to the VM's
close hook; close during an active VM preserves files until destruction. Library
load failure, failed native initialization and fatal requests also remove the
owned copy. PNG reads use its allowlist and rehash bytes. The original-center
Android debug fixture now routes native modules and compositor image reads
through the same snapshot. Its app-private delivery/selection remains unwired.

Eleven synthetic loader tests and three real-reference JNI tests passed with
zero skips. The latter execute the original controller from the snapshot,
observe its selected-layout request with a simulated peer, check PNG lifetime,
and verify cleanup/original retention on native failures. The 28-test host suite
passes, including deterministic binary-manifest preparation. Final full CI
passed in 2m53s: 3,180 tests, zero failures/errors, one skipped, plus lint and debug
builds. Public-tree, whitespace and the 25-task dependency graph checks passed.
The new loading route ran on host JNI and compiled for Android; it was not run
on the car or used to activate a physical Ultra surface. Delivery/selection in
the app, Activity lifetime/fallback and iPhone acceptance remain open.


## 2026-10-19 — Entrega y selección del paquete en APK de desarrollo

Se añadió staging verificado de los recursos de referencia y su inclusión solo
mediante una propiedad explícita de build debug. Gradle ejecuta el verificador
antes del merge. El APK habitual no contiene el paquete; el APK explícito contiene
3.073 archivos, todos idénticos al stage. No se instaló en el coche.

El selector Android reconoce el perfil central Wallpaper, importa únicamente el
inventario fijado y conserva una copia privada compartida por VM y PNG. La ausencia
del marcador devuelve ninguna selección; un paquete declarado pero dañado falla.
Se mantiene el override de versión como investigación. No demuestra provisión,
aceptación del iPhone ni presentación física.

CI completo aprobado: 3.188 pruebas, cero fallos/errores y una omisión previa;
40 pruebas Python aprobadas. Las ocho pruebas nuevas de selección/ejecución no se
omitieron; la de JNI ejecutó el controlador original con el paquete completo y un
interlocutor simulado. Reporte privado: `packaged-resource-verification.json`.

Sigue pendiente instalar la fábrica por conexión y conectar superficies/visibilidad,
recuperación y fallback en Activity. La muerte del proceso puede dejar copias en
noBackupFilesDir; antes de uso desatendido se necesita recuperar sesiones abandonadas
respetando la propiedad de cualquier sesión activa. La aceptación y el primer layout
visible con iPhone real siguen abiertos.


## 2026-10-19 — Instalador de Activity y prueba integrada de salida central

La ruta de desarrollo explícita ya prepara el host desde los assets del APK fuera
del hilo de interfaz y lo instala en la fábrica por conexión. Solo se selecciona
con el opt-in C11 existente y el paquete presente; la APK habitual mantiene su ruta.
Configura el panel central sin cluster, buffer 1920×720 y H.264. El decoder usa GL
fuera de pantalla y la superficie prestada recibe únicamente la composición.

El controller conserva el host durante reemplazos de Activity. Sus adjuntos de
visibilidad/superficie son reemplazables; callbacks tardíos no desconectan al nuevo
host. Un fallo o ausencia de UI Sync queda retenido y reconstruye la conexión
ordinaria, conservando preferencia y geometría normal. Los candidatos sin controller
cierran fuera de UI. La retención de última imagen del decoder ordinario no tapa
la salida compuesta. No se modificó el arranque por Bluetooth ni las barras.

Los recursos tienen ahora leases con bloqueos del SO: una nueva importación elimina
solo owners reconocidos abandonados y conserva sesiones activas de este u otros
procesos. Las copias sin el esquema nuevo se dejan intactas. La lease sigue viva
hasta la destrucción nativa de la VM.

CI final aprobado: 3.214 tests, cero fallos/errores y una omisión previa; las nuevas
pruebas JNI y de bloqueos entre procesos se ejecutaron. En Android 35 arm64, el smoke
desde el APK real verificó píxeles de AVC generado a 1920×720, pausa, adopción de una
segunda superficie, callbacks viejos, superficies prestadas válidas y retiro de
recursos. No instala la aplicación ni presenta una pantalla física.

La primera ejecución detectó un defecto del peer simulado: respondía al primer
RESET aunque foreground podía emitir otro con SSN vigente. Se añadió regresión JNI
y el simulador exige RESET_COMPLETE/solicitudes/ACK de la sesión actual; no se
alteró el protocolo original ni se retrasó artificialmente el foreground. Una
aserción nueva del sink alternativo también se corrigió: se descarta, no usa el
sink ordinario. Lint sufrió un fallo interno de PSI durante una edición de fixture;
la repetición con archivos estables pasó sin desactivar controles.

Candidata privada: `DiPlay-Ultra-center-integration-dev-20261019.apk`. Reportes:
`activity-integration-verification.json` y `activity-android-smoke-verification.json`.
Sin acceso ni instalación nueva en el coche. El siguiente paso es una conexión
coordinada con iPhone/coche: aceptación/provisión del paquete, topología, solicitud
y respuesta real de layout, salida física y fallback. La composición simulada no
demuestra CarPlay Ultra activado; rendimiento sostenido y retorno de foco tras
cámara siguen pendientes.


## 2026-10-19 — Diagnóstico real y corrección local de salida/recuperación

La candidata anterior se instaló preservando datos. En la conexión inicial la
preferencia Ultra estaba desactivada. Al activarla mediante ajustes/reconexión,
el iPhone habilitó `uiSync`, se instaló el renderer y el decoder qti recibió AVC
central 1920×720. Sin embargo no llegó SETUP del DataStream UI Sync: el único type130
correspondía a iAP. Habilitar la capacidad no demuestra abrir el canal ni aceptar
el paquete/layout. No hubo una escena Ultra física confirmada.

Aproximadamente 14 s después se abortaron control/iAP y el listener devolvió Socket
closed. No hay TEARDOWN remoto ni motivo local de liberación capturado antes del
corte. La causa concreta sigue sin confirmar: no se atribuye a rechazo del tema,
permisos, cifrado o al EADDRINUSE secundario, que también aparecía en CarPlay normal.
La comparación local de LIVI tampoco justifica inventar un plugin en el centro o
anunciar otro panel sin implementarlo.

Se reprodujo y corrigió el panel Preparando CarPlay persistente: solo escuchaba al
sink ordinario. Ahora el adjunto informa salida tras un Canvas aceptado, y Activity
comprueba identidades antes de ocultar el panel. El estado es propio de cada
activación/superficie; pausa, detach, reemplazo, fallo o cierre del panel lo invalidan.
Una decodificación por sí sola no cuenta como salida compuesta ni física.

También se bloquea publicar Canvas antes de adjuntar el propietario autenticado
UI Sync. Si ese canal no llega en 10 s de host resumed con superficie usable, el
watchdog conserva presupuesto durante pausas y termina la prueba con motivo
UI_SYNC_CHANNEL_TIMEOUT, reconstruyendo CarPlay ordinario. Abrir el canal cancela
esta espera; no cubre un futuro bloqueo de reset/layout con canal ya abierto. El
cierre del canal durante conexión activa recupera la ruta normal; el teardown de
una sesión ya cerrada no dispara un falso fallo.

Los nuevos logs separan envío Canvas, canal y categoría de fallo. La capa de red
registra motivo/generación de liberación y revocación VPN; los errores de accept de
una generación retirada no se atribuyen al propietario nuevo. Esto mejora la
próxima captura y no afirma haber resuelto la causa física del corte observado.

CI local completo: 3.233 pruebas, cero fallos/errores, una omisión previa. Diecinueve pruebas
nuevas cubren readiness, cierre, temporización y eventos tardíos; los tests JNI y
bloqueos entre procesos no se omitieron. El APK exacto volvió a verificar píxeles,
pausa/adopción de superficies y retiro de recursos en Android 35 arm64 con peer
simulado y AVC generado. Emulador cerrado; ningún acceso al coche durante el arreglo.

Candidata privada nueva: DiPlay-Ultra-channel-recovery-dev-20261019.apk,
SHA256 17c2688ae961d67d647e5e570a20aa493bb033cebdb98ef3bd794491818329ec.
Prueba: live-fixes-verification.json. No instalada en el coche, que quedó libre
para la otra sesión de SystemUpdate. La opción experimental real permanece activada
según la prueba anterior; coordinar la siguiente conexión antes de probarla.

## 2026-10-19 — Validación en coche de channel-recovery

Con autorización del usuario se instaló la candidata anterior (SHA256
17c2688ae961d67d647e5e570a20aa493bb033cebdb98ef3bd794491818329ec),
preservando datos, y se abrió DiPlay. El usuario confirma CarPlay ordinario y
desconexiones intermitentes durante el recorrido.

La captura del proceso inicial confirma `uiSync=true` a las 12:47:51.734,
renderer instalado a las 12:47:52.572 y ausencia de apertura del canal UI Sync.
A las 12:48:02.574 el watchdog emite `UI_SYNC_CHANNEL_TIMEOUT`; la liberación
`service_destroyed` posterior corresponde a la reconstrucción prevista. A las
12:48:09.109 la nueva sesión usa CarPlay ordinario (`uiSync=false`). Esto valida
el disparo de recuperación, sin demostrar salida Ultra ni aceptación del paquete.

A las 12:48:50.636, ya en modo ordinario, se abortan simultáneamente canales
de evento, control, vídeo e iAP; a las 12:48:50.644 falla accept con `Socket closed`
y se registra `transport_error`. No hay liberación local registrada inmediatamente
antes de esos abortos. La siguiente negociación ordinaria ocurre a las 12:48:57.582.
La causa inicial del corte sigue sin identificar; el fallo de accept es posterior
a los abortos y no basta para atribuirle su origen.

La captura filtrada por PID cubre únicamente hasta las 12:51:06.461. Al volver
el usuario, el proceso era otro y logcat no conservaba entradas de ese PID nuevo;
no se puede afirmar cuántos cortes hubo ni explicar todo el recorrido. Se detuvo
la captura antigua. Antes de otra prueba prolongada se necesita captura que siga
los cambios de proceso, limitada a los diagnósticos de DiPlay. No se cambió código
ni se afirma corregida la desconexión con esta validación.

## 2026-10-19 — Captura por UID y experimento de plugin central

La comparación con `livi-info.rs` (referencia fijada 4bcd1335) identifica una
limitación del anuncio anterior: la selección central quitaba el cluster y
también todo `displayPlugin`. La referencia explica que el plugin dispara Ultra
y que el tema compone la pantalla que lo declara. Su implementación usa cluster;
esto apoya una hipótesis de negociación, no prueba aceptación de un centro solo.

Se añade `UltraThemeBinding.composeMainDisplay`, desactivado por defecto. La
selección de recursos central empaquetada lo activa explícitamente y el anuncio
declara plugin y vídeo con la misma identidad `Center_Display`. El propietario
existente exige igualdad del binding completo. No se añade un panel ficticio ni
`showsInstruments`; los bindings anteriores conservan su anuncio. Se mantiene
la recuperación por ausencia de UI Sync. Los scripts originales y el bridge no
se alteran. Este cambio aún necesita validar SETUP, layout, recursos y presentación
con iPhone real; tampoco explica ni corrige el aborto de red en modo ordinario.

El comparador de contrato ahora distingue el plugin central explícito mediante
`--compose-center` y avisa cuando no hay ningún plugin anunciado, incluso si
identidades y geometría coinciden. Las pruebas exportan `center-composed.plist`
para cotejar la candidata sin confundir coincidencia estática con aceptación.

`tools/carplay/capture_events.py` captura por UID para seguir cambios de PID,
con comprobación del paquete exacto y soporte `--uid`. Solo guarda categorías
permitidas y números, descarta mensajes originales y señala interrupciones de
ADB separadas de cortes de CarPlay. El test de subprocesos reproduce salida de
logcat, nueva captura, cambio de PID, replay duplicado y mensajes sensibles grandes.
No se accedió al coche durante este trabajo local.

Validación local: CI completo pasó en 3 min 2 s; 3.234 tests Android, cero
fallos/errores y una omisión previa, más 16 tests de herramientas Python.
El contrato central compuesto coincide con los metadatos originales SHA256
084b6fddc2f56a034094f284d425b1046688ac727fcc15dccff675983b629511.
Los reportes privados son `center-plugin-verification.json`,
`center-composed-contract-check.json` y `center-plugin-ci.log`.
Candidata preparada, sin instalar: `DiPlay-Ultra-center-plugin-dev-20261019.apk`,
SHA256 0f18f8b60583b2c63b7e8ed982573f4bf366e2aaa853aee9e119411b97143d62.
La siguiente prueba debe capturar por UID antes de abrir DiPlay y distinguir
SETUP UI Sync, propietario, layout y Canvas de una mera capacidad negociada.

## 2026-10-19 — Canal real, respuesta de layout y regresiones visibles

La candidata center-plugin se instaló con autorización y se abrió con captura
por UID. A las 14:04:20.495 se observa SETUP UI Sync; a las 14:04:20.837 se adjunta
el propietario. El protocolo real completa el intercambio inicial, envía comando 5
con layout y recibe comando 6 cuyo layoutID coincide con el solicitado a las
14:04:21.410. A las 14:04:21.763 se registra Canvas aceptado. El usuario vio
«CarPlay Ultra» en el iPhone, pero en el coche vio CarPlay habitual, pequeño y muy
lento, sin nuevos widgets ni multipantalla. Estos resultados no equivalen a una
interfaz Ultra completa ni verifican aceptación/autenticidad del paquete.

A las 14:08:10.118 se registra cierre de control; el motivo queda sin clasificar
en la captura acotada. No se atribuye a los cortes previos sin evidencia adicional.
Se detuvo la captura; los eventos y metadatos sin payload quedan en
`center-plugin-live-events.jsonl`, `center-plugin-live-layout-observations.json`
y `center-plugin-live-result.json` privados. No se capturó una imagen del teléfono.

Se añadió una defensa contra mezclar geometrías: `sessionDisplay` solo conserva
áreas pendientes que coinciden con las realmente declaradas. Una revisión posterior
confirma que la rama Ultra ya limpiaba `pendingViewAreas`, por lo que esta defensa
no demuestra la causa del tamaño reducido observado por el usuario. Las regresiones comprueban que la
ruta ordinaria conserva sus áreas y que el centro llena el ancho de una ventana
2560×1176, con imagen 2560×960 centrada y márgenes verticales de 108 por su proporción.
Esto impide ese caso hipotético de encogimiento adicional; no cambia el formato original del recurso,
ni las barras del sistema, ni promete una foto idéntica a CarPlay ordinario.

La entrada GL hacía inversión de filas y conversión RGBA a enteros ARGB con
1.382.400 iteraciones Kotlin y un IntArray de ese tamaño por cada cuadro. Se mueve
la inversión al vertex shader y se copian los bytes RGBA directamente al Bitmap.
Se conserva la lectura GPU y el compositor CPU; no se afirma eliminada toda la
latencia. La APK exacta verificó cuatro cuadrantes/colores/orientación, composición
con recursos originales, pausa/adopción y retiro de recursos en Android 35 arm64.
No hay benchmark de FPS/latencia en el coche para esta corrección todavía.

El recurso central elegido contiene solamente Overlay y Wallpaper; seleccionar
Wallpaper nunca implementó nuevos widgets ni un destino adicional. Una interfaz
nueva y multipantalla siguen pendientes, ahora con evidencia real del canal y
del intercambio de layout. La captura por UID también conserva esas observaciones
como categorías/números, sin copiar layoutID ni mensajes originales.

CI completo de las correcciones: pasó en 3 min 31 s; 3.236 tests, cero fallos y
una omisión previa, más 17 tests Python. El SHA de la APK probada en emulador
coincide con la candidata conservada: 7bfb015d92b86ddb5b16613f6425bae0500edd31462014c67bdd4b91a4daed46,
`DiPlay-Ultra-geometry-readback-dev-20261019.apk`. Emulador cerrado. Esta candidata
no está instalada en el coche y su rendimiento físico sigue pendiente.

Por petición del usuario se consultaron únicamente metadatos de pantallas:
principal display 0/local:129, 2560×1440; acompañante display 3/local:130,
1920×720, área app actual 1920×576; instrumentos display 2/local:131,
1920×720; salida identificada HDMI display 4/local:4, 1920×1080. La asociación
del acompañante se comprobó con la actividad `com.santiago.passenger` en display 3.
El dato tentativo 1920×780 queda descartado. El 1920×720 del tema es su canvas
lógico y no debe confundirse con el área útil de la pantalla central.
Reporte privado: `geometry-readback-verification.json`.

## 2026-10-19 — Reutilización acotada de texturas por conexión

El compositor volvía a leer y decodificar cada PNG en cada cuadro: el mapa de
imágenes se limitaba a una llamada de render y el adaptador creaba un renderer
nuevo cada vez. La nueva sesión de dibujo mantiene una caché LRU de hasta 16 MiB
y 64 imágenes, ligada a un único lector del snapshot inmutable. No comparte
imágenes entre conexiones y conserva los límites por escena, incluso en cache hit.
Una imagen mayor que el presupuesto se usa sin retenerla. Solo se incorporan
texturas nuevas al terminar correctamente el cuadro; la expulsión ocurre después
del dibujo para no reciclar una imagen que otro nodo del mismo cuadro reutilice.

`UltraReferenceFrameDrawer` explicita propiedad y cierre. La fábrica de recursos
crea uno por conexión, los errores de construcción lo cierran y el pipeline lo
retira después de drenar el worker de composición. La caché no posee cuadros de
vídeo ni salidas Bitmap entregadas al host. El renderer sin caché conserva su
comportamiento anterior para consumidores aislados.

Regresiones verifican una carga entre cuadros, expulsión sin corromper nodos
posteriores, presupuesto, dimensiones, limpieza en error, cierre idempotente y
aislamiento entre renderers. La fixture Android usa la misma fábrica persistente
que Activity y exige exactamente un cierre del propietario de dibujo; verificó
píxeles, pausa/adopción y retiro de recursos con AVC generado y peer simulado.
Esta optimización reduce trabajo repetido; no demuestra aún mejora de FPS o
latencia física ni implementa widgets nuevos. El coche queda liberado para
SystemUpdate y no fue accedido durante este cambio.

El plan global se reconcilió con el canal/layout reales y añade una dependencia
explícita de encuadre/rendimiento al primer hito visual, que sigue pendiente.

CI completo aprobado en 3 min 38 s: 3.239 tests, cero fallos/errores y una omisión
previa; además pasan 17 tests Python. Prueba Android sobre la APK exacta completada
y emulador cerrado. Candidata privada sin instalar:
`DiPlay-Ultra-presentation-cache-dev-20261019.apk`, SHA256
78e8052e542040e841973793be8506070a6a6d9ef9356129b45ced29a3211812.
Evidencia: `presentation-cache-verification.json`, `presentation-cache-ci.log`
y `presentation-cache-android.log`. La siguiente prueba coordinada valida tamaño
y fluidez; no espera widgets nuevos ni multipantalla de esta candidata.

## 2026-10-19 — Integración funcional en paralelo y ampliación del objetivo

El usuario prioriza integración comparable a Aston Martin adaptada a Leapmotor:
Siri/climatización, batería real, navegación del iPhone en tablero, widgets y un
destino de entretenimiento/vídeo independiente para el acompañante. El plan deja
de posponer estos frentes hasta perfeccionar la presentación central. Tres
subagentes trabajaron en protocolo, acceso OEM y navegación/layout, sin coche.

### Navegación simultánea al centro Ultra

El binding ahora distingue `conventionalNavigation` de un cluster temático.
Mantiene configuración/URL/FPS propios de stream111 y `ALT_UUID`, sin plugin ni
propietario UI Sync para ese panel. Son alternativas excluyentes. El compositor
central compara su binding de recursos y valida el binding efectivo completo,
sin poseer el decoder auxiliar. Session/MediaEngine/Controller y Activity permiten
la ruta convencional cuando se declaró explícitamente; el cluster temático sin
renderer sigue descartado. Los controles de navegación vuelven a estar disponibles
para el stream convencional.

Activity solo conserva esa navegación experimental si la preferencia de mapa está
activa y `clusterDisplayConfig` obtuvo destino físico verificado; no anuncia el
fallback virtual al faltar la ruta. La prueba de Activity reproduce ruta C11
confirmada y caducidad tras BOOT_COUNT distinto. No se cambia la región720×720
del cuadro ni se añade un cluster Ultra completo. Hace falta probar apertura111
real y contenido del navegador del iPhone; no todos los navegadores están verificados.

### Datos del vehículo y temperatura desde el iPhone

UI Sync y Vehicle State pueden coexistir; se elimina la exclusión artificial entre
ambas capacidades. La variante C11 con opt-in Ultra habilita la oferta del protocolo
solo si existe snapshot real al configurar la conexión. El motor comprueba fuente
disponible y publica descripción de servicios reales. La batería SOC/autonomía
continúa por su ruta iAP2 existente; el esquema moderno confirmado cubre rango,
no una característica HVbattery inventada.

El servicio climático declara temperatura objetivo por zona solo con lectura,
límites, paso verificado y asiento físico explícito. Las escrituras se encolan,
tienen plazo/cancelación y responden éxito exclusivamente tras observación real
posterior coincidente. El adaptador OEM usa propiedad358614275, áreas49/68,
configuración Float y lecturas frescas. Defaults sin mapping/paso/permiso no
anuncian capacidades. El watchdog es independiente del worker de I/O.

Una prueba conjunta envía writeRequest, pasa por el protocolo, backend asíncrono
y controlador OEM simulado, comprueba el área física correcta y exige una lectura
nueva antes de writeResponse exitoso. Esto verifica el código local, no Siri real.
La app no instala automáticamente ese backend: CarProperty exige
CONTROL_CAR_CLIMATE y los brokers OEM examinados filtran permisos/paquetes. No hay
una vía autorizada para DiPlay confirmada ni paso real verificado. Esa dependencia
está abierta; no se enviaron órdenes al coche.

### Widgets y acompañante

El inventario original confirmó que Wallpaper no tiene zonas; OverlayCenter tiene
Settings/Camera, no regiones de widgets. El contrato `UltraWidgetPolicy` distingue
candidatos de solicitudes y rechaza zonas no implementadas, datos caducados o
esquema sin verificar. No envía ni dibuja widgets todavía. Carga, neumáticos y
carrocería tienen URLs originales confirmadas; no se inventa una URL de clima.
Los controles de temperatura usan el servicio climático. Las rutas CP semánticas
de batería se identificaron, pero no están conectadas al bridge ni a un gauge.
Véase [la matriz de layout y servicios](LEAPMOTOR_C11_ULTRA_WIDGET_LAYOUTS.md).

El acompañante requiere resolución de destino, sesión, entrada y audio propios.
El vídeo actual es un reproductor global sin selector de display y su disponibilidad
está ligada a BYD, no al C11. No hay rol Ultra de acompañante probado ni compatibilidad
universal de apps; las APIs públicas de vídeo de Apple tienen condiciones propias.
Véase [el estudio de entretenimiento](LEAPMOTOR_PASSENGER_ENTERTAINMENT.md).

### Verificación y candidata

CI completo pasó en2min55s:3.278 tests, cero fallos/errores y una omisión previa;
18 tests Python pasan. Se corrigieron errores de compilación de los tests nuevos
y una aserción antigua de identidad de objeto que ahora requiere igualdad del
binding. Los tests incluyen protocolo climático8, controlador8, reflexión3,
backend asíncrono9, cadena conjunta1 y política de widgets5, además de navegación
y negociación coexistentes. No se accedió al coche ni se alteró SystemUpdate.

Candidata privada sin instalar: `DiPlay-Ultra-vehicle-integration-dev-20261019.apk`,
SHA256 ddd65c0898b0679ae300393160138415b2bd0cdf0735d7b90314cba55dce7c0d.
Reportes privados: `vehicle-integration-verification.json`, `vehicle-integration-ci.log`.
La próxima validación física debe empezar por ruta de cuadro y stream111 simultáneo;
clima exige resolver primero acceso OEM/paso, widgets exigen layout/contenido real,
y acompañante exige una ruta de reproducción independiente.
