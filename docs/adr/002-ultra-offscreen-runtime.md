# ADR-002: Source-compiled offscreen Ultra resource host

**Status:** Accepted for research; live integration remains pending
**Date:** 2026-10-18
**Deciders:** Implementation within the owner's authorization to continue Ultra integration

## Context

The iPhone has opened UI Sync and sent reset/reset-complete. DiPlay currently
answers those messages without a local renderer. Apple's reference accessory
archive contains source scripts, images and a native `CP` bridge requirement.
All 193 source modules compile unchanged with upstream Luau 0.743. This establishes
source syntax compatibility; it does not establish complete runtime compatibility.
The shipped opaque bytecode is not necessary for the source-host experiment.

## Decision

Build a portable Luau host from a digest-pinned source archive and compile the
selected accessory's source text inside it. First execute the host as a separate
bounded process. Implement a small explicit `CP` geometry/layer/image subset,
serialize its scene, and rasterize that scene through a bounded Android Canvas
compositor. Gate physical output on the bridge subset, video and timing behavior
required by an explicitly selected layout. Completing every CP API and every
vehicle service is not a prerequisite for a bounded first scene; unsupported
operations must remain explicit failures.

The host uses a 128 MiB VM allocation ceiling, a persistent three-second VM
deadline in one-shot mode (per request in explicit step-stream mode), bounded
module loading and dynamic source compilation, and an external
process timeout. Dynamic compilation is needed by the original easing-function
module; it accepts text only. Source compilation can allocate outside the VM
allocator, so the VM ceiling is not a whole-process memory guarantee. No opaque
downloaded bytecode is loaded. The source-only resource preparer validates ZIP
paths/limits, pins the selected archive digest and exports file digests. It does
not implement Apple signature verification or the AirPlay asset-transfer protocol.

## Options Considered

| Option | Complexity | Cost / consequence | Evidence |
| --- | --- | --- | --- |
| Translate scripts to standard Lua | Extra language adaptation and maintenance | Changes manufacturer code before recovering its behavior | Original scripts use Luau syntax; all compile with Luau |
| Load the shipped bytecode | Requires compatibility and validation of opaque input | Upstream warns its VM expects compiler-produced bytecode | Source counterparts are available for the inspected archive |
| Compile original source with a pinned Luau VM | Module loader, resource boundary and bridge are required | Native dependency plus a substantial CP API port | Source compilation and a 15-module layer/image probe pass on Mac and DiPlay arm64 |
| Draw a replacement dashboard around phone video | Different implementation and assets | Does not validate execution of the inspected local UI | Useful upstream precedent, but a separate product choice |

## Trade-off Analysis

This approach makes the script/bridge boundary executable and exposes unsupported
operations as errors. The initial scene is a test fixture with an actual resource
image, not an instrument layout. The bridge reports an incomplete version by
default. An explicit disconnected research flag can bypass the scripts' CP
version guard while logging that the bridge remains incomplete; results using
that flag are not acceptance evidence.
Canvas provides a testable Android raster path. GPU composition, video-frame
integration and frame-level UI Sync scheduling remain separate work.

## Consequences

- Resources and source compilation can be tested on the real SOC without replacing
  the installed CarPlay app or changing its session features.
- Shapes, gradients and coroutine scheduling now have a partial implementation.
  With the explicit research override and simulated UI lifecycle, the original
  center entry point runs 172 modules; its scene has no phone video. The cluster
  entry point fails when a cruise-control gauge requires absent vehicle
  controllers. Masks, filters, decoration, video and synchronized scheduling
  remain implementation gaps.
- No image of this fixture constitutes Ultra activation, a resource download,
  a working dashboard or live vehicle-data validation.
- A selected archive digest provides provenance for research, not manufacturer
  authenticity or current-time resource acceptance.
- Public builds contain neither the Apple archive nor the Luau executable/source
  dependency. The debug-only render entry point writes a PNG and opens no display.

## Action Items

1. [x] Compile all inspected original source modules with a pinned upstream compiler.
2. [x] Execute a bounded geometry/layer/image probe on the Mac and DiPlay arm64.
3. [x] Rasterize the resulting scene in Android outside physical screen output.
4. [x] Verify geometry, clipping, ordering, opacity, PNG handling and runtime limits.
5. [x] Execute the original center entry point persistently in disconnected research.
6. [ ] Choose a minimal viable layout and inventory its required CP operations,
   services, display geometry and lifecycle states; complete that subset.
7. [ ] Establish resource acceptance/provision requirements; implement transfer
   only if the selected real session requires it.
8. [ ] Join the selected layout to the app's real Android session lifecycle;
   JNI embedding, debug packaging and exclusive protocol ownership are verified
   locally, including execution under ART in an isolated emulator.
9. [x] Preserve the original video timestamp through decoder release in local tests.
10. [ ] Bind video textures/ownership and a shared clock; apply transitions on the
    intended frame and observe actual composed output.
11. [ ] Validate a real scene and reconnect/recovery with the iPhone before
    expanding vehicle telemetry and additional screen roles.

## Dependency reassessment, 2026-10-18

The Kotlin channel handler, native script host and Android compositor
must be joined into one end-to-end visual path. The host now has an optional
JNI embedding and debug APK packaging in addition to the external research
executable. Local tests exercise Kotlin → JNI → original-script protocol and
exclusive ownership across encrypted channel replacement. A debug entry point
loaded from the APK also ran the original reset/layout exchange through the
packaged library under Android 35 ART in a fresh, isolated emulator. It used
`app_process` and a simulated lifecycle, with no installation or physical screen.
The real app's resource/session lifecycle and live composition are still
unvalidated. The release-time callback
is optional/unbound and does not confirm physical presentation. Resource
acceptance and the reference cluster's unavailable controllers are separate open
gates; integration work must not assume they are solved by module/test counts.

Use one owner of reset, sequence numbers and receipt acknowledgments per panel.
When the original session is integrated, the outer adapter should route its
authenticated messages rather than running the Kotlin reset state machine in
parallel. The inspected original scripts queue transport ACKs before deferred
visual application; do not equate receipt with successful composition or impose
an incompatible presentation-ACK interpretation on that protocol. Application
and frame/presentation evidence must be tracked separately.

The minimal-layout capability matrix and video/frame composition remain required.
The JNI embedding retains the research step budget and incomplete-version
override; the normal app never installs its injectable script owner. See the structured
[dependency inventory](../../tools/carplay/ultra_plan.json) and the objective review
in [the status report](../LEAPMOTOR_C11_ULTRA_STATUS.md).

Sources: [Luau syntax](https://luau.org/syntax/),
[embedding constraints](https://luau.org/sandbox/),
[upstream 0.743](https://github.com/luau-lang/luau/tree/0.743),
and the private, digest-pinned Apple research archive documented in
[the Ultra status report](../LEAPMOTOR_C11_ULTRA_STATUS.md).
