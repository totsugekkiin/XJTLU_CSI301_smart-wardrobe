"""Seasonal per-item routing and idle activity-date regression on a backed-up emulator."""
from season_weather_ux import *


def day_before(days):
    return (date-datetime.timedelta(days=days)).isoformat()


def main():
    ids={}
    try:
        fixture()
        shell('am','force-stop',PKG)
        with snapshot('source-flow') as source, sqlite3.connect(RESULTS/'flow-fixture.sqlite') as db:
            source.backup(db)
            cases=[
                ('久置送洗',118,0,90,0,[],None,None),
                ('六十一天',61,0,0,0,[],None,None),
                ('收起待洗',0,1,90,10,[today,today],None,None),
                ('次数护理',0,0,90,1,[today],None,None),
                ('刚好六十天',60,0,0,0,[],None,None),
                ('最近已洗',118,0,90,0,[day_before(1)],None,None),
                ('最近穿着',118,0,90,0,[],day_before(2),None),
                ('最近检查',118,0,90,0,[],None,day_before(3)),
                ('记录早于建档',0,0,0,0,[day_before(118)],None,None),
            ]
            for name,age,dirty,idle,interval,washes,worn,checked in cases:
                item=db.execute('''INSERT INTO garment(name,type,brand,fabric,original,processed,photo,seasons,
                    favorite,dirty,stored,care,symbols,source_text,label_text,wash_every,idle_days,created,archived)
                    VALUES(?,'外套','','','','','',8,0,?,1,'','最高 30°C','','',?,?,?,0)''',
                    (name,dirty,interval,idle,day_before(age))).lastrowid
                ids[name]=item
                for day in washes: db.execute('INSERT INTO wash(garment,day) VALUES(?,?)',(item,day))
                if worn:
                    history=db.execute("INSERT INTO history(name,scene,day) VALUES('穿着验证','',?)",(worn,)).lastrowid
                    db.execute('INSERT INTO history_garment VALUES(?,?)',(history,item))
                if checked:
                    db.execute("INSERT INTO care_event(garment,day,note,wash_checkpoint) VALUES(?,?,'历史检查',0)",(item,checked))
        run('push',str(RESULTS/'flow-fixture.sqlite'),'/data/local/tmp/flow-fixture.sqlite')
        shell('run-as',PKG,'rm','-f','databases/wardrobe.sqlite-wal','databases/wardrobe.sqlite-shm')
        shell('run-as',PKG,'cp','/data/local/tmp/flow-fixture.sqlite','databases/wardrobe.sqlite')
        cache(12)
        tap('换季整理')
        # Only the 61+ day, dirty or independently care-due garments receive routing buttons.
        for name in ['刚好六十天','最近已洗','最近穿着','最近检查']:
            tap(name,scroll=True)
            visible('取出衣物')
            tap('返回')
            top()
        print('PASS: latest wash/wear/check activity wins; exact 60 days remains ready with idle disabled',flush=True)

        tap('六十一天 直接穿',scroll=True)
        time.sleep(.25)
        with snapshot('direct-clean') as db:
            assert db.execute('SELECT stored,dirty FROM garment WHERE id=?',(ids['六十一天'],)).fetchone()==(0,0)
            assert db.execute('SELECT day,note,wash_checkpoint FROM care_event WHERE garment=?',(ids['六十一天'],)).fetchone()==(today,'换季检查完成',0)
            assert db.execute('SELECT COUNT(*) FROM wash WHERE garment=?',(ids['六十一天'],)).fetchone()==(0,)
        top()
        tap('收起待洗 直接穿',scroll=True)
        time.sleep(.25)
        with snapshot('direct-dirty') as db:
            assert db.execute('SELECT stored,dirty FROM garment WHERE id=?',(ids['收起待洗'],)).fetchone()==(0,0)
            assert db.execute('SELECT wash_checkpoint FROM care_event WHERE garment=?',(ids['收起待洗'],)).fetchone()==(2,)
            assert db.execute('SELECT COUNT(*) FROM wash WHERE garment=?',(ids['收起待洗'],)).fetchone()==(2,)
        top()
        tap('次数护理 直接穿',scroll=True)
        time.sleep(.25)
        top()
        tap('次数护理',scroll=True)
        visible('标为待洗')
        tap('返回')
        print('PASS: direct wear clears dirty/storage and atomically records care with the current wash checkpoint',flush=True)

        top()
        tap('久置送洗 送洗',scroll=True)
        time.sleep(.25)
        with snapshot('send-wash') as db:
            assert db.execute('SELECT stored,dirty FROM garment WHERE id=?',(ids['久置送洗'],)).fetchone()==(0,1)
            assert db.execute('SELECT COUNT(*) FROM wash WHERE garment=?',(ids['久置送洗'],)).fetchone()==(0,)
            assert db.execute('SELECT COUNT(*) FROM care_event WHERE garment=?',(ids['久置送洗'],)).fetchone()==(0,)
        top()
        tap('久置送洗',scroll=True)
        tap('已洗')
        visible('标为待洗')
        with snapshot('washed-clean') as db:
            assert db.execute('SELECT stored,dirty FROM garment WHERE id=?',(ids['久置送洗'],)).fetchone()==(0,0)
            assert db.execute('SELECT day FROM wash WHERE garment=?',(ids['久置送洗'],)).fetchone()==(today,)
            assert db.execute('SELECT COUNT(*) FROM care_event WHERE garment=?',(ids['久置送洗'],)).fetchone()==(0,)
        tap('返回')
        print('PASS: idle-overdue send/wash returns to ready without a synthetic care event; wash count increases once',flush=True)

        top()
        tap('记录早于建档 送洗',scroll=True)
        time.sleep(.25)
        with snapshot('old-record') as db:
            assert db.execute('SELECT stored,dirty FROM garment WHERE id=?',(ids['记录早于建档'],)).fetchone()==(0,1)
        print('PASS: creation date is only a fallback when no activity history exists',flush=True)
        top()
        tap('一键全部取出',scroll=True)
        with snapshot('batch-ready') as db:
            for name in ['刚好六十天','最近已洗','最近穿着','最近检查']:
                assert db.execute('SELECT stored,dirty FROM garment WHERE id=?',(ids[name],)).fetchone()==(0,0)
            assert_integrity(db)
        assert_no_crash('season-flow')
        print('PASS: existing batch take-out preserved; database integrity and app crash checks pass',flush=True)
    finally:
        if (RESULTS/'pre-task-data.tar').exists():
            runpy.run_path(str(ROOT/'restore_data.py'),run_name='__main__')


def visual():
    try:
        fixture()
        shell('am','force-stop',PKG)
        with snapshot('visual-source') as source, sqlite3.connect(RESULTS/'visual-fixture.sqlite') as db:
            source.backup(db)
            for name,dirty,idle in [('冬季羊毛大衣',0,90),('收起的白色衬衫',1,0),('换季针织开衫',0,0)]:
                db.execute('''INSERT INTO garment(name,type,brand,fabric,original,processed,photo,seasons,
                    favorite,dirty,stored,care,symbols,source_text,label_text,wash_every,idle_days,created,archived)
                    VALUES(?,'外套','','','','','',8,0,?,1,'','最高 30°C','','',10,?,?,0)''',
                    (name,dirty,idle,day_before(118)))
        run('push',str(RESULTS/'visual-fixture.sqlite'),'/data/local/tmp/flow-visual.sqlite')
        shell('run-as',PKG,'rm','-f','databases/wardrobe.sqlite-wal','databases/wardrobe.sqlite-shm')
        shell('run-as',PKG,'cp','/data/local/tmp/flow-visual.sqlite','databases/wardrobe.sqlite')
        cache(12)
        tap('换季整理')
        for _ in range(6):
            nodes=screen()
            if find(nodes,'冬季羊毛大衣 直接穿') is not None and find(nodes,'收起的白色衬衫 直接穿') is not None: break
            shell('input','swipe','360','1180','360','650','350')
        assert find(screen(),'冬季羊毛大衣 直接穿') is not None
        evidence('season-flow-actions')
        assert any(n.get('text')=='久置 118 天 / 需护理检查' for n in screen())
        assert all('·' not in n.get('text','') for n in screen())
        print('PASS: reason labels, white outlined per-item controls and screenshot captured',RESULTS,flush=True)
    finally:
        if (RESULTS/'pre-task-data.tar').exists():
            runpy.run_path(str(ROOT/'restore_data.py'),run_name='__main__')


if __name__=='__main__':
    visual() if '--visual' in __import__('sys').argv else main()
