#!/usr/bin/env python3
"""Real-model acceptance test on the Automotive demo emulator (stdlib only).

Requires installed debug APKs and an already configured private DeepSeek key.
Uses the UI to call the model; never reads the key or exports private traces.
Temporarily edits demo energy/home/offline fixtures and restores them in finally.
Navigation/music effects from the authorized scenarios remain, as in normal use.
ADB, ANDROID_SERIAL and ANDROID_USER can select the local emulator/user.
"""
import subprocess, json, time, re, shlex, hashlib, os, shutil, xml.etree.ElementTree as ET

ADB=os.environ.get('ADB') or shutil.which('adb')
USER=os.environ.get('ANDROID_USER', '10')
if not ADB:
    raise SystemExit('Set ADB to the Android SDK platform-tools/adb executable.')
DEMO='com.falcon.agent.demo'
SERVER='com.falcon.cross.server'
GATEWAY='com.falcon.mcp.gateway'
PREF='shared_prefs/agent-demo-vehicle.xml'

def adb(*args, data=None):
    return subprocess.run([ADB,*args],input=data,text=True,capture_output=True,check=True,timeout=30).stdout

def shell(*args, data=None):
    return adb('shell',*args,data=data)

def snapshot():
    root=ET.fromstring(shell('run-as',SERVER,'--user',USER,'cat',PREF))
    node=next(n for n in root if n.attrib.get('name')=='snapshot')
    return root,node,json.loads(node.text)

def change_home(value):
    # Stop both processes that could retain the vehicle snapshot before modifying only this fixture flag.
    shell('am','force-stop','--user',USER,DEMO)
    shell('am','force-stop','--user',USER,GATEWAY)
    shell('am','force-stop','--user',USER,SERVER)
    root,node,state=snapshot()
    previous=state['homeSet']
    state['homeSet']=value
    node.text=json.dumps(state,ensure_ascii=False,separators=(',',':'))
    shell('run-as',SERVER,'--user',USER,'sh','-c',shlex.quote('cat > '+PREF),data=ET.tostring(root,encoding='unicode'))
    assert snapshot()[2]['homeSet']==value
    shell('am','start','--user',USER,'-n',SERVER+'/.ServerActivity')
    # Starting the Activity is asynchronous: first wait for business route registration.
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        pid=subprocess.run([ADB,'shell','pidof',SERVER],capture_output=True,text=True).stdout.strip()
        if pid and 'Typed service and Dynamic RPC registered' in adb('logcat','-d','--pid='+pid,'-t','200'):
            break
        time.sleep(.5)
    else: raise RuntimeError('Server routes not ready')
    shell('am','start','--user',USER,'-n',GATEWAY+'/.GatewayActivity')
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        pid=subprocess.run([ADB,'shell','pidof',GATEWAY],capture_output=True,text=True).stdout.strip()
        if pid and 'Connected to com.falcon.cross.server:server' in adb('logcat','-d','--pid='+pid,'-t','200'):
            print('FALCON_READY homeSet='+str(value),flush=True)
            break
        time.sleep(.5)
    else: raise RuntimeError('Gateway peer connection not ready')
    return previous

def trace():
    raw=shell('run-as',DEMO,'--user',USER,'cat','files/last-execution.jsonl')
    rows=[]
    for line in raw.splitlines():
        try: rows.append(json.loads(line))
        except json.JSONDecodeError: pass # A currently written final line can be incomplete.
    return rows

catalog_hash=None
system_hash=None

def run_case(label, utterance, allowed, expected_writes):
    global catalog_hash,system_hash
    before=snapshot()[2]
    previous_workflow=next((r['data']['workflowId'] for r in trace() if r['stage']=='mcp.tools.list'), None)
    print('START',label,utterance,flush=True)
    shell('am','start','-S','--user',USER,'-n',DEMO+'/.AgentActivity','--es','test_utterance',shlex.quote(utterance))
    shell('uiautomator','dump','/sdcard/falcon-scenario-ui.xml')
    nodes=list(ET.fromstring(shell('cat','/sdcard/falcon-scenario-ui.xml')).iter('node'))
    assert any(n.attrib.get('class')=='android.widget.Switch' and n.attrib['checked']=='true' for n in nodes), 'Real DeepSeek switch not enabled'
    assert any(n.attrib.get('class')=='android.widget.EditText' and n.attrib['text']==utterance for n in nodes), 'Utterance not entered correctly'
    button=next(n for n in nodes if n.attrib.get('text')=='执行场景')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',button.attrib['bounds']))
    shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
    deadline=time.monotonic()+125
    while time.monotonic()<deadline:
        rows=trace()
        reqs=[r for r in rows if r['stage']=='deepseek.request']
        current_workflow=next((r['data']['workflowId'] for r in rows if r['stage']=='mcp.tools.list'), None)
        if current_workflow!=previous_workflow and any(r['stage']=='agent.error' for r in rows):
            raise RuntimeError('Agent failed; inspect its private execution trace on the emulator.')
        if reqs and current_workflow!=previous_workflow and reqs[0]['data']['body']['messages'][1]['content']==utterance:
            final=next((r for r in rows if r['stage'] in ['workflow.result','agent.error']),None)
            if final: break
        time.sleep(.6)
    else: raise RuntimeError('Scenario timed out')
    responses=[r for r in rows if r['stage']=='deepseek.response']
    reqs=[r for r in rows if r['stage']=='deepseek.request']
    requests=[r['data'] for r in rows if r['stage']=='mcp.tools.call.request']
    results={r['data']['toolCallId']:r['data']['result'] for r in rows if r['stage']=='mcp.tools.call.result'}
    feedback={r['data']['toolCallId']:r['data']['result'] for r in rows if r['stage']=='agent.tool.result'}
    modelcalls=[(r['data']['round'],c) for r in responses for c in r['data']['choice']['message'].get('tool_calls',[])]
    after=snapshot()[2]
    errors=[]
    def check(ok,msg):
        if not ok: errors.append(msg)
    check(final['stage']=='workflow.result','agent did not finish normally')
    check(final['data'].get('model')=='DeepSeek','not real DeepSeek')
    for r in reqs:
        body=r['data']['body']
        dh=hashlib.sha256(json.dumps(body['tools'],ensure_ascii=False,sort_keys=True).encode()).hexdigest()
        sh=hashlib.sha256(body['messages'][0]['content'].encode()).hexdigest()
        if catalog_hash is None: catalog_hash=dh;system_hash=sh
        check(dh==catalog_hash and sh==system_hash,'tool definitions or prompt changed across cases')
        check(len(body['tools'])==13 and body['tool_choice']=='auto','catalog was filtered or tool choice was forced')
        for message in body['messages']:
            if message['role']=='tool': check(json.loads(message['content'])==feedback[message['tool_call_id']], 'tool feedback mismatch')
    last_messages=reqs[-1]['data']['body']['messages']
    check(set(feedback)=={m['tool_call_id'] for m in last_messages if m['role']=='tool'}, 'not all results reached final model request')
    check(set(c['function']['name'] for _,c in modelcalls)<=set(allowed),'model selected unexpected function')
    check(set(r['name'] for r in requests)<=set(allowed),'unexpected tool reached MCP')
    workflow=next(r['data']['workflowId'] for r in rows if r['stage']=='mcp.tools.list')
    new_writes={k:v for k,v in after['dedup'].items() if k not in before['dedup']}
    actual_writes=[]
    for key,record in new_writes.items():
        match=next((r for r in requests if key==f"demo_driver:{workflow}:{r['actionId']}"),None)
        check(match is not None,'unattributed server write')
        if match:
            name,args=record['fingerprint'].split(':',1)
            check(name==match['name'] and json.loads(args)==match['arguments'],'server args differ from model tool request')
            check(record['result']==results[match['toolCallId']].get('structuredContent'),'server result differs from MCP')
            actual_writes.append(name)
    check(sorted(actual_writes)==sorted(expected_writes),'actual persisted writes differ from expectation')
    check(before['temperature']==after['temperature'] and before['version']==after['version'],'temperature changed unexpectedly')
    if 'navigation_start' not in expected_writes: check(before['navigation']==after['navigation'],'navigation changed unexpectedly')
    if 'media_play' not in expected_writes: check(before['media']==after['media'],'media changed unexpectedly')
    names=[c['function']['name'] for _,c in modelcalls]
    report={'scenario':label,'rounds':len(responses),'modelToolCalls':len(modelcalls),
        'actualMcpCalls':len(requests),'tools':names,'persistedWrites':actual_writes,'failures':errors}
    print('REPORT '+json.dumps(report,ensure_ascii=False),flush=True)
    if errors: raise RuntimeError('Case assertions failed: '+str(errors))
    return report


original={k:(k in snapshot()[2],snapshot()[2].get(k)) for k in ['energy','firstStationOffline','homeSet']}
def fixture(range_km):
    for pkg in [DEMO,GATEWAY,SERVER]: shell('am','force-stop','--user',USER,pkg)
    root,node,state=snapshot()
    state.update(energy={'socPercent':18 if range_km==24 else 70,'estimatedRangeKm':range_km,'reserveKm':10,'connector':'gbt_dc'}, firstStationOffline=True,homeSet=True)
    node.text=json.dumps(state,ensure_ascii=False,separators=(',',':'))
    shell('run-as',SERVER,'--user',USER,'sh','-c',shlex.quote('cat > '+PREF),data=ET.tostring(root,encoding='unicode'))
    change_home(True)
nav=['energy_get_state','profile_resolve_place','navigation_plan_route','navigation_start','navigation_get_state']
charge=['charging_search','charging_check_station']
try:
    fixture(24)
    run_case('low_energy_offline_fallback','我要回家，电量不多。先判断能否保留10公里续航余量直接回家；如果不够，就找回家沿途功率至少120千瓦、绕路不超过10分钟、排队不超过10分钟的充电站，优先单价最低。首选离线就换其他符合条件的站。导航过去并放轻松音乐，空调别动，不要预约或付款。',nav+charge+['media_search','media_play','media_get_state'],['navigation_start','media_play'])
    assert snapshot()[2]['navigation']['destinationRef']=='demo:charge:park'
    assert any(r['stage']=='mcp.tools.call.result' and r['data']['result'].get('_meta',{}).get('falcon/errorCode')=='STATION_OFFLINE' for r in trace())
    fixture(120)
    run_case('sufficient_range_direct_home','先看电量和回家路程，如果能保留10公里余量就直接导航回家，不用找充电站；不够才找功率至少120千瓦、绕路和排队都不超过10分钟的站。不要放音乐，也不要动空调。',nav,['navigation_start'])
    assert snapshot()[2]['navigation']['destinationRef']=='demo_driver:home'
    fixture(24)
    run_case('no_eligible_station','电量不多，先判断能否保留10公里余量回家。不够就找回家沿途功率至少200千瓦、绕路最多2分钟、排队最多5分钟的充电站。没有完全符合条件的站就告诉我，不要降低条件，不要启动导航，不要播放音乐，空调别动。',['energy_get_state','profile_resolve_place','navigation_plan_route','charging_search'],[])
    assert any(r['stage']=='mcp.tools.call.result' and r['data']['result'].get('structuredContent',{}).get('items')==[] for r in trace())
finally:
    for pkg in [DEMO,GATEWAY,SERVER]: shell('am','force-stop','--user',USER,pkg)
    root,node,state=snapshot()
    for k,(present,value) in original.items():
        if present: state[k]=value
        else: state.pop(k,None)
    node.text=json.dumps(state,ensure_ascii=False,separators=(',',':'))
    shell('run-as',SERVER,'--user',USER,'sh','-c',shlex.quote('cat > '+PREF),data=ET.tostring(root,encoding='unicode'))
    change_home(original['homeSet'][1])
    print('FIXTURES_RESTORED',flush=True)
print('ALL_THREE_CHARGING_CASES_PASSED',flush=True)
