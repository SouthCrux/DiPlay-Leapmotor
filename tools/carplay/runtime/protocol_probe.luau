-- SPDX-License-Identifier: GPL-3.0-only
-- Original center entry point with a simulated peer, real binary plists and no network.
-- Requires --research-allow-incomplete-cp14. Not phone/renderer acceptance evidence.
local incoming,observations={},{}
local nextPeer=3
local resetCount=0
local peer={}
peer.status=function()return #incoming>0,true,nil end
peer.receiveMessage=function()
    if #incoming==0 then return nil,"timeout" end
    return table.remove(incoming,1)
end
peer.reset=function()resetCount+=1;table.clear(incoming)end
peer.sendMessage=function(bytes)
    assert(#observations<64 and #incoming<64,"fixture message limit")
    local message=assert(CP.parsePlist(bytes))
    local command=message.data and assert(CP.parsePlist(message.data))
    table.insert(observations,{type=message.type,ssn=message.ssn,psn=message.psn,asn=message.asn,
        version=message.version,command=command})
    if message.type==0 then
        nextPeer=3
        -- Match the reset/reset-complete sequence observed on the real phone;
        -- the peer here is explicitly simulated, with a fixture identifier.
        table.insert(incoming,CP.createPlist({type=0,ssn=1,psn=1,asn=0,version=2,id="fixture-peer"}))
        table.insert(incoming,CP.createPlist({type=1,ssn=message.ssn,psn=2,asn=message.psn,version=2,id="fixture-peer"}))
    elseif message.type==2 then
        table.insert(incoming,CP.createPlist({type=3,ssn=message.ssn,psn=nextPeer,asn=message.psn}))
        nextPeer+=1
    end
    return true
end
Offscreen.setProtocolPeerForResearch(peer)
Offscreen.configureOutput("Center_Display",1920,720)
Offscreen.setSceneLifecycleForResearch(0)
assert(loadstring(readAsset("main.lua")))()
Offscreen.setOutputActive(true)
Offscreen.setSceneLifecycleForResearch(require("Enums").UISceneState.ACCESSORY_MODE)
Offscreen.runTasks(30)
local reset,complete,request,allow=false,false,false,false
for _,message in observations do
    reset=reset or message.type==0
    complete=complete or message.type==1
    if message.command then
        request=request or message.command.type==5
        allow=allow or message.command.type==17 and message.command.data.allow==true
    end
end
assert(reset and complete and request and allow,"original entry did not request remote UI")
return require("APL.json").encode({fixture=true,fullUltraUI=false,phoneConnected=false,
    incompleteBridgeOverride=true,simulatedPeer=true,simulatedSceneLifecycle=true,
    originalEntryExecuted=true,originalSessionExecuted=true,binaryPlists=true,
    remoteUIRequested=request,layoutChangesAllowed=allow,resetCount=resetCount,
    observations=observations,scene=Offscreen.snapshot()})
