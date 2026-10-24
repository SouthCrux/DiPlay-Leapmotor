# Leapmotor C11 development preview — 0.2.20-c11.20261024.4

> [!WARNING]
> # NO PROBADO CON ANDROID AUTO
> # NOT TESTED WITH ANDROID AUTO
>
> No se realizaron pruebas con Android Auto. No afirmamos compatibilidad,
> coexistencia ni ausencia de regresiones con Android Auto. Las pruebas físicas
> descritas corresponden a Apple CarPlay en un Leapmotor C11 concreto.

Date: 2026-10-24. **Prerelease / experimental development snapshot.**

| Item | Value |
| --- | --- |
| Repository | SouthCrux/DiPlay-Leapmotor |
| Branch | `leapmotor/c11-preview-20261024` |
| Release tag | `v0.2.20-c11.20261024.4` |
| APK versionCode | `44` |
| APK versionName | `0.2.20-c11.20261024.4-hud-test` |
| APK package | `com.shihab.diplay.hudtest` |
| Upstream base | `0.2.20`, commit `1b1ac5f27986ce83b6d17fdd8c4d1be852fbbb70` |

This is the accumulated C11 development work, not a claim that latest upstream
main has been merged. C11-specific telemetry targets the inspected Android12
firmware. Generic APK minimum SDK support does not establish C11 compatibility
on other Android/vehicle versions. DiLink is BYD-specific and is not the C11 platform.

## Delivered behavior and owner observations

- **Clara vehicle sidebar:** outline icons, OEM traction-battery percentage,
  reported range in km, exterior temperature in Celsius, four tyre pressures in
  kPa and per-wheel temperatures. Axle labels place `(kPa)` beside front/rear.
- **Trip consumption:** uses the OEM current-trip measurement and documented
  kWh/100km presentation unit. Zero is valid; negative, nonfinite or unavailable
  values are hidden. No consumption is derived from battery percentage. Physical
  consumption comparison is still pending.
- **Instrument navigation:** a measured720x720 tile on the verified physical
  cluster display preserves the OEM instruments. Owner accepted the map/card
  positioning and observed Google Maps/Waze. Support for every navigation app
  is not claimed.
- **Cluster recovery:** stale boot-bound routes are remeasured through an
  already-approved local ADB key. A fresh physical `local:131` identity, logical
  display, mode, geometry and boot are verified before atomic replacement. No
  fallback to passenger/HDMI. The owner vehicle's next real boot automatically
  renewed the route and received stream111 frames; subsequent visibility still
  requires normal owner acceptance.
- **Media controls:** native car Bluetooth retains media-control ownership,
  avoiding competing DiPlay controls/double play-pause. The owner confirmed
  next/previous/play-pause worked. The wheel Siri trigger was also confirmed.
- **C11 update channel:** incompatible official-upstream update prompts are
  suppressed for the C11 development profile; other profiles retain their updater.

These are observations on the development vehicle, not a broad compatibility
certification. Version44 itself has local build/check validation; no new physical
acceptance of the version44 public package is claimed.

## Read-only integration and diagnostic preparation

Ten modules contain bounded decoders, per-field source epochs/expiry and local
diagnostics: charging; doors/locks; windows/roof/mirrors; seats; lights; energy/
trips; wipers; driving; warnings; climate. The prepared catalogue has83 INT32 and
8 DOUBLE signals, separate from earlier SOC/range/exterior/tyre readers.

A passive startup capture recorded raw observations for75 channels across nine
modules. This is **not** confirmation of75 correctly interpreted measurements,
sensor health, equipment presence or controllable functions. Some values remain
unknown/sentinels. Wiper warnings are not normal wiper speed/AUTO/rain sensing.

The optional C11 diagnostics app includes manual read-only probes and experimental
camera raw-sample tooling. No working CarPlay camera view, camera format/geometry,
OEM coexistence or safe general camera integration is claimed. Diagnostic tools
are not automatically started by opening DiPlay.

## Experimental work and unresolved issues

- **Siri vehicle queries:** iAP2 already sends real C11 SOC/range using the
  upstream generic EV transport. The separate experimental AirPlay vehicle-state
  description exposes range. Neither delivery nor an opened channel establishes
  Siri acceptance. Historical Ultra subscription logs and one owner-reported
  partial range response exist; the cause has not been reproduced. No promise
  of battery/range/exterior/tyre answers, in either CarPlay mode or offline.
- **Climate control:** passive observations exist, but no production broker is
  registered to expose validated C11 actuation. An earlier20→21°C shell trial
  was unconfirmed and produced no owner-visible change. It does not demonstrate
  universal HAL impossibility. Climate/radio capabilities must not be fabricated.
- **CarPlay Ultra:** opt-in negotiation, rendering/resource/session scaffolding,
  isolated native-runtime research and tests are included. This is not a complete
  certified Ultra implementation. Private phone resource packages are excluded.
- **Wireless:** foreground/background-session hotspot recovery and bridged-idle
  timeout work are included. Local ADB approval was verified. Full recovery after
  a physical AP-off test and recurring Wi-Fi/Bluetooth disconnects remain open.
- **Passenger display, EV assistant and camera work:** source research exists;
  no complete live product is claimed. The EV planner needs real providers and
  verified inputs; fixtures are simulated.
- **SiriKit companion probe:** preserved under `tools/siri-probe` for history.
  It is shelved because the required solution is native DiPlay/CarPlay without
  another iPhone app. No iOS installation or Siri invocation was performed.

## Public APK and installation requirements

The release's `DiPlay-Leapmotor-C11-0.2.20-c11.20261024.4-public-source-preview.apk`
is built **without** `DIPLAY_AUTH_ASSETS_DIR`, `ultraNativeDirectory` or
`ultraDevelopmentAssetsDirectory`. It contains no private accessory identity or
original Apple Ultra resource package. Configure a separately provisioned
authentication method before expecting CarPlay connection. The private standalone
development APKs used on the owner vehicle are not published as release assets.

The HUD Test package installs separately from stable DiPlay. Upgrading an existing
HUD Test installation requires the same Android signing certificate. Existing
runtime/authentication configuration must be checked against this public build;
an update does not preserve assets embedded only in an older APK. Do not uninstall
an accepted working installation solely to bypass a signature mismatch.

Source and source archives exclude local keys, logs, captures, pairing/hotspot
settings, OEM decompiled APKs, original Apple packages and personal evidence.
GPL/AGPL and third-party notices remain in the repository; Lucide icons and Luau
research retain their corresponding license notices. No Apple/Leapmotor/BYD
endorsement or Apple certification is implied.

## Validation and rollback

The required Android CI command tests shared/common/home and runs mobile/home/
maphost lint and debug assembly; the diagnostics module adds tests/lint/assembly.
The workflow also checks the public source tree and filtered Python diagnostic/
EV-planner tests. Exact run results and artifact SHA-256 are attached as release
validation metadata. Swift core checks cover the source-only companion fixture;
they do not validate iOS, Siri or CarPlay.

No vehicle is accessed or updated as part of publishing this release. Retain a
known working APK and private configuration backup before any later installation.
If connection, audio, map routing or telemetry regresses, stop that test and
return to the previously accepted signed package/configuration. Firmware changes,
ADAS changes, root remounts and writable vehicle controls are not part of this
release procedure.
