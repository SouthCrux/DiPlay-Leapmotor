-- SPDX-License-Identifier: GPL-3.0-only
-- Read the original data module; do not edit manufacturer scripts or infer phone acceptance.
local package=require("r/PackageData")
local center=nil
for _,display in package.displays do
    if display.id=="Center_Display" then assert(center==nil);center=display end
end
assert(center and center.type=="centerConsole","reference center missing")
return require("APL.json").encode({
    asset={identifier=package.identifier,version=package.version,sdkVersion=package.minimumSDKVersion},
    display={id=center.id,role=center.type,size=center.size,densityDpi=center.density},
    layouts=center.layouts,
})
