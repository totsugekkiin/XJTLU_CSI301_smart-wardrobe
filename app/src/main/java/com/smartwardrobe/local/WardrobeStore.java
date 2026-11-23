package com.smartwardrobe.local;

import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;

final class WardrobeStore extends SQLiteOpenHelper {
    static final class Swatch {
        String name;
        int rgb;
        int percent;
        boolean primary;

        Swatch(String name, int rgb, int percent, boolean primary) {
            this.name = name;
            this.rgb = rgb;
            this.percent = percent;
            this.primary = primary;
        }
    }

    static final class Garment {
        long id;
        String name = "", type = "", brand = "", fabric = "", original = "", photo = "", processed = "",
                care = "", symbols = "", sourceText = "", labelText = "", created = LocalDate.now().toString();
        int seasons, washEvery = 10, idleDays = 90;
        boolean favorite, dirty, stored, archived;
        Point cutoutOrigin;
        List<Swatch> colors = new ArrayList<>();
    }

    static final class Layer {
        long garment;
        float x, y, scale;
        Layer(long garment, float x, float y, float scale) {
            this.garment = garment;
            this.x = x;
            this.y = y;
            this.scale = scale;
        }
    }

    static final class Outfit {
        long id;
        String name = "", scene = "";
        List<Layer> layers = new ArrayList<>();
    }

    record History(long id, String name, String scene, String date) {}
    record CareEvent(String day, String note) {}
    record Usage(int washes, String washed, int worn, String lastWorn, int sinceCare, String lastCare) {}

    private final File photos;
    private final LruCache<String, Bitmap> images = new LruCache<>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getAllocationByteCount(); }
    };

    WardrobeStore(Context context) {
        super(context, "wardrobe.sqlite", null, 5);
        photos = new File(context.getFilesDir(), "photos");
    }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE garment(id INTEGER PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, "
                + "brand TEXT, fabric TEXT, original TEXT, photo TEXT, seasons INTEGER, favorite INTEGER, dirty INTEGER, "
                + "stored INTEGER, care TEXT, symbols TEXT, source_text TEXT, label_text TEXT, wash_every INTEGER, idle_days INTEGER, created TEXT, "
                + "cutout_left INTEGER, cutout_top INTEGER, archived INTEGER NOT NULL DEFAULT 0, processed TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE color(garment INTEGER REFERENCES garment(id) ON DELETE CASCADE, "
                + "position INTEGER, name TEXT, rgb INTEGER, percent INTEGER, is_primary INTEGER, PRIMARY KEY(garment,position))");
        db.execSQL("CREATE TABLE wash(id INTEGER PRIMARY KEY, garment INTEGER REFERENCES garment(id) ON DELETE CASCADE, day TEXT)");
        db.execSQL("CREATE TABLE care_event(id INTEGER PRIMARY KEY, garment INTEGER REFERENCES garment(id) ON DELETE CASCADE, "
                + "day TEXT, note TEXT, wash_checkpoint INTEGER)");
        db.execSQL("CREATE TABLE outfit(id INTEGER PRIMARY KEY, name TEXT NOT NULL, scene TEXT)");
        db.execSQL("CREATE TABLE outfit_layer(outfit INTEGER REFERENCES outfit(id) ON DELETE CASCADE, position INTEGER, "
                + "garment INTEGER REFERENCES garment(id) ON DELETE CASCADE, x REAL, y REAL, scale REAL, PRIMARY KEY(outfit,position))");
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY, outfit INTEGER REFERENCES outfit(id) ON DELETE SET NULL, "
                + "name TEXT, scene TEXT, day TEXT, UNIQUE(outfit,day))");
        db.execSQL("CREATE TABLE history_garment(history INTEGER REFERENCES history(id) ON DELETE CASCADE, "
                + "garment INTEGER REFERENCES garment(id) ON DELETE CASCADE, PRIMARY KEY(history,garment))");
        createDraftTable(db);
        db.execSQL("CREATE TABLE file_cleanup(name TEXT PRIMARY KEY)");
        db.execSQL("CREATE INDEX history_day ON history(day)");
        db.execSQL("CREATE INDEX history_garment_item ON history_garment(garment)");
        db.execSQL("CREATE INDEX wash_item ON wash(garment)");
        db.execSQL("CREATE INDEX care_item ON care_event(garment)");
    }

    private static void createDraftTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE outfit_draft(id INTEGER PRIMARY KEY, outfit INTEGER REFERENCES outfit(id) ON DELETE CASCADE, "
                + "day TEXT, name TEXT NOT NULL, scene TEXT NOT NULL, layers TEXT NOT NULL, mark_dirty INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from < 2) {
            db.execSQL("ALTER TABLE garment ADD COLUMN cutout_left INTEGER");
            db.execSQL("ALTER TABLE garment ADD COLUMN cutout_top INTEGER");
        }
        if (from < 3) db.execSQL("ALTER TABLE garment ADD COLUMN archived INTEGER NOT NULL DEFAULT 0");
        if (from < 4) {
            createDraftTable(db);
            db.execSQL("ALTER TABLE garment ADD COLUMN processed TEXT NOT NULL DEFAULT ''");
            db.execSQL("UPDATE garment SET processed=photo WHERE photo<>original");
        }
        if (from < 5) db.execSQL("CREATE TABLE file_cleanup(name TEXT PRIMARY KEY)");
    }

    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (!db.isReadOnly()) cleanDeletedPhotos(db);
    }

    List<Garment> garments() { return garments(false); }

    long pendingWashCount() {
        return DatabaseUtils.longForQuery(getReadableDatabase(),
                "SELECT COUNT(*) FROM garment WHERE archived=0 AND dirty=1", null);
    }

    List<Garment> garments(boolean archived) {
        List<Garment> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM garment WHERE archived=? ORDER BY id DESC", new String[]{archived ? "1" : "0"})) {
            while (c.moveToNext()) result.add(readGarment(c));
        }
        return result;
    }

    Garment garment(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM garment WHERE id=?", arg(id))) {
            return c.moveToFirst() ? readGarment(c) : null;
        }
    }

    private Garment readGarment(Cursor c) {
        Garment g = new Garment();
        g.id = c.getLong(0);
        g.name = c.getString(1);
        g.type = c.getString(2);
        g.brand = c.getString(3);
        g.fabric = c.getString(4);
        g.original = c.getString(5);
        g.photo = c.getString(6);
        g.processed = c.getString(c.getColumnIndexOrThrow("processed"));
        g.seasons = c.getInt(7);
        g.favorite = c.getInt(8) != 0;
        g.dirty = c.getInt(9) != 0;
        g.stored = c.getInt(10) != 0;
        g.care = c.getString(11);
        g.symbols = c.getString(12);
        g.sourceText = c.getString(13);
        g.labelText = c.getString(14);
        g.washEvery = c.getInt(15);
        g.idleDays = c.getInt(16);
        g.created = c.getString(17);
        g.archived = c.getInt(c.getColumnIndexOrThrow("archived")) != 0;
        if (!c.isNull(18) && !c.isNull(19)) g.cutoutOrigin = new Point(c.getInt(18), c.getInt(19));
        try (Cursor colors = getReadableDatabase().rawQuery(
                "SELECT name,rgb,percent,is_primary FROM color WHERE garment=? ORDER BY position", arg(g.id))) {
            while (colors.moveToNext()) {
                g.colors.add(new Swatch(colors.getString(0), colors.getInt(1), colors.getInt(2), colors.getInt(3) != 0));
            }
        }
        return g;
    }

    void save(Garment g, Bitmap original, Bitmap cutout, Point origin, boolean useOriginal) throws IOException {
        String newOriginal = original == null ? g.original : writeImage(original);
        String newProcessed = original == null ? g.processed : "";
        long id = g.id;
        try {
            if (cutout != null && cutout != original) newProcessed = writeImage(cutout);
            String newPhoto = useOriginal || newProcessed.isEmpty() ? newOriginal : newProcessed;
            Point newOrigin = cutout == null && original == null ? g.cutoutOrigin : origin;

            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                ContentValues v = new ContentValues();
                v.put("name", g.name);
                v.put("type", g.type);
                v.put("brand", g.brand);
                v.put("fabric", g.fabric);
                v.put("original", newOriginal);
                v.put("photo", newPhoto);
                v.put("processed", newProcessed);
                if (newOrigin == null) {
                    v.putNull("cutout_left");
                    v.putNull("cutout_top");
                } else {
                    v.put("cutout_left", newOrigin.x);
                    v.put("cutout_top", newOrigin.y);
                }
                v.put("seasons", g.seasons);
                v.put("care", g.care);
                v.put("symbols", g.symbols);
                v.put("source_text", g.sourceText);
                v.put("label_text", g.labelText);
                v.put("wash_every", g.washEvery);
                v.put("idle_days", g.idleDays);
                v.put("created", g.created);
                if (id == 0) {
                    v.put("favorite", g.favorite);
                    v.put("dirty", g.dirty);
                    v.put("stored", g.stored);
                    id = db.insertOrThrow("garment", null, v);
                } else if (db.update("garment", v, "id=?", arg(id)) != 1) {
                    throw new IllegalStateException("衣物已删除");
                }

                db.delete("color", "garment=?", arg(id));
                for (int i = 0; i < g.colors.size(); i++) {
                    Swatch color = g.colors.get(i);
                    ContentValues item = new ContentValues();
                    item.put("garment", id);
                    item.put("position", i);
                    item.put("name", color.name);
                    item.put("rgb", color.rgb);
                    item.put("percent", color.percent);
                    item.put("is_primary", color.primary);
                    db.insertOrThrow("color", null, item);
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            g.cutoutOrigin = newOrigin;
            g.photo = newPhoto;
        } catch (IOException | RuntimeException e) {
            if (!newProcessed.equals(g.processed)) deleteImage(newProcessed);
            if (!newOriginal.equals(g.original)) deleteImage(newOriginal);
            throw e;
        }
        if (!g.processed.equals(newProcessed) && !g.processed.equals(g.original)) deleteImage(g.processed);
        if (!g.original.equals(newOriginal)) deleteImage(g.original);
        g.id = id;
        g.processed = newProcessed;
        g.original = newOriginal;
    }

    private String writeImage(Bitmap b) throws IOException {
        if (!photos.isDirectory() && !photos.mkdirs()) throw new IOException("无法创建照片目录");
        String filename = UUID.randomUUID() + ".png";
        File file = new File(photos, filename);
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!b.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("图片写入失败");
        } catch (IOException | RuntimeException e) {
            file.delete();
            throw e;
        }
        return filename;
    }

    private void deleteImage(String name) {
        if (name.isEmpty()) return;
        for (int sample : new int[]{1, 2, 4, 8}) images.remove(name + ":" + sample);
        new File(photos, name).delete();
    }

    Bitmap cachedImage(String name, int sample) {
        return images.get(name + ":" + sample);
    }

    Bitmap image(String name, int sample) {
        if (name == null || name.isEmpty()) return null;
        String key = name + ":" + sample;
        Bitmap bitmap = cachedImage(name, sample);
        if (bitmap == null) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            bitmap = BitmapFactory.decodeFile(new File(photos, name).getAbsolutePath(), opts);
            if (bitmap != null) images.put(key, bitmap);
        }
        return bitmap;
    }

    void archiveGarment(long id, boolean archived) {
        ContentValues values = new ContentValues();
        values.put("archived", archived);
        getWritableDatabase().update("garment", values, "id=?", arg(id));
    }

    void saveCareLabel(long id, String symbols, String text, String fabric, String care) {
        ContentValues values = new ContentValues();
        values.put("symbols", symbols);
        if (!text.isEmpty()) values.put("label_text", text);
        if (!fabric.isEmpty()) values.put("fabric", fabric);
        if (!care.isEmpty()) values.put("care", care);
        if (getWritableDatabase().update("garment", values, "id=?", arg(id)) != 1) {
            throw new IllegalStateException("衣物已删除");
        }
    }

    void setSeasons(long id, int seasons) {
        ContentValues values = new ContentValues();
        values.put("seasons", seasons);
        getWritableDatabase().update("garment", values, "id=?", arg(id));
    }

    record Associations(int outfits, int histories) {
        boolean any() { return outfits > 0 || histories > 0; }
    }

    Associations associations(long id) {
        SQLiteDatabase db = getReadableDatabase();
        return new Associations((int) DatabaseUtils.longForQuery(db,
                "SELECT COUNT(DISTINCT outfit) FROM outfit_layer WHERE garment=?", arg(id)),
                (int) DatabaseUtils.longForQuery(db,
                "SELECT COUNT(*) FROM history_garment WHERE garment=?", arg(id)));
    }

    void archiveGarments(Set<Long> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (long id : ids) archiveGarment(id, true);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    int deleteGarments(Set<Long> ids) {
        SQLiteDatabase db = getWritableDatabase();
        Set<Long> outfits = new LinkedHashSet<>(), histories = new LinkedHashSet<>();
        int deleted = 0;
        db.beginTransaction();
        try {
            for (long id : ids) {
                Garment garment = garment(id);
                if (garment == null) continue;
                collectIds(db, "SELECT outfit FROM outfit_layer WHERE garment=?", id, outfits);
                collectIds(db, "SELECT history FROM history_garment WHERE garment=?", id, histories);
                for (String name : new String[]{garment.original, garment.processed, garment.photo}) {
                    if (name == null || name.isEmpty()) continue;
                    ContentValues file = new ContentValues();
                    file.put("name", name);
                    db.insertWithOnConflict("file_cleanup", null, file, SQLiteDatabase.CONFLICT_IGNORE);
                }
                // Foreign keys remove colors, washes, checks, saved layers and history links.
                deleted += db.delete("garment", "id=?", arg(id));
            }
            // JSON draft layers have no foreign key; trim them in this same transaction.
            for (OutfitDraft draft : outfitDrafts()) {
                if (draft.outfit().layers.removeIf(layer -> ids.contains(layer.garment))) {
                    saveDraft(draft.id(), draft.outfit(), draft.day(), draft.markDirty());
                }
            }
            for (long id : outfits) {
                if (DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM outfit_layer WHERE outfit=?", arg(id)) != 0) continue;
                // A draft can contain extra unsaved items: retain it independently of its empty source outfit.
                ContentValues detached = new ContentValues();
                detached.putNull("outfit");
                db.update("outfit_draft", detached, "outfit=?", arg(id));
                db.delete("outfit", "id=?", arg(id));
            }
            for (long id : histories) {
                if (DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM history_garment WHERE history=?", arg(id)) == 0)
                    db.delete("history", "id=?", arg(id));
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        // Files cannot join a SQLite transaction. Commit the cleanup queue first, then remove them;
        // interrupted/failed cleanup is retried on the next database open.
        cleanDeletedPhotos(db);
        return deleted;
    }

    private void collectIds(SQLiteDatabase db, String sql, long garment, Set<Long> result) {
        try (Cursor c = db.rawQuery(sql, arg(garment))) {
            while (c.moveToNext()) result.add(c.getLong(0));
        }
    }

    private void cleanDeletedPhotos(SQLiteDatabase db) {
        List<String> pending = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT name FROM file_cleanup", null)) {
            while (c.moveToNext()) pending.add(c.getString(0));
        }
        for (String name : pending) {
            // A shared photo must survive while another garment still references it.
            if (DatabaseUtils.longForQuery(db,
                    "SELECT COUNT(*) FROM garment WHERE original=? OR processed=? OR photo=?",
                    new String[]{name, name, name}) > 0) {
                db.delete("file_cleanup", "name=?", new String[]{name});
                continue;
            }
            if (!new File(name).getName().equals(name)) continue;
            for (int sample : new int[]{1, 2, 4, 8}) images.remove(name + ":" + sample);
            File file = new File(photos, name);
            if (!file.exists() || file.delete()) db.delete("file_cleanup", "name=?", new String[]{name});
            else android.util.Log.w("SmartWardrobe", "Photo cleanup will retry: " + name);
        }
    }

    void state(long id, String field, boolean value) {
        if (!"dirty".equals(field) && !"stored".equals(field) && !"favorite".equals(field)) throw new IllegalArgumentException(field);
        ContentValues v = new ContentValues();
        v.put(field, value);
        getWritableDatabase().update("garment", v, "id=?", arg(id));
    }

    int markDirty(Set<Long> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("dirty", true);
            int count = 0;
            for (long id : ids) count += db.update("garment", values, "id=? AND archived=0", arg(id));
            db.setTransactionSuccessful();
            return count;
        } finally { db.endTransaction(); }
    }

    void takeOut(List<Garment> garments, boolean needsWash) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("stored", false);
            if (needsWash) values.put("dirty", true);
            for (Garment garment : garments) {
                db.update("garment", values, "id=? AND archived=0 AND stored=1", arg(garment.id));
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    boolean takeOutReady(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("stored", false);
            // "Direct wear" explicitly confirms cleanliness, including an earlier dirty flag.
            values.put("dirty", false);
            boolean changed = db.update("garment", values, "id=? AND archived=0 AND stored=1", arg(id)) > 0;
            if (changed) checked(id, "换季检查完成");
            db.setTransactionSuccessful();
            return changed;
        } finally { db.endTransaction(); }
    }

    Usage usage(long id) {
        String sql = "SELECT (SELECT COUNT(*) FROM wash WHERE garment=?1),"
                + "(SELECT MAX(day) FROM wash WHERE garment=?1),"
                + "(SELECT COUNT(*) FROM history_garment WHERE garment=?1),"
                + "(SELECT MAX(day) FROM history JOIN history_garment ON history.id=history_garment.history WHERE garment=?1),"
                + "(SELECT wash_checkpoint FROM care_event WHERE garment=?1 ORDER BY id DESC LIMIT 1),"
                + "(SELECT day FROM care_event WHERE garment=?1 ORDER BY id DESC LIMIT 1)";
        try (Cursor c = getReadableDatabase().rawQuery(sql, arg(id))) {
            c.moveToFirst();
            return new Usage(c.getInt(0), c.getString(1), c.getInt(2), c.getString(3),
                    c.getInt(0) - c.getInt(4), c.getString(5));
        }
    }

    /** One pass over the history tables; avoids a query per garment while a list is bound. */
    Map<Long, Usage> usages() {
        Map<Long, Integer> washes = new HashMap<>(), worn = new HashMap<>();
        Map<Long, String> washed = new HashMap<>(), lastWorn = new HashMap<>();
        Map<Long, Integer> checkpoints = new HashMap<>();
        Map<Long, String> lastCare = new HashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT garment, COUNT(*), MAX(day) FROM wash GROUP BY garment", null)) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                washes.put(id, c.getInt(1));
                washed.put(id, c.getString(2));
            }
        }
        try (Cursor c = db.rawQuery("SELECT hg.garment, COUNT(*), MAX(h.day) FROM history_garment hg"
                + " JOIN history h ON h.id=hg.history GROUP BY hg.garment", null)) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                worn.put(id, c.getInt(1));
                lastWorn.put(id, c.getString(2));
            }
        }
        try (Cursor c = db.rawQuery("SELECT garment, wash_checkpoint, day FROM care_event ORDER BY id DESC", null)) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                if (!checkpoints.containsKey(id)) {
                    checkpoints.put(id, c.getInt(1));
                    lastCare.put(id, c.getString(2));
                }
            }
        }
        Map<Long, Usage> result = new HashMap<>();
        for (Garment garment : garments()) {
            int count = washes.getOrDefault(garment.id, 0);
            int checkpoint = checkpoints.getOrDefault(garment.id, 0);
            result.put(garment.id, new Usage(count, washed.get(garment.id),
                    worn.getOrDefault(garment.id, 0), lastWorn.get(garment.id),
                    count - checkpoint, lastCare.get(garment.id)));
        }
        return result;
    }

    record WashChange(long record, long garment, boolean wasDirty) {}

    List<WashChange> washToday(List<Garment> garments) {
        SQLiteDatabase db = getWritableDatabase();
        List<WashChange> changes = new ArrayList<>();
        db.beginTransaction();
        try {
            String day = LocalDate.now().toString();
            for (Garment item : garments) {
                Garment current = garment(item.id);
                if (current == null || current.archived) continue;
                long record = wash(current.id, day, true);
                changes.add(new WashChange(record, current.id, current.dirty));
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return changes;
    }

    void undoWash(List<WashChange> changes) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (WashChange change : changes) {
                int removed = db.delete("wash", "id=? AND garment=?",
                        new String[]{Long.toString(change.record()), Long.toString(change.garment())});
                if (removed > 0) state(change.garment(), "dirty", change.wasDirty());
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    long wash(long id, String day, boolean markClean) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("garment", id);
            v.put("day", day);
            long record = db.insertOrThrow("wash", null, v);
            if (markClean) {
                ContentValues clean = new ContentValues();
                clean.put("dirty", false);
                db.update("garment", clean, "id=?", arg(id));
            }
            db.setTransactionSuccessful();
            return record;
        } finally { db.endTransaction(); }
    }

    List<String> washes(long id) {
        List<String> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT day FROM wash WHERE garment=? ORDER BY day DESC,id DESC", arg(id))) {
            while (c.moveToNext()) list.add(c.getString(0));
        }
        return list;
    }

    long checked(long id, String note) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long record = insertCheck(db, id, LocalDate.now().toString(), note);
            db.setTransactionSuccessful();
            return record;
        } finally { db.endTransaction(); }
    }

    /** Records one check for every garment in a single transaction, returning the row ids for undo. */
    List<Long> checkedAll(List<Garment> garments, String note) {
        List<Long> records = new ArrayList<>();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String day = LocalDate.now().toString();
            ContentValues clearStored = new ContentValues();
            clearStored.put("stored", false);
            for (Garment garment : garments) {
                db.update("garment", clearStored, "id=?", arg(garment.id));
                records.add(insertCheck(db, garment.id, day, note));
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return records;
    }

    /** Check plus a dirty/stored state change in one transaction, used by seasonal actions. */
    long checkAndState(long id, boolean dirty, boolean clearStored, String note) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("dirty", dirty);
            if (clearStored) values.put("stored", false);
            db.update("garment", values, "id=?", arg(id));
            long record = insertCheck(db, id, LocalDate.now().toString(), note);
            db.setTransactionSuccessful();
            return record;
        } finally { db.endTransaction(); }
    }

    void undoChecked(List<Long> records) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (long record : records) db.delete("care_event", "id=?", arg(record));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private long insertCheck(SQLiteDatabase db, long id, String day, String note) {
        ContentValues v = new ContentValues();
        v.put("garment", id);
        v.put("day", day);
        v.put("note", note);
        v.put("wash_checkpoint", DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM wash WHERE garment=?", arg(id)));
        return db.insertOrThrow("care_event", null, v);
    }

    List<CareEvent> checks(long id) {
        List<CareEvent> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT day,note FROM care_event WHERE garment=? ORDER BY id DESC", arg(id))) {
            while (c.moveToNext()) list.add(new CareEvent(c.getString(0), c.getString(1)));
        }
        return list;
    }

    List<Outfit> outfits() {
        List<Outfit> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,name,scene FROM outfit ORDER BY id DESC", null)) {
            while (c.moveToNext()) {
                Outfit outfit = new Outfit();
                outfit.id = c.getLong(0);
                outfit.name = c.getString(1);
                outfit.scene = c.getString(2);
                try (Cursor layers = getReadableDatabase().rawQuery(
                        "SELECT garment,x,y,scale FROM outfit_layer WHERE outfit=? ORDER BY position", arg(outfit.id))) {
                    while (layers.moveToNext()) outfit.layers.add(new Layer(layers.getLong(0),
                            layers.getFloat(1), layers.getFloat(2), layers.getFloat(3)));
                }
                list.add(outfit);
            }
        }
        return list;
    }

    void save(Outfit outfit) {
        SQLiteDatabase db = getWritableDatabase();
        long id = outfit.id;
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("name", outfit.name);
            v.put("scene", outfit.scene);
            if (id == 0) id = db.insertOrThrow("outfit", null, v);
            else db.update("outfit", v, "id=?", arg(id));

            db.delete("outfit_layer", "outfit=?", arg(id));
            for (int i = 0; i < outfit.layers.size(); i++) {
                Layer layer = outfit.layers.get(i);
                ContentValues l = new ContentValues();
                l.put("outfit", id);
                l.put("position", i);
                l.put("garment", layer.garment);
                l.put("x", layer.x);
                l.put("y", layer.y);
                l.put("scale", layer.scale);
                db.insertOrThrow("outfit_layer", null, l);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        outfit.id = id;
    }

    void deleteOutfit(long id) { getWritableDatabase().delete("outfit", "id=?", arg(id)); }

    record OutfitDraft(long id, Outfit outfit, LocalDate day, boolean markDirty) {}

    List<OutfitDraft> outfitDrafts() {
        List<OutfitDraft> drafts = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,outfit,day,name,scene,layers,mark_dirty FROM outfit_draft ORDER BY id DESC", null)) {
            while (c.moveToNext()) {
                Outfit outfit = new Outfit();
                outfit.id = c.getLong(1);
                outfit.name = c.getString(3);
                outfit.scene = c.getString(4);
                try {
                    org.json.JSONArray layers = new org.json.JSONArray(c.getString(5));
                    for (int i = 0; i < layers.length(); i++) {
                        org.json.JSONArray layer = layers.getJSONArray(i);
                        outfit.layers.add(new Layer(layer.getLong(0), (float) layer.getDouble(1),
                                (float) layer.getDouble(2), (float) layer.getDouble(3)));
                    }
                } catch (org.json.JSONException invalid) {
                    android.util.Log.w("SmartWardrobe", "Unable to load draft " + c.getLong(0), invalid);
                    continue;
                }
                LocalDate day = c.isNull(2) ? null : LocalDate.parse(c.getString(2));
                drafts.add(new OutfitDraft(c.getLong(0), outfit, day, c.getInt(6) != 0));
            }
        }
        return drafts;
    }

    long saveDraft(long id, Outfit outfit, LocalDate day, boolean markDirty) {
        org.json.JSONArray layers = new org.json.JSONArray();
        for (Layer layer : outfit.layers) {
            layers.put(new org.json.JSONArray(java.util.Arrays.asList(layer.garment, layer.x, layer.y, layer.scale)));
        }
        ContentValues values = new ContentValues();
        if (outfit.id != 0) values.put("outfit", outfit.id);
        values.put("day", day == null ? null : day.toString());
        values.put("name", outfit.name);
        values.put("scene", outfit.scene);
        values.put("layers", layers.toString());
        values.put("mark_dirty", markDirty);
        SQLiteDatabase db = getWritableDatabase();
        if (id == 0) return db.insertOrThrow("outfit_draft", null, values);
        if (db.update("outfit_draft", values, "id=?", arg(id)) != 1) throw new IllegalStateException("草稿已删除");
        return id;
    }

    void deleteDraft(long id) { getWritableDatabase().delete("outfit_draft", "id=?", arg(id)); }

    void saveOutfit(Outfit outfit, long draftId, LocalDate day, boolean dirty) {
        SQLiteDatabase db = getWritableDatabase();
        long previousId = outfit.id;
        db.beginTransaction();
        try {
            save(outfit);
            if (day != null) wear(outfit, outfit.scene, day.toString(), dirty);
            if (draftId != 0) deleteDraft(draftId);
            db.setTransactionSuccessful();
        } catch (RuntimeException e) {
            outfit.id = previousId;
            throw e;
        } finally {
            db.endTransaction();
        }
    }

    void wear(Outfit outfit, String scene, String day, boolean dirty) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            if (outfit.id != 0) {
                db.delete("history", "outfit=? AND day=?", new String[]{Long.toString(outfit.id), day});
                v.put("outfit", outfit.id);
            } else {
                v.putNull("outfit");
            }
            v.put("name", outfit.name);
            v.put("scene", scene);
            v.put("day", day);
            long id = db.insertOrThrow("history", null, v);
            for (Layer layer : outfit.layers) {
                ContentValues link = new ContentValues();
                link.put("history", id);
                link.put("garment", layer.garment);
                db.insertWithOnConflict("history_garment", null, link, SQLiteDatabase.CONFLICT_IGNORE);
                if (dirty) {
                    ContentValues pending = new ContentValues();
                    pending.put("dirty", true);
                    db.update("garment", pending, "id=? AND archived=0", arg(layer.garment));
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    List<History> history(String month) {
        List<History> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,scene,day FROM history WHERE day LIKE ? ORDER BY day DESC,id DESC", new String[]{month + "-%"})) {
            while (c.moveToNext()) list.add(new History(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)));
        }
        return list;
    }

    List<Garment> wornGarments(long id) {
        List<Garment> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT garment FROM history_garment WHERE history=? ORDER BY garment", arg(id))) {
            while (c.moveToNext()) {
                Garment g = garment(c.getLong(0));
                if (g != null) result.add(g);
            }
        }
        return result;
    }

    void deleteHistory(long id) { getWritableDatabase().delete("history", "id=?", arg(id)); }

    private static String[] arg(long id) { return new String[]{Long.toString(id)}; }
}
