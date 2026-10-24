-- SPDX-License-Identifier: GPL-3.0-only
-- A deliberately incomplete, offscreen CP geometry/layer bridge. Not a 1.4.0 bridge.
local function finite(n)
    assert(type(n) == "number" and n == n and math.abs(n) <= 1e9, "invalid geometry")
    return n
end
local function point(x, y) return {x = finite(x), y = finite(y)} end
local transformMethods = {}
local function transform(a,b,c,d,tx,ty)
    return setmetatable({a=finite(a),b=finite(b),c=finite(c),d=finite(d),tx=finite(tx),ty=finite(ty)}, {__index=transformMethods})
end
function transformMethods:multiply(r)
    return transform(self.a*r.a+self.c*r.b, self.b*r.a+self.d*r.b,
        self.a*r.c+self.c*r.d, self.b*r.c+self.d*r.d,
        self.a*r.tx+self.c*r.ty+self.tx, self.b*r.tx+self.d*r.ty+self.ty)
end
function transformMethods:add(x,y) return self:multiply(transform(1,0,0,1,x,y)) end
local function apply(t,x,y) return t.a*x+t.c*y+t.tx, t.b*x+t.d*y+t.ty end
local rectMethods = {}
local function rect(x,y,w,h)
    return setmetatable({origin=point(x,y),size={width=finite(w),height=finite(h)}}, {__index=rectMethods})
end
function rectMethods:applyTransform(t)
    local x,y,w,h=self.origin.x,self.origin.y,self.size.width,self.size.height
    local ax,ay=apply(t,x,y);local bx,by=apply(t,x+w,y)
    local cx,cy=apply(t,x,y+h);local dx,dy=apply(t,x+w,y+h)
    local left,top=math.min(ax,bx,cx,dx),math.min(ay,by,cy,dy)
    return rect(left,top,math.max(ax,bx,cx,dx)-left,math.max(ay,by,cy,dy)-top)
end
local function color(r,g,b,a)
    for _,n in {r,g,b,a} do assert(finite(n)>=0 and n<=1, "invalid color") end
    return {r=r,g=g,b=b,a=a}
end
local layerMethods = {}
local videoInputs={}
local videoLayers=setmetatable({},{__mode="k"})
local function videoInfo(node)
    local input=videoInputs[rawget(node,"_identifier")]
    local subscribed=rawget(node,"_wantsVideo")==true
    node._state.active=subscribed and input~=nil
    node._state.streamState=not subscribed and "UNSUBSCRIBED" or not input and "INVALID" or
        (input.pending or input.current) and "DATAAVAILABLE" or "SUBSCRIBED"
    return input
end
local properties = {name=true,hidden=true,opacity=true,position=true,anchorPoint=true,bounds=true,
    transform=true,backgroundColor=true,borderWidth=true,borderColor=true,maskToBounds=true,
    blendMode=true,cornerRadius=true,cornerCurve=true,groupBlending=true,shouldRasterize=true,texture=true,tint=true,
    path=true,lineWidth=true,strokeColor=true,strokeStart=true,strokeEnd=true,antialiased=true,
    fillColor=true,fillRule=true,lineCap=true,lineJoin=true,miterLimit=true,type=true,startPoint=true,endPoint=true,
    streamState=true,active=true}
local layerCount=0
local function layer()
    layerCount+=1;assert(layerCount<=4096,"layer limit")
    local state={hidden=false,opacity=1,position=point(0,0),anchorPoint=point(0.5,0.5),bounds=rect(0,0,0,0),
        transform=transform(1,0,0,1,0,0),backgroundColor=color(0,0,0,0),borderColor=color(0,0,0,0),
        borderWidth=0,maskToBounds=false,cornerRadius=0,blendMode="NORMAL",cornerCurve="CIRCULAR",tint=color(1,1,1,1),
        lineWidth=1,strokeColor=color(0,0,0,0),strokeStart=0,strokeEnd=1,antialiased=true,
        fillColor=color(0,0,0,0),fillRule="NONZERO",lineCap="BUTT",lineJoin="MITER",miterLimit=10,
        type="LINEAR",startPoint=point(0,0),endPoint=point(1,1),stops={},streamState="CLOSED",active=false}
    local node={_state=state,_children={},_filters={}}
    return setmetatable(node,{
        __index=function(self,k)
            if k=="numStops" then return #self._state.stops end
            if k=="frame" then
                local s=self._state;local b=s.bounds;local a=s.anchorPoint
                return rect(s.position.x-a.x*b.size.width,s.position.y-a.y*b.size.height,b.size.width,b.size.height)
            end
            if properties[k] then return self._state[k] end
            if layerMethods[k] then return layerMethods[k] end
            error("unsupported CP layer member: "..tostring(k))
        end,
        __newindex=function(self,k,v)
            if k=="frame" then
                local s=self._state;local a=s.anchorPoint
                s.bounds=rect(s.bounds.origin.x,s.bounds.origin.y,v.size.width,v.size.height)
                s.position=point(v.origin.x+a.x*v.size.width,v.origin.y+a.y*v.size.height)
            elseif properties[k] then self._state[k]=v
            else error("unsupported CP layer property: "..tostring(k)) end
        end,
    })
end
function layerMethods:getSublayers() return table.clone(self._children) end
function layerMethods:removeFromSuperlayer()
    local parent=rawget(self,"_parent")
    if parent then
        for i,v in parent._children do if v==self then table.remove(parent._children,i);break end end
        rawset(self,"_parent",nil)
    end
end
function layerMethods:insertSublayerAtIndex(child,index)
    assert(type(index)=="number" and index%1==0 and index>=1 and index<=#self._children+1,"invalid child index")
    local ancestor=self
    while ancestor do assert(ancestor~=child,"layer cycle");ancestor=rawget(ancestor,"_parent") end
    if rawget(child,"_parent")==self then
        for oldIndex,v in self._children do if v==child and oldIndex<index then index-=1;break end end
    end
    child:removeFromSuperlayer();table.insert(self._children,index,child);rawset(child,"_parent",self)
end
function layerMethods:addSublayer(child) self:insertSublayerAtIndex(child,#self._children+1) end
function layerMethods:removeSublayer(child)
    assert(rawget(child,"_parent")==self,"not a child");child:removeFromSuperlayer()
end
function layerMethods:removeSublayers()
    for _,child in self._children do rawset(child,"_parent",nil) end
    table.clear(self._children)
end
function layerMethods:insertSublayerBelow(child,sibling)
    for i,v in self._children do if v==sibling then self:insertSublayerAtIndex(child,i);return end end
    error("not a sibling")
end
function layerMethods:replaceSublayer(old,new)
    for i,v in self._children do
        if v==old then old:removeFromSuperlayer();self:insertSublayerAtIndex(new,i);return end
    end
    error("not a child")
end
function layerMethods:setStops(stops)
    assert(rawget(self,"_kind")=="gradient" and #stops<=64,"gradient stop limit/type")
    local copied={}
    for _,stop in stops do
        assert(finite(stop.location)>=0 and stop.location<=1,"invalid gradient location")
        local c=stop.color;table.insert(copied,{location=stop.location,color=color(c.r,c.g,c.b,c.a)})
    end
    table.sort(copied,function(a,b)return a.location<b.location end)
    self._state.stops=copied
end
function layerMethods:setColors(colors)
    local stops={}
    for i,c in colors do table.insert(stops,{color=c,location=#colors<=1 and 0 or (i-1)/(#colors-1)}) end
    self:setStops(stops)
end
function layerMethods:addFilter(filter)table.insert(self._filters,filter)end
function layerMethods:setMask(mask)rawset(self,"_mask",mask)end
function layerMethods:subscribe()
    assert(rawget(self,"_kind")=="video","not a video layer")
    rawset(self,"_wantsVideo",true);videoInfo(self)
end
function layerMethods:unsubscribe()rawset(self,"_wantsVideo",false);videoInfo(self)end
function layerMethods:getCurrentPTS()
    local input=videoInfo(self)
    if self._state.active and input.current then return input.current.pts end
    return nil,"no current frame"
end
function layerMethods:getNextPTS()
    local input=videoInfo(self)
    if self._state.active and input.pending then return input.pending.pts end
    return nil,"no next frame"
end
function layerMethods:flush()
    assert(rawget(self,"_kind")=="video","not a video layer")
    local input=videoInputs[rawget(self,"_identifier")]
    if input then input.pending=nil;input.current=nil;input.flushed=true end
    videoInfo(self)
end
function layerMethods:deferNextPTS()
    assert(rawget(self,"_kind")=="video","not a video layer")
    local input=videoInputs[rawget(self,"_identifier")]
    assert(input and input.pending,"no next frame to defer")
    input.deferred=true
end
local function enum(names)
    local t={};for name in string.gmatch(names,"%S+") do t[name]=name end;return table.freeze(t)
end
local root
local output
local outputActive=false
local presentationTime=monotonicTime()
local videoClock=nil
local sceneLifecycleEnabled=false
local sceneState=0
local sceneUpdates={}
local batteryServiceEnabled=false
local batteryValues={}
local function batteryCharacteristic(id,units)
    return {iid=id,units=units,supportsInvalid=true,initialValue=batteryValues[id]}
end
local tasks={}
local taskMethods={}
local function task()
    local value=setmetatable({_active=false},{__index=taskMethods})
    return value
end
local function pruneTasks()
    for i=#tasks,1,-1 do
        if not tasks[i]._active then tasks[i]._scheduled=false;table.remove(tasks,i) end
    end
end
function taskMethods:run(fn)
    assert(type(fn)=="function","task function required")
    pruneTasks()
    if not self._scheduled then assert(#tasks<256,"task limit");table.insert(tasks,self);self._scheduled=true end
    self._thread=coroutine.create(fn);self._active=true
end
function taskMethods:stop()self._active=false end
function taskMethods:isBusy()return self._active and self._thread~=nil and coroutine.status(self._thread)~="dead"end
local function runTasks(ticks)
    assert(type(ticks)=="number" and ticks%1==0 and ticks>=0 and ticks<=120,"task tick limit")
    for _=1,ticks do
        if not videoClock then presentationTime=monotonicTime() end
        local current=table.clone(tasks)
        for _,value in current do
            if value:isBusy() then
                local ok,err=coroutine.resume(value._thread)
                if not ok then value:stop();error(err) end
                if coroutine.status(value._thread)=="dead" then value:stop() end
            end
        end
        pruneTasks()
    end
end
local pathMethods={}
local function path()
    return setmetatable({_commands={},_points=0},{__index=function(self,k)
        if k=="numPoints" then return self._points end
        return pathMethods[k]
    end})
end
local function pathCommand(self,command,points)
    assert(#self._commands<4096,"path command limit")
    table.insert(self._commands,command);self._points+=points
end
function pathMethods:moveTo(p)pathCommand(self,{op="M",x=finite(p.x),y=finite(p.y)},1)end
function pathMethods:lineTo(p)pathCommand(self,{op="L",x=finite(p.x),y=finite(p.y)},1)end
function pathMethods:quadTo(p,c)pathCommand(self,{op="Q",x=finite(p.x),y=finite(p.y),cx=finite(c.x),cy=finite(c.y)},2)end
function pathMethods:cubeTo(p,c1,c2)
    pathCommand(self,{op="C",x=finite(p.x),y=finite(p.y),cx1=finite(c1.x),cy1=finite(c1.y),cx2=finite(c2.x),cy2=finite(c2.y)},3)
end
function pathMethods:closeSubpath()pathCommand(self,{op="Z"},0)end
function pathMethods:ellipseInRect(r)
    local x,y=r.origin.x+r.size.width/2,r.origin.y+r.size.height/2
    local rx,ry=r.size.width/2,r.size.height/2
    local k=0.5522847498307936
    self:moveTo(point(x+rx,y))
    self:cubeTo(point(x,y+ry),point(x+rx,y+k*ry),point(x+k*rx,y+ry))
    self:cubeTo(point(x-rx,y),point(x-k*rx,y+ry),point(x-rx,y+k*ry))
    self:cubeTo(point(x,y-ry),point(x-rx,y-k*ry),point(x-k*rx,y-ry))
    self:cubeTo(point(x+rx,y),point(x+k*rx,y-ry),point(x+rx,y-k*ry))
    self:closeSubpath()
end
-- Research convention: radians, x=cos(a), y=sin(a), clockwise decreases a.
-- This matches the resource's angle-progress calculations; native CP parity
-- still requires a reference image and is not implied by this implementation.
function pathMethods:arcTo(center,radius,startAngle,endAngle,clockwise)
    local x,y=finite(center.x),finite(center.y)
    radius=finite(radius);startAngle=finite(startAngle);endAngle=finite(endAngle)
    assert(radius>=0 and math.abs(startAngle)<=1000000 and math.abs(endAngle)<=1000000,"arc bounds refused")
    assert(type(clockwise)=="boolean","arc direction required")
    local tau=2*math.pi
    local delta=endAngle-startAngle
    if math.abs(delta)>=tau then delta=clockwise and -tau or tau
    elseif clockwise then delta=-((-delta)%tau)
    else delta=delta%tau end
    local start=point(x+radius*math.cos(startAngle),y+radius*math.sin(startAngle))
    if #self._commands==0 then self:moveTo(start) else self:lineTo(start) end
    local segments=math.ceil(math.abs(delta)/(math.pi/2))
    if segments==0 or radius==0 then return end
    local step=delta/segments
    for i=1,segments do
        local a=startAngle+(i-1)*step
        local b=a+step
        local k=4/3*math.tan(step/4)
        self:cubeTo(point(x+radius*math.cos(b),y+radius*math.sin(b)),
            point(x+radius*(math.cos(a)-k*math.sin(a)),y+radius*(math.sin(a)+k*math.cos(a))),
            point(x+radius*(math.cos(b)+k*math.sin(b)),y+radius*(math.sin(b)-k*math.cos(b))))
    end
end
local logLevel=6
local researchPeer=nil -- Explicit injected fixture callbacks; never a network endpoint.
local researchIngress,researchEgress=nil,nil
local ingressBytes,egressBytes=0,0
local function imageAsset(path)
    local data=readAsset(path)
    assert(string.sub(data,1,8)=="\137PNG\13\10\26\10" and string.sub(data,13,16)=="IHDR","invalid PNG")
    local function u32(at)
        local a,b,c,d=string.byte(data,at,at+3);assert(d,"short PNG")
        return ((a*256+b)*256+c)*256+d
    end
    local width,height=u32(17),u32(21)
    assert(width>0 and height>0 and width<=8192 and height<=8192 and width*height<=16000000,"PNG dimensions refused")
    return table.freeze({path=path,width=width,height=height,
        getSize=function()return width,height end,getData=function()return data,#data end})
end
CP={
    version="0.0.0-diplay-offscreen",
    createPlist=createBinaryPlist,
    parsePlist=parseBinaryPlist,
    createAssetRAW=readAsset,
    createAssetImage=imageAsset,
    DPImageLayer={create=layer},
    DPBackdropLayer={create=layer},
    DPBlurFilter={create=function()return {radius=0,hardEdges=false,normalizedEdges=false}end},
    DPVideoLayer={create=function(identifier)
        assert(type(identifier)=="string" and #identifier<=128,"video identity required")
        local n=layer();rawset(n,"_kind","video");rawset(n,"_identifier",identifier)
        videoLayers[n]=true;return n
    end,STREAMSTATE=enum("CLOSED SUBSCRIBED DATAAVAILABLE UNSUBSCRIBED INVALID")},
    DPPath={create=path},
    DPShapeLayer={create=layer,FILLRULE=enum("EVENODD NONZERO"),LINECAP=enum("ROUND SQUARE BUTT"),LINEJOIN=enum("MITER ROUND BEVEL")},
    DPGradientLayer={create=function()local n=layer();rawset(n,"_kind","gradient");return n end,KIND=enum("LINEAR RADIAL CONICAL")},
    DPGradientStop=function(c,location)return {color=c,location=finite(location)}end,
    DPTexture={createFromImage=function(asset)return asset end},
    LOGLEVELS=table.freeze({TRACE=0,DEBUG=1,INFO=2,WARNING=3,ERROR=4,NONE=6}),
    getLogLevel=function()return logLevel end,
    setLogLevel=function(level)logLevel=finite(level)end,
    log=function()end, -- Explicitly disabled in the offscreen host; no package messages escape.
    getSystemUpTime=monotonicTime,
    DPLuaTask={create=task},
    enumerateOutputStreams=function()return output and {table.clone(output)} or {}end,
    enumerateInputStreams=function()
        local streams={}
        for _,input in videoInputs do table.insert(streams,table.clone(input.info)) end
        table.sort(streams,function(a,b)return a.identifier<b.identifier end)
        return streams
    end,
    INVALIDVALUE=table.freeze({invalid=true}),
    getVehicleDataConfig=function()
        local config=sceneLifecycleEnabled and {uiControl={uiState={uiSceneState={iid=1}}}} or {}
        if batteryServiceEnabled then
            -- Local CP contract consumed by the unmodified VehicleDataUpdater, NOT an Apple wire schema.
            config.highVoltageBattery={
                batteryLevel={batteryLevel=batteryCharacteristic(2,nil)},
                batteryRemainingRange={distanceKM=batteryCharacteristic(3,151),distanceMiles=batteryCharacteristic(4,164)},
            }
        end
        return config
    end,
    getVehicleData=function(ids)
        local out={};for _,id in ids do
            if sceneLifecycleEnabled and id==1 then out[id]=sceneState
            elseif batteryServiceEnabled and batteryValues[id]~=nil then out[id]=batteryValues[id] end
        end;return out
    end,
    getVehicleDataUpdates=function()local out=sceneUpdates;sceneUpdates={};return out end,
    registerVehicleDataTypes=function(ids)
        for _,id in ids do
            assert((sceneLifecycleEnabled and id==1) or (batteryServiceEnabled and batteryValues[id]~=nil),"no vehicle services attached")
        end
    end,
    setVehicleData=function(values)assert(next(values)==nil,"vehicle writes unavailable");return {}end,
    status=function()if researchPeer then return researchPeer.status() end;return false,false,"timeout"end,
    receiveMessage=function()if researchPeer then return researchPeer.receiveMessage() end;return nil,"timeout"end,
    sendMessage=function(bytes)if researchPeer then return researchPeer.sendMessage(bytes) end;return nil,"no UI Sync transport attached"end,
    reset=function()
        if researchPeer then researchPeer.reset() end
        for _,input in videoInputs do input.pending=nil;input.current=nil;input.flushed=true end
        for node in videoLayers do videoInfo(node) end
    end,
    readyToEnterForeground=function()assert(output,"no output configured")end,
    UpdateVerifiedUIElements=function(elements)assert(next(elements)==nil,"verified vehicle indicators are not integrated")end,
    DPPoint=point,DPColor=color,
    DPRect=setmetatable({withSize=function(size)return rect(0,0,size.width,size.height)end},
        {__call=function(_,...)return rect(...)end}),
    DPAffineTransform=setmetatable({translate=function(x,y)return transform(1,0,0,1,x,y)end,
        scale=function(x,y)return transform(x,0,0,y,0,0)end,
        rotate=function(r)return transform(math.cos(r),math.sin(r),-math.sin(r),math.cos(r),0,0)end},
        {__call=function(_,...)return transform(...)end}),
    DPLayer={create=layer,
        BLEND=enum("COPY CLEAR SOURCEOVER SOURCEIN SOURCEOUT SOURCEATOP DESTOVER DESTIN DESTOUT DESTATOP ADDITIVE PLUSLIGHTER EXCLUSIVEOR XOR SCREEN EXCLUSION MAXIMUM MINIMUM SUBTRACTSRC SUBTRACTDEST COMPONENTMASK NORMAL"),
        CURVE=enum("CIRCULAR CONTINUOUS")},
    setRootLayer=function(identifier,value)
        assert(output and identifier==output.identifier and type(value)=="table","output/root binding refused")
        root=value
    end,
}
setmetatable(CP,{__index=function(_,key)
    if key=="PTS" then return presentationTime end -- Held in the selected input's clock when configured.
    if key=="isForegrounded" then return outputActive end
    return nil
end})
local function snapshot(node,depth)
    assert(depth<=64,"layer depth limit")
    local s=node._state
    assert(s.borderWidth==0 and s.cornerRadius==0 and not s.groupBlending and not s.shouldRasterize,
        "unsupported offscreen decoration")
    assert(#node._filters==0,"filter composition not implemented")
    assert(rawget(node,"_mask")==nil,"layer mask composition not implemented")
    assert(s.blendMode=="NORMAL" or s.blendMode=="SOURCEOVER","unsupported offscreen blend")
    local children={}
    for _,child in node._children do table.insert(children,snapshot(child,depth+1)) end
    local video=nil
    if rawget(node,"_kind")=="video" then
        local input=videoInfo(node)
        local frame=input and (not input.deferred and input.pending or input.current)
        video={identifier=rawget(node,"_identifier"),active=s.active,
            token=s.active and frame and frame.token or nil,pts=s.active and frame and frame.pts or nil}
    end
    return {name=s.name,bounds=s.bounds,position=s.position,anchorPoint=s.anchorPoint,transform=s.transform,
        opacity=s.opacity,hidden=s.hidden,backgroundColor=s.backgroundColor,maskToBounds=s.maskToBounds,children=children,
        texture=s.texture and {path=s.texture.path,width=s.texture.width,height=s.texture.height} or nil,tint=s.tint,video=video,
        gradient=rawget(node,"_kind")=="gradient" and {kind=s.type,startPoint=s.startPoint,endPoint=s.endPoint,stops=s.stops} or nil,
        shape=s.path and {commands=s.path._commands,lineWidth=s.lineWidth,strokeColor=s.strokeColor,
            strokeStart=s.strokeStart,strokeEnd=s.strokeEnd,antialiased=s.antialiased,fillColor=s.fillColor,
            fillRule=s.fillRule,lineCap=s.lineCap,lineJoin=s.lineJoin,miterLimit=s.miterLimit} or nil}
end
Offscreen={snapshot=function()
        -- The original controller builds its root after protocol/lifecycle startup. This is
        -- explicitly unavailable scene data, never a blank image or a submitted output frame.
        if not root then return {rootUnavailable=true} end
        return snapshot(root,0)
    end,
    configureVideoInput=function(identifier,width,height,frameDuration,kind)
        kind=if kind==nil then 3 else kind
        assert(kind==1 or kind==3,"unsupported input kind")
        assert(type(identifier)=="string" and string.match(identifier,"^[%w_.-]+$") and #identifier<=128,"invalid input identity")
        assert(not videoInputs[identifier],"input already configured")
        local count=0;for _ in videoInputs do count+=1 end;assert(count<8,"input count limit")
        assert(finite(width)>0 and finite(height)>0 and width%1==0 and height%1==0 and
            width<=4096 and height<=4096 and width*height<=8000000,"input dimensions refused")
        assert(type(frameDuration)=="number" and frameDuration>0 and frameDuration<=1,"frame duration refused")
        if kind==3 and not videoClock then videoClock=identifier;presentationTime=0 end
        videoInputs[identifier]={lastToken=0,lastPTS=0,deferred=false,flushed=false,
            info={identifier=identifier,width=width,height=height,frameDuration=frameDuration,
                streamMode=0,parameterMode=1,opacityCapability=1,positionCapability=1,sizeCapability=1,type=kind}}
    end,
    videoFrame=function(bytes)
        local message=assert(CP.parsePlist(bytes),"frame message required")
        local input=assert(videoInputs[message.input],"unknown video input")
        local action=message.action
        if action=="offer" then
            assert(not input.pending,"unresolved pending frame")
            local token,pts=message.token,message.pts
            assert(type(token)=="number" and token%1==0 and token>input.lastToken and token<=9007199254740991,"stale/invalid frame token")
            assert(type(pts)=="number" and pts==pts and pts>=input.lastPTS and pts<=1e10,"stale/invalid frame time")
            input.lastToken=token;input.lastPTS=pts;input.pending={token=token,pts=pts}
            if message.input==videoClock then presentationTime=math.max(presentationTime,pts) end
            input.deferred=false;input.flushed=false
        elseif action=="commit" then
            assert(input.pending and input.pending.token==message.token and not input.deferred and not input.flushed,"frame cannot commit")
            input.current=input.pending;input.pending=nil
        elseif action=="cancel" then
            assert(input.pending and input.pending.token==message.token,"frame cannot cancel")
            input.pending=nil;input.deferred=false
        elseif action=="clear" then
            -- Retire borrowed pixels without resetting token/PTS watermarks. A later offer must
            -- belong to a new frame; a paused Settings source cannot revive its old bitmap.
            input.pending=nil;input.current=nil;input.deferred=false;input.flushed=true
        else assert(action=="state","unknown frame action") end
        for layer in videoLayers do videoInfo(layer) end
        return {deferred=input.deferred,flushed=input.flushed,
            pendingToken=input.pending and input.pending.token or 0,currentToken=input.current and input.current.token or 0}
    end,
    startProtocolForResearch=function()
        assert(not researchPeer,"research peer already bound")
        researchIngress={};researchEgress={}
        researchPeer=table.freeze({
            status=function()return #researchIngress>0,true,nil end,
            receiveMessage=function()
                if #researchIngress==0 then return nil,"timeout" end
                local data=table.remove(researchIngress,1);ingressBytes-=#data;return data
            end,
            sendMessage=function(data)
                assert(type(data)=="userdata" and CP.parsePlist(data),"binary message required")
                assert(#researchEgress<64 and #data<=1048576-egressBytes,"research egress limit")
                table.insert(researchEgress,data);egressBytes+=#data;return true
            end,
            reset=function()table.clear(researchIngress);ingressBytes=0 end,
        })
    end,
    enqueueProtocolForResearch=function(data)
        assert(researchIngress and type(data)=="userdata" and CP.parsePlist(data),"binary message required")
        assert(#researchIngress<64 and #data<=1048576-ingressBytes,"research ingress limit")
        table.insert(researchIngress,data);ingressBytes+=#data
    end,
    takeProtocolForResearch=function()
        assert(researchEgress,"research transport not started")
        local out=researchEgress;researchEgress={};egressBytes=0;return out
    end,
    setProtocolPeerForResearch=function(peer)
        assert(not researchPeer and type(peer)=="table","research peer already bound or invalid")
        for _,name in {"status","receiveMessage","sendMessage","reset"} do
            assert(type(peer[name])=="function","research peer callback required")
        end
        researchPeer=table.freeze(table.clone(peer))
    end,
    configureOutput=function(identifier,width,height)
        assert(type(identifier)=="string" and string.match(identifier,"^[%w_.-]+$") and #identifier<=128,"invalid output identity")
        assert(finite(width)>0 and finite(height)>0 and width%1==0 and height%1==0 and width<=4096 and height<=4096 and width*height<=8000000,"output dimensions refused")
        assert(not root,"output already has a root")
        output={identifier=identifier,width=width,height=height,frameDuration=0}
    end,
    setOutputActive=function(active)assert(output and type(active)=="boolean","output state refused");outputActive=active end,
    configureSceneLifecycle=function()
        assert(not sceneLifecycleEnabled,"scene lifecycle already configured")
        sceneLifecycleEnabled=true;sceneState=CP.INVALIDVALUE;sceneUpdates[1]=CP.INVALIDVALUE
    end,
    updateSceneLifecycle=function(state)
        assert(sceneLifecycleEnabled,"scene lifecycle not configured")
        assert(state==nil or (type(state)=="number" and state%1==0 and state>=0 and state<=4),"invalid scene state")
        local nextState=if state==nil then CP.INVALIDVALUE else state
        if nextState~=sceneState then sceneState=nextState;sceneUpdates[1]=nextState end
    end,
    configureBatteryService=function()
        assert(not batteryServiceEnabled,"battery service already configured")
        batteryServiceEnabled=true
        for id=2,4 do batteryValues[id]=CP.INVALIDVALUE;sceneUpdates[id]=CP.INVALIDVALUE end
    end,
    updateBatteryService=function(payload)
        assert(batteryServiceEnabled,"battery service not configured")
        local value=CP.parsePlist(payload)
        assert(type(value)=="table","battery snapshot required")
        for key in value do assert(key=="chargePercent" or key=="rangeKilometers","unknown battery snapshot field") end
        local percent=value.chargePercent
        local kilometers=value.rangeKilometers
        assert(percent==nil or (type(percent)=="number" and finite(percent)>=0 and percent<=100),"invalid battery percentage")
        assert(kilometers==nil or (type(kilometers)=="number" and finite(kilometers)>=0 and kilometers<=100000),"invalid battery range")
        local values={percent or CP.INVALIDVALUE,kilometers or CP.INVALIDVALUE,
            if kilometers==nil then CP.INVALIDVALUE else kilometers/1.609344}
        for index,nextValue in values do
            local id=index+1
            if batteryValues[id]~=nextValue then batteryValues[id]=nextValue;sceneUpdates[id]=nextValue end
        end
    end,
    setSceneLifecycleForResearch=function(state)
        assert(type(state)=="number" and state%1==0 and state>=0 and state<=4,"invalid test scene state")
        sceneLifecycleEnabled=true;sceneState=state;sceneUpdates[1]=state
    end,
    runTasks=runTasks}
-- Missing bridge operations remain absent and fail when called. No fake success callbacks.
