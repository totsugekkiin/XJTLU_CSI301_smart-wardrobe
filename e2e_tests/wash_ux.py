"""Focused emulator regression: direct wash, undo, and past-only backdating.

Backs up/restores the full app sandbox; never targets a physical phone.
"""
import runpy
from datetime import date, timedelta
import device
from device import *

device.RESULTS = RESULTS / ('wash-ux-' + str(time.time_ns()))
device.RESULTS.mkdir()
RESULTS = device.RESULTS
shell('am', 'force-stop', PKG)
with (RESULTS / 'pre-task-data.tar').open('wb') as backup:
    subprocess.run([*ADB, 'exec-out', 'run-as', PKG, 'tar', '-cf', '-', '.'],
                   stdout=backup, check=True, timeout=180)

def state(name):
    with snapshot('wash-state') as db:
        assert_integrity(db)
        return db.execute('''SELECT dirty,stored,(SELECT count(*) FROM wash WHERE garment=g.id)
            FROM garment g WHERE name=?''', (name,)).fetchone()

def wash_row(name):
    nodes = screen()
    item = find(nodes, name)
    if item is None:
        tap(name, scroll=True)
        tap('已洗')
        return
    _, y1, _, y2 = map(int, re.findall(r'\d+', item.get('bounds')))
    buttons = [n for n in nodes if n.get('text') == '已洗']
    button = min(buttons, key=lambda n: abs((int(re.findall(r'\d+', n.get('bounds'))[1])
                  + int(re.findall(r'\d+', n.get('bounds'))[3])) / 2 - (y1+y2)/2))
    x1, b1, x2, b2 = map(int, re.findall(r'\d+', button.get('bounds')))
    shell('input', 'tap', str((x1+x2)//2), str((b1+b2)//2))

try:
    runpy.run_path(str(ROOT / 'seed_regression.py'), run_name='__main__')
    tap('洗护')
    # Single-row wash must update today immediately, with no chooser.
    wash_row('E2E-Machine30')
    assert state('E2E-Machine30') == (0, 0, 1)
    tap('撤销')
    assert state('E2E-Machine30') == (1, 0, 0)
    # Whole group wash/undo, including a stored garment.
    top()
    tap('整组标记已洗')
    assert state('E2E-Machine30') == (0, 0, 1)
    assert state('E2E-Machine30B') == (0, 0, 1)
    assert state('E2E-DirtyStored') == (0, 1, 1)
    tap('撤销')
    assert state('E2E-Machine30') == (1, 0, 0)
    assert state('E2E-DirtyStored') == (1, 1, 0)
    # Detail primary wash/undo.
    tap('E2E-Machine30')
    tap('已洗')
    assert state('E2E-Machine30') == (0, 0, 1)
    tap('撤销')
    assert state('E2E-Machine30') == (1, 0, 0)
    # Backdating opens the calendar directly and excludes today/future.
    tap('更多操作', scroll=True)
    tap('补记洗涤')
    visible('仅补录历史洗涤次数，不影响当前是否待洗')
    today = date.fromisoformat(shell('date', '+%F').decode().strip())
    nodes = screen()
    for node in nodes:
        label = node.get('content-desc', '')
        if re.fullmatch(r'\d{4}-\d{2}-\d{2}', label) and label >= str(today):
            assert node.get('enabled') == 'false', label
    evidence('backdate-picker')
    tap('确认')
    assert state('E2E-Machine30') == (1, 0, 1)
    with snapshot('backdate-dirty') as db:
        assert db.execute('SELECT day FROM wash JOIN garment g ON garment=g.id WHERE g.name=?',
                          ('E2E-Machine30',)).fetchone() == (str(today-timedelta(days=1)),)
    # Same backdating behavior when already clean; cancellation adds nothing.
    top()
    tap('已洗')
    assert state('E2E-Machine30') == (0, 0, 2)
    tap('更多操作', scroll=True)
    tap('补记洗涤')
    tap('关闭')
    assert state('E2E-Machine30') == (0, 0, 2)
    tap('更多操作')
    tap('补记洗涤')
    tap('确认')
    assert state('E2E-Machine30') == (0, 0, 3)
    assert_no_crash('wash-ux')
    print('PASS: row/detail/group immediate wash + undo; stored preserved; past-only backdate; cancel; dirty/clean preserved')
finally:
    runpy.run_path(str(ROOT / 'restore_data.py'), run_name='__main__')
