"""Check the shared garment image stays opaque and has no doubled silhouette."""
import cv2
import numpy as np
from unified_garment import *

original_scale=shell('settings','get','global','animator_duration_scale').decode().strip()
recording=None
try:
    fixture()
    shell('settings','put','global','animator_duration_scale','1')
    restart()
    node=find(screen(),TITLE)
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    recording=subprocess.Popen([*ADB,'shell','screenrecord','--bit-rate','4000000','--time-limit','5',
                                '/sdcard/white-hero-final.mp4'],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    time.sleep(.5)
    shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(1)
    shell('input','tap','648','225')
    time.sleep(.45)
    shell('input','tap','648','225')
    time.sleep(.6)
    recording.wait(timeout=10)
    run('pull','/sdcard/white-hero-final.mp4',str(RESULTS/'hero-final.mp4'))
    evidence('overview-final')
    image=cv2.imdecode(np.fromfile(str(RESULTS/'overview-final.png'),dtype=np.uint8),cv2.IMREAD_COLOR)
    assert np.count_nonzero(np.mean(image[196:670,160:545],axis=2)<240)>70000,'Final garment image became transparent'
    absent = find(screen(),'保存')
    assert absent is None,'Preview changed dirty state'
    capture=cv2.VideoCapture(str(RESULTS/'hero-final.mp4'))
    heights=[]
    while True:
        ok,frame=capture.read()
        if not ok: break
        position=capture.get(cv2.CAP_PROP_POS_MSEC)
        if position>1200: break
        roi=frame[180:900,:530].astype(np.int16)
        mask=((roi.max(axis=2)-roi.min(axis=2)<60)&(roi.mean(axis=2)>100)&(roi.mean(axis=2)<240)).astype(np.uint8)
        count,labels,stats,centroids=cv2.connectedComponentsWithStats(mask)
        if count>1:
            component=stats[1:][np.argmax(stats[1:,cv2.CC_STAT_AREA])]
            if component[cv2.CC_STAT_AREA]>20000: heights.append(int(component[cv2.CC_STAT_HEIGHT]))
    capture.release()
    assert heights and max(heights)<510,heights
    assert max(heights)-min(heights)>25,heights
    assert_no_crash('hero-final')
    print('PASS: intermediate shared-image sizes, single silhouette, fully opaque final photo and pristine Save',flush=True)
finally:
    if recording is not None and recording.poll() is None: recording.wait(timeout=10)
    if original_scale=='null': shell('settings','delete','global','animator_duration_scale')
    else: shell('settings','put','global','animator_duration_scale',original_scale)
    if (RESULTS/'pre-task-data.tar').exists():
        runpy.run_path(str(ROOT/'restore_data.py'),run_name='__main__')
