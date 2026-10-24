-- SPDX-License-Identifier: GPL-3.0-only
-- Read-only metadata inventory. A declared zone does not establish phone acceptance or rendering.
local package = require("r/PackageData")
local function keys(value)
    local result = {}
    for key in value or {} do table.insert(result, key) end
    table.sort(result)
    return result
end
local displays = {}
for _, display in package.displays do
    local layouts = {}
    for _, layout in display.layouts or {} do
        local zones = {}
        for _, zone in layout.zones or {} do
            local components = {}
            for _, component in zone.components or {} do
                local slots = {}
                for _, slot in component.slots or {} do
                    local kind = slot.instrument and slot.instrument.kind or {}
                    table.insert(slots, {
                        position = slot.positionId, frame = slot.frame, kinds = keys(kind),
                        punchThroughType = kind.punchThroughType and kind.punchThroughType.type,
                        punchThroughIdentifier = kind.punchThrough and kind.punchThrough.identifier,
                    })
                end
                table.insert(components, {id = component.id, name = component.name,
                    type = component.type, size = component.size, slots = slots})
            end
            table.insert(zones, {id = zone.id, name = zone.displayName,
                frame = zone.frame, kinds = keys(zone.type), components = components})
        end
        table.insert(layouts, {id = layout.id, name = layout.displayName,
            type = layout.layoutType, zones = zones})
    end
    table.insert(displays, {id = display.id, role = display.type, size = display.size,
        densityDpi = display.density, layouts = layouts})
end
return require("APL.json").encode({
    asset = {identifier = package.identifier, version = package.version,
        sdkVersion = package.minimumSDKVersion}, displays = displays,
    metadataOnly = true, phoneAcceptanceVerified = false, physicalPresentation = false,
})
