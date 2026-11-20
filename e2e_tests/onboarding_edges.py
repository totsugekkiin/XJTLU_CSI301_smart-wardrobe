"""Onboarding large text, reduced motion, today reset and actual process-death recovery."""
import runpy
import device
from device import *

device.RESULTS=RESULTS/('onboarding-edges-'+str(time.time_ns()))
device.RESULTS.mkdir()
RESULTS=device.RESULTS


def shown(index):
    label=f'{index:02d} / 06'
    for _ in range(12):
        if find(screen(),label) is not None: return
        time.sleep(.3)
    raise AssertionError(label)


def replay():
    tap('衣橱');tap('筛选');tap('使用指南');shown(1)


settings={('system','font_scale'):None,('global','animator_duration_scale'):None,
          ('global','transition_animation_scale'):None,('global','window_animation_scale'):None}
for key in settings: settings[key]=shell('settings','get',*key).decode().strip()
shell('am','force-stop',PKG)
with (RESULTS/'pre-task-data.tar').open('wb') as backup:
    subprocess.run([*ADB,'exec-out','run-as',PKG,'tar','-cf','-','.'],stdout=backup,check=True,timeout=60)
try:
    shell('am','start','-n',PKG+'/.MainActivity')
    time.sleep(.8)
    if find(screen(),'01 / 06') is None:
        tap('筛选');tap('使用指南')
    shown(1)
    evidence('final-step-01')
    tap('下一步');shown(2)
    shell('settings','put','system','font_scale','1.3')
    time.sleep(1)
    shown(2)
    evidence('final-large-text-step-02')
    tap('下一步');shown(3)
    shell('settings','put','system','font_scale','1.0')
    time.sleep(1)
    shown(3);tap('跳过')
    print('PASS: enlarged text retains step and next button remains operable',flush=True)

    for namespace,key in settings:
        if namespace=='global': shell('settings','put',namespace,key,'0')
    tap('日历');tap('下个月')
    future=[n.get('text') for n in screen() if re.fullmatch(r'\d+月\d+日  周[一二三四五六日]',n.get('text',''))]
    replay()
    for step in range(2,7): tap('下一步');shown(step)
    evidence('final-step-06')
    tap('开始使用');tap('日历')
    today=shell('date','+%m-%d').decode().strip()
    month,day=map(int,today.split('-'))
    assert any(n.get('text','').startswith(f'{month}月{day}日  周') for n in screen())
    assert future and not future[0].startswith(f'{month}月{day}日  周')
    print('PASS: reduced-motion six-step flow and calendar reset from a future selection to today',flush=True)

    replay()
    for step in range(2,5): tap('下一步');shown(step)
    before=shell('pidof',PKG).strip()
    shell('input','keyevent','3')
    time.sleep(1)
    shell('am','kill',PKG)
    time.sleep(.7)
    killed=run('shell','sh','-c',f"'pidof {PKG} || true'").strip()
    assert not killed,('Process did not stop',killed)
    shell('am','start','-n',PKG+'/.MainActivity')
    shown(4)
    after=shell('pidof',PKG).strip()
    assert before!=after,(before,after)
    evidence('final-restored-step-04')
    tap('下一步');shown(5);tap('下一步');shown(6);tap('开始使用')
    print('PASS: actual process death changes PID and restores step 04 even after onboarding was previously completed',flush=True)
    assert_no_crash('onboarding-edges')
finally:
    shell('am','force-stop',PKG)
    for (namespace,key),value in settings.items():
        if value=='null': shell('settings','delete',namespace,key)
        else: shell('settings','put',namespace,key,value)
    runpy.run_path(str(ROOT/'restore_data.py'),run_name='__main__')
