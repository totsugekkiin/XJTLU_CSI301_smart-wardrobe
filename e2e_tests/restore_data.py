"""Restore and verify the selected emulator's full pre-test private-data backup."""
import hashlib
import io
import tarfile
from contextlib import closing
from device import *

archive = RESULTS / 'pre-task-data.tar'
with tarfile.open(archive) as saved:
    for member in saved.getmembers():
        assert not member.name.startswith('/') and '..' not in Path(member.name).parts
    original_db = saved.extractfile('./databases/wardrobe.sqlite').read()
    original_wal = saved.extractfile('./databases/wardrobe.sqlite-wal').read() if './databases/wardrobe.sqlite-wal' in saved.getnames() else None
    photos = {m.name: hashlib.sha256(saved.extractfile(m).read()).hexdigest()
              for m in saved.getmembers() if m.isfile() and m.name.startswith('./files/photos/')}
shell('am', 'force-stop', PKG)
# Clearing here removes only the emulator test fixture. The backup above was
# checked first and is never overwritten by this script.
shell('pm', 'clear', PKG)
run('push', str(archive), '/data/local/tmp/wardrobe-original-data.tar')
shell('run-as', PKG, 'tar', '-xf', '/data/local/tmp/wardrobe-original-data.tar')
path = RESULTS / 'original-expected.sqlite'
path.write_bytes(original_db)
if original_wal is not None:
    Path(str(path)+'-wal').write_bytes(original_wal)
with closing(sqlite3.connect(path)) as expected, closing(snapshot('restored')) as actual:
    assert_integrity(actual)
    tables = expected.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").fetchall()
    for (table,) in tables:
        sql = 'SELECT * FROM "'+table+'" ORDER BY rowid'
        assert expected.execute(sql).fetchall() == actual.execute(sql).fetchall(), table
if photos:
    # Hash on-device in one call instead of transferring every full-size photo.
    hashes = shell('run-as', PKG, 'sh', '-c', "'sha256sum files/photos/*'").decode().splitlines()
    actual_photos = {}
    for line in hashes:
        digest, name = line.split(None, 1)
        actual_photos['./' + name.strip()] = digest
    assert actual_photos == photos, 'Restored photo inventory or contents differ'
shell('am','start','-n',PKG+'/.MainActivity')
print('PASS: original emulator tables and all photo files restored exactly; final APK retained')
