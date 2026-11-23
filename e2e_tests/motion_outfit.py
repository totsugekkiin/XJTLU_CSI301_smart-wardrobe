"""Emulator-only motion album UI, persistence and lifecycle regression. Restores app data."""
import device
import sys
from device import *
from PIL import Image, ImageDraw

device.RESULTS = RESULTS / ('motion-outfit-' + str(time.time_ns()))
device.RESULTS.mkdir()
RESULTS = device.RESULTS


def launch():
    shell('am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(.6)


def enter():
    tap('穿搭')
    tap('体感搭配')
    time.sleep(.5)


def current(row):
    nodes = screen()
    prefix = row + '，'
    return next(n.get('content-desc') for n in nodes if n.get('content-desc', '').startswith(prefix))


def swipe_row(row):
    node = next(n for n in screen() if n.get('content-desc', '').startswith(row + '，'))
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    shell('input', 'swipe', str(x2 * 3 // 4), str((y1 + y2) // 2), str(x2 // 4), str((y1 + y2) // 2), '240')
    time.sleep(.3)


def put_db(path):
    shell('am', 'force-stop', PKG)
    run('push', str(path), '/data/local/tmp/motion-fixture.sqlite')
    shell('run-as', PKG, 'rm', '-f', 'databases/wardrobe.sqlite-wal', 'databases/wardrobe.sqlite-shm')
    shell('run-as', PKG, 'cp', '/data/local/tmp/motion-fixture.sqlite', 'databases/wardrobe.sqlite')


def save_named(name):
    tap('保存')
    edit = next(n for n in screen() if n.get('class') == 'android.widget.EditText')
    assert edit.get('focused') == 'true'
    # The dialog focuses this field itself; coordinate taps race the IME resize
    # and can hit a keyboard suggestion instead of the moving input.
    shell('input', 'text', name)
    back()
    actual = next(n.get('text') for n in screen() if n.get('class') == 'android.widget.EditText')
    assert actual == name, ('Unexpected IME input', actual)
    save = max([n for n in screen() if n.get('text') == '保存'],
               key=lambda n: int(re.findall(r'\d+', n.get('bounds'))[1]))
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', save.get('bounds')))
    shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.4)


shell('am', 'force-stop', PKG)
backup = RESULTS / 'pre-task-data.tar'
with backup.open('wb') as stream:
    subprocess.run([*ADB, 'exec-out', 'run-as', PKG, 'tar', '-cf', '-', '.'], stdout=stream, check=True, timeout=60)
try:
    run('install', '-r', str(ROOT.parent / 'app/build/outputs/apk/debug/app-debug.apk'))
    original = snapshot('original')
    original_counts = {name: original.execute('SELECT count(*) FROM "' + name + '"').fetchone()[0]
                       for (name,) in original.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
    fixture = RESULTS / 'fixture.sqlite'
    connection = sqlite3.connect(fixture)
    original.backup(connection)
    original.close()
    connection.execute('PRAGMA foreign_keys=OFF')
    tables = [r[0] for r in connection.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
    for table in tables:
        connection.execute('DELETE FROM "' + table + '"')
    fields = list(connection.execute('PRAGMA table_info(garment)'))
    columns = [f[1] for f in fields]
    shell('run-as', PKG, 'mkdir', '-p', 'files/photos')
    for i in range(6):
        image = Image.new('RGBA', (400, 480), (255, 255, 255, 0))
        draw = ImageDraw.Draw(image)
        if i < 3:
            outline = [(140, 35), (80, 55), (15, 150), (80, 190), (110, 145), (110, 430), (290, 430), (290, 145), (320, 190), (385, 150), (320, 55), (260, 35), (235, 65), (165, 65)]
        else:
            outline = [(95, 25), (305, 25), (345, 440), (235, 440), (200, 200), (165, 440), (55, 440)]
        draw.polygon(outline, fill=['#111111', '#7F533F', '#283B50'][i % 3])
        photo = RESULTS / ('motion-fixture-' + str(i) + '.png')
        image.save(photo)
        run('push', str(photo), '/data/local/tmp/motion-photo.png')
        shell('run-as', PKG, 'cp', '/data/local/tmp/motion-photo.png', 'files/photos/' + photo.name)
    for i, kind in enumerate(['上衣', '衬衫', '外套', '长裤', '牛仔裤', '半裙', '鞋子', '连衣裙', '上衣', '长裤', '夹克', '针织衫']):
        values = {f[1]: '' if f[2] == 'TEXT' else 0 for f in fields}
        values.update(id=100 + i, name='Motion' + str(i), type=kind, wash_every=10, idle_days=90, created='2026-11-23', seasons=2 if i == 3 else 8)
        values['photo'] = 'motion-fixture-' + str((3 if kind in ['长裤', '牛仔裤', '半裙'] else 0) + i % 3) + '.png'
        if i == 8: values['archived'] = 1
        if i == 9: values['stored'] = 1
        connection.execute('INSERT INTO garment (' + ','.join(columns) + ') VALUES (' + ','.join('?' for _ in columns) + ')', [values[c] for c in columns])
        connection.execute('INSERT INTO color VALUES(?,?,?,?,?,?)', (100+i, 0, '黑色' if i % 2 == 0 else '深蓝', 0xff111111 if i % 2 == 0 else 0xff283b50, 100, 1))
    if '--header-only' in sys.argv:
        for outfit in [1, 2]:
            connection.execute('INSERT INTO outfit VALUES(?,?,?)', (outfit, 'Preview'+str(outfit), ''))
            connection.execute('INSERT INTO outfit_layer VALUES(?,?,?,?,?,?)', (outfit, 0, 111, .5, .28, .46))
            connection.execute('INSERT INTO outfit_layer VALUES(?,?,?,?,?,?)', (outfit, 1, 105, .5, .73, .46))
    connection.commit()
    connection.close()
    put_db(fixture)
    prefs = b'<?xml version="1.0" encoding="utf-8"?><map><boolean name="has_completed_onboarding" value="true" /></map>'
    run('shell', 'run-as', PKG, 'sh', '-c', "'cat > shared_prefs/onboarding.xml'", data=prefs)
    run('logcat', '-c')
    launch()
    if '--header-only' in sys.argv:
        tap('穿搭')
        nodes = screen()
        def bounds(node): return list(map(int, re.findall(r'\d+', node.get('bounds'))))
        density = int(re.findall(r'\d+', shell('wm','density').decode())[-1])/160
        assert find(nodes, '2 套穿搭') is not None
        entry = find(nodes, '体感搭配'); create = find(nodes, '+ 新建')
        assert entry.get('class') == 'android.widget.TextView'
        eb, cb = bounds(entry), bounds(create)
        assert cb[3]-cb[1] == round(34*density) and cb[0]-eb[2] == round(16*density), (eb,cb)
        assert abs(eb[1]+eb[3]-cb[1]-cb[3]) <= 2
        assert find(nodes, '+ 新建穿搭') is None
        evidence('minimal-outfit-header')
        image = Image.open(RESULTS/'minimal-outfit-header.png').convert('RGB')
        for x,y in [(eb[0]+1,eb[1]+1),(eb[2]-2,eb[3]-2)]: assert image.getpixel((x,y)) == (255,255,255)
        preview = next(n for n in nodes if n.get('content-desc','').startswith('穿搭预览 / '))
        pb = bounds(preview)
        for x,y in [(pb[0]+20,pb[1]+20),(pb[2]-20,pb[1]+20),(pb[0]+20,pb[3]-20)]:
            assert image.getpixel((x,y)) == (255,255,255), 'Preview background is not pure white'
        tap('体感搭配'); assert find(screen(),'保存') is not None; back()
        tap('+ 新建'); assert find(screen(),'穿搭画布') is not None
        assert_no_crash('minimal-header')
        print('PASS: plain text motion entry, 16 dp gap, 34 dp new button, pure white preview and both entry actions',flush=True)
        sys.exit(0)
    enter()
    if '--frames-only' in sys.argv:
        density = int(re.findall(r'\d+', shell('wm', 'density').decode())[-1]) / 160
        def bounds(node): return list(map(int, re.findall(r'\d+', node.get('bounds'))))
        def geometry(labels, side):
            nodes = screen()
            rows = [next(n for n in nodes if n.get('content-desc', '').startswith(label+'，')) for label in labels]
            boxes = [bounds(n) for n in rows]
            assert len({b[3]-b[1] for b in boxes}) == 1, boxes
            assert boxes[0][3]-boxes[0][1] == round(side*density), boxes
            for previous, current_box in zip(boxes, boxes[1:]): assert current_box[1]-previous[3] == round(12*density), boxes
            pins = [bounds(find(nodes, '锁定'+label)) for label in labels]
            assert len({p[0] for p in pins}) == 1, pins
            for box, pin in zip(boxes, pins):
                assert pin[1] == box[1] and pin[3]-pin[1] == round(48*density), (box, pin)
            return boxes
        top, bottom = geometry(['上装', '下装'], 230)
        evidence('square-two-piece')
        image = Image.open(RESULTS/'square-two-piece.png').convert('RGB')
        left = (image.width-round(230*density))//2
        assert min(image.getpixel((left+round(6*density), top[1]+1))) < 30, 'Missing black corner arm'
        assert image.getpixel((left+1, (top[1]+top[3])//2)) == (255,255,255), 'Old full frame still present'
        # Switching styles changes only the fitted photo, never the container or pin positions.
        swipe_row('上装'); assert geometry(['上装', '下装'], 230) == [top, bottom]
        swipe_row('下装'); evidence('square-bottom-focus')
        save_named('230')
        tap('切换至三件模式'); geometry(['外套', '上装', '下装'], 165)
        evidence('square-three-piece'); save_named('165')
        back()
        nodes = screen()
        assert find(nodes, '2 套穿搭') is not None
        motion_button = next(n for n in nodes if n.get('clickable') == 'true' and n.get('content-desc') == '体感搭配')
        create_button = find(nodes, '+ 新建')
        mb, cb = bounds(motion_button), bounds(create_button)
        assert cb[3]-cb[1] == round(34*density), (mb,cb)
        assert abs(mb[1]+mb[3]-cb[1]-cb[3]) <= 2 and cb[0]-mb[2] == round(16*density), (mb,cb)
        evidence('outfit-collection')
        preview = next(n for n in nodes if n.get('content-desc','').startswith('穿搭预览 / '))
        pb = bounds(preview)
        image = Image.open(RESULTS/'outfit-collection.png').convert('RGB')
        assert image.getpixel((pb[0]+round(12*density),pb[1]+round(12*density))) == (255,255,255), 'Preview is not white'
        assert image.getpixel(((pb[0]+pb[2])//2,pb[1])) == (239,239,239), 'Preview border is not 1 px EFEFEF'
        tap('+ 新建'); assert find(screen(), '穿搭画布') is not None
        assert_no_crash('square-frames')
        print('PASS: 230/165 dp square frames, 12 dp gaps, aligned pins, black corner focus, minimal header, dynamic count and white previews', flush=True)
        sys.exit(0)
    if '--empty-only' in sys.argv:
        empty = sqlite3.connect(fixture)
        empty.execute('DELETE FROM garment'); empty.commit(); empty.close()
        put_db(fixture); launch(); enter()
        for label in ['添加上装', '添加下装']:
            tap(label)
            assert find(screen(), '档案 Tab') is not None, label
            back()
        tap('切换至三件模式'); tap('添加外套')
        assert find(screen(), '档案 Tab') is not None
        back(); evidence('empty-album')
        assert_no_crash('empty-motion')
        print('PASS: empty outer, top and bottom all open the actual garment editor and return', flush=True)
        sys.exit(0)
    assert 'Motion11' in current('上装') and 'Motion5' in current('下装')
    assert all(n.get('text', '') in ['', '<', '保存'] for n in screen()), 'Canvas contains redundant text'
    evidence('album')
    image = Image.open(RESULTS / 'album.png').convert('RGB')
    for row in ['上装', '下装']:
        node = next(n for n in screen() if n.get('content-desc', '').startswith(row + '，'))
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
        pixel = image.getpixel((image.width // 2, int(y1 + (y2-y1)*.3)))
        assert pixel == (40, 59, 80), (row, 'center was dimmed', pixel)
    if '--visual-only' in sys.argv:
        image = Image.open(RESULTS / 'album.png').convert('RGB')
        for row in ['上装', '下装']:
            node = next(n for n in screen() if n.get('content-desc', '').startswith(row + '，'))
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            for left, right in [(8, 45), (image.width - 45, image.width - 8)]:
                edge = image.crop((left, int(y1 + (y2-y1)*.3), right, int(y1 + (y2-y1)*.72)))
                assert sum(min(pixel) < 245 for pixel in edge.getdata()) > 60, (row, 'neighbor not visible')
        print('PASS: top and bottom images expose both neighboring silhouettes', flush=True)
        sys.exit(0)
    before = current('上装')
    swipe_row('上装')
    assert before != current('上装')
    swipe_row('下装')
    assert '当前激活' in current('下装')
    print('PASS: entry, eligible categories, touch swipe and focus', flush=True)

    # Full-opacity center colors are unchanged when the active row changes.
    tap('暂停体感')
    assert find(screen(), '恢复体感') is not None
    inactive = shell('dumpsys', 'sensorservice').decode(errors='replace').split('Active sensors:')[-1].split('Previous Registrations:')[0]
    assert PKG not in inactive
    tap('恢复体感')
    tap('筛选'); tap('冬'); tap('黑色'); tap('应用')
    assert 'Motion10' in current('上装') and 'Motion4' in current('下装')
    evidence('filtered')
    tap('筛选'); tap('重置'); tap('应用')
    tap('切换至三件模式')
    assert 'Motion10' in current('外套')
    assert 'Motion' in current('上装') and 'Motion' in current('下装')
    assert current('外套').split('，')[1] != current('上装').split('，')[1]
    evidence('three-piece')
    save_named('MotionThree')
    with snapshot('three-saved') as db:
        selected = db.execute("SELECT g.type FROM outfit_layer l JOIN garment g ON g.id=l.garment JOIN outfit o ON o.id=l.outfit WHERE o.name='MotionThree' ORDER BY l.position").fetchall()
        assert len(selected) == 3 and selected[0][0] in ['夹克', '外套'] and selected[1][0] not in ['夹克', '外套'] and selected[2][0] in ['长裤', '牛仔裤', '半裙'], selected
        assert_integrity(db)
    print('PASS: three-piece save persists outer, inner and bottom as three distinct layers', flush=True)
    if '--three-piece-only' in sys.argv:
        sys.exit(0)
    tap('切换至两件模式')
    print('PASS: sensor toggle unregisters, seasonal/color filters apply, three-piece categories split without duplicates', flush=True)

    tap('锁定上装')
    locked_top = current('上装').split('，')[1]
    tap('随机搭配')
    time.sleep(1.5)
    assert current('上装').split('，')[1] == locked_top
    assert find(screen(), '解锁上装') is not None
    tap('锁定下装')
    both = (current('上装'), current('下装'))
    tap('随机搭配')
    time.sleep(.3)
    assert both == (current('上装'), current('下装'))
    tap('解锁下装')
    print('PASS: random animation respects one or both pinned rows', flush=True)

    save_named('MotionSaved')
    with snapshot('saved') as db:
        assert db.execute("SELECT count(*) FROM outfit WHERE name='MotionSaved'").fetchone() == (1,)
        layers = db.execute("SELECT g.type,g.name FROM outfit_layer l JOIN garment g ON g.id=l.garment JOIN outfit o ON o.id=l.outfit WHERE o.name='MotionSaved' ORDER BY l.position").fetchall()
        assert len(layers) == 2 and layers[0][1] == locked_top
        assert_integrity(db)
    tap('校准')
    shell('input', 'keyevent', '3')
    time.sleep(.4)
    sensor_dump = shell('dumpsys', 'sensorservice').decode(errors='replace')
    (RESULTS / 'background-sensors.txt').write_text(sensor_dump, encoding='utf-8')
    # Only inspect active connection summaries, not historical registration entries.
    active_dump = sensor_dump.split('Active sensors:')[-1].split('Previous Registrations:')[0]
    assert PKG not in active_dump, 'Background sensor listener remains registered'
    launch()
    assert find(screen(), '解锁上装') is not None
    back()
    assert find(screen(), 'MotionSaved') is not None
    print('PASS: naming persists two selected layers, calibration, background cleanup and collection refresh', flush=True)

    # Empty groups route into the real preselected garment form and return to the album.
    empty = sqlite3.connect(fixture)
    empty.execute('DELETE FROM garment')
    empty.commit()
    empty.close()
    put_db(fixture)
    launch(); enter()
    tap('添加上装')
    assert find(screen(), '档案 Tab') is not None
    back()
    assert find(screen(), '添加下装') is not None
    tap('添加下装')
    assert find(screen(), '档案 Tab') is not None
    back()
    evidence('empty-album')
    assert_no_crash('motion')
    print('PASS: both empty-group add routes and return, no app crashes', flush=True)
finally:
    shell('am', 'force-stop', PKG)
    # Fixed app-private directories, on an explicitly selected emulator, backed up above.
    shell('run-as', PKG, 'rm', '-rf', 'databases', 'files', 'shared_prefs')
    run('push', str(backup), '/data/local/tmp/motion-backup.tar')
    shell('run-as', PKG, 'tar', '-xf', '/data/local/tmp/motion-backup.tar', '-C', '.')
    shell('rm', '-f', '/data/local/tmp/motion-backup.tar', '/data/local/tmp/motion-photo.png', '/data/local/tmp/motion-fixture.sqlite')
    with snapshot('restored') as db:
        restored = {name: db.execute('SELECT count(*) FROM "' + name + '"').fetchone()[0] for name in original_counts}
        assert restored == original_counts
        assert_integrity(db)
    launch()
    print('RESTORED: original app data and table counts', RESULTS, flush=True)
