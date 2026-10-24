-- SPDX-License-Identifier: GPL-3.0-only
-- Selected reference center only. Research initialization, never live acceptance evidence.
Offscreen.configureOutput("Center_Display",1920,720)
Offscreen.configureVideoInput("iPhoneVideo",1920,720,1/30)
Offscreen.startProtocolForResearch()
-- Declare the required local service with an invalid initial value, not a fabricated vehicle mode.
Offscreen.configureSceneLifecycle()
-- Read-only CP battery values start invalid. Only explicit local provider snapshots can fill them.
Offscreen.configureBatteryService()
assert(loadstring(readAsset("main.lua")))()
return "initialized"
