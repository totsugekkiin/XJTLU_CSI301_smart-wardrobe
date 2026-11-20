"""Real coarse-provider request attempt, offline fallback and daily throttling."""
from season_weather_ux import *

wifi=shell('settings','get','global','wifi_on').decode().strip()
mobile=shell('settings','get','global','mobile_data').decode().strip()
location=shell('cmd','location','is-location-enabled').decode().strip()
mock_mode=shell('appops','get','com.android.shell','android:mock_location').decode()
provider_added=False

try:
    season_fixture()
    shell('pm','grant',PKG,NETWORK_PERMISSION)
    cache(12)
    visible('冬季衣橱待唤醒')
    shell('svc','wifi','disable')
    shell('svc','data','disable')
    time.sleep(2)
    restart()
    tap('洗护')
    assert all(not n.get('text','').startswith('未来3天平均最高温') for n in screen())
    evidence('offline-month-fallback')
    print('PASS: offline ignores temperature cache and falls back to the device month without an error popup',flush=True)

    shell('svc','wifi','enable')
    shell('svc','data','enable')
    time.sleep(2)
    shell('cmd','location','set-location-enabled','true')
    shell('appops','set','com.android.shell','android:mock_location','allow')
    shell('cmd','location','providers','add-test-provider','network','--requiresNetwork')
    provider_added=True
    shell('cmd','location','providers','set-test-provider-enabled','network','true')
    shell('cmd','location','providers','set-test-provider-location','network','--location','31.28,120.74','--accuracy','3000')
    cache(12,age=25*60*60*1000,extra={'last_attempt_day':'','fetched_day':''})
    for _ in range(12):
        if pref_value('last_attempt_at'): break
        time.sleep(.5)
    started=pref_value('last_attempt_at')
    assert started is not None,'Coarse network fix never reached the HTTPS fetch'
    time.sleep(10)
    result='LIVE_SUCCESS' if int(pref_value('fetched_at') or '0')>int(started) else 'LIVE_NOT_RETRIEVED_IN_THIS_NETWORK'
    (RESULTS/'live-weather-result.txt').write_text(result,encoding='utf-8')
    print('RESULT:',result,flush=True)
    restart()
    tap('洗护')
    assert pref_value('last_attempt_at')==started,'More than one request in the same day'
    package=shell('dumpsys','package',PKG).decode()
    assert 'android.permission.ACCESS_FINE_LOCATION' not in package
    assert 'android.permission.ACCESS_BACKGROUND_LOCATION' not in package
    assert 'android.permission.ACCESS_COARSE_LOCATION: granted=true' in package
    assert_no_crash('season-network')
    print('PASS: coarse-only fix reaches the HTTPS request, same-day reopen makes no second request, all failures stay silent',flush=True)
finally:
    shell('am','force-stop',PKG)
    if provider_added: shell('cmd','location','providers','remove-test-provider','network')
    shell('appops','set','com.android.shell','android:mock_location','allow' if 'allow' in mock_mode else 'deny')
    shell('cmd','location','set-location-enabled',location)
    shell('svc','wifi','enable' if wifi=='1' else 'disable')
    shell('svc','data','enable' if mobile=='1' else 'disable')
    if (RESULTS/'pre-task-data.tar').exists():
        runpy.run_path(str(ROOT/'restore_data.py'),run_name='__main__')
