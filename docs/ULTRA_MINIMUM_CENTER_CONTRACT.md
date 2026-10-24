# Minimum reference center contract

Selected on 2026-10-19 for disconnected integration: **Wallpaper**, primary layout
`9E19BC4E-5251-5821-A0BA-26479A79F722`, on `Center_Display` in reference resource
`44C9FA31EC`, version 985, minimum SDK 19.0. This is a reference package, not a
Leapmotor-specific theme. Selection does not establish iPhone acceptance.

## Scope and geometry

| Contract | Selected value | Evidence and boundary |
| --- | --- | --- |
| Display role | `centerConsole` | Read from the original `r/PackageData.lua`; no passenger or cluster role inferred |
| Output / input dimensions | 1920 × 720 | Reference geometry only; normal C11 CarPlay remains at its own negotiated dimensions |
| Reference density | 157 dpi | Metadata, not a physical C11 measurement |
| Phone input | `iPhoneVideo`, 1/30 second frame duration | Original `LayoutController` requires this exact input name |
| Primary layout | Wallpaper, zero zones | No instruments, gauges, DCA components or vehicle values are required by the selected primary layout |
| Startup overlay | Overlay, `BFB3D9B9-E393-546B-AC44-B21D6F5C1BF8` | The original controller also constructs Settings and Camera overlay zones; these are not enabled by this contract |
| Other resource displays | Cluster excluded from the first composition | Its instrument controllers are incomplete; the center test establishes nothing about them |

The original controller loads every layout for its selected display during
startup. Zero Wallpaper zones therefore does not mean an empty bootstrap or
unrestricted overlay support. The reference Settings input and Camera punch-through
are separate capabilities. The disconnected input list contains only `iPhoneVideo`.

## Required bridge and state matrix

| Area | Minimum requirement | Current evidence / remaining gate |
| --- | --- | --- |
| Resource host | Source module loading, PNG/raw reads, bplist and original package metadata | Digest-pinned, source-only preparation; source hashes checked before and after the host probe. This is not signature verification |
| Protocol | Original reset, sequence state, request-layout, metadata-transfer, start/end-layout and receipt ACKs | Original scripts own this state through the single panel owner. Simulated peer requests Wallpaper and sends end-layout with its layout ID |
| UI lifecycle | Foreground ownership and `uiControl.uiState.uiSceneState` | The explicit center policy combines a local request with resumed host/ready surface; JNI tests run it with the original controller. A raw scene provider is the alternative owner. Activity callback wiring has unit coverage; real phone responses remain unverified |
| Video | Enumeration, subscription/state, pending/current PTS, flush/defer and exact input token | Implemented in the optional pipeline and tested independently with a real decoder; selected-controller test uses generated AVC |
| Layers | Root binding, child order, bounds, anchor, position, transform, hidden/opacity and background colors | Android rendered the original selected snapshot with generated 1920 × 720 AVC and checked all four color quadrants; unsupported other layouts remain excluded |
| Timing | Held phone-source `CP.PTS`, task ticks, animation and protocol monotonic uptime | A latched frame drives composition; polling cannot substitute a host clock. An offered frame after the completed layout transition makes the original phone layer visible |
| Verified indicators | No nonempty required indicator set for Wallpaper | Bridge rejects nonempty verified elements. This cannot be extended to cluster layouts or mandatory vehicle indicators |
| Surface/session | One owner per AirPlay connection; decoder released before GL destination; stale callbacks rejected | Optional engine/pipeline and offscreen Canvas lifecycle tested; Explicit development Activity wiring, attachment replacement and full-stack fallback are implemented; physical validation remains pending |

### Local vehicle scene input and phone responses are separate

The selected original `Workspace.lua` describes its pending scene state as
vehicle-requested. `VehicleDataUpdater.lua` obtains it from the local CP service
path `uiControl.uiState.uiSceneState`; it is not an iPhone permission message.
The selected `Enums.lua` defines NONE/STARTUP/DRIVE/EXIT/ACCESSORY_MODE as 0–4.
`LayoutController.lua` requires that service. Its invalid-value handling maps an
unavailable reading to NONE rather than inventing a running vehicle mode.

This field requests a local UI scene; it is not by itself an ignition or gear
reading. Integration still needs an explicit, validated source or host scene
policy appropriate to the selected layout. A direct power-signal mapping is one
possible source to investigate, not a proven requirement of the protocol. Do not
expand physical vehicle telemetry merely to satisfy an assumed interpretation.

The selected source entry now declares this one service before loading the
original controller, with `CP.INVALIDVALUE` as its initial reading. Explicit
input opcode 8 updates 0–4 or invalidates with 255. Unlike the old research
setter, it requires a predeclared service. Missing is distinct from an explicit
NONE reading. The local instance ID 1 is a bridge ID, not a C11 property ID.
No other vehicle services, scene options or writable characteristics are supplied.

The optional pipeline can select a connection-owned scene input. Callbacks only
record the latest enum or null; the panel applies it with foreground state at a
serialized protocol/frame boundary. Disconnect closes that input. The default
pipeline does not select the service. No provider, vehicle-mode inference or
freshness timer is installed; a future verified provider must invalidate an
absent/stale observation. Host resume, ready surface, phone focus and video
reception cannot be used as substitutes for that vehicle-to-scene mapping.

The original center fixture and host probe supply a simulated ACCESSORY_MODE
through this explicit route. They establish that the original layout works with
the new input, not that the C11 is in that mode or that an iPhone accepted Ultra.
Acceptance/provision, UI Sync layout/focus responses and physical presentation
remain independent gates.

### Explicit accessory UI policy for this center

`UltraSceneInputMode.REFERENCE_CENTER_ACCESSORY` now selects a bounded local
policy instead of a vehicle-mode provider. This is an explicit request for the
selected Wallpaper's accessory UI. It does not assert that the C11's ignition
is in accessory mode. Ordinary startup keeps scene input disabled. An explicit
service provider is the alternative owner; the policy rejects direct enum writes
so two sources cannot compete for this service.

The policy starts withdrawn. `requestAccessoryScene(true)` records an intent
for this connection only. It becomes foreground/ACCESSORY_MODE only with both
resumed host and ready surface. Losing either condition yields background/NONE;
the intent can resume within that connection. Explicit withdrawal removes it,
and replacement connections inherit no request. Foreground and scene are applied
as one snapshot under the panel's VM lock. No script runs on a host callback.

The original workspace retains its last active scene while backgrounded.
Accordingly the owner drops inactive-policy frames before composition and the
pipeline checks an activation token before publishing a completed output. A
pause/resume or withdrawal during composition invalidates that output even if
the new state is active again. Already executing host-surface publication is
not canceled by this nonblocking callback; Activity integration must serialize
surface detach/replacement through `UltraSurfaceOutput`.

The pipeline restricts this policy to Center_Display/iPhoneVideo at 1920x720;
the caller must still verify/select resource 44C9FA31EC/985 and the specific
Wallpaper layout before constructing it. Geometry alone does not prove those
resources or phone acceptance. The original-controller JNI test requests the
selected layout from the policy with a simulated peer. The explicit development Activity installer now supplies real resume/surface
callbacks through replaceable attachments. Package acceptance and real transition
recovery remain open.

### Verified resource snapshot for the optional Android host

`UltraPreparedResources` loads an explicitly selected prepared source directory,
not the signed outer asset or the iPhone's cache. The reference identity pins
archive SHA-256 `ddf1317ef051f8ac251c9d892b5ea0651a75e14dd59e0f5ac2ff13b37729d504`
and binary-manifest SHA-256
`a76a3daf759b480e7c165d29fad162a79efaaf3f795ec55baf2cc3d7b7c49796`.
The manifest hash is checked before parsing, then all selected file hashes and
copied Info.plist asset/version/SDK are checked before any VM starts. Source
hashes are neither signature validation nor iPhone acceptance.

Only listed Lua/PNG/plist files enter a fresh session copy: at most 4096 files,
4MiB each and 128MiB aggregate, with bounded object/depth parsing, safe paths,
regular-file/no-symlink checks and no bytecode. Unlisted input files are not
opened or copied. The source is not changed. The prepared reference contains
193 Lua sources; its unsupported cluster PNG names are preserved as data without
claiming that the selected center renderer supports those layouts.

Native modules and compositor PNG reads use that same private copy. It survives
while its VM owns it and is removed on close, failed initialization or fatal
request. A second runtime cannot claim the same snapshot. The original-center
debug path is adapted to this loader; tests also exercise the original controller
and image access through JNI on the host. An explicit development APK can now carry the verified reference package.
`UltraPackagedResourceSource` selects only the pinned central Wallpaper and imports
allowlisted files into that same private copy; an absent package returns no
selection, while a damaged declared package fails. The default/release build does
not include these assets. The development Activity/factory path uses this selection.
OS leases and a registry lock now reclaim recognized abandoned owner directories
while preserving live sessions in this or other processes; the lease remains until
native VM destruction. Legacy directories without ownership metadata are left
untouched. Physical Android/phone validation remains pending.

An ACK alone does not prove layout application. The original controller's
`end-layout` handler needs `data.layoutID` to notify its workspace which layout
completed. Earlier transition probes supplied regions without that ID and did
not establish remote-video visibility. The selected probe checks the original
request, the commands, source frame/token and effective video opacity separately.
Wallpaper has no local instrument zones, so this does not demonstrate an OEM
zone animation, gauges or useful vehicle widgets.

## Reproducible checks

`tools/carplay/runtime/probe_center_layout.py` takes an explicitly selected host,
prepared source directory and archive digest. It executes the original metadata
module, rejects a changed resource profile/geometry or a primary layout with
additional zones, verifies the prepared files, and runs the original center entry
with a simulated peer and lifecycle. The report and scene are written to explicit
output paths. The tool never connects to a phone or car.

`UltraOriginalCenterCompositionSmoke` is a debug-only Android check. It uses the
selected entry source, original controller and original 1920 × 720 geometry with
real generated AVC encode/decode, latched-frame tokens and the Android compositor.
It checks the resulting red/green/blue/white pixels and retires its pipeline.
It does not display a physical surface or use video from an iPhone.

The Android check passed on 2026-10-19 in the isolated Android 35 emulator. All
193 original Lua modules in the emulator matched the prepared-source manifest.
The host regression suite passed 27 tests, and Android CI (unit tests, lint and
debug builds) passed. The emulator was closed without accessing the car.

Private reports and images belong under `.private/c11/ultra-20261019/`; public
source contains neither the proprietary package nor authentication material.

## Gates before claiming real Ultra integration

1. Establish resource acceptance/provision with the real iPhone. The inspected
   signature leaf's historical expiry and the delivery/cache contract remain open.
2. Validate the explicitly selected central topology with the iPhone. A center-only
   binding now emits a single stream/panel without a cluster capability. The
   no-assets C11 probe retains its previous two-display binding. The explicit
   packaged development build selects the center-only binding when the existing
   handshake opt-in is enabled. See [resource/topology evidence](ULTRA_RESOURCE_NEGOTIATION.md).
3. Validate genuine Activity visibility and surface readiness through the prepared
   per-connection mailbox. Its callback only stores a conflated snapshot; the
   panel worker applies it before protocol or frame transactions. Both resumed
   host and ready surface are required for foreground. Focus alone is not used,
   and retired owners discard late callbacks. This reports only local output
   visibility; it does not infer `uiSceneState`, select ACCESSORY_MODE, request
   phone focus or recover the app after the car's camera. Select the explicit
   center policy or a verified local provider, and validate the phone's
   layout/focus response separately. Retain ordinary CarPlay geometry and route on
   fallback. Surface dimensions must agree with the selected layout rather than
   silently scaling the normal view.
4. Select the explicit session-owned runtime policy only with correct teardown,
   then validate sustained use. It now crosses the old research request counter
   without removing operation/memory/VM limits. CPU readback at reference
   resolution remains an integration check, not a car performance result.
5. Capture a real layout request/response, video-origin/metadata application and
   physical output, then repeat after reconnect. Only then expand vehicle signals.


### Explicit Activity development path

The existing C11 handshake opt-in plus explicitly packaged assets prepares the host
off the UI thread before starting the controller. The host supplies the same
central binding to configuration and renderer installation. Main110 decodes into
its GL input; only the composed output borrows the physical host surface. No alt
stream is attached to that surface. TextureView buffer size or SurfaceHolder fixed
size is set to1920×720 for this trial; the ordinary fitted view remains responsible
for display/touch coordinates. Ordinary CarPlay restores its own geometry on fallback.

The controller owns the host beyond Activity destruction. New Activities replace
attachment handles; late callbacks cannot detach the new surface. Host pause and
surface replacement invalidate publication eligibility without running scripts on
the UI callback. Missing UI Sync, binding mismatch or renderer failure latches the
trial failure and rebuilds the complete ordinary connection. It preserves the
user opt-in and does not switch a live decoder in place. Unowned prepared candidates
close off the UI thread. Last-picture holding is excluded from this composed path.

This explicit research path still uses the incomplete bridge override. It is a way
to attempt and observe the real protocol, not evidence that the iPhone accepted
resources or that Ultra has appeared on the car display. Sustained performance,
phone provisioning/layout responses and actual camera interruption recovery need
real-device validation.
