-- SPDX-License-Identifier: GPL-3.0-only
-- Disconnected synthetic-source fixture only. Never selected by the live Android entry.
Offscreen.configureOutput("Center_Display",1920,720)
Offscreen.configureVideoInput("SyntheticVehicleSettings",1650,720,1/30,1)
Offscreen.configureVideoInput("iPhoneVideo",1920,720,1/30)
Offscreen.startProtocolForResearch()
Offscreen.configureSceneLifecycle()
Offscreen.configureBatteryService()
assert(loadstring(readAsset("main.lua")))()
return "synthetic-settings-initialized"
