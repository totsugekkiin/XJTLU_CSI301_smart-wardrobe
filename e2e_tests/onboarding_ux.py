"""Real-view onboarding on an empty and restored wardrobe; dedicated emulator only."""
import runpy
import xml.etree.ElementTree as XML
import device
from device import *

device.RESULTS = RESULTS / ('onboarding-' + str(time.time_ns()))
device.RESULTS.mkdir()
RESULTS = device.RESULTS
TITLES = ['录入单品','档案与编辑','智能洗护','换季唤醒','灵感穿搭','穿着日历']


def shown(step):
    for _ in range(10):
        nodes=screen()
        if find(nodes,TITLES[step]) is not None:
            assert find(nodes,f'{step+1:02d} / 06') is not None
            assert all(not re.search('[·•●\U0001F000-\U0001FAFF]',n.get('text','')) for n in nodes)
            assert not any('permission_' in n.get('resource-id','') for n in nodes)
            return nodes
        time.sleep(.3)
    raise AssertionError(('Missing onboarding step',step))


def completed():
    raw=run('exec-out','run-as',PKG,'cat','shared_prefs/onboarding.xml')
    return any(n.get('name')=='has_completed_onboarding' and n.get('value')=='true' for n in XML.fromstring(raw))


def restart():
    shell('am','force-stop',PKG)
    shell('am','start','-n',PKG+'/.MainActivity')
    time.sleep(.8)


shell('am','force-stop',PKG)
with (RESULTS/'pre-task-data.tar').open('wb') as backup:
    subprocess.run([*ADB,'exec-out','run-as',PKG,'tar','-cf','-','.'],stdout=backup,check=True,timeout=60)
rotation=shell('settings','get','system','user_rotation').decode().strip()
auto_rotation=shell('settings','get','system','accelerometer_rotation').decode().strip()
font_scale=shell('settings','get','system','font_scale').decode().strip()

try:
    shell('pm','clear',PKG)
    restart()
    shown(0)
    evidence('empty-step-01')
    with snapshot('empty') as db:
        assert db.execute('SELECT COUNT(*) FROM garment').fetchone()==(0,)
    # The original add control lies inside the hole. It must remain noninteractive.
    shell('input','tap','600','100')
    shown(0)
    shell('input','tap','350','1200')
    shown(0)
    tap('下一步')
    shown(1)
    evidence('empty-step-02-placeholder')
    shell('input','keyevent','4')
    shown(0)
    tap('下一步');shown(1)
    tap('下一步');shown(2)
    evidence('empty-step-03')
    tap('上一步');shown(1)
    tap('跳过')
    assert completed()
    restart()
    assert find(screen(),'录入单品') is None
    visible('0 件单品')
    print('PASS: automatic empty tour, blocked backdrop/target touches, back/previous, skip persistence and zero sample data',flush=True)

    tap('筛选')
    visible('已归档');visible('使用指南')
    evidence('guide-replay-entry')
    tap('使用指南');shown(0)
    for step in range(6):
        shown(step)
        evidence(f'empty-replay-step-{step+1:02d}')
        if step < 5: tap('下一步')
    tap('开始使用')
    visible('0 件单品')
    assert completed()
    restart()
    assert find(screen(),'录入单品') is None
    with snapshot('empty-after-tour') as db:
        for table in ['garment','wash','care_event','outfit','history']:
            assert db.execute('SELECT COUNT(*) FROM '+table).fetchone()==(0,),table
    print('PASS: filter replay, all six steps, completion persistence and no database sample/history writes',flush=True)

    # Restore the actual pre-test wardrobe, then replay without modifying its records.
    runpy.run_path(str(ROOT/'restore_data.py'),run_name='__main__')
    time.sleep(.8)
    if find(screen(),'01 / 06') is None:
        tap('筛选');tap('使用指南')
    shown(0)
    with snapshot('real-before') as db:
        tables=[r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
        baseline={t:db.execute('SELECT * FROM "'+t+'" ORDER BY rowid').fetchall() for t in tables}
    tap('下一步');shown(1)
    evidence('real-step-02')
    # Rotation is a layout change (the activity handles orientation in its manifest).
    shell('settings','put','system','accelerometer_rotation','0')
    shell('settings','put','system','user_rotation','1')
    time.sleep(.8)
    shown(1)
    evidence('real-landscape-step-02')
    shell('settings','put','system','user_rotation','0')
    time.sleep(.8)
    shown(1)
    shell('settings','put','system','font_scale','1.3')
    time.sleep(1)
    shown(1)
    evidence('real-large-text-step-02')
    shell('settings','put','system','font_scale','1.0')
    time.sleep(1)
    shown(1)
    for step in range(2,6):
        tap('下一步');shown(step)
        evidence(f'real-step-{step+1:02d}')
        if step == 3:
            shell('input','keyevent','3')
            time.sleep(.6)
            shell('am','kill',PKG)
            shell('am','start','-n',PKG+'/.MainActivity')
            time.sleep(.8)
            shown(3)
            evidence('restored-step-04')
    tap('开始使用')
    with snapshot('real-after') as db:
        for table,rows in baseline.items():
            assert db.execute('SELECT * FROM "'+table+'" ORDER BY rowid').fetchall()==rows,table
        assert_integrity(db)
    assert_no_crash('onboarding')
    print('PASS: real garment targets, rotation, large text/activity recreation, process restoration and unchanged original database',flush=True)
finally:
    shell('settings','put','system','user_rotation',rotation)
    shell('settings','put','system','accelerometer_rotation',auto_rotation)
    shell('settings','put','system','font_scale',font_scale if font_scale != 'null' else '1.0')
    runpy.run_path(str(ROOT/'restore_data.py'),run_name='__main__')
