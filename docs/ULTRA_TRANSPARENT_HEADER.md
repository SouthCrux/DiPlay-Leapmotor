# Development variant: system-wallpaper Ultra header

Build the mobile development APK with `-PultraTransparentHeader=true`. The common resource and ordinary builds default to false. Only the explicit Leapmotor C11 profile uses the wallpaper theme and inline battery style. This is not a new Settings option.

The activity keeps `windowIsTranslucent=false`, so this is a wallpaper-backed opaque task rather than a window revealing the previous application. `windowShowWallpaper=true`, a transparent window background and `PixelFormat.TRANSLUCENT` request an alpha-capable window surface above the system wallpaper. The Android reference explicitly requires a translucent surface for `FLAG_SHOW_WALLPAPER` to have a visible effect:

- https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SHOW_WALLPAPER
- https://developer.android.com/reference/android/graphics/PixelFormat#TRANSLUCENT

The root drawable paints opaque black everywhere except the top letterbox, and opens that region only when all existing Ultra visibility/SOC eligibility checks pass and the real battery indicator fits there. System bars, cutouts and visible control exclusions stay opaque. With no valid battery, during connection preparation, menus, or ordinary CarPlay, the root is fully opaque again. The bottom and side letterboxes never become transparent.

The battery indicator becomes a single line (existing battery icon, localized vehicle battery label and real percentage), without capsule background or border and with a legibility shadow. It uses existing exclusion-aware placement restricted to the top letterbox. It hides when that area cannot fit the text at the current font scale/density. There is no fallback over video or into a bottom band. Zero percent remains valid; missing/invalid SOC hides the indicator and closes the alpha region.

Neither negotiated video dimensions nor fit, transforms, source pixels, touches, surface lifecycle, OEM bars, cluster navigation or Bluetooth launch preferences change. The existing baseline capsule remains available when the build property is absent/false.

No wallpaper contents, screenshot, personal image or file is read. Whether this firmware actually publishes a system wallpaper behind the task remains physical validation: an absent or black system wallpaper can leave the header visually black. Native screenshots demonstrate alpha against a synthetic transparent canvas; they do not prove an OEM backdrop exists. This variant does not promise transparent access to the OEM launcher or another app.

`UltraTransparentHeaderTest` covers theme task opacity, top-only placement, missing SOC, real zero percent, narrow/no-top-space hiding, exclusion opacity, header alpha, black bottom band, unchanged video pixels and compact/full/measured C11/Arabic/high-density layouts at font scale 1.5. Generated native PNGs belong in `common/build/reports/ultra-transparent-header/`. Root coordinates full CI and visual inspection before reporting the variant complete.
