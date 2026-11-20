"""Unified garment UI regression, restricted to a backed-up emulator."""
import json
import runpy
import device
from device import *

device.RESULTS = RESULTS / ('unified-garment-' + str(time.time_ns()))
device.RESULTS.mkdir()
RESULTS = device.RESULTS
TITLE = '浅灰半拉链卫衣'


def visible(label):
    nodes = screen()
    if find(nodes, label) is None:
        evidence('failure')
        raise AssertionError((label, [(n.get('text'), n.get('content-desc')) for n in nodes
                                     if n.get('text') or n.get('content-desc')]))


def enabled(label, expected):
    node = find(screen(), label)
    assert node is not None and node.get('enabled') == str(expected).lower(), (label, expected)


def restart():
    shell('am', 'force-stop', PKG)
    shell('am', 'start', '-n', PKG + '/.MainActivity')


def tab(label):
    top()
    tap(label + ' Tab')


def fixture():
    restart()
    time.sleep(1)
    shell('am', 'force-stop', PKG)
    with (RESULTS / 'pre-task-data.tar').open('wb') as backup:
        subprocess.run([*ADB, 'exec-out', 'run-as', PKG, 'tar', '-cf', '-', '.'],
                       stdout=backup, check=True, timeout=60)
    with snapshot('source') as source, sqlite3.connect(RESULTS / 'fixture.sqlite') as db:
        source.backup(db)
        today = shell('date', '+%F').decode().strip()
        params = (TITLE, 'unified-original.jpg', 'unified-processed.jpg', 'unified-processed.jpg', today)
        garment = db.execute('''INSERT INTO garment(name,type,brand,fabric,original,processed,photo,seasons,
            favorite,dirty,stored,care,symbols,source_text,label_text,wash_every,idle_days,created,archived)
            VALUES(?, '上衣','adidas','棉 / 聚酯纤维',?,?,?,5,0,0,0,'温和洗涤，阴凉处晾干',
            '最高 30°C\n不可漂白\n阴凉处悬挂晾干\n低温熨烫 · 一点\n不可干洗',
            '商品截图原文','30°C gentle wash',10,90,?,0)''', params).lastrowid
        db.execute('INSERT INTO color VALUES(?,0,?, ?,75,1)', (garment, '浅灰色', -3355444))
        db.execute('INSERT INTO color VALUES(?,1,?, ?,25,0)', (garment, '白色', -1))
        for _ in range(2):
            db.execute('INSERT INTO wash(garment,day) VALUES(?,?)', (garment, today))
        outfit = db.execute("INSERT INTO outfit(name,scene) VALUES('周末日常','校园')").lastrowid
        db.execute('INSERT INTO outfit_layer VALUES(?,?,?,.5,.5,.4)', (outfit, 0, garment))
        history = db.execute("INSERT INTO history(outfit,name,scene,day) VALUES(?,'周末日常','校园',?)", (outfit, today)).lastrowid
        db.execute('INSERT INTO history_garment VALUES(?,?)', (history, garment))
        db.execute('INSERT INTO care_event(garment,day,note,wash_checkpoint) VALUES(?,?,?,0)', (garment,today,'已检查领口与袖口'))
    run('push', str(RESULTS / 'fixture.sqlite'), '/data/local/tmp/unified-fixture.sqlite')
    shell('run-as', PKG, 'rm', '-f', 'databases/wardrobe.sqlite-wal', 'databases/wardrobe.sqlite-shm')
    shell('run-as', PKG, 'cp', '/data/local/tmp/unified-fixture.sqlite', 'databases/wardrobe.sqlite')
    sample = ROOT.parent.parent.parent / 'outputs/Smart-Wardrobe-Clothing-30/05_Grey_Sweatshirts_13089.jpg'
    assert sample.exists(), sample
    run('push', str(sample), '/data/local/tmp/unified-sample.jpg')
    shell('run-as', PKG, 'mkdir', '-p', 'files/photos')
    for name in ['unified-original.jpg', 'unified-processed.jpg']:
        shell('run-as', PKG, 'cp', '/data/local/tmp/unified-sample.jpg', 'files/photos/' + name)
    if os.environ.get('WARDROBE_FIXTURE_CUTOUT'):
        run('push',os.environ['WARDROBE_FIXTURE_CUTOUT'],'/data/local/tmp/unified-cutout.png')
        shell('run-as',PKG,'cp','/data/local/tmp/unified-cutout.png','files/photos/unified-processed.jpg')
    shell('logcat', '-c')
    return garment


def main():
    try:
        garment = fixture()
        restart()
        tap(TITLE)
        enabled('保存', False)
        tap('看原图')
        enabled('保存', False)
        tap('看封面')
        enabled('保存', False)
        text('名称 *', 'InlineEdited')
        enabled('保存', True)
        tab('洗护')
        enabled('保存', True)
        tab('记录')
        visible('累计洗涤次数')
        tap('关闭')
        visible('放弃未保存修改？')
        tap('取消')
        tab('档案')
        visible('InlineEdited')
        tap('返回')
        tap('放弃修改')
        tap(TITLE)
        enabled('保存', False)
        with snapshot('discarded') as db:
            assert db.execute('SELECT name FROM garment WHERE id=?', (garment,)).fetchone() == (TITLE,)
        print('PASS: preview is read-only; dirty tracking across tabs; cancel/discard on both close controls', flush=True)

        tap('更多操作')
        tap('用原图重新抠图')
        time.sleep(2)
        for _ in range(20):
            if find(screen(), '保存') is not None:
                break
            time.sleep(1)
        enabled('保存', True)
        tap('保存')
        time.sleep(1)
        enabled('保存', False)
        evidence('01-profile-photo')
        with snapshot('photo-saved') as db:
            row = db.execute('SELECT original,processed,photo FROM garment WHERE id=?', (garment,)).fetchone()
            assert row[0] and row[1] and row[2] == row[1], row
        print('PASS: existing garment reprocessing and save stays on unified page', flush=True)

        tab('洗护')
        evidence('02-care')
        text('洗护说明', 'GentleWashOnly')
        text('洗涤提醒间隔（次）', '0')
        text('闲置提醒间隔（天）', '90')
        enabled('保存', True)
        tap('保存')
        enabled('保存', False)
        with snapshot('care-saved') as db:
            assert db.execute('SELECT care,wash_every,idle_days FROM garment WHERE id=?',(garment,)).fetchone() == ('GentleWashOnly',0,90)
        tab('记录')
        shell('input','swipe','350','1180','350','740','300')
        evidence('03-records')
        visible('周末日常')
        tap('查看洗涤历史')
        visible('洗涤历史')
        back()
        print('PASS: care inline edits persisted; reminders; usage/history and linked outfits', flush=True)

        top()
        tap('标为待洗')
        visible('已洗')
        tap('已洗')
        visible('已记录今日洗涤')
        tap('撤销')
        visible('已洗')
        tap('更多操作')
        tap('标为洁净')
        visible('标为待洗')
        tap('收起衣物')
        visible('取出衣物')
        tap('取出衣物')
        visible('标为待洗')
        tap('收藏')
        visible('取消收藏')
        enabled('保存', False)
        tap('更多操作')
        tap('归档衣物')
        visible('恢复到衣橱')
        tap('恢复到衣橱')
        visible('标为待洗')
        with snapshot('states') as db:
            assert db.execute('SELECT favorite,dirty,stored,archived FROM garment WHERE id=?',(garment,)).fetchone()==(1,0,0,0)
            assert db.execute('SELECT count(*) FROM wash WHERE garment=?',(garment,)).fetchone()==(2,)
        print('PASS: favourite, dirty/wash/undo, storage and archive/restore preserve original state rules',flush=True)

        tap('关闭')
        tap('＋ 添加')
        tap('手动建档')
        enabled('完成添加', False)
        evidence('04-add')
        text('名称 *','UnifiedNew')
        enabled('完成添加', False)
        tap('选择类别')
        tap('衬衫')
        enabled('完成添加', True)
        tap('完成添加')
        enabled('保存', False)
        with snapshot('new-saved') as db:
            assert db.execute("SELECT count(*) FROM garment WHERE name='UnifiedNew' AND type='衬衫'").fetchone()==(1,)
            assert_integrity(db)
        tap('关闭')
        visible('UnifiedNew')
        assert_no_crash('unified')
        print('PASS: shared new form; required-field gating; save succeeds and remains in unified view',flush=True)
    finally:
        if (RESULTS / 'pre-task-data.tar').exists():
            runpy.run_path(str(ROOT / 'restore_data.py'), run_name='__main__')



if __name__ == "__main__":
    main()
