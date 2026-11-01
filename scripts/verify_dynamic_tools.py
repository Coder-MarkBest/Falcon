#!/usr/bin/env python3
"""Real DeepSeek acceptance on an Automotive emulator; never reads credentials.

Install/start the rebuilt Cross Server, Gateway and Agent first, configure the
Agent's private key, then run with ADB, ANDROID_SERIAL and ANDROID_USER selected.
Only the independent provider APK is installed by this script. It registers and
unregisters via UI, force-stops the provider, and leaves it registered at the end.
Outputs synthetic-case summaries only; private execution traces remain on-device.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get('ADB') or shutil.which('adb')
USER = os.environ.get('ANDROID_USER', '10')
AGENT = 'com.falcon.agent.demo'
GATEWAY = 'com.falcon.mcp.gateway'
PROVIDER = 'com.falcon.comfort.provider'
TOOL = 'comfort_air_quality'
UTTERANCE = '只查询主驾座舱空气质量，告诉我二氧化碳和PM2.5读数并给通风建议。不要导航、放音乐或执行任何调节。'


def adb(*args):
    return subprocess.run([ADB, *args], text=True, capture_output=True, check=True, timeout=30).stdout


def shell(*args):
    return adb('shell', *args)


def nodes():
    shell('uiautomator', 'dump', '/sdcard/falcon-dynamic-ui.xml')
    return list(ET.fromstring(shell('cat', '/sdcard/falcon-dynamic-ui.xml')).iter('node'))


def tap(text):
    button = next(n for n in nodes() if n.attrib.get('text') == text)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', button.attrib['bounds']))
    shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def trace():
    raw = shell('run-as', AGENT, '--user', USER, 'cat', 'files/last-execution.jsonl')
    rows = []
    for line in raw.splitlines():
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            pass
    return rows


def provider_calls():
    raw = shell('run-as', PROVIDER, '--user', USER, 'cat', 'shared_prefs/tools.xml')
    return int(next((n.attrib['value'] for n in ET.fromstring(raw) if n.attrib.get('name') == 'calls'), '0'))


def vehicle_state():
    raw = shell('run-as', 'com.falcon.cross.server', '--user', USER, 'cat', 'shared_prefs/agent-demo-vehicle.xml')
    return json.loads(next(n.text for n in ET.fromstring(raw) if n.attrib.get('name') == 'snapshot'))


def fingerprint(package):
    apk = shell('pm', 'path', '--user', USER, package).strip().splitlines()[0].removeprefix('package:')
    return shell('sha256sum', apk).split()[0]


def action(register):
    shell('am', 'start', '--user', USER, '-n', PROVIDER+'/.ComfortActivity')
    tap('注册空气质量工具' if register else '注销空气质量工具')


prompt_hash = None

def run_case(label, present):
    global prompt_hash
    previous = next((r['data']['workflowId'] for r in trace() if r['stage'] == 'mcp.tools.list'), None)
    before = vehicle_state()
    calls_before = provider_calls() if label != 'before_provider_install' else 0
    shell('am', 'start', '-S', '--user', USER, '-n', AGENT+'/.AgentActivity', '--es', 'test_utterance', shlex.quote(UTTERANCE))
    ui = nodes()
    assert any(n.attrib.get('class') == 'android.widget.Switch' and n.attrib.get('checked') == 'true' for n in ui), 'Real model switch disabled'
    tap('执行场景')
    deadline = time.monotonic()+125
    while time.monotonic() < deadline:
        rows = trace()
        current = next((r['data']['workflowId'] for r in rows if r['stage'] == 'mcp.tools.list'), None)
        if any(r['stage'] == 'agent.error' for r in rows):
            raise RuntimeError('Agent error; inspect on-device private trace')
        final = next((r for r in rows if r['stage'] == 'workflow.result'), None)
        if current != previous and final:
            break
        time.sleep(.5)
    else:
        raise RuntimeError('Model task timed out')
    assert final['data']['model'] == 'DeepSeek'
    requests = [r['data']['body'] for r in rows if r['stage'] == 'deepseek.request']
    names = [t['function']['name'] for t in requests[0].get('tools', [])]
    assert (TOOL in names) == present, (label, names)
    assert len(names) == (14 if present else 13), names
    system_hash = hashlib.sha256(requests[0]['messages'][0]['content'].encode()).hexdigest()
    if prompt_hash is None:
        prompt_hash = system_hash
    assert prompt_hash == system_hash, 'System prompt changed across cases'
    for request in requests:
        assert request['tool_choice'] == 'auto'
        assert request['tools'] == requests[0]['tools']
    invocations = [r['data'] for r in rows if r['stage'] == 'mcp.tools.call.request']
    model_calls = [c for r in rows if r['stage'] == 'deepseek.response' for c in r['data']['choice']['message'].get('tool_calls', [])]
    assert [c['function']['name'] for c in model_calls] == ([TOOL] if present else [])
    assert [r['name'] for r in invocations] == ([TOOL] if present else [])
    results = {r['data']['toolCallId']: r['data']['result'] for r in rows if r['stage'] == 'agent.tool.result'}
    actual_results = {r['data']['toolCallId']: r['data']['result'] for r in rows if r['stage'] == 'mcp.tools.call.result'}
    assert results == actual_results
    assert {m['tool_call_id']: json.loads(m['content']) for m in requests[-1]['messages'] if m['role'] == 'tool'} == results
    if present:
        assert invocations[0]['arguments'] == {'zone': 'driver'}
        returned = next(iter(results.values()))['structuredContent']
        assert returned['co2Ppm'] == 1450 and returned['pm25'] == 12 and returned['zone'] == 'driver'
    assert before == vehicle_state(), 'Unexpected vehicle side effect'
    if label != 'before_provider_install':
        assert provider_calls() == calls_before + int(present), 'Provider handler count mismatch'
    print(json.dumps({'case': label, 'catalogSize': len(names), 'rounds': len(requests),
                      'calls': [r['name'] for r in invocations], 'vehicleStateUnchanged': True,
                      'providerEffectVerified': present, 'summary': final['data']['summary']}, ensure_ascii=False), flush=True)


def main():
    if not ADB:
        raise SystemExit('Set ADB to the Android SDK adb executable')
    hosts_before = {p: fingerprint(p) for p in [GATEWAY, AGENT]}
    gateway_pid = shell('pidof', GATEWAY).strip()
    assert gateway_pid, 'Start Gateway first'
    print('FROZEN_HOST_APKS '+json.dumps(hosts_before), flush=True)
    # Fresh install is deliberate: no provider tool is present in the initial catalog.
    installed = shell('pm', 'list', 'packages', '--user', USER, PROVIDER)
    if PROVIDER in installed:
        action(False)
    run_case('before_provider_install' if PROVIDER not in installed else 'before_registration', False)
    apk = Path(__file__).resolve().parents[1] / 'falcon-comfort-provider/build/outputs/apk/debug/falcon-comfort-provider-debug.apk'
    adb('install', '-r', str(apk))
    try:
        action(True)
        run_case('registered', True)
        action(False)
        run_case('unregistered', False)
        action(True)
        run_case('reregistered', True)
        shell('am', 'force-stop', '--user', USER, PROVIDER)
        time.sleep(1)
        run_case('offline_force_stop', False)
        action(True)
        run_case('reconnected', True)
    finally:
        action(True)
    assert hosts_before == {p: fingerprint(p) for p in hosts_before}, 'Gateway/Agent APK changed'
    assert gateway_pid == shell('pidof', GATEWAY).strip(), 'Gateway process restarted'
    print('PASS: six real-model tasks; host APKs unchanged; Gateway PID unchanged', flush=True)


if __name__ == '__main__':
    main()
