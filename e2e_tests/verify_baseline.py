from device import *

ITEM = os.environ.get('ITEM', 'E2E-Human-Shirt')
LOOK = os.environ.get('LOOK', 'E2E-Human-Look')
with snapshot('baseline-after') as db:
    item = db.execute('SELECT id,dirty,stored FROM garment WHERE name=? ORDER BY id DESC', (ITEM,)).fetchone()
    assert item is not None and item[1:] == (1, 0), item
    look = db.execute('SELECT id FROM outfit WHERE name=? ORDER BY id DESC', (LOOK,)).fetchone()[0]
    assert db.execute('SELECT garment FROM outfit_layer WHERE outfit=?', (look,)).fetchall() == [(item[0],)]
    assert db.execute('SELECT count(*) FROM history WHERE outfit=?', (look,)).fetchone() == (1,)
    assert db.execute('SELECT count(*) FROM history_garment WHERE garment=?', (item[0],)).fetchone() == (1,)
    assert db.execute('SELECT count(*) FROM wash WHERE garment=?', (item[0],)).fetchone() == (0,)
    assert db.execute('SELECT count(*) FROM outfit_draft WHERE name=?', (LOOK,)).fetchone() == (0,)
    assert_integrity(db)
evidence('baseline-detail')
assert_no_crash('baseline')
print('PASS: baseline UI, statistics, calendar, draft cleanup, SQLite integrity and logcat')
