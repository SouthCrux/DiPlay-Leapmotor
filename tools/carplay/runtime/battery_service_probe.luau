-- SPDX-License-Identifier: GPL-3.0-only
-- Offscreen contract fixture: simulated values, no phone, car, or visible widget.
Offscreen.configureBatteryService()
local VehicleDataUpdater=require("VehicleDataUpdater")
local updater=VehicleDataUpdater.new(CP.getVehicleDataConfig())
local function values()
    updater:_fetch(false,false)
    return updater:getData({"chargeLevel","remainingBatteryDistance"})
end
local initial=values()
assert(initial.chargeLevel==CP.INVALIDVALUE and initial.remainingBatteryDistance==CP.INVALIDVALUE)
Offscreen.updateBatteryService(CP.createPlist({chargePercent=75,rangeKilometers=160.9344}))
local valid=values()
assert(valid.chargeLevel==75)
assert(math.abs(valid.remainingBatteryDistance[151]-160.9344)<0.000001)
assert(math.abs(valid.remainingBatteryDistance[164]-100)<0.000001)
Offscreen.updateBatteryService(CP.createPlist({chargePercent=0}))
local partial=values()
assert(partial.chargeLevel==0 and partial.remainingBatteryDistance==CP.INVALIDVALUE)
Offscreen.updateBatteryService(CP.createPlist({}))
local lost=values()
assert(lost.chargeLevel==CP.INVALIDVALUE and lost.remainingBatteryDistance==CP.INVALIDVALUE)
return require("APL.json").encode({originalUpdater=true,initiallyInvalid=true,
    percentVerified=true,unitConversionVerified=true,partialInvalidationVerified=true,
    sourceLossVerified=true,simulatedValues=true,phoneConnected=false,visibleWidget=false})
