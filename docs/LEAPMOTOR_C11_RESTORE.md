# C11 installation inventory and recovery after a factory reset

This inventory covers this DiPlay integration only. It is not a complete recovery
plan for language changes, passenger-screen software or other modifications made
in separate projects. A factory-reset recovery has not been rehearsed. Recheck
firmware compatibility and actual Android user/display identifiers before applying
the procedure; do not assume the current identifiers survive a reset.

## What must be installed

| Component | Needed for normal DiPlay use? | Recovery action |
| --- | --- | --- |
| Private authenticated C11 DiPlay APK | Yes | Install the owner's verified APK in the active car user. Current development package: `com.shihab.diplay.hudtest`. Preserve its signing key for future updates. |
| OEM vehicle services and native SOME/IP library | Yes, for telemetry and the physical assistant button | Supplied by the compatible car firmware, not by a separate helper APK. Revalidate if firmware changes. |
| Local ADB hotspot helper | Yes for the tested automatic-hotspot path | Included in the DiPlay APK and run from it. There is no separate helper app to reinstall. |
| Telemetry service | Yes for battery/range and the button subscription | Included in the same APK, in its disposable `:c11telemetry` process. No second installation. |
| C11 Diagnostics (`com.southcrux.c11diagnostics`) | No | Optional investigation tool. Removed from the car after runtime integration. Its source remains available for future diagnostics. |
| UI dumps, probe output and preference-transfer copies in `/data/local/tmp` | No | Development artifacts; not installation dependencies. Seven identified files were archived privately on the Mac and removed. |
| Original DiPlay / AutoKit | Not a runtime dependency of this private authenticated fork | Separate apps retained for the owner's use. Restore independently if desired. |

The standard source-only build does not contain the owner's private authentication
material. Keep the authenticated recovery APK privately outside the car. A generic
APK downloaded from a public repository is not an equivalent replacement for that
private build.

## Restore sequence

1. Confirm the firmware version and the Android user that owns the visible car
   session. Regain the installation/ADB access used on this firmware, if needed.
2. Install the private authenticated DiPlay APK. Its bootstrap can recreate its
   local authentication files from the packaged private assets. Do not publish it.
3. Grant the permissions requested by the features in use: microphone and nearby
   devices/Bluetooth for CarPlay; location if forwarding car location; notification
   permission on platforms that require it. Allow modification of system settings
   for hotspot automation. Allow drawing over other apps for the cluster overlay.
4. Re-enable and authorize DiPlay's local ADB access for the tested hotspot startup
   fallback. DiPlay generates its own ADB key. This authorization is not restored
   by importing ordinary settings. If needed, re-enable the firmware's ADB TCP
   endpoint using the verified setup procedure for that firmware.
5. Reconfigure the car hotspot and reconnect/pair the iPhone. Restore connection
   mode, automatic hotspot startup and display preferences. The current choices
   are dock on the driver's side and both OEM system bars shown.
6. Restore the assistant-button preference if desired: one click = Default
   assistant (Siri on CarPlay), double click = ChatGPT. App launch remains an
   experimental feature requiring a physical-button test.
7. Re-measure and provision the instrument-cluster navigation-tile route before
   enabling that overlay. Its current route is firmware- and boot-bound and is
   intentionally excluded from portable settings. Never reuse an old logical
   display ID or redirect it to the passenger screen as a fallback.
8. Validate CarPlay video/audio, Siri, hotspot startup/recovery, SOC/range and the
   optional cluster tile separately. Ultra is not enabled; restoring this build
   does not restore a working Ultra multi-screen system.

## Settings backup limits

The current settings export includes selected connection/display preferences,
picture controls and hotspot-automation preferences. It deliberately excludes
phone pairing secrets, hotspot credentials, authentication material, local ADB
keys, Android permission grants and the boot-bound cluster route. At this revision
the separate dock and assistant preference files are not included in the export;
set those through the UI or restore the owner's private record. An exported
settings file alone is not a full recovery kit.

## Cleanup boundary

On 2026-10-17 the standalone diagnostic APK and seven identified DiPlay staging
files were removed. Other research files found in `/data/local/tmp` belong to
separate investigations (including language, clock, maps and maintenance work).
Their runtime dependencies have not been audited here, so they are not classified
as disposable merely because they are in a temporary directory. A complete
car-wide cleanup requires reconciling those projects with their own restore plans.

Keep the working DiPlay APK, its private app data, phone pairing, required ADB
authorization and permissions. Ordinary app-private caches can be reviewed later;
clearing the app's data is not a harmless cache cleanup.
