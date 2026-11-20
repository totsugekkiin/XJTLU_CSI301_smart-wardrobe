from device import *

with snapshot('final-regression-state') as db:
    worn = db.execute("SELECT id,archived FROM garment WHERE name='E2E-Layer-00'").fetchone()
    assert worn is not None and worn[1] == 1
    assert db.execute('SELECT count(*) FROM history_garment WHERE garment=?',(worn[0],)).fetchone() == (1,)
    assert db.execute('SELECT count(*) FROM outfit_layer WHERE garment=?',(worn[0],)).fetchone() == (1,)
    assert db.execute("SELECT count(*) FROM garment WHERE name='E2E-Layer-20'").fetchone() == (0,)
    assert db.execute("SELECT count(*) FROM outfit WHERE name='E2E-DeleteCascade'").fetchone() == (0,)
    cap=db.execute("SELECT id FROM outfit WHERE name='E2E-Cap20'").fetchone()[0]
    assert db.execute('SELECT count(*),count(DISTINCT garment) FROM outfit_layer WHERE outfit=?',(cap,)).fetchone() == (20,20)
    assert db.execute('SELECT count(*) FROM history WHERE outfit=?',(cap,)).fetchone() == (1,)
    assert db.execute('SELECT count(*) FROM history_garment JOIN history ON history.id=history_garment.history WHERE outfit=?',(cap,)).fetchone() == (20,)
    for name in ('E2E-CareStored','E2E-IdleStored'):
        item=db.execute('SELECT id FROM garment WHERE name=?',(name,)).fetchone()[0]
        washes=db.execute('SELECT count(*) FROM wash WHERE garment=?',(item,)).fetchone()[0]
        assert db.execute('SELECT wash_checkpoint FROM care_event WHERE garment=? ORDER BY id DESC LIMIT 1',(item,)).fetchone() == (washes,)
    assert_integrity(db)
assert_no_crash('final-regression')
print('PASS: archive preserves wear and outfit links; deletion cascades; limits, deduplication and care checkpoints verified')
