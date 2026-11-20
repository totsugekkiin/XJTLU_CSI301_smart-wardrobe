"""Final visual evidence, shared image transition and primary-colour undo."""
import cv2
import numpy as np
from unified_garment import *

recording=None
original_scale=shell("settings","get","global","animator_duration_scale").decode().strip()


def absent(label):
    assert find(screen(),label) is None,label


def shot(name):
    evidence(name)
    for node in screen():
        assert '·' not in node.get('text',''),node.get('text')


try:
    garment=fixture()
    shell("settings","put","global","animator_duration_scale","1")
    restart()
    node=find(screen(),TITLE)
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    recording=subprocess.Popen([*ADB,'shell','screenrecord','--bit-rate','4000000','--time-limit','8',
                                '/sdcard/wardrobe-white-hero.mp4'],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    time.sleep(.5)
    shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(.7)
    shell('input','tap','648','225')
    time.sleep(.4)
    shell('input','tap','648','225')
    time.sleep(.4)
    shell('input','tap','584','860')
    time.sleep(.45)
    shell('input','tap','352','220')
    time.sleep(.45)
    shell('input','tap','352','448')
    time.sleep(.5)
    recording.wait(timeout=15)
    run('pull','/sdcard/wardrobe-white-hero.mp4',str(RESULTS/'white-hero.mp4'))
    absent('保存')
    tap('返回')
    tap(TITLE)
    shot('white-overview')
    tap('洗护 Tab')
    tap('档案 Tab')
    shot('white-profile')
    tap('洗护 Tab')
    shot('white-care')
    tap('干燥要求')
    shot('white-care-expanded')
    tap('记录 Tab')
    shot('white-records')
    image=cv2.imdecode(np.fromfile(str(RESULTS/'white-records.png'),dtype=np.uint8),cv2.IMREAD_COLOR)
    # No residual solid action-button edge at the top of the scrolled content.
    assert np.all(image[174:184,530:670]>230), 'clipped dark action at content edge'
    for filename in ['white-profile','white-care','white-records']:
        image=cv2.imdecode(np.fromfile(str(RESULTS/(filename+'.png')),dtype=np.uint8),cv2.IMREAD_COLOR)
        assert not np.any(np.all(image == np.array([247,245,245],dtype=np.uint8),axis=2)),filename
    print('PASS: shared-image video, pure-white profile/care/records, no middle-dot separators or clipped black edge',flush=True)

    tap('档案 Tab')
    text('材质','Cotton')
    shot('white-save-active')
    tap('返回')
    tap('放弃修改')
    tap(TITLE)
    tap('洗护 Tab')
    tap('档案 Tab')
    tap('删除颜色 浅灰色')
    tap('撤销')
    absent('保存')
    visible('浅灰色 75%')
    visible('白色 25%')
    tap('删除颜色 浅灰色')
    visible('白色')
    absent('白色 25%')
    tap('保存')
    time.sleep(.5)
    absent('保存')
    with snapshot('primary-promoted') as db:
        assert db.execute('SELECT name,is_primary FROM color WHERE garment=? ORDER BY position',(garment,)).fetchall()==[('白色',1)]
    print('PASS: primary deletion promotes next colour; undo restores order/primary; single-colour percentage hidden',flush=True)

    tap('返回')
    tap('＋ 添加')
    tap('手动建档')
    shot('white-add')
    absent('完成添加')
    tap('返回')
    with snapshot('visual-integrity') as db: assert_integrity(db)
    assert_no_crash('minimal-visual')
    print('PASS: clean new page, hidden pristine Save, final integrity and logcat',flush=True)
finally:
    if original_scale=="null": shell("settings","delete","global","animator_duration_scale")
    else: shell("settings","put","global","animator_duration_scale",original_scale)
    if recording is not None and recording.poll() is None: recording.wait(timeout=15)
    if (RESULTS/'pre-task-data.tar').exists():
        runpy.run_path(str(ROOT/'restore_data.py'),run_name='__main__')
