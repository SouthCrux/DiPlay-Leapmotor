"""Run with DIPLAY_ULTRA_TEST_HOST=/path/to/ultra-host; no Apple resources required."""
import hashlib
import json
import os
import plistlib
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from build_host import build
from prepare_resources import prepare
from step_client import StepClient


class ResourcePreparationTest(unittest.TestCase):
    def test_sources_only_are_extracted_and_input_digest_is_bound(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);archive=root/'input.zip'
            with zipfile.ZipFile(archive,'w') as stream:
                stream.writestr('main.lua','return {}')
                stream.writestr('main.luac',b'not trusted bytecode')
                stream.writestr('r/a.png',b'not decoded by preparation')
            digest=hashlib.sha256(archive.read_bytes()).hexdigest()
            with self.assertRaises(ValueError):prepare(archive,'0'*64,root/'bad')
            self.assertFalse((root/'bad').exists())
            report=prepare(archive,digest,root/'resources')
            self.assertEqual({'main.lua','r/a.png'},set(report['files']))
            self.assertFalse((root/'resources/main.luac').exists())
            self.assertFalse(report['signatureVerified'])
            self.assertFalse(report['scriptsExecuted'])
            android_manifest = (root/'resources/research-input.plist').read_bytes()
            self.assertEqual(report, plistlib.loads(android_manifest))
            self.assertEqual(android_manifest, plistlib.dumps(report, fmt=plistlib.FMT_BINARY, sort_keys=True))

    def test_traversal_and_wrong_upstream_source_fail_before_creating_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);archive=root/'input.zip'
            with zipfile.ZipFile(archive,'w') as stream:stream.writestr('../escaped.lua','return 1')
            digest=hashlib.sha256(archive.read_bytes()).hexdigest()
            with self.assertRaises(ValueError):prepare(archive,digest,root/'resources')
            with self.assertRaises(ValueError):build(archive,root/'work',root/'host')
            self.assertFalse((root/'work').exists())
            self.assertFalse((root/'resources').exists())


@unittest.skipUnless(os.environ.get('DIPLAY_ULTRA_TEST_HOST'),'Explicit compiled research host required')
class RuntimeHostTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.root=Path(self.temp.name)
        self.resources=self.root/'resources';self.resources.mkdir()
        self.bridge=self.root/'bridge.luau';self.bridge.write_text('')
        self.probe=self.root/'probe.luau'

    def tearDown(self):self.temp.cleanup()

    def run_script(self, source, **files):
        for name,text in files.items():
            if isinstance(text,bytes):(self.resources/name).write_bytes(text)
            else:(self.resources/name).write_text(text)
        self.probe.write_text(source)
        return subprocess.run([os.environ['DIPLAY_ULTRA_TEST_HOST'],str(self.resources),str(self.bridge),str(self.probe)],
                              capture_output=True,text=True,timeout=7)

    def test_no_os_environment_or_native_module_access_and_no_bytecode_execution(self):
        result=self.run_script('''
            assert(io==nil and os==nil and package==nil and loadfile==nil and dofile==nil)
            assert(getfenv==nil and setfenv==nil)
            assert(not pcall(function()require("../private")end))
            assert(not pcall(function()readAsset("../private")end))
            assert(loadstring(string.char(5,1,3,4))==nil)
            assert(loadstring("return 7")()==7)
            return "ok"
        ''')
        self.assertEqual(0,result.returncode,result.stderr);self.assertEqual('ok\n',result.stdout)

    def test_module_cache_and_dependency_failure_leave_other_modules_usable(self):
        result=self.run_script('''
            assert(not pcall(function()require("a")end))
            local x=require("good");assert(x==require("good"))
            return "ok"
        ''', **{'a.lua':'return require("b")','b.lua':'return require("a")','good.lua':'return {}'})
        self.assertEqual(0,result.returncode,result.stderr)

    def test_symlink_and_oversized_source_are_refused(self):
        outside=self.root/'secret.lua';outside.write_text('return "private-marker"')
        (self.resources/'linked.lua').symlink_to(outside)
        result=self.run_script('assert(not pcall(function()require("linked")end));return "ok"')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertNotIn('private-marker',result.stdout+result.stderr)
        (self.resources/'huge.lua').write_text(' '* (4*1024*1024+1))
        result=self.run_script('assert(not pcall(function()require("huge")end));return "ok"')
        self.assertEqual(0,result.returncode,result.stderr)

    def test_infinite_script_is_interrupted(self):
        result=self.run_script('while true do end')
        self.assertNotEqual(0,result.returncode);self.assertIn('deadline',result.stderr)

    def test_memory_exhaustion_and_dynamic_compile_limits_are_bounded(self):
        result=self.run_script('return string.rep("x",200*1024*1024)')
        self.assertNotEqual(0,result.returncode)
        result=self.run_script('assert(not pcall(function()loadstring(string.rep(" ",65537))end));return "ok"')
        self.assertEqual(0,result.returncode,result.stderr)

    def test_layer_order_reparenting_and_unsupported_effects_are_explicit(self):
        self.bridge=Path(__file__).with_name('bridge.luau')
        result=self.run_script('''
            local a,b,c=CP.DPLayer.create(),CP.DPLayer.create(),CP.DPLayer.create()
            a:addSublayer(b);a:addSublayer(c);a:addSublayer(b)
            assert(a:getSublayers()[1]==c and a:getSublayers()[2]==b)
            assert(not pcall(function()b:addSublayer(a)end))
            Offscreen.configureOutput("fixture",40,30)
            CP.setRootLayer("fixture",a);a.cornerRadius=5
            assert(not pcall(Offscreen.snapshot))
            assert(not pcall(function()CP.DPPath.create():arcTo()end))
            return "ok"
        ''')
        self.assertEqual(0,result.returncode,result.stderr)

    def test_dynamic_cp_properties_and_completed_tasks_do_not_freeze_or_accumulate(self):
        self.bridge=Path(__file__).with_name('bridge.luau')
        result=self.run_script('''
            local read=require("clock")
            Offscreen.configureOutput("fixture",40,30)
            assert(read()==false)
            Offscreen.setOutputActive(true)
            assert(read()==true)
            local last=CP.PTS
            local count=0
            for i=1,300 do
                local task=CP.DPLuaTask.create()
                task:run(function()count+=1;coroutine.yield();count+=1 end)
                Offscreen.runTasks(1);assert(task:isBusy())
                Offscreen.runTasks(1);assert(not task:isBusy())
            end
            assert(count==600 and CP.PTS>=last)
            local task=CP.DPLuaTask.create()
            task:run(function()error("stopped task must not run")end)
            task:stop();Offscreen.runTasks(1)
            return "ok"
        ''', **{'clock.lua':'return function()return CP.isForegrounded, CP.PTS end'})
        self.assertEqual(0,result.returncode,result.stderr)

    def test_scene_service_starts_invalid_and_invalidates_without_inventing_drive(self):
        self.bridge=Path(__file__).with_name('bridge.luau')
        result=self.run_script('''
            assert(CP.getVehicleDataConfig().uiControl==nil)
            assert(not pcall(function()Offscreen.updateSceneLifecycle(2)end))
            Offscreen.configureSceneLifecycle()
            assert(CP.getVehicleDataConfig().uiControl.uiState.uiSceneState.iid==1)
            assert(CP.getVehicleData({1})[1]==CP.INVALIDVALUE)
            assert(CP.getVehicleDataUpdates()[1]==CP.INVALIDVALUE)
            CP.registerVehicleDataTypes({1})
            assert(not pcall(function()CP.registerVehicleDataTypes({2})end))
            for state=0,4 do
                Offscreen.updateSceneLifecycle(state)
                assert(CP.getVehicleData({1})[1]==state)
                assert(CP.getVehicleDataUpdates()[1]==state)
                Offscreen.updateSceneLifecycle(state)
                assert(next(CP.getVehicleDataUpdates())==nil)
            end
            Offscreen.updateSceneLifecycle(nil)
            assert(CP.getVehicleData({1})[1]==CP.INVALIDVALUE)
            assert(CP.getVehicleDataUpdates()[1]==CP.INVALIDVALUE)
            assert(not pcall(function()Offscreen.updateSceneLifecycle(5)end))
            assert(not pcall(function()Offscreen.configureSceneLifecycle()end))
            return "ok"
        ''')
        self.assertEqual(0,result.returncode,result.stderr)

    def test_arc_direction_connection_full_circle_and_degenerate_bounds(self):
        self.bridge=Path(__file__).with_name('bridge.luau')
        result=self.run_script('''
            local function near(a,b)assert(math.abs(a-b)<0.00001)end
            local p=CP.DPPath.create()
            p:arcTo(CP.DPPoint(10,20),5,0,math.pi/2,false)
            assert(#p._commands==2 and p._commands[1].op=="M")
            near(p._commands[2].x,10);near(p._commands[2].y,25)
            near(p._commands[2].cx1,15)
            assert(p._commands[2].cy1>20)
            local q=CP.DPPath.create();q:moveTo(CP.DPPoint(1,2))
            q:arcTo(CP.DPPoint(0,0),5,0,-math.pi/2,true)
            assert(q._commands[2].op=="L")
            near(q._commands[3].x,0);near(q._commands[3].y,-5)
            local c=CP.DPPath.create()
            c:arcTo(CP.DPPoint(0,0),5,0,2*math.pi,true)
            assert(#c._commands==5);near(c._commands[5].x,5);near(c._commands[5].y,0)
            local z=CP.DPPath.create();z:arcTo(CP.DPPoint(0,0),0,0,0,false)
            assert(#z._commands==1)
            assert(not pcall(function()z:arcTo(CP.DPPoint(0,0),-1,0,1,false)end))
            return "ok"
        ''')
        self.assertEqual(0,result.returncode,result.stderr)

    def test_native_binary_plist_interoperates_with_independent_encoder(self):
        self.bridge=Path(__file__).with_name('bridge.luau')
        inner=plistlib.dumps({'type':3,'data':{'pts':12.125,'layoutID':'é日本🚙'}},fmt=plistlib.FMT_BINARY)
        incoming=plistlib.dumps({'type':2,'ssn':1,'psn':3,'asn':2,'data':inner,
                                'booleans':[True,False],'negative':-2},fmt=plistlib.FMT_BINARY)
        float32=b'bplist00'+b'\x22\x3f\xa0\x00\x00'+b'\x08'+b'\x00'*6+b'\x01\x01'+(1).to_bytes(8,'big')+(0).to_bytes(8,'big')+(13).to_bytes(8,'big')
        self.assertEqual(1.25,plistlib.loads(float32))
        result=self.run_script('''
            local v=assert(CP.parsePlist(readAsset("peer.plist")))
            assert(v.type==2 and v.negative==-2 and v.booleans[1] and not v.booleans[2])
            assert(type(v.data)=="userdata" and #v.data>40)
            local nested=assert(CP.parsePlist(v.data))
            assert(nested.data.pts==12.125 and nested.data.layoutID=="é日本🚙")
            assert(CP.parsePlist(readAsset("float32.plist"))==1.25)
            local bytes=binaryPlistBytes(CP.createPlist(v));local hex={}
            for i=1,#bytes do hex[i]=string.format("%02x",string.byte(bytes,i)) end
            return table.concat(hex)
        ''', **{'peer.plist':incoming,'float32.plist':float32})
        self.assertEqual(0,result.returncode,result.stderr)
        actual=plistlib.loads(bytes.fromhex(result.stdout.strip()));expected=plistlib.loads(incoming)
        self.assertEqual(expected,actual)

    def test_binary_plist_refuses_cycles_sparse_arrays_nonfinite_and_expansion(self):
        self.bridge=Path(__file__).with_name('bridge.luau')
        result=self.run_script('''
            local cycle={};cycle.self=cycle
            assert(not pcall(function()CP.createPlist(cycle)end))
            assert(not pcall(function()CP.createPlist({[2]=1})end))
            assert(not pcall(function()CP.createPlist({pts=0/0})end))
            local deep={};local current=deep
            for i=1,40 do current.next={};current=current.next end
            assert(not pcall(function()CP.createPlist(deep)end))
            local repeated=string.rep("x",65536);local values={}
            for i=1,32 do values[i]=repeated end
            assert(not pcall(function()CP.createPlist(values)end))
            assert(CP.parsePlist("not a plist")==nil)
            assert(CP.parsePlist(readAsset("expanded.plist"))==nil)
            assert(CP.parsePlist(readAsset("nulls.plist"))==nil)
            assert(not pcall(function()binaryPlistBytes({})end))
            local a=CP.createPlist({a=1})
            local b=CP.parsePlist(CP.createPlist({data=a})).data
            assert(a==b and a~=CP.createPlist({a=2}))
            return "ok"
        ''', **{'expanded.plist':plistlib.dumps(['x'*65536]*32,fmt=plistlib.FMT_BINARY),
                'nulls.plist':plistlib.dumps([None],fmt=plistlib.FMT_BINARY)})
        self.assertEqual(0,result.returncode,result.stderr)

    def test_binary_plist_cycles_invalid_unicode_and_malformed_offsets_are_bounded(self):
        self.bridge=Path(__file__).with_name('bridge.luau')
        cycle=bytearray(plistlib.dumps([0],fmt=plistlib.FMT_BINARY));cycle[9]=0
        unicode=bytearray(plistlib.dumps('é',fmt=plistlib.FMT_BINARY));unicode[9:11]=b'\xdc\x00'
        valid=plistlib.dumps({'pts':1.25,'items':[1,True,'test']},fmt=plistlib.FMT_BINARY)
        corrupt=[]
        for position in range(len(valid)):
            item=bytearray(valid);item[position]^=255;corrupt.append(bytes(item))
        packed=b''.join(len(item).to_bytes(2,'big')+item for item in corrupt)
        result=self.run_script('''
            assert(CP.parsePlist(readAsset("cycle.plist"))==nil)
            assert(CP.parsePlist(readAsset("unicode.plist"))==nil)
            local corpus=readAsset("corpus.bin");local at=1
            while at<=#corpus do
                local a,b=string.byte(corpus,at,at+1);local n=a*256+b;at+=2
                CP.parsePlist(string.sub(corpus,at,at+n-1));at+=n
            end
            return "ok"
        ''',**{'cycle.plist':bytes(cycle),'unicode.plist':bytes(unicode),'corpus.bin':packed})
        self.assertEqual(0,result.returncode,result.stderr)

    def test_step_stream_preserves_vm_and_binary_transport_between_requests(self):
        self.bridge=Path(__file__).with_name('bridge.luau')
        self.probe.write_text('''
            Offscreen.configureOutput("fixture",40,30)
            local root=CP.DPLayer.create();root.frame=CP.DPRect(0,0,40,30)
            CP.setRootLayer("fixture",root);Offscreen.startProtocolForResearch()
            local count=0;local task=CP.DPLuaTask.create()
            task:run(function()while true do
                count+=1
                local readable=CP.status()
                if readable then
                    local message=assert(CP.parsePlist(CP.receiveMessage()))
                    CP.sendMessage(CP.createPlist({count=count,echo=message}))
                end
                coroutine.yield()
            end end)
            return "initialized"
        ''')
        with StepClient(Path(os.environ['DIPLAY_ULTRA_TEST_HOST']),self.resources,self.bridge,self.probe) as host:
            self.assertEqual([],host.request(6))
            host.request(1,plistlib.dumps({'pts':12.125},fmt=plistlib.FMT_BINARY))
            host.request(2,b'\x01')
            first=plistlib.loads(host.request(6)[0]);self.assertEqual({'count':1,'echo':{'pts':12.125}},first)
            self.assertEqual([],host.request(6))
            host.request(1,plistlib.dumps({'pts':13.125},fmt=plistlib.FMT_BINARY))
            host.request(2,b'\x01')
            self.assertEqual(2,plistlib.loads(host.request(6)[0])['count'])

    def test_invalid_step_terminates_its_session_and_oversized_client_requests_are_refused(self):
        self.bridge=Path(__file__).with_name('bridge.luau')
        self.probe.write_text('Offscreen.configureOutput("fixture",40,30);Offscreen.startProtocolForResearch();return "initialized"')
        with StepClient(Path(os.environ['DIPLAY_ULTRA_TEST_HOST']),self.resources,self.bridge,self.probe) as host:
            with self.assertRaises(ValueError):host.request(1,b'x'*(1024*1024+1))
            with self.assertRaises(RuntimeError):host.request(2,b'\x00')
            self.assertIsNotNone(host.process.poll())
        command=[os.environ['DIPLAY_ULTRA_TEST_HOST'],str(self.resources),str(self.bridge),str(self.probe),
                 '--research-allow-incomplete-cp14','--research-step-stream']
        for raw,expected in [(b'\x01'+(1024*1024+1).to_bytes(4,'big'),b'step size limit'),
                             (b'\x01\x00\x00\x00\x02',b'truncated step payload')]:
            result=subprocess.run(command,input=raw,capture_output=True,timeout=7)
            self.assertNotEqual(0,result.returncode);self.assertIn(expected,result.stderr)

    def test_persistent_tick_keeps_cpu_deadline_and_client_cleans_up_failed_host(self):
        self.bridge=Path(__file__).with_name('bridge.luau')
        self.probe.write_text('''
            local task=CP.DPLuaTask.create()
            task:run(function()while true do end end)
            return "initialized"
        ''')
        with StepClient(Path(os.environ['DIPLAY_ULTRA_TEST_HOST']),self.resources,self.bridge,self.probe) as host:
            with self.assertRaises(RuntimeError):host.request(2,b'\x01')
            self.assertIsNotNone(host.process.poll())


@unittest.skipUnless(os.environ.get('DIPLAY_ULTRA_TEST_HOST') and os.environ.get('DIPLAY_ULTRA_TEST_RESOURCES'),
                     'Explicit research host and prepared private resources required')
class OriginalProtocolIntegrationTest(unittest.TestCase):
    def test_original_entry_requests_ui_after_binary_reset_with_simulated_peer(self):
        resources=Path(os.environ['DIPLAY_ULTRA_TEST_RESOURCES'])
        manifest=json.loads((resources/'research-input.json').read_text())
        self.assertEqual('ddf1317ef051f8ac251c9d892b5ea0651a75e14dd59e0f5ac2ff13b37729d504',manifest['archiveSha256'])
        # Bind this test to the original prepared sources, not edited resource scripts.
        sources={name:digest for name,digest in manifest['files'].items() if name.endswith('.lua')}
        self.assertEqual(193,len(sources))
        for name,digest in sources.items():
            self.assertEqual(digest,hashlib.sha256((resources/name).read_bytes()).hexdigest(),name)
        here=Path(__file__).parent
        run=subprocess.run([os.environ['DIPLAY_ULTRA_TEST_HOST'],str(resources),str(here/'bridge.luau'),
                            str(here/'protocol_probe.luau'),'--research-allow-incomplete-cp14'],
                           capture_output=True,text=True,timeout=7)
        self.assertEqual(0,run.returncode,run.stderr)
        report=json.loads(run.stdout)
        self.assertTrue(report['simulatedPeer'] and report['originalSessionExecuted'] and report['binaryPlists'])
        self.assertFalse(report['phoneConnected'] or report['fullUltraUI'])
        commands=[message['command'] for message in report['observations'] if 'command' in message]
        self.assertEqual([5,17,7],[command['type'] for command in commands])
        self.assertTrue(commands[0]['data']['layoutID'])
        self.assertTrue(commands[1]['data']['allow'])

    def test_persistent_original_session_exchanges_messages_across_separate_steps(self):
        here=Path(__file__).parent
        with StepClient(Path(os.environ['DIPLAY_ULTRA_TEST_HOST']),Path(os.environ['DIPLAY_ULTRA_TEST_RESOURCES']),
                        here/'bridge.luau',here/'persistent_probe.luau') as host:
            host.request(2,b'\x01')
            reset=plistlib.loads(host.request(6)[0]);self.assertEqual(0,reset['type'])
            host.request(1,plistlib.dumps({'type':1,'ssn':reset['ssn'],'psn':1,'asn':reset['psn'],
                                         'version':2,'id':'fixture-peer'},fmt=plistlib.FMT_BINARY))
            host.request(2,b'\x08')
            replies=[plistlib.loads(data) for data in host.request(6)]
            self.assertTrue(any(message['type']==1 for message in replies))
            # No process restart between the reset and foreground/lifecycle events.
            host.request(5,b'\x01');host.request(4,b'\x04');host.request(2,b'\x0a')
            messages=[plistlib.loads(data) for data in host.request(6)]
            commands=[plistlib.loads(message['data']) for message in messages if message['type']==2]
            self.assertTrue(any(command['type']==5 for command in commands))
            self.assertFalse(json.loads(host.request(3))['fullUltraUI'])


@unittest.skipUnless(os.environ.get('DIPLAY_ULTRA_TEST_HOST'), 'Explicit source-built research host required')
class VideoFrameProtocolTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root/'APL').mkdir()
        # Test-owned serializer: no Apple resources are needed for the frame contract.
        (self.root/'APL/json.lua').write_text('''return {encode=function(report)
            local v=report.scene.children[1].video
            return string.format('{"token":%s,"pts":%s,"active":%s,"clock":%s}',
                tostring(v.token or "null"),tostring(v.pts or "null"),tostring(v.active),tostring(CP.PTS))
        end}''')
        self.entry = self.root/'entry.luau'
        self.entry.write_text('''
            Offscreen.configureOutput("fixture",40,30)
            Offscreen.configureVideoInput("phone-video",4,4,1/30)
            assert(#CP.enumerateInputStreams()==1)
            local root=CP.DPLayer.create();root.frame=CP.DPRect(0,0,40,30)
            local video=CP.DPVideoLayer.create("phone-video");video.frame=CP.DPRect(0,0,40,30)
            root:addSublayer(video);CP.setRootLayer("fixture",root);video:subscribe()
            assert(video.streamState=="SUBSCRIBED" and video:getNextPTS()==nil)
            Offscreen.startProtocolForResearch()
            local task=CP.DPLuaTask.create()
            task:run(function()while true do
                if CP.status() then
                    local event=assert(CP.parsePlist(CP.receiveMessage()))
                    if event.action=="defer" then video:deferNextPTS()
                    elseif event.action=="flush" then video:flush()
                    else error("unknown fixture action") end
                end
                coroutine.yield()
            end end)
            return "initialized"
        ''')
        self.host = StepClient(Path(os.environ['DIPLAY_ULTRA_TEST_HOST']),self.root,
                               Path(__file__).with_name('bridge.luau'),self.entry)

    def tearDown(self):
        self.host.close()
        self.temp.cleanup()

    def frame(self, action, **extra):
        return self.host.request(7,plistlib.dumps({'action':action,'input':'phone-video',**extra},fmt=plistlib.FMT_BINARY))

    def scene(self):return json.loads(self.host.request(3))

    def test_frame_offer_commit_defer_and_cancel_preserve_the_right_pixels_and_time(self):
        self.assertEqual({'token':None,'pts':None,'active':True,'clock':0},self.scene())
        state=self.frame('offer',token=100,pts=12.125)
        self.assertEqual(100,state['pendingToken']);self.assertEqual(0,state['currentToken'])
        self.assertEqual({'token':100,'pts':12.125,'active':True,'clock':12.125},self.scene())
        self.frame('commit',token=100)
        self.frame('offer',token=101,pts=12.158)
        self.host.request(1,plistlib.dumps({'action':'defer'},fmt=plistlib.FMT_BINARY))
        self.host.request(2,b'\x01')
        state=self.frame('state');self.assertTrue(state['deferred'])
        self.assertEqual(101,state['pendingToken']);self.assertEqual(100,state['currentToken'])
        self.assertEqual(100,self.scene()['token'])
        self.frame('cancel',token=101)
        self.assertEqual(100,self.scene()['token'])

    def test_flush_invalidates_current_and_pending_frames(self):
        self.frame('offer',token=100,pts=12.125);self.frame('commit',token=100)
        self.frame('offer',token=101,pts=12.158)
        self.host.request(1,plistlib.dumps({'action':'flush'},fmt=plistlib.FMT_BINARY))
        self.host.request(2,b'\x01')
        state=self.frame('state')
        self.assertTrue(state['flushed']);self.assertEqual(0,state['pendingToken']);self.assertEqual(0,state['currentToken'])
        self.assertIsNone(self.scene()['token'])
        with self.assertRaises(RuntimeError):self.frame('commit',token=101)

    def test_reused_frame_token_terminates_instead_of_reusing_stale_scene_state(self):
        self.frame('offer',token=100,pts=12.125);self.frame('commit',token=100)
        with self.assertRaises(RuntimeError):self.frame('offer',token=100,pts=12.158)

    def test_backwards_video_time_is_refused(self):
        self.frame('offer',token=100,pts=12.125);self.frame('commit',token=100)
        with self.assertRaises(RuntimeError):self.frame('offer',token=101,pts=12.0)

    def test_protocol_polls_hold_the_video_clock_until_a_new_latched_frame_is_offered(self):
        self.host.request(2,b'\x0a');self.assertEqual(0,self.scene()['clock'])
        self.frame('offer',token=100,pts=12.125);self.frame('commit',token=100)
        self.host.request(2,b'\x0a')
        self.assertEqual(12.125,self.scene()['clock'])
        self.frame('offer',token=101,pts=12.158);self.frame('cancel',token=101)
        self.assertEqual(12.158,self.scene()['clock'])
        self.assertEqual(100,self.scene()['token'])


if __name__=='__main__':unittest.main()
