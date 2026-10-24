# Offscreen accessory-resource host

Research tools for the script/native boundary. These tools do not implement a
complete Apple CP bridge, enable Ultra, download resources from the iPhone or
write to a vehicle display. `probe.luau` creates a fixture with original script
wrappers and a real image; it is not an instrument-cluster layout.

The bridge reports `0.0.0-diplay-offscreen` by default. The optional host flag
`--research-allow-incomplete-cp14` bypasses the original CP version guard only for
disconnected porting experiments. It does not establish CP 1.4.0 compatibility.
Under that explicit override, `entrypoint_probe.luau` executes the original center
`main.lua` and 172 modules with a simulated UI lifecycle. Its scene has no phone
video. The cluster entry point still fails because its cruise-control gauge
requires unavailable vehicle controllers. See [ADR-002](../../../docs/adr/002-ultra-offscreen-runtime.md).

## Build

Supply the [upstream Luau 0.743 source archive](https://api.github.com/repos/luau-lang/luau/tarball/0.743).
The build does no downloads and checks SHA-256
`ed14de4375864304295d0d02eca2fe409e3c8f5a018a2e0697cf639f2ae5bcc8`
before extracting. Work directories must be new. It invokes the selected compiler
and archiver directly, without executing upstream build scripts.

```sh
python3 tools/carplay/runtime/build_host.py --source-archive /path/to/luau.tar.gz \
  --work /tmp/ultra-build-host --output /tmp/ultra-host

python3 tools/carplay/runtime/build_host.py --source-archive /path/to/luau.tar.gz \
  --work /tmp/ultra-build-arm64 --output /tmp/ultra-host-arm64 \
  --android-ndk /path/to/Android/sdk/ndk/28.2.13676358
```

No Apple resources or runtime credentials are linked into these executables.
The build writes an adjacent `.build.json` with the target and binary digest.
Upstream Luau is MIT-licensed; retain its `LICENSE.txt` when distributing it.

## Research resources and execution

Select the archive explicitly and provide its independently recorded digest:

```sh
python3 tools/carplay/runtime/prepare_resources.py /path/to/accessory.zip \
  --expected-sha256 YOUR_RECORDED_ARCHIVE_DIGEST --directory /tmp/ultra-resources

/tmp/ultra-host /tmp/ultra-resources tools/carplay/runtime/bridge.luau \
  tools/carplay/runtime/probe.luau > /tmp/ultra-scene.json
```

Preparation checks ZIP bounds/paths and extracts sources, PNGs and plists; it
excludes `.luac`. The manifest records each extracted file's digest. This binds
the owner's research input, **not** Apple's signature or acceptance policy.
The current probe specifically expects the inspected `44C9FA31EC` version 985.

The host compiles source modules and caches their results. `CP` is a mutable
compiler global so frame time and foreground state are not cached as constants.
VM allocations are
limited to 128 MiB; module depth/count and source bytes are bounded. The default
one-shot mode has a persistent three-second VM deadline; the explicit step mode
below gives each request its own three-second deadline. Use an external process
timeout for total lifetime. Compiler allocations
are outside the VM ceiling. The runtime provides no OS/environment API or native
module loader. The only file reads are bounded resource reads within the selected
directory; symlinked resource components and relative traversal are refused.
The original easing module needs bounded `loadstring`; inputs are compiled as text,
never passed directly to the bytecode loader. Script logs are disabled.

`CP.createPlist` and `CP.parsePlist` now use a native bounded binary-plist codec.
Nested binary data remains an immutable userdata with byte equality and length;
UTF-8/UTF-16 text, booleans, exact signed integers within Luau's numeric range,
finite 32/64-bit reals, dictionaries and nonempty arrays are supported. Empty Lua
tables encode as dictionaries. XML plists, dates/UIDs, nonfinite numbers, explicit
null container entries, cycles and excessive graph expansion are refused. Input,
output and aggregate expanded payloads are bounded to 1 MiB, with 8,192 objects
and depth 32. These are research bounds, not a complete platform plist API.

`protocol_probe.luau` injects an explicitly simulated peer through
`Offscreen.setProtocolPeerForResearch` and executes the unmodified center main
entry point. It exchanges real binary reset/reset-complete messages and observes
the original scripts sending request-layout, allow-layout-change and give-focus
commands. Run it with the research override as the final argument. Its report
always identifies the simulated peer and disconnected phone. It does not apply
incoming visual transitions, bind video or validate activation on an iPhone.

## Persistent research session

`--research-allow-incomplete-cp14 --research-step-stream` keeps one VM alive after
`persistent_probe.luau` initializes the original center entry point. It accepts
structured steps, not executable source from its input. Initialization and each
request return a four-byte big-endian length followed by a binary plist with
`ok` and optional `data`. Requests have a one-byte opcode, four-byte big-endian
payload length, and payload:

| Opcode | Payload | Operation |
| --- | --- | --- |
| 0 | empty | Close |
| 1 | inner binary UI Sync plist | Queue incoming message |
| 2 | one byte, 1–120 | Run coroutine ticks; no display cadence is claimed |
| 3 | empty | Get research-labeled scene JSON as binary data |
| 4 | one byte, 0–4 | Set simulated UI lifecycle |
| 5 | one byte, 0–1 | Set logical offscreen foreground state |
| 6 | empty | Drain outgoing binary plists as an array |
| 7 | binary plist frame event | Offer/commit/cancel a latched video frame or query input state |
| 8 | one byte, 0–4 or 255 | Update explicitly declared local scene service; 255 invalidates an absent/stale value |
| 9 | binary plist, up to 1024 bytes | Deliver local read-only battery snapshot; omitted values invalidate them |

Opcode 8 requires `Offscreen.configureSceneLifecycle()` in the chosen entry,
before the original controller loads. The declared service starts at
`CP.INVALIDVALUE`; it does not infer a vehicle mode from foreground, video or
phone focus. Values 0–4 follow the selected package's NONE/STARTUP/DRIVE/EXIT/
ACCESSORY_MODE enum. The provider must verify its vehicle-to-scene mapping and
invalidate lost/stale observations. This API installs no provider and sends no
scene value to the iPhone. The original controller consumes the local service;
phone layout/focus responses remain a separate UI Sync contract. A successful
input call establishes delivery only, not actual vehicle mode or Ultra activation.

Opcode 9 requires `Offscreen.configureBatteryService()` before the controller
loads. The only fields are `chargePercent` (0–100) and `rangeKilometers`
(0–100000); absent fields become `CP.INVALIDVALUE`. The range bound is a local
input budget, not an asserted vehicle maximum. The CP contract is
`highVoltageBattery.batteryLevel.batteryLevel` and
`highVoltageBattery.batteryRemainingRange.distanceKM/distanceMiles`; identifiers
2/3/4 are local to this VM and are not Apple ASC identifiers. Distance units
151/164 come from the original `Units.Length`; miles are kilometers/1.609344.
No charging status, warning thresholds or writable characteristics are invented.
The managed session queries an explicit provider at most once per second on its
existing worker, and invalidates a failed/missing provider or local delivery older
than five seconds. `locallyReceivedAtMillis` describes delivery, not OEM sampling;
the provider remains responsible for rejecting stale source measurements.
The unmodified `VehicleDataUpdater` consumes the contract in a JNI test. This
does not add a visible gauge to Wallpaper, which has no instrument zones, or send
battery data to the phone through an unverified wire schema.

### Prepared resources in the Android host

Preparation now also writes deterministic `research-input.plist` alongside the
JSON report. Its hash must be selected independently of the input directory;
the archive hash written inside a replaceable manifest is not a trust anchor.
`UltraReferenceCenterResources.identity` pins both the selected archive and its
binary manifest, plus the reference asset/version/SDK/module count.

`UltraPreparedResources.prepare(source, privateParent, identity)` checks that
manifest and copies only listed Lua/PNG/plist files into a fresh private snapshot.
It bounds file count, individual and aggregate bytes, rejects traversal/symlinks
and bytecode, verifies file digests, and checks copied Info.plist identity.
`readPng` returns only listed image bytes and verifies their digest again; image
decoding and dimension limits remain in the compositor. The implementation needs
Android API26; the intended C11 path already requires API28 or later.

The local manifest parser has a separate 1MiB limit because this inventory is
larger than a UI Sync message. Network plist decoding retains its existing
256KiB limit and both readers keep object/depth budgets. A source-only fixture
and the real pinned reference are tested separately.

`openRuntime` consumes the snapshot and transfers its retirement to the native
runtime's close hook. Closing the resource object during an active VM does not
delete files under script or PNG reads; destroying the VM releases the copy.
Failed library loads/initialization and fatal native requests also retire it.
The caller supplies its app-private destination parent and must keep unrelated
writers out of the selected source. This is not a hostile shared-filesystem
importer or an Apple signature verifier. No resources or credentials are bundled
by this loader, and it installs no Activity renderer.

The original-center debug fixture now loads scripts and compositor PNGs from
the same snapshot. Its simulated peer/generated video remain research inputs;
the future application installer still needs resource delivery/selection,
Activity surface/fallback wiring and real phone acceptance evidence.

The transport queues allow at most 64 messages and 1 MiB per direction. The host
accepts at most 4,096 requests; invalid steps terminate the session rather than
reusing partially changed state. Each record/response is limited to 1 MiB. Scene
serialization uses the original `APL.json` module, so that operation requires the
prepared resources. Standard streams are the only IPC: there are no phone sockets
or physical-display bindings. The mode still requires the incomplete-version
research override.

`step_client.py` provides a synchronous, bounded Python client with response
deadlines and child-process cleanup. Run calls on a worker thread. Its integration
test completes reset and later requests the layout through separate IPC calls
without restarting the original scripts. On the SOC, the persistent smoke test
also observed appearance requests as the simulated foreground/lifecycle changed.
That test used a simulated peer, not the real iPhone.

An optional Pillow-based reference rasterizer runs on the desktop for scenes
without shapes or gradients (it explicitly refuses those operations):

```sh
python3 tools/carplay/runtime/render_scene.py /tmp/ultra-scene.json \
  --resources /tmp/ultra-resources --output /tmp/ultra-fixture.png
```

## In-process JNI embedding

The same bounded VM/step implementation can now build as a JNI library. No
subprocess or ADB console is needed by the Kotlin adapter. This remains an
explicit development input: ordinary app startup does not load it, install the
script protocol owner or advertise a compositor.

```sh
python3 tools/carplay/runtime/build_host.py --source-archive /path/to/luau.tar.gz \
  --work /tmp/ultra-jni-build --output /path/to/native/arm64-v8a/libdiplay_ultra.so \
  --android-ndk /path/to/Android/sdk/ndk/28.2.13676358 --jni

./gradlew :mobile:assembleDebug -PultraNativeDirectory=/absolute/path/to/native
```

`ultraNativeDirectory` affects debug packaging only. The current NDK builder
targets arm64; it does not produce libraries for the other app ABIs. Apple
resources, bridge/entry sources and authentication inputs are not embedded by
that switch. Debug packaging with this switch includes the Luau license from
`docs/licenses`; retain it when distributing the native library.

`UltraNativeRuntime` owns an opaque native handle, closes it on failed steps and
serializes calls off the UI thread. The native registry permits four simultaneous
VMs, rejects stale handles and defaults to the research limit of 4,096 requests per
VM. The explicit session-owned lifetime is described below; it does not turn a
fixed-delay protocol poll into a display clock or establish product endurance.

`UiSyncPanelRouter` preserves the inner binary plist while routing the outer
panel envelope. A selected script owner replaces the Kotlin reset owner for that
panel; they never both advance sequence/ACK state. Replacement/teardown closes
all old owners and cancels polling. The injectable factory in `CarPlayMediaEngine`
is not configured by normal startup. Video surfaces, synchronized transitions and
resource acceptance still gate a real session.

Desktop JNI tests use the actual native library, not a mocked native boundary:

```sh
python3 tools/carplay/runtime/build_host.py --source-archive /path/to/luau.tar.gz \
  --work /tmp/ultra-desktop-jni-build --output /tmp/libdiplay_ultra.dylib \
  --jni --java-home /path/to/jdk

DIPLAY_ULTRA_JNI_LIBRARY=/tmp/libdiplay_ultra.dylib \
DIPLAY_ULTRA_BRIDGE_SOURCE="$PWD/tools/carplay/runtime/bridge.luau" \
DIPLAY_ULTRA_ENTRY_SOURCE="$PWD/tools/carplay/runtime/persistent_probe.luau" \
DIPLAY_ULTRA_TEST_RESOURCES=/tmp/ultra-resources \
./gradlew :shared:testDebugUnitTest --tests '*UltraNativeRuntimeTest'
```

The original-script test uses an explicitly simulated lifecycle and disconnected
peer. Without the local inputs, the JNI tests are skipped; router and
encrypted-channel ownership tests still run normally. On Linux, use a `.so`
output instead of `.dylib` for the desktop library. Cross-compilation and APK
packaging do not establish successful execution under Android ART.

The debug-only `com.shilapi.xcertplay.airplay.UltraNativeSmoke` entry point checks
the original reset/layout exchange in-process under Android ART. It takes four
explicit paths: JNI library, prepared resources, bridge source, entry source.
The APK can be its `CLASSPATH` for `app_process`; installation and a CarPlay
session are unnecessary. `com.shilapi.xcertplay.ultra.UltraComposedSmoke` adds a
fifth path for an output PNG and passes the resulting scene to the existing
Android compositor. Both entry points explicitly use an incomplete bridge and a
simulated peer/lifecycle. Neither opens an Activity or physical display, and a
PNG does not establish video synchronization or a complete layout.

## Decoded video and frame transactions

Native lifetime policy is explicit. The default JNI constructor and stdio CLI
retain the 4,096-request research budget. `SESSION_OWNED_INTEGRATION` calls the new
`createSessionOwned` JNI entry point and removes only the cumulative request cap.
The same four-VM ceiling, allocation limits, per-operation deadline and message
bounds apply. The session/pipeline must close it on disconnect. The original
center debug check queries input state 5,000 times before protocol/video; this
crosses the old counter, not hours of wall-clock endurance. No Activity selects
this mode or the incomplete renderer implicitly.

The selected reference center candidate and its remaining activation gates are
documented in [the minimum layout contract](../../../docs/ULTRA_MINIMUM_CENTER_CONTRACT.md).
`probe_center_layout.py` verifies an explicitly prepared/digest-pinned source tree,
reads the original package metadata and exercises Wallpaper request/start/end
through the original controller with a simulated peer. It records frame/token and
effective video opacity; it does not infer pixels, resource acceptance or live Ultra.
`center_wallpaper_entry.luau` declares that candidate's exact input/output geometry.
The debug-only Android `UltraOriginalCenterCompositionSmoke` extends the check to
generated 1920×720 AVC and actual composed pixels. It requires explicit library,
resource, bridge, entry and PNG paths and does not install the path in the Activity.

`SurfaceTextureVideoOutput` is an optional, non-display decoder target. Register it
in `AndroidMediaSink.videoCompositionTargets` and provide its exact Surface as the
decoder destination. Other surfaces and mirrors retain the ordinary video path.
The decoder registers an origin before releasing a buffer; a unique timestamp
joins that registration to the buffer actually latched by the GL worker. The
consumer applies the texture transform and transfers an SDR bitmap lease. This
CPU readback path has not been benchmarked on the car.

Declare `Offscreen.configureVideoInput(identifier,width,height,frameDuration)`
before the original program starts; that program expects `iPhoneVideo` for the
main video input. Opcode 7 accepts `input` and an `action`: `offer` adds a positive
unique `token` and source-clock `pts` in seconds; `commit`/`cancel` identify the
pending token; `state` returns current/pending tokens and deferred/flushed flags.
With a video input configured, `CP.PTS` is held until a frame is offered, so
protocol polling cannot advance metadata using the host clock. System uptime
remains independent for protocol timers.

`UltraFrameCoordinator` owns offer/tick/snapshot/commit as one VM transaction.
`UltraComposedPanelSession` combines that coordinator with the script protocol
owner under one session lock. Router closure and protocol failures retire its
retained frame and native VM together; late frames are discarded. Each new
connection requires a fresh owner/runtime. JNI fixtures verify timed scene
changes, close during composition and a restarted source clock on replacement.
`UltraVideoPipeline` now owns the decoder, GL input, worker and panel together.
Its nonblocking close waits for actual codec release before destroying the input
surface. `CarPlayMediaEngine.ultraRendererFactory` is an optional per-connection
installer shared by main video and UI Sync, retired on connection closure.
`UltraSurfaceOutput` provides explicit Canvas output attachment/replacement and
detach without owning the host surface. The explicit packaged-development Activity path installs these through
`UltraReferenceCenterHost` when the C11 handshake opt-in is enabled. Ordinary
no-assets builds retain their previous path; actual phone acceptance, physical
output and sustained performance remain open.
`LatestVideoFrameWorker` bounds pending work and discards replaced leases.
`UltraVideoFrameSource` supplies the renderer's exact requested input/token/PTS;
borrowed input pixels remain owned by the frame coordinator. See
[ADR-003](../../../docs/adr/003-ultra-video-frame-ownership.md) for lifetime,
failure, clock and activation limits.

`com.shilapi.xcertplay.media.VideoCompositionSmoke` performs real AVC
encoding/decoding of generated color quadrants and checks latched pixels, their
origin and orientation. Its optional argument is an output PNG path.
`com.shilapi.xcertplay.ultra.UltraDecodedCompositionSmoke` takes JNI library,
prepared resources, bridge source and output PNG paths. It verifies real decoded
pixels through the JNI frame transaction and local-layer composition. These are
debug-only, disconnected tests; neither proves iPhone acceptance or presentation.

## Android rasterization

The debug-only `com.shilapi.xcertplay.ultra.UltraRenderProbe` entry point takes
three file paths: scene JSON, resource directory, output PNG. It calls
`UltraSceneRenderer`, opens no Activity and writes no physical display. A source-only
debug APK can be copied into a temporary directory and used as its `CLASSPATH`
with `app_process`; it does not need installation, authentication assets, or a
CarPlay restart. Use a process timeout and preserve the generated PNG/log separately
from screenshots of the running app.

`UltraSceneRendererTest` checks actual pixels using Android's native graphics
backend: nested anchors/bounds, rotation, clip inheritance, ordering, opacity,
PNG decode/tint/cache, shape fills/strokes and contour trimming, gradients and
refused input. The bridge also runs coroutine tasks and converts ellipse/arc paths
to cubic segments. Arc direction and conical-gradient conventions remain research
interpretations requiring comparison against the native renderer. The compositor
supports only the scene subset emitted by this bridge. Masks, filters, borders,
rounded corners, unsupported blending and GPU-native video textures remain
incomplete and are not silently accepted. Frame borrowing, source-clock windows
and composition transactions are tested with generated video; a live iPhone's
clock, sustained performance and physical presentation remain unverified.
No real vehicle telemetry is attached.

## Tests

No Apple archive is required for the host isolation tests:

```sh
DIPLAY_ULTRA_TEST_HOST=/tmp/ultra-host \
  python3 -m unittest discover -s tools/carplay/runtime -p 'test_*.py'
```

Without that explicit host path, VM tests are skipped; preparation tests still run.
Run the repository's full `AGENTS.md` command for Android changes. Keep the host
test results distinct from JUnit counts and from real-iPhone protocol evidence.
Set `DIPLAY_ULTRA_TEST_RESOURCES=/tmp/ultra-resources` as well to run the original
protocol integration test. It verifies all 193 prepared source digests before
execution. Apple resources are private inputs and are not included in the tree.

## Explicit development APK assets

`stage_android_assets.py stage --source PREPARED --output FRESH_DIRECTORY`
creates a verified, allowlisted `ultra/reference-center/` tree from the pinned
reference input, repository bridge and center entrypoint. `verify --directory DIR`
checks the complete staged tree, including source hashes and unexpected files.
No archive, credential material or proprietary resource is checked into the tree.

Pass both `-PultraDevelopmentAssetsDirectory=DIR` and
`-PultraNativeDirectory=NATIVE_DIR` to a debug build to package that selection.
Gradle verifies it before merging assets. With no asset property, the ordinary
build has no reference package; release source sets never include this directory.

`UltraPackagedResourceSource.prepare(context)` returns null when the selection
marker is absent. A declared but damaged package fails explicitly. A valid package
produces a center-only Wallpaper selection and a private, verified session copy;
its VM and PNG reader share that copy until teardown. This remains an explicit
research selection with a bridge version override, not proof of phone acceptance.
With the existing C11 handshake opt-in enabled, the Activity prepares this host
off the UI thread, configures its center-only binding and installs its renderer
factory. The controller owns the host across Activity replacement; Activities use
replaceable attachment handles for actual resume and surface callbacks. The
reference output has an exact 1920×720 buffer and does not share a decoder surface
with the normal sink. Failed initialization/composition or declined UI Sync latches
failure and rebuilds ordinary CarPlay rather than switching decoders mid-stream.
The opt-in preference is preserved. No-assets builds retain the handshake-only probe.

Packaged imports use OS owner/registry locks. New imports reclaim only recognized
abandoned owner directories; active sessions in this or another process remain
untouched. Legacy copies without ownership metadata are deliberately not removed.
The cleanup lease survives until native VM destruction. This is still a research
path: phone resource acceptance, real layout responses, physical presentation and
sustained car performance remain unverified.


`com.shilapi.xcertplay.ultra.UltraPackagedCenterCompositionSmoke` is a debug-only
entry point taking `LIBRARY APK FRESH_CACHE_DIR`. It loads assets directly from
that APK without installing it, creates the real host/pipeline, simulates the
current UI Sync peer and verifies generated AVC quadrant pixels via ImageReader.
It also pauses the host, adopts another borrowed surface and verifies resource
retirement. Its peer handles fresh resets during foreground startup and accepts
reset-complete/layout commands only for the current session. This is not a phone
or physical-screen test; its JSON records those limits explicitly.


The reference host now withholds Canvas publication until an authenticated UI Sync
router attaches its panel owner. `Attachment.isOutputReady` is set only after a
successful Canvas submission for that attachment/activation. Observer hints run
outside the host lock; the Activity posts them and checks both identities before
reading the current state. Decode/stream activity cannot hide its preparation UI.
Pause, detach, replacement, failure and panel closure invalidate readiness.

An internal development watchdog allows 10 seconds of resumed host time with a
usable surface while waiting for that channel. Pauses preserve the remaining
budget; channel opening or retirement cancels it. Expiry latches
`UI_SYNC_CHANNEL_TIMEOUT` and reconstructs ordinary CarPlay. This locally chosen
trial budget is not an Apple protocol deadline and does not establish why a phone
failed to open the channel. Reset/layout acceptance after opening remains separate.

### Synthetic Settings input contract

`Offscreen.configureVideoInput` accepts an optional fifth argument: SETTINGS `1` or phone video
`3` (default). No other kinds are supported. The live entry still declares only iPhoneVideo.
`settings_input_entry.luau` is an offline fixture and is not packaged as the live entry.

`probe_settings_input.py` verifies prepared sources before/after running the original controller,
then presents/dismisses a synthetic1650×720 Settings gauge using UI Sync transition markers and
phone-source PTS for animations. It checks the offered token and effective visibility, not pixels
or an OEM app. Run with the same explicit host/resources/digest arguments as `probe_center_layout.py`.
`test_settings_input.py` requires `DIPLAY_ULTRA_TEST_HOST` and `DIPLAY_ULTRA_TEST_RESOURCES` and
rejects unsupported kind values. See [Vehicle/settings contract](../../../docs/ULTRA_VEHICLE_SETTINGS_CONTRACT.md).

Settings registration order never assigns the phone clock: only the first type3 input owns `CP.PTS`.
The fixture registers Settings before iPhoneVideo; a regression also checks secondary phone inputs.
