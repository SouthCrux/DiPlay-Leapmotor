# ADR-003: Latched video frames and source-clock composition

**Status:** Implemented for disconnected integration; live activation remains gated

## Decision

Use an optional, non-display `SurfaceTextureVideoOutput` as a decoder destination.
The decoder registers the phone timestamp before releasing a buffer and assigns
that buffer a unique, exact-in-Luau surface timestamp. After `updateTexImage`, the
consumer joins the actual latched buffer to that registration. A release callback
alone never establishes pixel ownership or presentation.

The GL worker applies the SurfaceTexture transform, reads SDR RGBA pixels and
transfers a copied frame lease. It permits at most three outstanding leases and
32 origin records. `LatestVideoFrameWorker` permits one active transaction and
one pending frame; replacing a pending frame closes it. Targets and their frame
workers belong to one session and must not be shared across phone sessions.
The decoder must retire before the target's GL resources are closed.

`UltraFrameCoordinator` serializes offer, script tick, state check, scene snapshot
and commit under the native VM lock. It retains the previous frame until successful
composition/commit. Deferred frames are canceled/dropped without replacing the
previous frame. Flush closes both leases. A failed composition retires the VM
instead of continuing with partially applied state.

The source bridge declares input dimensions and frame duration before the
original entry point enumerates streams. The inspected original center program
requires the exact input identifier `iPhoneVideo`. With a configured input, `CP.PTS`
starts at zero and advances only when a latched frame is offered for the primary
input. Protocol polls hold that clock. The clock never rewinds after cancellation.
`CP.getSystemUpTime` remains the host monotonic clock for protocol timers.
Unconfigured research probes retain their original monotonic clock behavior.

The native video layer exposes current/pending PTS, subscription, deferral and
flush. Its scene records the selected input, frame token and PTS. The Android
compositor borrows the exact frame requested by those fields, applies inherited
geometry/opacity and returns an independent bitmap. It does not own/recycle the
borrowed input. A successful composition is separate from receipt ACKs and from
physical presentation.

`UltraComposedPanelSession` is the single optional panel owner for both the
script protocol and frame coordinator. Message delivery/tick/outbox drain is
serialized with composition. Router shutdown waits for an active draw to finish,
then retires its retained frame and VM; a late offered frame is closed without
drawing. A protocol failure retires composition too. Reconnection requires a
fresh owner/runtime, allowing the new phone source clock to restart without
inheriting old frame tokens or watermarks. Transport/frame callbacks must not
reenter the router from a draw callback. Decoder, target and worker teardown
remain the application's responsibility; this owner does not yet wire them into
the Activity lifecycle.

The optional `CarPlayMediaEngine.ultraRendererFactory` now creates one renderer
per negotiated AirPlay control session. The selected owner's sink receives main
screen data and the same owner supplies UI Sync panels. Closing that connection
or disabling UI Sync retires its renderer; normal audio, alternate screen routing
and the default reset-only path keep their existing owners. No Activity installs
the factory yet. A failed experimental renderer requires a new connection; it is
not silently replaced mid-session with a fresh native protocol owner.

`UltraVideoPipeline` owns its Android decoder, `SurfaceTextureVideoOutput`, bounded
composition worker and combined panel owner. Close first stops publication and
pending composition, then closes the decoder. Actual codec-release notification
starts off-thread retirement, which waits for active composition before releasing
the panel/VM and GL input. Timeouts never free a surface still owned by a codec.
GL input failure stops new origins and notifies the owner rather than destroying
the codec's live destination itself. The pipeline has an optional failure callback
for its installer; reconnect/fallback remains to wire into the Activity.

`UltraSurfaceOutput` borrows an explicitly attached host surface, submits composed
bitmaps through Canvas and serializes detach with drawing. A late detach from a
replaced surface is ignored. Selected-layout/output dimensions must agree; this
class does not scale or change ordinary CarPlay layout. It never releases the
host's surface. Canvas submission is not physical-presentation evidence.

## Tradeoff and limits

CPU readback makes the first integration directly testable with the existing
Canvas renderer and keeps decoder/scene ownership explicit. It introduces a
copy and is not a performance result for the C11. GPU-native composition, HDR,
surface/session lifecycle and sustained full-resolution performance remain open.
The renderer's other unsupported operations remain rejected.

The bridge is incomplete and its original-program compatibility override remains
research-only. JNI retains a four-VM ceiling and bounded per-operation CPU/memory.
The default research lifetime and CLI still stop after 4,096 requests. Explicit
`SESSION_OWNED_INTEGRATION` uses a distinct native create entry and removes only
that cumulative counter; its owner must close on disconnect. It does not remove
the per-operation deadline, VM memory ceiling, message bounds or registry limit.
An older library cannot silently accept the new ABI call. This is an integration
policy, not an endurance/performance result or live-renderer approval. Normal application startup does
not install this path, and no reference geometry is forced on ordinary CarPlay.

## Evidence

- Session-owned JNI completed 8,192 desktop operations; the default research
  policy still retired on request 4,097. A timed-out operation terminated its
  session, and legacy/session-owned handles shared the same four-VM ceiling.
  The arm64 original-center check crossed 5,000 state queries before protocol,
  real generated AVC composition and retirement. These are counter/lifecycle
  checks, not wall-clock endurance or full-resolution car performance results.

- Native host tests cover offer/commit/defer/cancel/flush, stale tokens,
  backwards time and a held source clock.
- Kotlin/JNI tests verify lease lifetime, transaction failure and stale-frame
  rejection; renderer tests check actual pixels and borrowing semantics.
- A composed-owner JNI fixture routes a timestamped opacity change through
  UI Sync, distinguishes its immediate receipt from application at source time,
  and checks the resulting scene's frame token/opacity. It also tests disconnect
  during an active draw, protocol failure retirement, late-frame disposal and a
  replacement session with a restarted source clock. This fixture is not the
  original OEM transition implementation or evidence of live phone acceptance.
- Android 35 in an isolated emulator performed real AVC encoding/decoding of
  generated color quadrants, SurfaceTexture latching, exact origin association,
  JNI frame transactions and video/local-layer composition. A yellow native
  layer and the decoded quadrant colors were checked in the result.
- The selected original center controller subsequently requested Wallpaper and
  completed a simulated layout transition including `data.layoutID`. Its original
  scene then composed generated 1920×720 AVC through the same pipeline and Android
  renderer. Four quadrant colors and pipeline retirement passed; all 193 original
  Lua modules on the emulator matched the prepared manifest. This advances the
  original-controller evidence beyond the synthetic yellow-layer fixture, but
  still uses a simulated peer/lifecycle and no phone video or physical display.
- Three subsequent complete process cycles passed after listener removal,
  explicit GL completion and joining the GL worker were added to shutdown.
  An earlier process-exit run had emitted a native destroyed-mutex warning.
- A disconnected probe of the unmodified original center program completed UI
  Sync reset, requested its Wallpaper layout and held future transition metadata
  until the offered video's time window reached it. The same-layout Wallpaper
  region was intentionally ignored by that program; this is scheduling evidence,
  not proof of a visible new OEM layout.

None of this establishes iPhone resource acceptance, live Ultra, a working
cluster, vehicle widgets or presentation on a physical display.

Android's documented timestamp contracts are in the
[MediaCodec source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/media/java/android/media/MediaCodec.java)
and [SurfaceTexture reference](https://developer.android.com/reference/android/graphics/SurfaceTexture).
