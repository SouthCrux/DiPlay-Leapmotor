# Vehicle/settings entry: three separate paths

## Integration update, 2026-10-21

The new local increment connects the actual read-only vehicle producer to the
explicit C11 Ultra preparation path. It declares the fixed SETTINGS input only
when that connection owns a real producer. It uses the existing experimental
Ultra opt-in; ordinary CarPlay and a fresh installation do not activate it.
The installed stability APK `14450bee` predates this increment and is unchanged.
Earlier phone-only statements below describe that baseline.

`UltraReferenceCenterDraw.Session` records the Settings image token and scene
placement while rendering. The host makes it eligible for input only after a
successful Canvas submission and revalidation of the connection, attachment and
activation. An image being created or drawn is insufficient. A pause, surface
change, host replacement or retirement invalidates input immediately. The router
retains scalar geometry and tokens, not the borrowed source pixels or output bitmap.
Scene geometry is analyzed on the composition worker outside the pause/touch
monitor. An invalidation revision rejects a traversal that completes late, and a
concurrent regression proves pausing does not wait for that traversal.

Hit mapping follows the compositor's affine placement, anchor and rectangular
clipping. Actions require one fully opaque, axis-aligned Settings image and its
exact current source token. Later paint conservatively blocks actions; empty
gradients, transparent textures and shapes that paint nothing do not. Visible
Settings regions with ambiguous/occluded/transitional geometry consume gestures
without activating controls or forwarding a hidden touch to the iPhone. This can
temporarily make a partly covered panel unresponsive; it does not invent a hit area.

Gesture ownership also lives at the Activity level so a local DOWN cannot leak
its UP to the phone after fallback, connection replacement or pipeline closure.
A phone gesture cannot be stolen mid-sequence by a newly attached panel.
Multitouch, drags, changed pointer identity, Android CANCEL, invalid coordinates
and a changed image/placement cancel the local action. Source token checks remain
independent of the Canvas check.

The actual producer/renderer test exercises battery summary, detail and Back
using a supplied test snapshot. Separate geometry/gesture and host lifecycle
regressions cover accepted/rejected Canvas and invalidation during publication.
These are local tests with synthetic readings. No HVAC, roof or radio controls
are added. The actual Vehicle-icon callback and a visible/dismissible panel with
the phone still require physical correlation; generic requestUI/HOME routing
remains unchanged. Full required CI plus diagnostics passed3,599 JUnit tests,
zero failures/errors and one existing skip;126 Python tests passed. The archived
APK `DiPlay-Ultra-vehicle-panel-touch-dev-20261021.apk` has SHA-256
`bc964a64c156414eb7490435c4a655b01d6a8af7423b25d7fe44a306ca9f4151`.
It has not been installed; the installed stability baseline remains14450bee.

Initial local source review, 2026-10-20. This identifies an absent live renderer contract; it does not establish
which callback the owner's specific Vehicle icon emitted. No car connection, command, Kotlin
change, resource modification or build was performed. Radio is outside this review.

## Distinguish the entries before changing routing

| Entry | Existing route | What it does not establish |
| --- | --- | --- |
| OEM/car icon advertised by DiPlay | `AirPlayInfoPlist` sets `oemIconVisible`, label and images. Generic `requestUI` calls `onHostUiRequested`; `CarPlayController` launches Android MAIN/HOME | It does not open DiPlay Settings, a vehicle configuration activity or an embedded Ultra panel |
| Reference resource Settings overlay | Original UI Sync zone transition can present an OEM-controlled SETTINGS input stream | The resource's layout/zone IDs are not an Android package, launch Intent or granted vehicle-control interface |
| Climate app | Separate VehicleState climate capabilities and a working backend/data/command contract | A working Settings image stream would not provide HVAC commands, and telemetry alone does not implement climate control |

The owner clarified that the request concerns the Vehicle icon inside CarPlay Ultra. That fixes
the product entry being discussed, but does not establish which protocol callback it emits.
The icon/label alone is insufficient to select one of these paths. The ordinary `onHostUiRequested` callback currently loses the request URL by design; safe
metadata can discriminate known requests without persisting arbitrary URLs. This review does not
reroute unknown requests to a vehicle app or relabel the Settings zone as the owner's Vehicle app.

## Demonstrable missing Settings input

The selected reference is resource44C9FA31EC/version985, display `Center_Display`,1920×720.
Its Overlay layout is `BFB3D9B9-E393-546B-AC44-B21D6F5C1BF8`.

| Resource element | Exact declaration |
| --- | --- |
| Settings zone | `785E764A-6089-5106-BBDD-53A8AC49EE60`, frame135,0,1650,720 |
| Settings component | `6789D75F-D2F5-50D4-A7CA-7A99B75BA799` |
| Slot contract | `punchThroughType.type="setting"` |
| Original input kind | SETTINGS=1; OEM-controlled |
| Current declared input | `iPhoneVideo`, type3 only |

The original `Layout.lua` resolves `punchThroughType="setting"` by enumerating all input streams
of kind SETTINGS. With no type1 input, it produces no Settings gauge. `LayoutController.lua`
collects settings gauges into `_settingsInputStreams`; its start-zone-transition handler looks
up `command.data.presentStream` in that table and returns when absent. This is a concrete missing
contract, independent of the read/write permissions needed by whatever vehicle panel would supply
those pixels.

At the initial review the bridge's `configureVideoInput` always declared type3. The laboratory
increment below permits an explicit type1; the live selected entry still configures only
`iPhoneVideo` type3. The primary Wallpaper has no zones. Its successful layout reply/Canvas does
not demonstrate a functioning Settings overlay or another OEM application. Enabling a field or
registering an empty type1 stream would not implement the required image source.

Settings and Camera also remain distinct: Camera zone `1CB7221D-8CBD-4A4E-A07F-5BF201A31CDD`
uses punch-through identifier `camera`, not type-setting or a climate service.

## Small next executable step

1. Correlate an owner-selected Vehicle entry with bounded protocol metadata: generic/default
   requestUI versus a known Settings zone transition/presentStream. Do not log unknown URLs,
   arbitrary stream names, UI payloads or screen contents.
2. For the Settings path, record only declared SETTINGS-input count and whether a presentation
   target matches a declared Settings input. No source means unsupported; do not generate an
   empty video stream or silently claim an OEM panel.
3. If that path is confirmed, choose and implement a real source: an authorized OEM panel or a
   deliberately scoped local vehicle panel. It needs frame ownership/geometry, input dispatch,
   present/dismiss and pause/retirement behavior. Navigation/climate state remains separate.
4. Validate the actual entry and a visible, dismissible panel before expanding controls. Sensor
   caches, an Android HOME launch and a UI Sync acknowledgement are not substitutes for that test.

The existing safe live observations establish layout exchange/composition, but do not identify a
Vehicle-icon click as a Settings presentStream. This review therefore leaves request routing
unchanged and does not assert that the absent Settings source alone caused the owner's symptom.

## Exact source locations

Repository sources:

- `shared/.../airplay/AirPlayInfoPlist.kt:70`: OEM icon advertisement.
- `shared/.../airplay/AirPlaySession.kt:824`: requestUI diagnostic and callback routing.
- `shared/.../orchestration/CarPlayController.kt:427`: host UI callback launches MAIN/HOME.
- `tools/carplay/runtime/center_wallpaper_entry.luau:5`: only iPhoneVideo input configured.
- `tools/carplay/runtime/bridge.luau:396`: input metadata defaults to type3; the laboratory change
  accepts explicit type1 as its only alternative.

Previously prepared development resource sources in
`/private/tmp/diplay-ultra-runtime-resources/` (also under the packaged-development assets root):

- `r/PackageData.lua:62478`: center Overlay, Settings and Camera declarations.
- `InputStream.lua:14`: input kinds; `:141`: OEM-controlled classification.
- `Layout.lua:1646`: SETTINGS-input lookup; no match produces no gauge.
- `LayoutController.lua:442`: Settings-input collection; `:1414`: presentStream lookup/early return.

Evidence scope: `.private/c11/ultra-20261019/minimal-center-contract.json` documents the selected
Wallpaper and sole iPhoneVideo input; `center-plugin-live-layout-observations.json` records bounded
live layout events, without proving a Settings entry/presentation.


## Subsequent laboratory increment: original Settings gauge works with a declared source

`Offscreen.configureVideoInput(identifier,width,height,frameDuration,kind)` now accepts the optional
fifth argument1 (SETTINGS) or3 (iPhoneVideo). Omitting it preserves3. Cameras, widgets, road signs,
strings, booleans and other numeric kinds are rejected. This capability alone does not advertise
Settings in the live entry or attach an OEM image source.

The new **offline-only** `settings_input_entry.luau` declares a synthetic Settings source at
1650×720 in addition to the unchanged phone source. `probe_settings_input.py` verifies the pinned
prepared resource inventory before and after execution, runs the original controller unchanged,
completes its Wallpaper transition with a simulated peer, and then exercises its original
presentStream/dismissStream handlers inside transition markers. Phone-source PTS advances the
original animation clock; repeated task ticks alone are not substituted for time. Only the first
type3 input owns that clock. The fixture deliberately registers Settings first, and a dedicated
test proves that neither its offered PTS nor a later secondary phone input advances `CP.PTS`.

The actual laboratory run produced one Settings video gauge, initially hidden, then visible with
synthetic token101/PTS11.125 at effective opacity1, then dismissed/unsubscribed at opacity0. Its
native scene bounds are1650×720. Four dedicated tests passed (allowed/default kinds, rejection
of unsupported kinds, original controller present/dismiss, and Settings-first clock ownership). Four existing center-layout tests
also passed, including original Wallpaper/iPhoneVideo visibility. The older host-verified binary
predates the required scene opcode; the successful run used the existing scene-capable host.

Reproduce without connecting to a car or phone:

```sh
python3 tools/carplay/runtime/probe_settings_input.py \
  --host /private/tmp/diplay-ultra-scene-host \
  --resources /private/tmp/diplay-ultra-runtime-resources \
  --expected-archive-sha256 ddf1317ef051f8ac251c9d892b5ea0651a75e14dd59e0f5ac2ff13b37729d504 \
  --output /private/tmp/settings-input-report.json \
  --scene /private/tmp/settings-input-scene.json
```

Private report/scene: `.private/c11/ultra-20261020/settings-input-lab/`. The report records source,
entry, bridge and host hashes. This proves local gauge creation/protocol visibility/tokens only:
no OEM application, Settings pixels, physical display, Vehicle-icon click, phone acceptance or
complete Ultra app is established. The live entry, proprietary resource sources and routing are
unchanged. Real input-image production, rendering/touch ownership and entry correlation remain
required before this can become a functioning Vehicle panel.

## Bounded live observation for the next Vehicle-entry test

The script-owner diagnostic now distinguishes START_ZONE_TRANSITION requests with
`settingsStreamAction=NONE/PRESENT/DISMISS/INVALID/AMBIGUOUS`. It checks only command1's
`presentStream`/`dismissStream` fields, exports no stream names or URLs, and retains the existing
per-channel observation limit. Different presentation/dismissal shapes are not deduplicated
together. Malformed identifiers and simultaneous fields receive their own fixed classification;
the original controller still decides how to handle the command.

These are requested operations, not confirmation that an input was declared, handled, rendered
or physically displayed. The public capture parser accepts the fixed enum, rejects payload
suffixes/invalid combinations and continues to accept older logs as `UNOBSERVED`. The generic
requestUI/HOME route is unchanged. No launch Intent or new live Settings input was guessed.

## Coordinated verification and artifact

Full required Android CI plus diagnostics passed3,563 JUnit tests with no failures/errors and
one existing skip. The original-controller Settings suite passed4 tests, center regression4,
asset packaging12 and filtered capture37. The prepared APK is
`DiPlay-Ultra-vehicle-settings-contract-dev-20261020.apk`, SHA-256
`e6ced9da96f674ec31962332ea7178b90609b96a92965929c01f1c1f31df72c7`, **not installed**.
Its staged bridge matches the laboratory report hash and its live entry remains phone-only.
The original header resource is false. No car, phone, OEM command or radio action was used.

Next integration work needs an actual source for the Vehicle panel's pixels and touch handling,
with lifecycle/ownership and legitimate backend capabilities. The safe protocol observation
will identify whether the real Vehicle entry uses this Settings path when the car returns.
The laboratory input is deliberately not advertised as a working app.

## Local Android vehicle panel source (prepared increment)

`common/.../ultra/UltraVehiclePanelSession.kt` and `UltraVehiclePanelRenderer.kt` now provide a
separate read-only vehicle panel image source. It is independent of DiPlay Settings, OEM app
launching and the reference Lua resources. The constructor takes an existing `VehicleStatusProvider`;
that provider must only read its already leased cache and return null for absent/stale data.
The panel does not acquire telemetry or manufacture freshness timestamps.

The summary shows actual vehicle SOC/range with existing car iconography. Tapping its real
battery card opens the detail page; its Back control returns to the summary. Coordinates and
immutable hit regions refer to the exact returned bitmap. Unknown/invalid values produce a new
explicit unavailable bitmap with dashes; they do not leave the prior values on screen. No HVAC,
roof, radio, charging status, battery health or reserve warnings are invented.

`nextFrame(width=1650,height=720)` returns a newly owned Frame only when facts, page, dimensions,
locale or font scale change. It returns null for no change, so callers retain their prior image
without allocating a1650×720 bitmap for every phone frame. `invalidate()` requests retransmission
for a new protocol activation and retires old hit targets. `tap(frame,x,y)` accepts only the exact
current frame; old pages/images, invalid coordinates and closed sessions are rejected.

The caller owns each delivered Frame/Bitmap until Frame.close(), including during composition.
Session.close() retires input and rejects/recycles unfinished renders; it does not recycle pixels
already transferred to the compositor. A replaced image is closed by its owner after rendering
has finished with it. Source-provider ownership stays with the existing vehicle lease.

Two short panel strings (estimated range and unavailable data) have translations in all eight
locales. Existing Vehicle/Battery/Back labels and icons are reused. Native screenshot fixtures
cover825×360 and1650×720, font1.5, Arabic RTL, detail/back geometry and unavailable data, under
`common/build/reports/ultra-vehicle-panel/`. Lifecycle tests cover coalescing, stale input,
source invalidation, rendering completion after retirement and caller-owned pixel lifetime.
Root coordinates CI, image inspection, the shared Settings provider adapter and compositor wiring.
This source component alone does not establish that the phone's Vehicle icon opens the panel.
