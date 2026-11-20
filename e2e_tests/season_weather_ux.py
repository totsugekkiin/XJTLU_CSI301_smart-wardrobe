"""Season recommendations, permission denial and cache fallbacks on a backed-up emulator."""
import datetime
import xml.etree.ElementTree as XML
from unified_garment import *

NETWORK_PERMISSION='android.permission.ACCESS_COARSE_LOCATION'
today=shell('date','+%F').decode().strip()
date=datetime.date.fromisoformat(today)
quarter=f'{date.year}-Q{(date.month-1)//3+1}'
weather_pref=None


def absent(label):
    assert find(screen(),label) is None,label


def click_node(node):
    assert node is not None
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(.3)


def read_prefs():
    try: return XML.fromstring(run('exec-out','run-as',PKG,'cat','shared_prefs/season_weather.xml'))
    except subprocess.CalledProcessError: return XML.Element('map')


def pref_value(key):
    node=next((n for n in read_prefs() if n.get('name')==key),None)
    return None if node is None else node.get('value',node.text)


def cache(temperature=12, age=0, dismissed='', malformed=False, extra=None):
    shell('am','force-stop',PKG)
    data={'timezone':'Asia/Shanghai','daily':{
        'time':[(date+datetime.timedelta(days=i)).isoformat() for i in range(3)],
        'temperature_2m_max':[temperature-1,temperature,temperature+1],
        'temperature_2m_min':[temperature-6]*3}}
    root=XML.Element('map')
    values={'permission_asked':True,'fetched_at':int(time.time()*1000)-age,
            'fetched_day':today,'last_attempt_day':today,'dismissed_quarter':dismissed,
            'forecast':'{invalid' if malformed else json.dumps(data)}
    if extra: values.update(extra)
    for key,value in values.items():
        if isinstance(value,bool): XML.SubElement(root,'boolean',name=key,value=str(value).lower())
        elif isinstance(value,int): XML.SubElement(root,'long',name=key,value=str(value))
        else: XML.SubElement(root,'string',name=key).text=value
    path=RESULTS/'weather-seed.xml'
    XML.ElementTree(root).write(path,encoding='utf-8',xml_declaration=True)
    run('push',str(path),'/data/local/tmp/weather-seed.xml')
    shell('run-as',PKG,'mkdir','-p','shared_prefs')
    for retry in range(3):
        try:
            shell('run-as',PKG,'cp','/data/local/tmp/weather-seed.xml','shared_prefs/season_weather.xml')
            break
        except subprocess.CalledProcessError:
            if retry==2: raise
            time.sleep(.5)
    restart()
    tap('洗护')


def season_fixture():
    garment=fixture()
    shell('am','force-stop',PKG)
    with snapshot('before-season-items') as source, sqlite3.connect(RESULTS/'season-fixture.sqlite') as db:
        source.backup(db)
        for name,bits,stored,dirty,interval in [('冬季外套A',8,1,0,10),('冬季针织B',8,1,1,10),
                ('冬季卫衣C',8,0,0,1),('冬季夹克D',8,0,1,10),('夏季短袖',2,1,0,10),('秋季风衣',4,1,0,10)]:
            item=db.execute('''INSERT INTO garment(name,type,brand,fabric,original,processed,photo,seasons,
                favorite,dirty,stored,care,symbols,source_text,label_text,wash_every,idle_days,created,archived)
                VALUES(?,'外套','','','','','',?,0,?,?,'','最高 30°C','','',?,90,?,0)''',
                (name,bits,dirty,stored,interval,today)).lastrowid
            if name=='冬季卫衣C': db.execute('INSERT INTO wash(garment,day) VALUES(?,?)',(item,today))
    run('push',str(RESULTS/'season-fixture.sqlite'),'/data/local/tmp/season-fixture.sqlite')
    shell('run-as',PKG,'rm','-f','databases/wardrobe.sqlite-wal','databases/wardrobe.sqlite-shm')
    shell('run-as',PKG,'cp','/data/local/tmp/season-fixture.sqlite','databases/wardrobe.sqlite')


def main():
    try:
        season_fixture()
        shell('pm','revoke',PKG,NETWORK_PERMISSION)
        restart()
        tap('洗护')
        nodes=screen()
        deny=next((n for n in nodes if n.get('resource-id','').endswith('permission_deny_button')),None)
        assert deny is not None,'Expected only coarse location permission request'
        assert not any('precise' in n.get('text','').lower() for n in nodes)
        click_node(deny)
        absent('待洗分组')
        absent('换季检查')
        visible('换季整理')
        assert all(not n.get('text','').startswith('未来3天平均最高温') for n in screen())
        evidence('care-month-fallback')
        tap('换季整理')
        visible('按当前月份推荐冬季')
        tap('夏季')
        assert find(screen(),'夏季').get('selected')=='true'
        tap('返回')
        tap('换季整理')
        assert find(screen(),'冬季').get('selected')=='true'
        tap('返回')
        print('PASS: coarse-only request, refusal is silent, independent page and default target resets after manual choice',flush=True)

        shell('pm','grant',PKG,NETWORK_PERMISSION)
        cache(12)
        visible('冬季衣橱待唤醒')
        visible('未来3天平均最高温 12°C\n2 件已收起 / 3 件需洗护检查')
        visible('天气来源：Open-Meteo')
        evidence('care-weather-demo')
        before=pref_value('last_attempt_day')
        tap('去整理')
        visible('未来3天平均最高温 12°C  建议冬季')
        evidence('season-winter-demo')
        tap('返回')
        assert pref_value('last_attempt_day')==before==today
        tap('本季度关闭换季提醒')
        absent('去整理')
        restart()
        tap('洗护')
        absent('去整理')
        assert pref_value('dismissed_quarter')==quarter
        cache(28,dismissed=quarter)
        absent('去整理')
        cache(28,dismissed='previous-quarter')
        visible('夏季衣橱待唤醒')
        visible('未来3天平均最高温 28°C\n1 件已收起 / 0 件需洗护检查')
        cache(24)
        visible('秋季衣橱待唤醒')
        visible('未来3天平均最高温 24°C\n1 件已收起 / 0 件需洗护检查')
        print('PASS: three-day temperature seasons, fresh cache, daily no-repeat, persistent quarter dismissal and next-quarter reset',flush=True)

        cache(12,age=24*60*60*1000+1000)
        assert all(not n.get('text','').startswith('未来3天平均最高温') for n in screen())
        cache(12,malformed=True)
        assert all(not n.get('text','').startswith('未来3天平均最高温') for n in screen())
        shell('pm','revoke',PKG,NETWORK_PERMISSION)
        restart()
        tap('洗护')
        assert all(not n.get('text','').startswith('未来3天平均最高温') for n in screen())
        tap('整组标记已洗',scroll=True)
        tap('撤销')
        with snapshot('wash-undo') as db:
            assert db.execute("SELECT dirty,stored FROM garment WHERE name='冬季针织B'").fetchone()==(1,1)
            assert db.execute("SELECT dirty,stored FROM garment WHERE name='冬季夹克D'").fetchone()==(1,0)
            assert_integrity(db)
        for node in screen(): assert '·' not in node.get('text','')
        assert_no_crash('season-weather')
        print('PASS: expired/malformed cache and revoked permission fall back silently; old wash/undo semantics preserved',flush=True)
    finally:
        if (RESULTS/'pre-task-data.tar').exists():
            runpy.run_path(str(ROOT/'restore_data.py'),run_name='__main__')



if __name__ == "__main__":
    main()
