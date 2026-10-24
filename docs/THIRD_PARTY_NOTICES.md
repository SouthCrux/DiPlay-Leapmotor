# Credits and license notices

## Receiver

DiPlay is a modified version of [xcertplay by shilapi](https://github.com/shilapi/xcertplay). The upstream receiver is licensed under GNU GPL version 3; the full text is in `LICENSE` and the original README is retained in `docs/UPSTREAM-README.md`.

Upstream credits [LIVI](https://github.com/f-io/LIVI) and [Showcase](https://github.com/amineross/showcase) for protocol research. Existing source comments and attribution are preserved.

The bounded OPACK codec and offline vehicle-state prototype in `shared` follow
the wire format and message structures in LIVI revision
`4bcd1335c2daedba40640e6dc432cd2ed7acfaa4`, specifically
`native/livi-helperd/crates/livi-cp/src/opack.rs` and `vehicle_state.rs`.
`OpackTest` includes its reference register-response fixture. These adaptations
are GPL-3.0-only; LIVI's GPL v3 license is available at that revision and the
same license text is included in the repository's `LICENSE`.
`VehicleStateChannel` also follows that revision's `iap_tunnel.rs` package
framing, key labels, synchronous acknowledgments and asynchronous replies.
The handshake probe also follows that revision's `info.rs` display/panel bindings,
`ui_sync.rs` reset exchange, and `livi-runtime/src/ident.rs` / `bringup.rs` resource
announcement fields. The inspected Apple phone package supplies reference metadata
(asset ID/version, logical display names, geometry and density); its binaries and
scripts are not bundled or relicensed as project source.

## Home and settings UI

`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` adapts the palette, visual arrangement and interface copy of the [DiAuto project](https://github.com/shihabal3amri/DiAuto). DiAuto's source is licensed under AGPL version 3. The UI file is marked AGPL-3.0-only; its license text is included in `docs/licenses/DiAuto-AGPL-3.0.txt`.

## CarPlay icon

The unmodified icon was obtained from Apple's developer site at:

https://developer.apple.com/assets/elements/icons/carplay/carplay-96x96_2x.png

CarPlay and the CarPlay icon are Apple Inc. marks/assets. This asset is not covered by the project's open-source code license. Its use here does not imply Apple approval or certification.

## Runtime dependencies

The separate offscreen research host under `tools/carplay/runtime` builds against
[Luau 0.743](https://github.com/luau-lang/luau/tree/0.743), copyright Roblox
Corporation and contributors, under the MIT license. Its source/executable is not
bundled in ordinary Android builds. The build requires an explicitly supplied,
digest-pinned upstream archive and retains the upstream `LICENSE.txt` in its
source directory. A copy is included at `docs/licenses/Luau-MIT.txt`.
The optional desktop fixture rasterizer uses Pillow, under the HPND license.
Apple accessory scripts and artwork used as local research inputs are not copied
into the public repository, APK assets or relicensed as project code.

- AndroidX and Jetpack Compose — Android Open Source Project; Apache License 2.0.
- Kotlin standard library — JetBrains; Apache License 2.0.
- Bouncy Castle 1.79 — The Legion of the Bouncy Castle Inc.; Bouncy Castle license (MIT-style).
- JmDNS 3.6.3 — JmDNS contributors; Apache License 2.0.
- SLF4J — QOS.ch; MIT license.

Gradle dependency declarations and version catalog accompany the source. License files available in the resolved artifacts are included under `docs/licenses/dependencies/`.

## Native call echo cancellation

DiPlay vendors a subset of [SpeexDSP 1.2.1](https://github.com/xiph/speexdsp/tree/SpeexDSP-1.2.1) from Xiph.org Foundation and its contributors, including the acoustic echo canceller, preprocessor and FFT/filterbank support. It is compiled into `libspeex_echo.so` for the optional experimental **Call echo cancellation** feature.

SpeexDSP uses a BSD-style three-clause license. The full copyright notices, redistribution conditions and disclaimer are retained in [the vendored COPYING file](../shared/src/main/jni/speexdsp/COPYING). Component source headers retain their additional notices, including KISS FFT's Mark Borgerding attribution. The [vendoring notes](../shared/src/main/jni/speexdsp/README.md) identify the source version and build configuration.

## Software Opus microphone encoder

DiPlay vendors [Concentus](https://github.com/lostromb/concentus), Logan Stromberg's pure-Java port of the Opus reference library from Xiph.Org Foundation, Skype Limited, Microsoft Corporation and other contributors. It encodes the CarPlay microphone on head units whose Android has no MediaCodec Opus encoder.

Concentus uses the Opus BSD-style license. The full copyright notices, redistribution conditions and disclaimer are retained in [the vendored LICENSE file](../shared/src/main/java/org/concentus/LICENSE). The [vendoring notes](../shared/src/main/java/org/concentus/README.md) identify the source commit.

## Experimental authentication data

Historical upstream public preview APKs included an accessory certificate/key pair
recovered from public Carlinkit C2Air Allwinner V821 firmware during the owner's
local investigation. These are not newly generated Apple-issued credentials for
DiPlay and are not relicensed as project source code. Their continued acceptance
and suitability for general distribution remain unresolved.

The C11 `0.2.20-c11.20261024.4` public source-preview APK and source archive do
**not** include that identity or original private Ultra resource packages. Private
standalone development APKs may use explicitly selected local inputs but are not
published as this release's assets. Android APK-signing private keys are never
distributed.

## Download website

The static site layout, CSS and generator adapt DiAuto (AGPL-3.0). The AGPL license text is included with the source.

## BYD HUD maneuver icons

Required Notice: Copyright AndyShaman (https://github.com/AndyShaman/BYDMate)

The maneuver PNGs under `shared/src/main/assets/byd-hud-icons` were imported from BYDMate. Its PolyForm Noncommercial 1.0.0 terms and required notice are included alongside the assets. These files are separate from the project code license; upstream describes them as donor assets and their original provenance is not independently established. The validated DiLink5.1 windshield path uses factory turn codes rather than these images.

## Lucide sidebar icons

The C11 vehicle sidebar uses vector adaptations of Lucide 1.17.0
`battery-medium`, `route`, `thermometer` and `circle-gauge` icons. Lucide is
distributed under the ISC license. The original copyright notices, license and
disclaimer are retained in [Lucide-ISC.txt](licenses/Lucide-ISC.txt).
