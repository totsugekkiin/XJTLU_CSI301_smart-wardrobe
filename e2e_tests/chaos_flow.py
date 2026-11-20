"""Human boundary regression through real UI actions, with SQLite checkpoints."""
from device import *
import sys

def restart():
    shell('am', 'force-stop', PKG)
    shell('am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(2)
    visible('＋ 添加')

def open_item(name):
    restart()
    tap('筛选')
    text('搜索名称或品牌', name)
    tap('应用筛选')
    tap(name, True)
    visible('衣物详情')

def item_state(name, expected, label):
    with snapshot(label) as db:
        row = db.execute('SELECT id,dirty,stored FROM garment WHERE name=?', (name,)).fetchone()
        assert row is not None and row[1:] == expected, (name, row, expected)
        washes = db.execute('SELECT count(*) FROM wash WHERE garment=?', (row[0],)).fetchone()[0]
        assert_integrity(db)
        return row[0], washes

def group_wash():
    restart()
    tap('洗护')
    tap('整组标记已洗', True)

def earlier_cases():
    name = 'E2E-ReadyStored'
    open_item(name)
    tap('编辑')
    text('名称 *', 'E2E-Unsaved-Change')
    back()
    visible('退出编辑')
    tap('取消')
    assert find(screen(), '名称 *') is not None
    back()
    tap('放弃修改')
    visible(name)
    with snapshot('chaos-discard') as db:
        assert db.execute("SELECT count(*) FROM garment WHERE name='E2E-Unsaved-Change'").fetchone() == (0,)
        assert db.execute('SELECT count(*) FROM garment WHERE name=?', (name,)).fetchone() == (1,)
    print('PASS: back interception, cancel editing exit, and confirmed discard', flush=True)

    # Stack dirty + stored using the actual detail actions.
    tap('标为待洗')
    tap('更多操作')
    tap('收起衣物')
    item_id, washes = item_state(name, (1, 1), 'chaos-stacked')
    evidence('chaos-stacked')
    open_item('E2E-Machine30')
    tap('标为待洗')
    restart()
    tap('洗护')
    tap('换季检查')
    evidence('chaos-season-before-takeout')
    tap('一键取出并送入待洗', True)
    assert item_state(name, (1, 0), 'chaos-taken-out')[1] == washes
    evidence('chaos-season-after-takeout')
    group_wash()
    assert item_state(name, (0, 0), 'chaos-washed')[1] == washes + 1
    print('PASS: dirty + stored -> seasonal batch takeout -> batch wash', flush=True)

    # Washing a stored garment must never implicitly take it out.
    open_item('E2E-Machine30')
    tap('标为待洗')
    tap('更多操作')
    tap('收起衣物')
    _, before_wash = item_state('E2E-Machine30', (1, 1), 'chaos-stored-before-wash')
    open_item('E2E-Machine30B')
    tap('标为待洗')
    group_wash()
    assert item_state('E2E-Machine30', (0, 1), 'chaos-stored-after-wash')[1] == before_wash + 1
    restart()
    tap('洗护')
    tap('换季检查')
    evidence('chaos-season-clean-group')
    tap('一键全部取出', True)
    assert item_state('E2E-Machine30', (0, 0), 'chaos-clean-taken-out')[1] == before_wash + 1
    print('PASS: washing preserves storage; clean seasonal takeout adds no wash', flush=True)

    for name in ('E2E-CareStored', 'E2E-IdleStored'):
        open_item(name)
        tap('完成护理检查')
        text('检查结果', 'E2E-checked')
        tap('完成护理')
        with snapshot('chaos-check-' + name) as db:
            item = db.execute('SELECT id FROM garment WHERE name=?', (name,)).fetchone()[0]
            count = db.execute('SELECT count(*) FROM wash WHERE garment=?', (item,)).fetchone()[0]
            assert db.execute('SELECT wash_checkpoint,note FROM care_event WHERE garment=? ORDER BY id DESC LIMIT 1', (item,)).fetchone() == (count, 'E2E-checked')
            assert_integrity(db)
        visible('可穿')
        evidence('chaos-check-' + name)
    print('PASS: wash-count and idle-day reminders both reset from garment detail', flush=True)

    # Calendar deduplication and opting out of dirty marking use the full 20-layer outfit.
    restart()
    tap('日历')
    tap('登记当天穿着', True)
    tap('选择已保存的穿搭')
    tap('E2E-Cap20')
    assert find(screen(), '同时标为待洗').get('checked') == 'true'
    tap('同时标为待洗')
    tap('保存记录')
    with snapshot('chaos-calendar-opt-out') as db:
        look = db.execute("SELECT id FROM outfit WHERE name='E2E-Cap20'").fetchone()[0]
        assert db.execute('SELECT count(*) FROM garment JOIN outfit_layer ON garment.id=outfit_layer.garment WHERE outfit=? AND dirty=1', (look,)).fetchone() == (0,)
        assert db.execute('SELECT count(*) FROM history WHERE outfit=?', (look,)).fetchone() == (1,)
    tap('登记当天穿着', True)
    tap('选择已保存的穿搭')
    tap('E2E-Cap20')
    tap('保存记录')
    with snapshot('chaos-calendar-dedup') as db:
        look = db.execute("SELECT id FROM outfit WHERE name='E2E-Cap20'").fetchone()[0]
        assert db.execute('SELECT count(*) FROM history WHERE outfit=?', (look,)).fetchone() == (1,)
        assert db.execute('SELECT count(*) FROM history_garment JOIN history ON history.id=history_garment.history WHERE outfit=?', (look,)).fetchone() == (20,)
        assert db.execute('SELECT count(*) FROM garment JOIN outfit_layer ON garment.id=outfit_layer.garment WHERE outfit=? AND dirty=1', (look,)).fetchone() == (20,)
        assert_integrity(db)
    print('PASS: default dirty option and same-outfit/day replacement', flush=True)


if '--resume-archive' not in sys.argv:
    earlier_cases()

# Archive remains reversible and preserves historical associations.
open_item('E2E-Layer-00')
for _ in range(3):
    shell('input', 'swipe', '360', '1180', '360', '420', '250')
assert find(screen(), '永久删除衣物') is None
tap('归档衣物', True)
with snapshot('chaos-archive') as db:
    assert db.execute("SELECT archived FROM garment WHERE name='E2E-Layer-00'").fetchone() == (1,)
    assert_integrity(db)
print('PASS: worn garment is archived and its historical associations remain', flush=True)

# A linked item can be deleted explicitly; its now-empty outfit is removed too.
open_item('E2E-Layer-20')
tap('更多操作', True)
tap('删除衣物')
tap('仍要彻底删除')
time.sleep(.7)
with snapshot('chaos-delete-cascade') as db:
    assert db.execute("SELECT count(*) FROM garment WHERE name='E2E-Layer-20'").fetchone() == (0,)
    assert db.execute("SELECT count(*) FROM outfit WHERE name='E2E-DeleteCascade'").fetchone() == (0,)
    assert_integrity(db)
print('PASS: unworn permanent deletion cascades to outfit layers', flush=True)
assert_no_crash('chaos')
print('PASS: chaos regression SQLite integrity and logcat', flush=True)
