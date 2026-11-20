"""Replace ONLY the selected emulator DB with deterministic regression fixtures.

Requires the full pre-task-data.tar backup; restore it after verification.
Never use this against a phone or without backing up the emulator first.
"""
from datetime import date, timedelta
from device import *

assert (RESULTS / 'pre-task-data.tar').stat().st_size > 0, 'Back up the emulator first'
shell('am', 'force-stop', PKG)
with snapshot('before-regression-fixture') as source:
    target = RESULTS / 'regression-fixture.sqlite'
    with sqlite3.connect(target) as db:
        source.backup(db)
        db.execute('PRAGMA foreign_keys=OFF')
        for (table,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").fetchall():
            db.execute('DELETE FROM "' + table + '"')
        today = shell('date', '+%F').decode().strip()
        old = str(date.fromisoformat(today) - timedelta(days=120))
        def garment(name, symbols='', dirty=0, stored=0, wash_every=10, idle_days=90, created=today):
            return db.execute('''INSERT INTO garment(name,type,brand,fabric,original,photo,processed,seasons,
                favorite,dirty,stored,care,symbols,source_text,label_text,wash_every,idle_days,created,archived)
                VALUES(?,?,?,?,?,?,?,15,0,?,?,?,?,?,?,?,?,?,0)''',
                (name,'衬衫','','棉','','','',dirty,stored,'',symbols,'','',wash_every,idle_days,created)).lastrowid
        garment('E2E-Machine30', '最高 30°C', dirty=1)
        garment('E2E-Machine30B', '最高 30°C', dirty=1)
        garment('E2E-Machine60', '最高 60°C', dirty=1)
        garment('E2E-Gentle', '最高 30°C · 柔洗', dirty=1)
        garment('E2E-Hand', '手洗', dirty=1)
        garment('E2E-Unknown', dirty=1)
        garment('E2E-Dry', '不可水洗\n专业干洗 · P', dirty=1)
        garment('E2E-ReadyStored', '最高 30°C', stored=1)
        care = garment('E2E-CareStored', '最高 30°C', stored=1, wash_every=1)
        db.execute('INSERT INTO wash(garment,day) VALUES(?,?)', (care,today))
        garment('E2E-IdleStored', '最高 30°C', stored=1, idle_days=30, created=old)
        garment('E2E-DirtyStored', '最高 30°C', dirty=1, stored=1)
        # Single-layer deletion cascade + a full canvas for boundary checks.
        ids = [garment('E2E-Layer-%02d' % n) for n in range(21)]
        cap = db.execute("INSERT INTO outfit(name,scene) VALUES('E2E-Cap20','')").lastrowid
        for index, item in enumerate(ids[:20]):
            db.execute('INSERT INTO outfit_layer VALUES(?,?,?,?,?,?)',(cap,index,item,.5,.5,.44))
        delete = db.execute("INSERT INTO outfit(name,scene) VALUES('E2E-DeleteCascade','')").lastrowid
        db.execute('INSERT INTO outfit_layer VALUES(?,?,?,?,?,?)',(delete,0,ids[-1],.5,.5,.44))
        db.commit()
run('push', str(target), '/data/local/tmp/wardrobe-regression.sqlite')
shell('run-as', PKG, 'rm', '-f', 'databases/wardrobe.sqlite-wal',
      'databases/wardrobe.sqlite-shm', 'databases/wardrobe.sqlite-journal')
shell('run-as', PKG, 'cp', '/data/local/tmp/wardrobe-regression.sqlite', 'databases/wardrobe.sqlite')
shell('logcat', '-c')
shell('am', 'start', '-n', PKG + '/.MainActivity')
print('Seeded deterministic emulator-only regression fixture; original data backup retained.')
