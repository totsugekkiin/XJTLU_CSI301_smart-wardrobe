from device import *

shell('am', 'force-stop', PKG)
shell('am', 'start', '-n', PKG + '/.MainActivity')
time.sleep(2)
tap('穿搭')
for _ in range(8):
    nodes = screen()
    title = find(nodes, 'E2E-Cap20')
    if title is not None:
        parents = {child: parent for parent in nodes for child in parent}
        owner = parents[title]
        edit = next(n for n in owner.iter('node') if n.get('text') == '编辑')
        x1,y1,x2,y2 = map(int,re.findall(r'\d+',edit.get('bounds')))
        shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
        break
    shell('input','swipe','360','1180','360','420','300')
else:
    raise AssertionError('Cap outfit missing')
tap('＋ 添加单品', True)
text('搜索名称或品牌', 'E2E-Layer-00')
visible('没有匹配的衣物')
text('搜索名称或品牌', 'E2E-Layer-20')
tap('E2E-Layer-20')
tap('画布图层（20）', True)
text('场景标签', 'E2E-Cap-Boundary')
with snapshot('canvas-limit-draft') as db:
    import json
    layers = json.loads(db.execute("SELECT layers FROM outfit_draft WHERE name='E2E-Cap20' ORDER BY id DESC LIMIT 1").fetchone()[0])
    assert len(layers) == 20
    assert len({layer[0] for layer in layers}) == 20
    assert_integrity(db)
tap('保存')
with snapshot('canvas-limit-saved') as db:
    look = db.execute("SELECT id FROM outfit WHERE name='E2E-Cap20'").fetchone()[0]
    assert db.execute('SELECT count(*),count(DISTINCT garment) FROM outfit_layer WHERE outfit=?', (look,)).fetchone() == (20,20)
    assert_integrity(db)
assert_no_crash('canvas')
print('PASS: duplicate candidate excluded, 21st rejected, draft and saved outfit remain 20 distinct layers')
