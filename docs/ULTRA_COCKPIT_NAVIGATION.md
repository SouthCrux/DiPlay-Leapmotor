# C11 cockpit navigation

## Physical owner validation, 2026-10-20

The owner confirmed that turn instructions appeared on the instrument display on
installed510df783 with current-boot route2793. They were too small; a simplified
perspective route map was missing. This validates the semantic instruction path,
not a map, 3D view or overall session stability. The subsequent legibility and
per-stream decoder corrections are prepared in2a50aebf, not installed. See
[the current map gap and evidence](ULTRA_CLUSTER_MAP_GAP.md).


## Implemented increment

The C11's verified navigation tile can show DiPlay's interpreted next-turn card even when the iPhone has not started an alternate-screen map stream. Previously the C11 window required an active map stream, so real iAP2 guidance could remain invisible. The window now stays visible while either the map stream or a current semantic instruction exists. Clearing/expiring the instruction makes a mapless window transparent again. Dismissal retires its cached instruction and map-active flag; reopening cannot resurrect a black tile from a retired stream.

This uses the existing custom turn card: maneuver glyph, distance, destination road, roundabout exit and, when supplied, arrival time, remaining time and distance. It consumes structured iAP2 `0x5201`/`0x5202` updates through `CarPlayTurnOverlay`. There is no screenshot capture, OCR, navigation-app polling, or inferred road geometry. Which fields Google Maps supplies through the iPhone remains a physical validation item; the protocol path is not a promise of feature parity with its Android interface.

## Existing configuration and legitimate Ultra route

Use the existing Advanced dashboard map card and enable the instrument-cluster map. For the explicit C11 build profile, absent/unknown content selection now defaults to “map with custom turn card”; an existing valid driver selection always wins. Other profiles preserve their upstream defaults. The default does not enable the cluster itself or persist any preference. The content option remains manually selectable. Position, size, opacity and theme reuse their existing persistence. No new Settings category or duplicate switch is introduced. Enabling the cluster applies at the next connection through the existing reconnect notice; changing same-map custom-card selection is a live overlay change. This work does not alter connection/auto-launch preferences.

The current Ultra path already supports a separate conventional navigation destination:

1. `CarPlayHostActivity.clusterDisplayConfig()` validates the C11 firmware, current-boot route file, actual display mode and geometry. Its only physical window is the 720 × 720 navigation tile at x=1200, y=0 on the measured 1920 × 720 cluster display.
2. `airPlayConfig()` retains that cluster configuration during Ultra only when the reference center host exists and the destination is physical (`clusterStreamOnDisplay`).
3. `themeBinding.withConventionalNavigation(it.cluster)` assigns stream type 111 to independent navigation, rather than claiming a scripted Ultra instrument canvas. `suppressesConventionalCluster` is consequently false.
4. The custom card now remains usable if semantic guidance arrives without map video. It does not require invented map frames.

The change does not bypass Ultra owner checks. With no reference center host, a disabled cluster, an expired boot-specific route, missing overlay permission, or an unverified destination, the physical output remains unavailable. A successful build alone does not establish that the currently installed car session meets these conditions. A new boot requires route measurement/provisioning again; logical display IDs are not hardcoded.

The current car inspection found cluster enabled but `cluster_content` absent (the preceding candidate defaulted to `MAP`, which did not select DiPlay’s interpreted card), and a route file from boot 2758 against current boot 2791. Both blocked the preceding candidate: the exact current display must be revalidated/reprovisioned for the current boot. The new C11 default resolves the absent-content-key case without modifying the car’s preferences; an explicit MAP choice still requires a driver selection change to request an interpreted card. The scoped app API is `AirPlayPersistence.saveClusterContent(context, CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD)`; it saves just the content enum and notifies the live overlay listener. Do not rewrite an entire preferences file, which may contain unrelated private connection settings. This task did not change the car configuration.

The primary screen layout is unchanged. The overlay is nonfocusable and nontouchable and never extends into the OEM speed or warning regions. The caller's visibility gate also hides the semantic card. With only guidance active the tile has a plain background and the instruction card; it is not a reduced map.

## Source lifetime

`CarPlayTurnOverlay` uses the existing shared route parser. Arrival and an explicit session stop clear the instruction; cancelling navigation is subject to the parser's existing short grace period, empty maneuver lists have an eight-second grace period, and silent guidance expires after 120 seconds. Wireless interruption can retain the prior instruction until that expiry. That existing policy is not changed here and must be assessed during the physical navigation test. A replacement window does not inherit its retired window's instruction cache; fresh host publication is required.

## Requested features still requiring data contracts

- A real reduced 3D map needs the actual iPhone map stream or a licensed map renderer with real route geometry, camera and position. A single next-turn instruction cannot reconstruct streets or a map. The new fallback deliberately renders none of these.
- Cameras/speed cameras, speed bumps, accidents and highway service/exit cards require typed, current events from a real navigator/provider. The implemented iAP2 subset has no such verified events, so no incident cards or empty placeholders are shown.
- A perspective maneuver diagram could be added later as a clearly identified diagram; it must not be presented as a geographic map.
- User reference: https://photos.icloud.com/shared/album/0197OUjPDny-cjbaJA9tqiH7A . A 320 × 240 preview was subsequently available locally and inspected: Google Maps shows a real perspective map, a green next-turn banner with distance and road, and a smaller following instruction. This increment reuses the existing DiPlay card, so no visual match, following-instruction card, or 3D map is claimed.

## Validation

Regression cases in `LeapmotorClusterDisplayTest` cover fresh semantic guidance without a map, map stream loss, explicit visibility gating, route clear/expiry notification, a real map surviving turn clear, and retired-window state. The existing test suite also checks the bounded destination, firmware/boot/mode gates, permission and retired Surface callbacks.

No car installation or physical display test was performed by this task. Root coordinates the required full CI before reporting this increment as verified. Physical acceptance needs a current provisioned route, the custom-card configuration and a live navigation route; observe the actual turn/distance/road, stop navigation, and verify that the OEM tile returns when neither map nor guidance remains.

The root-coordinated full CI passed before the follow-up C11 default change (3,484 JUnit tests, no failures); the follow-up adds four profile/default regression cases and requires another full CI.

### Contextual vehicle fallback

A subsequent local increment adds a C11 traction-battery card only when neither a real map
stream nor current semantic guidance is active, and only for the custom-card content choice.
It consumes the host's already leased, validated SOC/range cache; it cannot acquire telemetry.
Navigation always takes priority. Five-second host-publication expiry, source-null updates,
hiding and window retirement remove the fallback. See
[ULTRA_CONTEXTUAL_COCKPIT.md](ULTRA_CONTEXTUAL_COCKPIT.md) for implementation boundaries and
synthetic screenshot fixtures. This does not implement the requested maintenance startup sequence.

### Separate center startup budgets

The reference-center host now bounds two distinct startup stages: up to ten seconds of usable
foreground time waiting for the UI Sync panel, then a separate ten seconds waiting for the first
accepted Canvas submission. Opening the panel alone no longer disables all startup protection.
The second failure is reported as `FIRST_FRAME_TIMEOUT`; this is a diagnostic recovery budget to
avoid indefinite black output, not evidence of acceptable rendering performance.

Both budgets pause while the host is backgrounded or lacks a usable, correctly sized surface.
A decoded video frame alone is insufficient. The first-frame phase completes only after output
publication succeeds and the connection epoch, attachment and activation remain current after
publication. An old frame, paused activation, replaced connection or already dispatched obsolete
timeout cannot cancel/fail its successor. There is no recurring watchdog after the first accepted
Canvas and no per-frame timer. Physical presentation remains unverified by Canvas submission.

If a timeout was already dispatched when the host paused, recovery is deferred until the next
usable foreground activation. Its exhausted budget resumes at zero; pausing does not grant
another ten seconds. A valid accepted Canvas arriving before that recovery still retires the
phase ticket, so the obsolete timeout cannot later fail the session.

### C11 turn-card legibility correction

The original custom-card geometry applied BYD full-cluster safe-area margins to the C11 window,
which was already cropped to its verified 720 × 720 navigation tile. This double restriction left
only 29% of the tile as the card's sizing reference. At the unchanged default 55% size, the result
hit a 150 × 58 pixel minimum: distance text was about 17 pixels and the road about 10 pixels.

The C11 overlay now explicitly selects a bounded-tile layout. The same 55% setting produces a
396 × 150 pixel banner, with 45 pixel distance and 25.5 pixel road text at font scale 1.0. C11 text
also follows the configured font scale; the 1.5 fixtures exercise larger text, long road names,
route totals and Arabic RTL placement. Existing persisted size/position percentages retain their
values and refer to the same tile; no preferences are overwritten and other car profiles retain
the upstream BYD geometry. Card and totals remain clamped to the existing tile. This improves the
interpreted instruction card and adds no route geometry, synthetic 3D map or incident data.

`LeapmotorClusterTurnCardTest` checks the geometric cause, all size/horizontal choices and
representative vertical positions, and generates native PNGs under
`common/build/reports/ultra-c11-turn-card/` (compact/full/Arabic and explicit size/position,
font scale 1.5). Root coordinates CI, image inspection and physical acceptance; these source
changes are not a claim of a new installed car build.

### Debug-only cluster buffer observation

The C11 debug app can take one in-memory 64 × 64 PixelCopy sample two seconds after each visible
stream/surface activation. It emits only this fixed metadata line through `DiPlay-Cluster`:

```
cluster buffer sample result=SUCCESS content=VARIED
```

Results are `SUCCESS`, `NO_DATA`, `TIMEOUT` or `ERROR`. Content is `UNIFORM_DARK`, `UNIFORM_LIGHT`,
`UNIFORM_OTHER`, `VARIED` or `UNKNOWN`. A sample is uniform when every RGB channel spans at most
8/255. It is dark only when all RGB values are at most 24, light only when all are at least 224;
other uniform colours use `UNIFORM_OTHER`. Nonopaque pixels make the classification unknown.
`VARIED` establishes variation only, not a geographic map, correct stream or physical presentation.

There is no PNG, hash, pixel/RGB output, location, screen identifier, new setting or source lease.
The image remains in memory and is recycled after success, error or stale completion. Hiding,
retiring or replacing the output invalidates pending work and callbacks. Release apps do not
construct the sampler: the guard uses the actual package's `FLAG_DEBUGGABLE` because this library
has no generated debug BuildConfig. There is no continuous frame polling or Z-order change.

[PixelCopy](https://developer.android.com/reference/android/view/PixelCopy) reads the most recently
queued Surface buffer. A varied sample combined with an empty composed display would support a
layer/composition investigation; a uniform sample would instead direct attention toward source
content or decoding/geometry. Neither result alone proves the cause. The normal
[SurfaceView](https://developer.android.com/reference/android/view/SurfaceView) contract places
its surface behind the parent and punches a transparent hole through the window, with ordinary
sibling views above it. A black parent background alone does not establish that it covers video.

### Local route-map input inventory and next discriminating observation

The currently decoded iAP2 `0x5201/0x5202` subset provides maneuver index/type, driving side,
road names, distance and ETA/trip totals (`BydHudRouteState` → `ClusterTurnGuidance`). Its current
DTO/parser has no route polyline, geographic position or map camera. This does not establish that
all possible iAP2 extensions lack such fields. The Android location provider does have position
and bearing, but sends them **to the iPhone** through the location/NMEA path; it does not return the
navigator's route. A GPS fix plus one maneuver is insufficient to reconstruct the route.

LeapMoto's isolated `Itinerary` contains provider/origin references and leg distances/times,
without a renderer-ready polyline or connected route provider. OEM navigation/parking protobuf
class names found in captured development APKs likewise do not establish an accessible, current
copy of the iPhone's route. No synthetic streets or navigation-looking 3D mock were added.

The existing real-map path is the iPhone-rendered stream111. Its navigator owns geographic
content, vehicle marker and perspective; the current implementation has a map zoom command but
no verified pitch/camera or route-geometry contract. Solving the map gap with that actual stream
remains the shortest implementation path to a real mini map.

One diagnostic ambiguity is now removed: an incoming `requestUI` for the **exact** known URL
`maps:/car/instrumentcluster/map` emits `airplay UI request target=MAP_CLUSTER`, rather than OTHER.
Query strings, fragments, whitespace, case variants and other URLs remain OTHER and are never
logged. This only classifies fixed metadata: the existing command still calls `onHostUiRequested`.
It adds no new routing, reconnect, automatic showUI or speculative bootstrap. Regression tests
exercise the real command handler and retain its response/callback behavior.

The next executable comparison is a new session with the candidate's per-stream decoder
geometry and debug buffer sampler: correlate MAP_CLUSTER requests, stream111 setup/processing,
PixelCopy content classification and the bounded display. If a MAP_CLUSTER request is observed
immediately before a flat buffer, investigate whether host-UI routing or an explicit map showUI
is required; the observation alone does not establish either. `initialURL` currently seeds an
internal displayed state, which is not phone acknowledgement. Changing that policy requires
protocol/session evidence and should stay fenced by stream generation and owner. No local
camera or fake route renderer is a substitute for this missing evidence.
