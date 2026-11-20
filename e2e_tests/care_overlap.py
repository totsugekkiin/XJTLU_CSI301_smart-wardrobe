"""A care reminder remains actionable while another state owns the main CTA."""
from device import *

shell('am','force-stop',PKG)
shell('am','start','-n',PKG+'/.MainActivity')
time.sleep(2)
tap('筛选')
text('搜索名称或品牌','E2E-CareStored')
tap('应用筛选')
tap('E2E-CareStored')
tap('更多操作')
tap('补记洗涤')
tap('确认')
tap('更多操作')
tap('收起衣物')
visible('已收起')
tap('完成护理检查', True)
text('检查结果','E2E-stored-care')
tap('完成护理')
with snapshot('care-overlap') as db:
    item=db.execute("SELECT id,dirty,stored FROM garment WHERE name='E2E-CareStored'").fetchone()
    assert item[1:] == (0,1),item
    washes=db.execute('SELECT count(*) FROM wash WHERE garment=?',(item[0],)).fetchone()[0]
    assert db.execute('SELECT wash_checkpoint,note FROM care_event WHERE garment=? ORDER BY id DESC LIMIT 1',(item[0],)).fetchone()==(washes,'E2E-stored-care')
    assert_integrity(db)
evidence('care-overlap')
tap('取出衣物')
visible('可穿')
visible(f'洗涤 {washes} 次    穿着 0 次')
assert_no_crash('care-overlap')
print('PASS: stored garment care CTA stays accessible, resets checkpoint, and preserves storage')
