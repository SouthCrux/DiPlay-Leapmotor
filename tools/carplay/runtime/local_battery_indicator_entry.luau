-- SPDX-License-Identifier: GPL-3.0-only
-- Disconnected fixture only. Original generic gauge, local wrapper, no Apple layout declaration.
local APL=require("APL.APL")
local AppearanceController=require("AppearanceController")
local Palette=require("Palette")
local ColorType=require("ColorType")
local Enums=require("Enums")
local VehicleDataUpdater=require("VehicleDataUpdater")
local LabelController=require("LabelController")
local ChargeFuelUtils=require("ChargeFuelUtils")
local MeasurementLabelConfiguration=require("MeasurementLabelConfiguration")
local MeasurementLabelGauge=require("MeasurementLabelGauge")

Offscreen.configureOutput("local-battery-fixture",320,120)
Offscreen.configureBatteryService()
APL.Asset.setManifest("AssetManifestData","r/Center_Display")
local updater=VehicleDataUpdater.new(CP.getVehicleDataConfig())
-- The original redaction path requests group rasterization, outside the current bridge subset.
-- This local adapter has no replacement value: missing CP data hides the entire local indicator.
local controller=LabelController.new(function(update,previous,identifier)
    local percent,unit=ChargeFuelUtils.chargeLevelForUpdate(update,previous,identifier)
    return if percent==CP.INVALIDVALUE then nil else percent,unit
end,"chargeLevel")
controller:setVehicleDataUpdater(updater)

local function color(value)
    return ColorType.fromJson({single={value={stops={["0.0"]=value}}}})
end
local palette=Palette.new("local-fixture","Local fixture",{
    text=color("#ffffffff"),unavailable=color("#888888ff"),
},{})
local appearance=AppearanceController.new(AppearanceController.State.new(palette,Enums.AppearanceMode.DARK))
local configuration=MeasurementLabelConfiguration.fromJson({
    font={name="default"},colorIDs={
        iconColorIDs={["0"]="text"},valueColorIDs={["0"]="text"},unitColorIDs={["0"]="text"},
        iconRedactedColorID="unavailable",valueRedactedColorID="unavailable",unitRedactedColorID="unavailable",
    },precision=0,divider=1,alignment=3,labelSpacing=6,valueYOffset=0,offsetType=1,
    statefulImages={},defaultUnitLabel="%",
})
local gauge=MeasurementLabelGauge.newGenericLabel(configuration,controller,appearance,"LocalBatteryPercent")
-- The reference default font is 118 logical pixels high. Use an explicit fixture scale so the
-- full glyph portals, including 100%, fit with margins instead of relying on clipping their ink.
gauge:setFrame(APL.Rect.new(0,0,560,160))

-- The main background stands in for a resource-owned root. The local indicator is a separate
-- sibling and may only occupy its explicit rectangle. No existing controller root is modified.
local wrapper=APL.Layer.new():setName("LocalCompositionWrapper"):setFrame(APL.Rect.new(0,0,320,120))
local base=APL.Layer.new():setName("SimulatedMainBackground"):setFrame(APL.Rect.new(0,0,320,120))
    :setBackgroundColor("#102030ff"):addToSuperLayer(wrapper)
local overlay=APL.Layer.new():setName("LocalBatteryOverlay"):setFrame(APL.Rect.new(20,20,280,80))
    :setMaskToBounds(true):setVisible(false):addToSuperLayer(wrapper)
local fitted=APL.Layer.new():setName("LocalBatteryFixtureScale"):setAnchorPoint(0,0)
    :setFrame(APL.Rect.new(0,0,560,160)):setTransform(APL.Transform.newWithScale(0.5,0.5))
    :addToSuperLayer(overlay)
gauge:getRootLayer():addToSuperLayer(fitted)
controller:setUpdateHandler(function(value) overlay:setVisible(value~=nil) end)
CP.setRootLayer("local-battery-fixture",wrapper:getNativeLayer())
updater:start()
return "local indicator fixture initialized; simulated input only"
