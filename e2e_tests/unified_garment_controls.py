"""Colour, symbol, cover and photo-tool checks plus final screenshots."""
from unified_garment import *


def wait_saved():
    for _ in range(15):
        node = find(screen(), '保存')
        if node is not None and node.get('enabled') == 'false':
            return
        time.sleep(.3)
    raise AssertionError('Save did not finish')


try:
    garment = fixture()
    restart()
    tap(TITLE)
    text('品牌', 'Uniqlo')
    text('材质 / 成分', 'Cotton')
    tap('春秋', scroll=True)
    tap('四季适用')
    tap('确认')
    tap('+ 添加颜色', scroll=True)
    text('颜色名称', 'Slate')
    text('占比（0–100）', '30')
    tap('保存')
    enabled('保存', True)
    with snapshot('color-before-save') as db:
        assert db.execute('SELECT count(*) FROM color WHERE garment=?',(garment,)).fetchone()==(2,)
    tap('保存')
    wait_saved()
    with snapshot('profile-controls') as db:
        assert db.execute('SELECT brand,fabric,seasons FROM garment WHERE id=?',(garment,)).fetchone()==('Uniqlo','Cotton',15)
        assert db.execute('SELECT percent FROM color WHERE garment=? AND name=?',(garment,'Slate')).fetchone()==(30,)
    tap('删除颜色 Slate', scroll=True)
    enabled('保存', True)
    tap('保存')
    wait_saved()
    with snapshot('color-removed') as db:
        assert db.execute('SELECT count(*) FROM color WHERE garment=?',(garment,)).fetchone()==(2,)
    print('PASS: inline brand/material, season multi-select, deferred colour save and chip removal',flush=True)

    tab('洗护')
    tap('编辑符号')
    tap('洗涤')
    tap('最高 40°C', scroll=True)
    tap('确认使用')
    enabled('保存', True)
    tap('查看 / 修改', scroll=True)
    text('原文','LabelText')
    tap('确认')
    tap('保存')
    wait_saved()
    with snapshot('symbols-saved') as db:
        symbols,raw=db.execute('SELECT symbols,label_text FROM garment WHERE id=?',(garment,)).fetchone()
        assert '最高 40°C' in symbols and raw=='LabelText',(symbols,raw)
    text('洗涤提醒间隔（次）','2')
    tap('保存')
    wait_saved()
    top()
    tap('完成护理检查')
    tap('完成护理')
    visible('标为待洗')
    enabled('保存',False)
    print('PASS: symbol/label text edits, care reminder action and completing a care check',flush=True)

    tap('设置封面')
    tap('使用原图作封面')
    enabled('保存',True)
    tap('保存')
    wait_saved()
    with snapshot('original-cover') as db:
        original,processed,photo=db.execute('SELECT original,processed,photo FROM garment WHERE id=?',(garment,)).fetchone()
        assert photo==original and original!=processed
    tap('设置封面')
    tap('使用处理图作封面')
    tap('保存')
    wait_saved()
    tap('更多操作')
    tap('用原图重新抠图')
    for _ in range(20):
        if find(screen(),'保存') is not None: break
        time.sleep(1)
    tap('保存')
    wait_saved()
    tap('框选')
    visible('框选衣物')
    back()
    enabled('保存',False)
    tap('笔刷')
    visible('保留笔刷')
    brush=find(screen(),'在原图上涂抹保留区域')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',brush.get('bounds')))
    x=(x1+x2)//2
    y=(y1+y2)//2
    shell('input','swipe',str(x),str(y),str(x+12),str(y+30),'400')
    tap('完成')
    enabled('保存',True)
    tap('保存')
    wait_saved()
    print('PASS: original/processed cover persistence, crop cancellation, brush result deferred until Save',flush=True)

    tab('档案')
    evidence('final-overview')
    shell('input','swipe','350','1200','350','420','450')
    evidence('final-profile')
    tab('洗护')
    shell('input','swipe','350','1200','350','470','450')
    evidence('final-care')
    tab('记录')
    shell('input','swipe','350','1200','350','650','450')
    evidence('final-records')
    top()
    tap('关闭')
    tap('＋ 添加')
    tap('手动建档')
    evidence('final-add')
    enabled('完成添加',False)
    tap('关闭')
    assert find(screen(),'放弃未保存修改？') is None
    with snapshot('final-integrity') as db:
        assert_integrity(db)
    assert_no_crash('unified-controls')
    print('PASS: final screenshots, unchanged new form exits directly, database integrity and logcat',flush=True)
finally:
    if (RESULTS / 'pre-task-data.tar').exists():
        runpy.run_path(str(ROOT / 'restore_data.py'), run_name='__main__')
