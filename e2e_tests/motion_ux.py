"""Motion regression with a real screen recording; emulator data is restored."""
import cv2
import numpy as np
from unified_garment import *

original_scale = shell('settings','get','global','animator_duration_scale').decode().strip()
recording = None


def nav(label):
    node = next(n for n in screen() if n.get('content-desc') == label and n.get('clickable') == 'true')
    return list(map(int,re.findall(r'\d+',node.get('bounds'))))


try:
    garment = fixture()
    shell('settings','put','global','animator_duration_scale','1')
    restart()
    bounds = nav('衣橱')
    nav_y = bounds[1]
    nav_tap = (bounds[1] + bounds[3]) // 2
    recording = subprocess.Popen([*ADB,'shell','screenrecord','--bit-rate','3000000',
                                  '--time-limit','20','/sdcard/wardrobe-motion.mp4'],
                                 stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    time.sleep(.7)
    for x in [264,616,88,616,264,88]:
        shell('input','tap',str(x),str(nav_tap))
        time.sleep(.45)
    tap('＋ 添加')
    title = find(screen(),'添加衣物')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',title.get('bounds')))
    x,y=(x1+x2)//2,(y1+y2)//2
    shell('input','swipe',str(x),str(y),str(x),str(y+80),'600')
    visible('批量导入')
    shell('input','swipe',str(x),str(y),str(x),str(min(y+340,1480)),'400')
    time.sleep(.4)
    assert find(screen(),'批量导入') is None
    tap('筛选')
    visible('应用筛选')
    back()
    recording.wait(timeout=30)
    run('pull','/sdcard/wardrobe-motion.mp4',str(RESULTS/'motion.mp4'))
    capture=cv2.VideoCapture(str(RESULTS/'motion.mp4'))
    fps=capture.get(cv2.CAP_PROP_FPS)
    centers=[]
    for _ in range(int(fps*5)):
        ok,frame=capture.read()
        if not ok: break
        row=frame[nav_y+1]
        xs=np.where(np.all(row < 40,axis=1))[0]
        if 48 <= len(xs) <= 62:
            centers.append(round(float(xs.mean()),1))
    capture.release()
    intermediate=[x for x in centers if min(abs(x-y) for y in (88,264,440,616)) > 4]
    assert len(set(intermediate)) >= 4, centers
    print('PASS: recorded continuous indicator positions, sheet entry/short-drag return/downward dismiss and filter',flush=True)

    for label in ['收藏 '+TITLE,'取消收藏 '+TITLE]:
        tap(label)
    with snapshot('favorite-restored') as db:
        assert db.execute('SELECT favorite FROM garment WHERE id=?',(garment,)).fetchone()==(0,)
    tap(TITLE)
    enabled('保存',False)
    for label in ['洗护 Tab','记录 Tab','档案 Tab','记录 Tab','档案 Tab']:
        tap(label)
    enabled('保存',False)
    tap('看原图')
    tap('看封面')
    enabled('保存',False)
    print('PASS: favourite pulse persists once and restores state; sub-tabs/preview preserve pristine Save state',flush=True)

    tap('更多操作')
    tap('用原图重新抠图')
    for _ in range(20):
        if find(screen(),'保存') is not None: break
        time.sleep(.5)
    tap('保存')
    enabled('保存',False)
    tap('笔刷')
    brush=find(screen(),'在原图上涂抹保留区域')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',brush.get('bounds')))
    x,y=(x1+x2)//2,(y1+y2)//2
    shell('input','swipe',str(x),str(y),str(x),str(y+150),'700')
    visible('保留笔刷')
    tap('完成')
    enabled('保存',True)
    tap('关闭')
    tap('放弃修改')
    print('PASS: vertical brush strokes are not intercepted by sheet drag-to-dismiss',flush=True)

    shell('settings','put','global','animator_duration_scale','0')
    tap('＋ 添加')
    visible('批量导入')
    back()
    assert find(screen(),'批量导入') is None
    tap('筛选')
    visible('应用筛选')
    back()
    with snapshot('motion-integrity') as db: assert_integrity(db)
    assert_no_crash('motion')
    print('PASS: system-disabled animations, database integrity and no app/SQLite errors',flush=True)
finally:
    if recording is not None and recording.poll() is None:
        recording.wait(timeout=30)
    if original_scale == 'null': shell('settings','delete','global','animator_duration_scale')
    else: shell('settings','put','global','animator_duration_scale',original_scale)
    if (RESULTS/'pre-task-data.tar').exists():
        runpy.run_path(str(ROOT/'restore_data.py'),run_name='__main__')
