"""Read-only SQLite observer; run alongside care_flow.yaml."""
import json
from device import *

timeline = []
deadline = time.monotonic() + 600
while time.monotonic() < deadline:
    with snapshot('care-observed') as db:
        rows = db.execute('SELECT name,dirty,stored,(SELECT count(*) FROM wash WHERE garment=g.id) FROM garment g ORDER BY id').fetchall()
        assert_integrity(db)
    if not timeline or rows != timeline[-1]['rows']:
        state = {'time': time.time(), 'dirty': sum(r[1] for r in rows), 'stored': sum(r[2] for r in rows),
                 'washes': sum(r[3] for r in rows), 'rows': rows}
        timeline.append(state)
        (RESULTS / 'care-timeline.json').write_text(json.dumps(timeline, ensure_ascii=False, indent=2), encoding='utf-8')
        print({k:v for k,v in state.items() if k != 'rows'}, flush=True)
        if state['dirty'] == 5 and state['stored'] == 0 and state['washes'] == 6:
            break
    time.sleep(4)
else:
    raise AssertionError('Timed out observing care flow')
assert [(s['dirty'],s['stored'],s['washes']) for s in timeline] == [
    (8,4,1), (8,3,1), (10,0,1), (5,0,6)], timeline
print('PASS: SQLite observed every seasonal and wash batch transition', flush=True)
