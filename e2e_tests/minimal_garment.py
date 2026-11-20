"""White garment form, contextual care controls and dynamic header save."""
from unified_garment import *


def absent(label):
    nodes=screen()
    if find(nodes,label) is not None:
        evidence('failure')
        raise AssertionError((label,[(n.get('text'),n.get('content-desc')) for n in nodes if n.get('text') or n.get('content-desc')]))


def pristine():
    absent('保存')
    absent('完成添加')


def reveal(label):
    for _ in range(8):
        node=find(screen(),label)
        if node is not None:
            coords=list(map(int,re.findall(r'\d+',node.get('bounds'))))
            if coords[3]-coords[1]>=60: return node
        shell('input','swipe','350','1200','350','800','300')
    raise AssertionError(label)


try:
    garment=fixture()
    restart()
    tap(TITLE)
    pristine()
    absent('衣物')
    absent('关闭')
    tap('修图菜单')
    visible('更换照片')
    visible('框选识别')
    visible('保留笔刷')
    if os.environ.get('WARDROBE_FIXTURE_CUTOUT'):
        back()
    else:
        tap('重新识别')
        for _ in range(20):
            if find(screen(),'保存') is not None: break
            time.sleep(.5)
        tap('保存')
        time.sleep(.5)
    pristine()
    evidence('minimal-overview')
    tap('查看原图')
    tap('查看处理图')
    pristine()
    print('PASS: minimal header, no fixed save, photo tools and read-only preview',flush=True)

    # Use the saved photo but restore two fixture colours for exact undo checks.
    # Adding/removing colours still goes through the real editor.
    tap('档案 Tab')
    text('品牌','Uniqlo')
    node=find(screen(),'保存')
    assert node is not None and int(re.findall(r'\d+',node.get('bounds'))[1])<180
    tap('返回')
    visible('放弃未保存修改？')
    tap('取消')
    text('品牌','adidas')
    pristine()
    tap('+ 添加',scroll=True)
    text('颜色名称','Slate')
    text('占比（0–100）','30')
    tap('保存')
    tap('保存')
    time.sleep(.5)
    pristine()
    label=reveal('删除颜色 Slate')
    assert label is not None
    coords=list(map(int,re.findall(r'\d+',label.get('bounds'))))
    assert coords[3]-coords[1]==68,coords
    tap('删除颜色 Slate')
    tap('撤销')
    pristine()
    with snapshot('undo-colours') as db:
        assert db.execute('SELECT count(*) FROM color WHERE garment=? AND name=?',(garment,'Slate')).fetchone()==(1,)
    evidence('minimal-profile')
    print('PASS: header Save follows real dirty state; discard cancellation; 34dp chip and exact colour undo',flush=True)

    tap('洗护 Tab')
    pristine()
    absent('洗涤提醒间隔（次）')
    absent('闲置提醒间隔（天）')
    visible('温和洗涤，阴凉处晾干')
    evidence('minimal-care')
    tap('干燥要求')
    visible('干燥：阴凉处悬挂晾干')
    evidence('minimal-care-expanded')
    tap('干燥要求')
    absent('干燥：阴凉处悬挂晾干')
    tap('每 10 次 / 90 天')
    text('洗涤间隔（次）','0')
    text('闲置间隔（天）','0')
    tap('确认')
    visible('已关闭')
    tap('保存')
    time.sleep(.5)
    pristine()
    with snapshot('reminders-off') as db:
        assert db.execute('SELECT wash_every,idle_days FROM garment WHERE id=?',(garment,)).fetchone()==(0,0)
    tap('洗涤要求')
    tap('修改')
    tap('最高 40°C',scroll=True)
    tap('保存')
    time.sleep(.5)
    pristine()
    print('PASS: contextual care symbols, expand/collapse, group picker and merged reminder settings',flush=True)

    tap('记录 Tab')
    pristine()
    absent('可穿')
    absent('完成护理检查')
    absent('补记洗涤')
    visible('洗涤历史')
    visible('护理记录')
    evidence('minimal-records')
    tap('洗涤历史')
    visible('+ 补记')
    tap('+ 补记')
    visible('补记洗涤')
    back()
    tap('护理记录')
    absent('完成护理')
    back()
    tap('洗护 Tab')
    tap('已关闭')
    text('洗涤间隔（次）','2')
    text('闲置间隔（天）','90')
    tap('确认')
    tap('保存')
    time.sleep(.5)
    tap('记录 Tab')
    tap('护理记录')
    visible('完成护理')
    tap('完成护理')
    tap('完成护理')
    pristine()
    print('PASS: clean records layout, wash-history backdating entry and reminder-gated care action',flush=True)

    tap('返回')
    tap('＋ 添加')
    tap('手动建档')
    pristine()
    evidence('minimal-add')
    text('名称 *','MinimalNew')
    visible('完成添加')
    tap('完成添加')
    visible('选择衣物类别')
    tap('衬衫')
    tap('完成添加')
    time.sleep(.5)
    pristine()
    tap('返回')
    visible('MinimalNew')
    with snapshot('minimal-integrity') as db:
        assert_integrity(db)
        assert db.execute("SELECT count(*) FROM garment WHERE name='MinimalNew' AND type='衬衫'").fetchone()==(1,)
    assert_no_crash('minimal')
    print('PASS: new form gating and persistence, database integrity and no app errors',flush=True)
finally:
    if (RESULTS/'pre-task-data.tar').exists():
        runpy.run_path(str(ROOT/'restore_data.py'),run_name='__main__')
