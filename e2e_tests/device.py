"""ADB UI + SQLite evidence helpers. Only operate on an explicitly selected emulator."""
import os
import re
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / 'results'
RESULTS.mkdir(exist_ok=True)
SERIAL = os.environ.get('WARDROBE_SERIAL', 'emulator-5572')
if not SERIAL.startswith('emulator-'):
    raise RuntimeError('This suite changes test data; select a dedicated emulator.')
ADB = [os.environ.get('ADB', 'E:/androidstudio/platform-tools/adb.exe'), '-s', SERIAL]
PKG = 'com.smartwardrobe.local'

def run(*args, data=None):
    return subprocess.check_output([*ADB, *args], input=data, timeout=45)

def shell(*args):
    return run('shell', *args)

def screen():
    for _ in range(5):
        result = shell('uiautomator', 'dump', '/sdcard/wardrobe-e2e.xml')
        if b'dumped to' not in result:
            time.sleep(1)
            continue
        raw = run('exec-out', 'cat', '/sdcard/wardrobe-e2e.xml')
        try:
            return list(ET.fromstring(raw).iter('node'))
        except ET.ParseError:
            time.sleep(1)
    raise AssertionError('UI hierarchy unavailable')

def find(nodes, label):
    candidates = [n for n in nodes if label in (n.get('text'), n.get('content-desc'))]
    return next((n for n in candidates if n.get('class') != 'android.widget.EditText'),
                candidates[0] if candidates else None)

def tap(label, scroll=False):
    for _ in range(9 if scroll else 2):
        nodes = screen()
        node = find(nodes, label)
        if node is not None:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            time.sleep(.25)
            return
        if scroll:
            shell('input', 'swipe', '360', '1180', '360', '420', '350')
    raise AssertionError((label, [n.get('text') for n in nodes if n.get('text')]))

def visible(label):
    assert find(screen(), label) is not None, label

def back():
    shell('input', 'keyevent', '4')

def text(label, value):
    for _ in range(9):
        nodes = screen()
        node = next((n for n in nodes if n.get('class') == 'android.widget.EditText'
                     and label == n.get('content-desc')), None)
        if node is not None:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            break
        shell('input', 'swipe', '360', '1180', '360', '420', '350')
    else:
        raise AssertionError('Missing input: ' + label)
    shell('input', 'keyevent', 'KEYCODE_MOVE_END')
    previous = node.get('text', '')
    if previous:
        shell('input', 'keyevent', *(['KEYCODE_DEL'] * len(previous)))
    shell('input', 'text', value)
    back()  # dismiss IME, not the form

def top():
    for _ in range(4):
        shell('input', 'swipe', '360', '430', '360', '1180', '200')

def snapshot(name):
    # Android may use WAL. Capture it too; SQLite rebuilds its own shared-memory
    # index. Unique copies prevent an earlier evidence connection locking the file.
    path = RESULTS / (name + '-' + str(time.time_ns()) + '.sqlite')
    main = run('exec-out', 'run-as', PKG, 'cat', 'databases/wardrobe.sqlite')
    assert main.startswith(b'SQLite format 3\0'), 'Could not read application database'
    path.write_bytes(main)
    for suffix in ('-wal',):
        try:
            data = run('exec-out', 'run-as', PKG, 'cat', 'databases/wardrobe.sqlite' + suffix)
            if data[:4] in (b'\x37\x7f\x06\x82', b'\x37\x7f\x06\x83'):
                Path(str(path) + suffix).write_bytes(data)
        except subprocess.CalledProcessError:
            pass
    return sqlite3.connect(path)

def evidence(name):
    screen()  # wait for the accessibility tree after a page transition
    time.sleep(.4)
    (RESULTS / (name + '.png')).write_bytes(run('exec-out', 'screencap', '-p'))
    (RESULTS / (name + '.xml')).write_bytes(run('exec-out', 'cat', '/sdcard/wardrobe-e2e.xml'))

def assert_integrity(db):
    assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
    assert db.execute('PRAGMA foreign_key_check').fetchall() == []
    assert not db.execute('SELECT outfit FROM outfit_layer GROUP BY outfit HAVING count(*)>20').fetchall()
    assert not db.execute('SELECT outfit,garment FROM outfit_layer GROUP BY outfit,garment HAVING count(*)>1').fetchall()
    assert not db.execute('SELECT outfit,day FROM history WHERE outfit IS NOT NULL GROUP BY outfit,day HAVING count(*)>1').fetchall()

def assert_no_crash(name):
    log = run('logcat', '-d', '-v', 'threadtime').decode('utf-8', errors='replace')
    (RESULTS / (name + '-logcat.txt')).write_text(log, encoding='utf-8')
    assert not re.search(r'FATAL EXCEPTION[^\n]*\n[^\n]*Process: com\.smartwardrobe\.local', log), 'App crash in logcat'
    errors = [line for line in log.splitlines() if ' E ' in line and ('SmartWardrobe' in line or 'SQLite' in line or 'AndroidRuntime' in line)]
    assert not errors, '\n'.join(errors)
