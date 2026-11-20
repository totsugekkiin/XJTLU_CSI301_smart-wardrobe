"""Deletion/multi-select UI + SQLite/files regression on a backed-up emulator."""
import base64
import json
import runpy
import device
from device import *

device.RESULTS = RESULTS / ('delete-ux-' + str(time.time_ns()))
device.RESULTS.mkdir()
RESULTS = device.RESULTS
# Open once to migrate the existing database before taking a restorable backup.
shell('am', 'start', '-n', PKG + '/.MainActivity')
time.sleep(3)
shell('am', 'force-stop', PKG)
with (RESULTS / 'pre-task-data.tar').open('wb') as backup:
    subprocess.run([*ADB, 'exec-out', 'run-as', PKG, 'tar', '-cf', '-', '.'],
                   stdout=backup, check=True, timeout=180)
files = []
ids = {}

def open_item(name):
    shell('am', 'force-stop', PKG)
    shell('am', 'start', '-n', PKG + '/.MainActivity')
    tap('筛选')
    text('搜索名称或品牌', name)
    tap('应用筛选')
    tap(name)

def links_absent(db, deleted):
    assert_integrity(db)
    for item in deleted:
        for table in ('garment', 'color', 'wash', 'care_event', 'outfit_layer', 'history_garment'):
            key = 'id' if table == 'garment' else 'garment'
            assert db.execute(f'SELECT count(*) FROM {table} WHERE {key}=?', (item,)).fetchone() == (0,), table
    for (layers,) in db.execute('SELECT layers FROM outfit_draft'):
        assert not {layer[0] for layer in json.loads(layers)}.intersection(deleted)
    assert db.execute('SELECT count(*) FROM file_cleanup').fetchone() == (0,)

def photos_absent(prefix):
    for name in files:
        if name.startswith(prefix):
            assert shell('run-as', PKG, 'sh', '-c', f"'test ! -e files/photos/{name}; echo $?'" ).strip() == b'0', name

try:
    with snapshot('source') as source:
        target = RESULTS / 'fixture.sqlite'
        with sqlite3.connect(target) as db:
            source.backup(db)
            db.execute('PRAGMA foreign_keys=OFF')
            for (table,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").fetchall():
                db.execute('DELETE FROM "' + table + '"')
            today = shell('date', '+%F').decode().strip()
            for label in ('Plain', 'Linked', 'Keep', 'BatchD', 'BatchE', 'Archive'):
                names = ['delete-test-' + label + '-' + suffix + '.png' for suffix in ('original', 'processed', 'cover')]
                files.extend(names)
                ids[label] = db.execute('''INSERT INTO garment(name,type,brand,fabric,original,processed,photo,seasons,
                    favorite,dirty,stored,care,symbols,source_text,label_text,wash_every,idle_days,created,archived)
                    VALUES(?, '衬衫','','',?,?,?,15,0,0,0,'','','','',10,90,?,0)''',
                    ('DEL-' + label, *names, today)).lastrowid
                item = ids[label]
                db.execute('INSERT INTO wash(garment,day) VALUES(?,?)', (item, today))
                db.execute('INSERT INTO care_event(garment,day,note,wash_checkpoint) VALUES(?,?,?,1)', (item,today,'check'))
                db.execute('INSERT INTO color VALUES(?,0,?,0,100,1)', (item,'黑色'))
            def outfit(name, labels):
                look = db.execute('INSERT INTO outfit(name,scene) VALUES(?,?)', (name,'')).lastrowid
                for pos, label in enumerate(labels):
                    db.execute('INSERT INTO outfit_layer VALUES(?,?,?,?,?,?)', (look,pos,ids[label],.5,.5,.4))
                wear = db.execute('INSERT INTO history(outfit,name,scene,day) VALUES(?,?,?,?)', (look,name,'',today)).lastrowid
                for label in labels:
                    db.execute('INSERT INTO history_garment VALUES(?,?)', (wear,ids[label]))
                return look
            only = outfit('DEL-OnlyLinked', ['Linked'])
            mixed = outfit('DEL-MixedLinked', ['Linked','Keep'])
            outfit('DEL-BatchEmpty', ['BatchD','BatchE'])
            outfit('DEL-BatchPartial', ['BatchD','Keep'])
            outfit('DEL-ArchiveLook', ['Archive'])
            for name, look, labels in [('DEL-DraftDetach',only,['Linked','Keep']),
                                       ('DEL-DraftMixed',mixed,['Linked','Keep','BatchD']),
                                       ('DEL-DraftEmpty',None,['Linked'])]:
                db.execute('INSERT INTO outfit_draft(outfit,day,name,scene,layers,mark_dirty) VALUES(?,NULL,?,?,?,1)',
                    (look,name,'',json.dumps([[ids[label],.5,.5,.4] for label in labels])))
            db.commit()
    run('push', str(target), '/data/local/tmp/wardrobe-delete-fixture.sqlite')
    shell('run-as', PKG, 'rm', '-f', 'databases/wardrobe.sqlite-wal', 'databases/wardrobe.sqlite-shm')
    shell('run-as', PKG, 'cp', '/data/local/tmp/wardrobe-delete-fixture.sqlite', 'databases/wardrobe.sqlite')
    png = RESULTS / 'pixel.png'
    png.write_bytes(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1kAAAAASUVORK5CYII='))
    run('push', str(png), '/data/local/tmp/delete-test-pixel.png')
    for name in files:
        shell('run-as', PKG, 'cp', '/data/local/tmp/delete-test-pixel.png', 'files/photos/' + name)
    shell('logcat', '-c')

    # Case A: simple confirmation; cancel preserves everything, confirm cleans files and child rows.
    open_item('DEL-Plain')
    tap('更多操作')
    tap('删除衣物')
    visible('确认彻底删除该衣物？删除后照片与数据将无法恢复')
    tap('取消')
    with snapshot('cancel-single') as db:
        assert db.execute('SELECT count(*) FROM garment WHERE id=?',(ids['Plain'],)).fetchone() == (1,)
    tap('更多操作')
    tap('删除衣物')
    tap('彻底删除')
    time.sleep(.7)
    with snapshot('plain-deleted') as db: links_absent(db, {ids['Plain']})
    photos_absent('delete-test-Plain-')
    print('PASS: plain deletion, cancel, wash/check/color cascade and three photo variants', flush=True)

    # Case B: dynamic counts, linked deletion and both in-memory/persisted draft repair.
    open_item('DEL-Linked')
    tap('更多操作')
    tap('删除衣物')
    assert any('2 套穿搭和 2 次穿着记录' in n.get('text','') for n in screen())
    visible('改为归档（保留历史）')
    evidence('linked-confirm')
    tap('仍要彻底删除')
    time.sleep(.7)
    with snapshot('linked-deleted') as db:
        links_absent(db, {ids['Plain'],ids['Linked']})
        assert db.execute("SELECT count(*) FROM outfit WHERE name='DEL-OnlyLinked'").fetchone() == (0,)
        assert db.execute("SELECT count(*) FROM history WHERE name='DEL-OnlyLinked'").fetchone() == (0,)
        assert db.execute("SELECT count(*) FROM outfit_layer JOIN outfit o ON o.id=outfit WHERE o.name='DEL-MixedLinked'").fetchone() == (1,)
        assert db.execute("SELECT outfit,layers FROM outfit_draft WHERE name='DEL-DraftDetach'").fetchone() == (None,json.dumps([[ids['Keep'],.5,.5,.4]],separators=(',',':')))
    photos_absent('delete-test-Linked-')
    tap('穿搭')
    tap('继续编辑 · DEL-DraftDetach')
    tap('保存')
    with snapshot('resaved-detached-draft') as db:
        assert db.execute("SELECT garment FROM outfit_layer JOIN outfit o ON o.id=outfit WHERE o.name='DEL-DraftDetach'").fetchall() == [(ids['Keep'],)]
        assert_integrity(db)
    print('PASS: dynamic counts, linked deletion, empty cleanup, retained mixed records and live draft resave', flush=True)

    # Long press enters selection rather than opening a deletion dialog.
    shell('am','force-stop',PKG)
    shell('am','start','-n',PKG+'/.MainActivity')
    tap('筛选')
    text('搜索名称或品牌','DEL-Batch')
    tap('应用筛选')
    nodes = screen()
    node = find(nodes,'DEL-BatchD')
    lx1,ly1,lx2,ly2 = map(int,re.findall(r'\d+',node.get('bounds')))
    cards = [n for n in nodes if n.get('long-clickable') == 'true']
    for card in cards:
        x1,y1,x2,y2 = map(int,re.findall(r'\d+',card.get('bounds')))
        if x1 <= lx1 and x2 >= lx2 and y1 <= ly1 and y2 >= ly2:
            break
    else: raise AssertionError('Long-pressable garment card missing')
    x, y = (x1+x2)//2, y1+min(100,(y2-y1)//2)
    shell('input','touchscreen','swipe',str(x),str(y),str(x),str(y),'1500')
    visible('已选 1 件')
    tap('全选')
    visible('已选 2 件')
    evidence('multi-select')
    tap('删除')
    assert any('其中 2 件关联' in n.get('text','') for n in screen())
    tap('取消')
    visible('已选 2 件')
    tap('删除')
    tap('彻底删除')
    time.sleep(.7)
    with snapshot('batch-deleted') as db:
        links_absent(db, {ids[key] for key in ('Plain','Linked','BatchD','BatchE')})
        assert db.execute("SELECT count(*) FROM outfit WHERE name='DEL-BatchEmpty'").fetchone() == (0,)
        assert db.execute("SELECT count(*) FROM history WHERE name='DEL-BatchEmpty'").fetchone() == (0,)
        assert db.execute("SELECT count(*) FROM history_garment JOIN history h ON h.id=history WHERE h.name='DEL-BatchPartial'").fetchone() == (1,)
    assert find(screen(),'退出多选') is None
    photos_absent('delete-test-Batch')
    print('PASS: long press/select all, batch confirmation/cancel, empty/mixed cleanup, automatic selection exit',flush=True)

    # Visible selection entry and reversible archive preserve photos and historical links.
    open_item('DEL-Archive')
    tap('更多操作')
    tap('删除衣物')
    tap('改为归档（保留历史）')
    with snapshot('archive-recommendation') as db:
        assert db.execute('SELECT archived FROM garment WHERE id=?',(ids['Archive'],)).fetchone() == (1,)
        assert db.execute('SELECT count(*) FROM history_garment WHERE garment=?',(ids['Archive'],)).fetchone() == (1,)
    open_item('DEL-Keep')
    tap('返回')
    tap('多选')
    visible('已选 0 件')
    assert find(screen(),'删除').get('enabled') == 'false'
    tap('全选')
    visible('已选 1 件')
    tap('取消全选')
    visible('已选 0 件')
    tap('退出多选')
    tap('多选')
    tap('全选')
    tap('归档')
    with snapshot('batch-archive') as db:
        assert db.execute('SELECT archived FROM garment WHERE id=?',(ids['Keep'],)).fetchone() == (1,)
        assert db.execute('SELECT count(*) FROM history_garment WHERE garment=?',(ids['Keep'],)).fetchone()[0] > 0
        assert_integrity(db)
    assert find(screen(),'退出多选') is None
    for name in files:
        if name.startswith(('delete-test-Keep-','delete-test-Archive-')):
            assert shell('run-as',PKG,'sh','-c',f"'test -f files/photos/{name}; echo $?'" ).strip() == b'0'
    assert_no_crash('delete-ux')
    print('PASS: recommended archive, explicit selection, zero state, select/deselect all, batch archive preserves history/photos',flush=True)
finally:
    for name in files:
        shell('run-as', PKG, 'rm', '-f', 'files/photos/' + name)
    runpy.run_path(str(ROOT / 'restore_data.py'), run_name='__main__')
