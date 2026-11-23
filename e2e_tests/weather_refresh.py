"""Emulator-only weather refresh, same-day cache bypass and icon layout check."""
import datetime
import json
import xml.etree.ElementTree as XML
import device
from device import *

device.RESULTS = RESULTS / ('weather-refresh-' + str(time.time_ns()))
device.RESULTS.mkdir()
RESULTS = device.RESULTS
shell('am', 'force-stop', PKG)
backup = RESULTS / 'before.tar'
with backup.open('wb') as stream:
    subprocess.run([*ADB, 'exec-out', 'run-as', PKG, 'tar', '-cf', '-', '.'], stdout=stream, check=True, timeout=60)
provider_added = False
location = shell('cmd', 'location', 'is-location-enabled').decode().strip()
permission = 'ACCESS_COARSE_LOCATION: granted=true' in shell('dumpsys', 'package', PKG).decode()
mock_mode = shell('appops', 'get', 'com.android.shell', 'android:mock_location').decode()


def pref(key):
    root = XML.fromstring(run('exec-out', 'run-as', PKG, 'cat', 'shared_prefs/season_weather.xml'))
    node = next((n for n in root if n.get('name') == key), None)
    return None if node is None else node.get('value', node.text)


def launch():
    shell('am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(.5)
    tap('洗护'); tap('换季整理')


try:
    run('install', '-r', str(ROOT.parent / 'app/build/outputs/apk/debug/app-debug.apk'))
    shell('pm', 'grant', PKG, 'android.permission.ACCESS_COARSE_LOCATION')
    shell('cmd', 'location', 'set-location-enabled', 'true')
    shell('appops', 'set', 'com.android.shell', 'android:mock_location', 'allow')
    shell('cmd', 'location', 'providers', 'add-test-provider', 'network', '--requiresNetwork')
    provider_added = True
    shell('cmd', 'location', 'providers', 'set-test-provider-enabled', 'network', 'true')
    shell('cmd', 'location', 'providers', 'set-test-provider-location', 'network', '--location', '31.28,120.74', '--accuracy', '3000')
    date = datetime.date.fromisoformat(shell('date', '+%F').decode().strip())
    forecast = {'daily': {'time': [(date+datetime.timedelta(days=i)).isoformat() for i in range(3)],
                          'temperature_2m_max': [11, 12, 13], 'temperature_2m_min': [6, 7, 8]}}
    prefs = XML.Element('map')
    values = {'permission_asked': True, 'fetched_at': int(time.time()*1000), 'fetched_day': str(date),
              'last_attempt_day': str(date), 'last_attempt_at': 1, 'forecast': json.dumps(forecast)}
    for key, value in values.items():
        if isinstance(value, bool): XML.SubElement(prefs, 'boolean', name=key, value=str(value).lower())
        elif isinstance(value, int): XML.SubElement(prefs, 'long', name=key, value=str(value))
        else: XML.SubElement(prefs, 'string', name=key).text = value
    path = RESULTS/'weather.xml'
    XML.ElementTree(prefs).write(path, encoding='utf-8', xml_declaration=True)
    run('push', str(path), '/data/local/tmp/weather-refresh.xml')
    shell('run-as', PKG, 'mkdir', '-p', 'shared_prefs')
    shell('run-as', PKG, 'cp', '/data/local/tmp/weather-refresh.xml', 'shared_prefs/season_weather.xml')
    run('shell', 'run-as', PKG, 'sh', '-c', "'cat > shared_prefs/onboarding.xml'",
        data=b'<?xml version="1.0" encoding="utf-8"?><map><boolean name="has_completed_onboarding" value="true" /></map>')
    run('logcat', '-c')
    launch()
    nodes = screen()
    assert not any('天气来源' in n.get('text', '') or '使用当地气温' == n.get('text') for n in nodes)
    text_node = next(n for n in nodes if n.get('text', '').startswith('未来3天平均最高温 12°C'))
    icon = find(nodes, '刷新天气')
    assert icon is not None
    a = list(map(int, re.findall(r'\d+', text_node.get('bounds'))))
    b = list(map(int, re.findall(r'\d+', icon.get('bounds'))))
    assert abs((a[1]+a[3])-(b[1]+b[3])) <= 2 and b[0] >= a[2], (a, b)
    assert pref('last_attempt_at') == '1', 'Automatic reopen unexpectedly fetched despite same-day cache'
    evidence('inline-refresh')
    tap('刷新天气')
    for _ in range(20):
        if int(pref('last_attempt_at') or 0) > 1: break
        time.sleep(.3)
    first = pref('last_attempt_at')
    assert int(first) > 1, 'Manual refresh was blocked by same-day cache'
    time.sleep(11)
    tap('刷新天气')
    for _ in range(20):
        if pref('last_attempt_at') != first: break
        time.sleep(.3)
    assert int(pref('last_attempt_at')) > int(first), 'Second manual refresh was blocked by daily limit'
    second = pref('last_attempt_at')
    time.sleep(11)
    live = int(pref('fetched_at') or '0') > int(second)
    print('LIVE_WEATHER_SUCCESS' if live else 'LIVE_WEATHER_NOT_CONFIRMED_IN_THIS_NETWORK', flush=True)
    shell('am', 'force-stop', PKG); launch()
    assert pref('last_attempt_at') == second, 'Automatic refresh no longer respects its daily limit'
    assert_no_crash('weather-refresh')
    print('PASS: unboxed inline icon, no source label, manual refresh bypasses fresh cache and daily limit; automatic limit retained', flush=True)
finally:
    shell('am', 'force-stop', PKG)
    if provider_added: shell('cmd', 'location', 'providers', 'remove-test-provider', 'network')
    shell('appops', 'set', 'com.android.shell', 'android:mock_location', 'allow' if 'allow' in mock_mode else 'deny')
    shell('cmd', 'location', 'set-location-enabled', location)
    if not permission: shell('pm', 'revoke', PKG, 'android.permission.ACCESS_COARSE_LOCATION')
    shell('run-as', PKG, 'rm', '-rf', 'databases', 'files', 'shared_prefs')
    run('push', str(backup), '/data/local/tmp/weather-refresh-backup.tar')
    shell('run-as', PKG, 'tar', '-xf', '/data/local/tmp/weather-refresh-backup.tar', '-C', '.')
    shell('rm', '-f', '/data/local/tmp/weather-refresh.xml', '/data/local/tmp/weather-refresh-backup.tar')
    print('RESTORED: original emulator application data and location settings', flush=True)
