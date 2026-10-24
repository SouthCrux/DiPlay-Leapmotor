-- SPDX-License-Identifier: GPL-3.0-only
-- Initialization for --research-step-stream; no phone or physical display binding.
Offscreen.configureOutput("Center_Display",1920,720)
Offscreen.startProtocolForResearch()
Offscreen.setSceneLifecycleForResearch(0)
assert(loadstring(readAsset("main.lua")))()
return "initialized"
