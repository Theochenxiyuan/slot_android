package fyi.slot.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Persistent favorite and recently launched ROM identities, isolated by full library identity. */
final class ShelfCollections {
    static final String PREFS = "shelf_collections";
    static final int MAX_RECENTS = 10;
    private static final String FAVORITES_KEY = "favorites", RECENTS_KEY = "recents";
    private final SharedPreferences storage;
    private final LinkedHashSet<String> favorites = new LinkedHashSet<>();
    private final ArrayList<String> recents = new ArrayList<>();

    ShelfCollections(Context context) {
        storage = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        favorites.addAll(decode(storage.getString(FAVORITES_KEY, ""), Integer.MAX_VALUE));
        recents.addAll(decode(storage.getString(RECENTS_KEY, ""), MAX_RECENTS));
    }

    boolean isFavorite(RomRecord rom) {
        return rom != null && isFavorite(rom.id);
    }

    boolean isFavorite(String id) {
        return id != null && favorites.contains(id);
    }

    Set<String> favorites() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(favorites));
    }

    List<String> recents() {
        return List.copyOf(recents);
    }

    boolean toggleFavorite(RomRecord rom) {
        if (rom == null || rom.id == null || rom.id.isEmpty()) return false;
        boolean added = !favorites.remove(rom.id);
        if (added) favorites.add(rom.id);
        storage.edit().putString(FAVORITES_KEY, encode(favorites)).apply();
        return added;
    }

    void recordLaunch(RomRecord rom) {
        if (rom == null || rom.id == null || rom.id.isEmpty()) return;
        recents.remove(rom.id);
        recents.add(0, rom.id);
        while (recents.size() > MAX_RECENTS) recents.remove(recents.size() - 1);
        storage.edit().putString(RECENTS_KEY, encode(recents)).apply();
    }

    static List<String> decode(String raw, int limit) {
        if (raw == null || raw.isEmpty() || limit <= 0) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String line : raw.split("\n")) {
            String item = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (!item.isEmpty()) {
                unique.add(item);
                if (unique.size() >= limit) break;
            }
        }
        return List.copyOf(unique);
    }

    static String encode(Iterable<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) {
            if (item == null || item.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(item);
        }
        return out.toString();
    }
}
