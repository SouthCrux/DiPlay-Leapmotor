-- SPDX-License-Identifier: GPL-3.0-only
-- Executes the original entry point in a disconnected, offscreen research environment.
-- Requires the explicit host --research-allow-incomplete-cp14 flag. Never use for acceptance.
Offscreen.configureOutput("Center_Display",1920,720)
Offscreen.setSceneLifecycleForResearch(0)
local main=assert(loadstring(readAsset("main.lua")))
main()
Offscreen.setOutputActive(true)
Offscreen.setSceneLifecycleForResearch(require("Enums").UISceneState.ACCESSORY_MODE)
Offscreen.runTasks(5)
return require("APL.json").encode({fixture=true,fullUltraUI=false,originalEntryExecuted=true,
    incompleteBridgeOverride=true,phoneConnected=false,vehicleTelemetryAttached=false,simulatedSceneLifecycle=true,
    scene=Offscreen.snapshot()})
