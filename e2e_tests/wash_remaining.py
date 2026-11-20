"""Verify the original single-item wash path for five distinct care-label groups."""
from device import *

for name in ('E2E-Machine60','E2E-Gentle','E2E-Hand','E2E-Unknown','E2E-Dry'):
    shell('am','force-stop',PKG)
    shell('am','start','-n',PKG+'/.MainActivity')
    time.sleep(2)
    tap('筛选')
    text('搜索名称或品牌',name)
    tap('应用筛选')
    tap(name)
    tap('已洗')
    with snapshot('single-wash-' + name) as db:
        row=db.execute('SELECT id,dirty,stored FROM garment WHERE name=?',(name,)).fetchone()
        assert row[1:] == (0,0), (name,row)
        assert db.execute('SELECT count(*) FROM wash WHERE garment=?',(row[0],)).fetchone()==(1,),name
        assert_integrity(db)
    print('PASS: original single-item wash: '+name,flush=True)
assert_no_crash('single-washes')
