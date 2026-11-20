from device import *

expected = {'E2E-Machine30': 1, 'E2E-Machine30B': 1, 'E2E-Machine60': 1, 'E2E-Gentle': 1,
            'E2E-Hand': 1, 'E2E-Unknown': 1, 'E2E-Dry': 1, 'E2E-ReadyStored': 0,
            'E2E-CareStored': 2, 'E2E-IdleStored': 1, 'E2E-DirtyStored': 1}
with snapshot('care-after') as db:
    for name, washes in expected.items():
        row = db.execute('SELECT id,dirty,stored FROM garment WHERE name=?', (name,)).fetchone()
        assert row is not None and row[1:] == (0, 0), (name, row)
        assert db.execute('SELECT count(*) FROM wash WHERE garment=?', (row[0],)).fetchone() == (washes,), name
    assert_integrity(db)
evidence('care-after')
assert_no_crash('care')
print('PASS: original care-label groups, both seasonal actions, per-item counts and integrity')
