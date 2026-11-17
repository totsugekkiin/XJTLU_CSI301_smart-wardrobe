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
import java.util.List;
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
        String name = "", type = "", brand = "", fabric = "", original = "", photo = "",
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
        super(context, "wardrobe.sqlite", null, 3);
        photos = new File(context.getFilesDir(), "photos");
    }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE garment(id INTEGER PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, "
                + "brand TEXT, fabric TEXT, original TEXT, photo TEXT, seasons INTEGER, favorite INTEGER, dirty INTEGER, "
                + "stored INTEGER, care TEXT, symbols TEXT, source_text TEXT, label_text TEXT, wash_every INTEGER, idle_days INTEGER, created TEXT, "
                + "cutout_left INTEGER, cutout_top INTEGER, archived INTEGER NOT NULL DEFAULT 0)");
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
        db.execSQL("CREATE INDEX history_day ON history(day)");
        db.execSQL("CREATE INDEX history_garment_item ON history_garment(garment)");
        db.execSQL("CREATE INDEX wash_item ON wash(garment)");
        db.execSQL("CREATE INDEX care_item ON care_event(garment)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from < 2) {
            db.execSQL("ALTER TABLE garment ADD COLUMN cutout_left INTEGER");
            db.execSQL("ALTER TABLE garment ADD COLUMN cutout_top INTEGER");
        }
        if (from < 3) db.execSQL("ALTER TABLE garment ADD COLUMN archived INTEGER NOT NULL DEFAULT 0");
    }

    List<Garment> garments() { return garments(false); }

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

    void save(Garment g, Bitmap original, Bitmap cutout, Point origin) throws IOException {
        String newOriginal = original == null ? g.original : writeImage(original);
        String newPhoto = g.photo;
        long id = g.id;
        try {
            if (cutout != null) newPhoto = cutout == original ? newOriginal : writeImage(cutout);
            else if (original != null) newPhoto = newOriginal;
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
                if (newOrigin == null) {
                    v.putNull("cutout_left");
                    v.putNull("cutout_top");
                } else {
                    v.put("cutout_left", newOrigin.x);
                    v.put("cutout_top", newOrigin.y);
                }
                v.put("seasons", g.seasons);
                v.put("favorite", g.favorite);
                v.put("dirty", g.dirty);
                v.put("stored", g.stored);
                v.put("care", g.care);
                v.put("symbols", g.symbols);
                v.put("source_text", g.sourceText);
                v.put("label_text", g.labelText);
                v.put("wash_every", g.washEvery);
                v.put("idle_days", g.idleDays);
                v.put("created", g.created);
                if (id == 0) id = db.insertOrThrow("garment", null, v);
                else if (db.update("garment", v, "id=?", arg(id)) != 1) throw new IllegalStateException("衣物已删除");

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
        } catch (IOException | RuntimeException e) {
            if (!newPhoto.equals(g.photo) && !newPhoto.equals(newOriginal)) deleteImage(newPhoto);
            if (!newOriginal.equals(g.original)) deleteImage(newOriginal);
            throw e;
        }
        if (!g.photo.equals(newPhoto) && !g.photo.equals(newOriginal)) deleteImage(g.photo);
        if (!g.original.equals(newOriginal) && !g.original.equals(newPhoto)) deleteImage(g.original);
        g.id = id;
        g.photo = newPhoto;
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

    Bitmap image(String name, int sample) {
        if (name == null || name.isEmpty()) return null;
        String key = name + ":" + sample;
        Bitmap bitmap = images.get(key);
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

    void setSeasons(long id, int seasons) {
        ContentValues values = new ContentValues();
        values.put("seasons", seasons);
        getWritableDatabase().update("garment", values, "id=?", arg(id));
    }

    boolean deleteGarment(Garment g) {
        int deleted = getWritableDatabase().delete("garment",
                "id=? AND NOT EXISTS (SELECT 1 FROM history_garment AS h WHERE h.garment=garment.id)", arg(g.id));
        if (deleted == 0) return false;
        deleteImage(g.photo);
        deleteImage(g.original);
        return true;
    }

    void state(long id, String field, boolean value) {
        if (!"dirty".equals(field) && !"stored".equals(field) && !"favorite".equals(field)) throw new IllegalArgumentException(field);
        ContentValues v = new ContentValues();
        v.put(field, value);
        getWritableDatabase().update("garment", v, "id=?", arg(id));
    }

    void reactivate(long id, boolean dirty) {
        ContentValues v = new ContentValues();
        v.put("stored", false);
        v.put("dirty", dirty);
        getWritableDatabase().update("garment", v, "id=?", arg(id));
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

    void wash(long id, String day, boolean markClean) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("garment", id);
            v.put("day", day);
            db.insertOrThrow("wash", null, v);
            if (markClean) state(id, "dirty", false);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    List<String> washes(long id) {
        List<String> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT day FROM wash WHERE garment=? ORDER BY day DESC,id DESC", arg(id))) {
            while (c.moveToNext()) list.add(c.getString(0));
        }
        return list;
    }

    void checked(long id, String note) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("garment", id);
            v.put("day", LocalDate.now().toString());
            v.put("note", note);
            v.put("wash_checkpoint", DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM wash WHERE garment=?", arg(id)));
            db.insertOrThrow("care_event", null, v);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
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

    void saveOutfitAndWear(Outfit outfit, String day) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            save(outfit);
            wear(outfit, outfit.scene, day);
            db.setTransactionSuccessful();
        } catch (RuntimeException e) {
            outfit.id = 0;
            throw e;
        } finally {
            db.endTransaction();
        }
    }

    void wear(Outfit outfit, String scene, String day) {
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
