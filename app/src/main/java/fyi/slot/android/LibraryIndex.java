package fyi.slot.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Rebuildable, root-scoped ROM index. All access occurs on the library worker. */
final class LibraryIndex extends SQLiteOpenHelper {
    LibraryIndex(Context context) { super(context, "library.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE roms (root TEXT NOT NULL, path TEXT NOT NULL, platform TEXT NOT NULL, "
                + "size INTEGER NOT NULL, modified INTEGER NOT NULL, head BLOB NOT NULL, crc INTEGER, hidden INTEGER NOT NULL DEFAULT 0, "
                + "PRIMARY KEY(root,path))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("Unknown index version"); }
    List<RomRecord> read(String root) {
        List<RomRecord> out = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("roms", null, "root=?", new String[]{root}, null, null, null)) {
            while (cursor.moveToNext()) out.add(new RomRecord(root, cursor.getString(cursor.getColumnIndexOrThrow("path")),
                    Cartridge.Platform.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("platform"))),
                    cursor.getLong(cursor.getColumnIndexOrThrow("size")), cursor.getLong(cursor.getColumnIndexOrThrow("modified")),
                    cursor.getBlob(cursor.getColumnIndexOrThrow("head")), cursor.isNull(cursor.getColumnIndexOrThrow("crc")) ? null
                    : cursor.getLong(cursor.getColumnIndexOrThrow("crc")), cursor.getInt(cursor.getColumnIndexOrThrow("hidden")) != 0));
        }
        out.sort((a, b) -> { int order = RomNaming.compare(a.title, b.title); return order == 0 ? a.id.compareTo(b.id) : order; });
        return out;
    }
    int merge(String root, Cartridge.Platform platform, List<RomRecord> scanned) {
        SQLiteDatabase db = getWritableDatabase();
        Map<String, RomRecord> old = new HashMap<>();
        Map<String, RomRecord> identities = new HashMap<>();
        for (RomRecord rom : read(root)) if (rom.platform == platform) {
            old.put(rom.path, rom); identities.put(rom.id, rom);
        }
        int changes = 0;
        db.beginTransaction();
        try {
            for (RomRecord rom : scanned) {
                RomRecord previous = old.remove(rom.path);
                if (previous == null) {
                    previous = identities.get(rom.id);
                    if (previous != null) {
                        old.remove(previous.path);
                        db.delete("roms", "root=? AND path=?", new String[]{root, previous.path});
                    }
                }
                identities.remove(rom.id);
                if (previous != null && previous.size == rom.size && previous.modified == rom.modified
                        && previous.path.equals(rom.path) && Arrays.equals(previous.head, rom.head)) continue;
                ContentValues values = new ContentValues();
                values.put("root", root); values.put("path", rom.path); values.put("platform", platform.name());
                values.put("size", rom.size); values.put("modified", rom.modified); values.put("head", rom.head);
                values.putNull("crc"); values.put("hidden", previous != null && previous.hidden ? 1 : 0);
                db.insertWithOnConflict("roms", null, values, SQLiteDatabase.CONFLICT_REPLACE); changes++;
            }
            for (RomRecord rom : old.values()) { db.delete("roms", "root=? AND path=?", new String[]{root, rom.path}); changes++; }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return changes;
    }
    void saveCrc(RomRecord rom, long crc) {
        ContentValues values = new ContentValues(); values.put("crc", crc);
        getWritableDatabase().update("roms", values, "root=? AND path=? AND size=? AND modified=?",
                new String[]{rom.root, rom.path, Long.toString(rom.size), Long.toString(rom.modified)});
    }
    void setHidden(RomRecord rom, boolean hidden) {
        ContentValues values = new ContentValues(); values.put("hidden", hidden ? 1 : 0);
        SQLiteDatabase db = getWritableDatabase();
        if (db.update("roms", values, "root=? AND path=?", new String[]{rom.root, rom.path}) != 0) return;
        for (RomRecord saved : read(rom.root)) if (saved.id.equals(rom.id)) {
            db.update("roms", values, "root=? AND path=?", new String[]{saved.root, saved.path}); break;
        }
    }
}
