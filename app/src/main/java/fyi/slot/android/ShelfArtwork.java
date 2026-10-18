package fyi.slot.android;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/** A small rolling window follows navigation; idle warming stays inside the bitmap budget. */
final class ShelfArtwork {
    private static final long IDLE_DELAY = 400;
    private static final int MAX_WANTED = 32;
    private ShelfModel.Category category;
    private String selected;
    private float previousPosition;
    private int direction = 1;
    private long movedAt;
    private long nextDelay = Long.MAX_VALUE;

    void prepare(CartridgeCache cache, ShelfModel shelves, float position, int selectedPosition,
                 int firstVisible, int lastVisible, boolean moving, boolean canWarm, long now) {
        ShelfModel.Shelf shelf = shelves.current();
        Cartridge current = shelf.selectedCartridge();
        if (current == null || current.rom == null) {
            if (selected != null) cache.prioritize(java.util.List.of());
            selected = null; nextDelay = Long.MAX_VALUE; return;
        }
        boolean changed = category != shelves.category() || !current.appearanceKey.equals(selected);
        if (category == shelves.category() && Math.abs(position - previousPosition) > .001f
                && Math.abs(position - previousPosition) < shelf.size() / 2f) {
            direction = position > previousPosition ? 1 : -1;
        }
        if (changed || moving) movedAt = now;
        category = shelves.category(); selected = current.appearanceKey; previousPosition = position;
        boolean quiet = canWarm && !moving && now - movedAt >= IDLE_DELAY;
        nextDelay = canWarm && !moving && !quiet ? Math.max(1, IDLE_DELAY - (now - movedAt)) : Long.MAX_VALUE;

        LinkedHashMap<String, Cartridge> wanted = new LinkedHashMap<>();
        add(wanted, current);
        add(wanted, shelf.cartridgeAtPosition(selectedPosition + direction));
        add(wanted, shelf.cartridgeAtPosition(selectedPosition - direction));
        // Include the actual animation viewport, even during a long letter jump.
        int center = Math.round(position);
        for (int radius = 0; radius <= lastVisible - firstVisible; radius++) {
            for (int sign : new int[]{direction, -direction}) {
                int at = center + radius * sign;
                if (at >= firstVisible && at <= lastVisible) add(wanted, shelf.cartridgeAtPosition(at));
            }
        }
        for (int offset = 2; offset <= 4; offset++) add(wanted, shelf.cartridgeAtPosition(selectedPosition + offset * direction));
        for (int offset = 2; offset <= 3; offset++) add(wanted, shelf.cartridgeAtPosition(selectedPosition - offset * direction));
        if (quiet) {
            add(wanted, shelf.cartridgeAtPosition(selectedPosition - 4 * direction));
            for (int offset = 5; offset <= 12; offset++) {
                add(wanted, shelf.cartridgeAtPosition(selectedPosition + offset * direction));
                add(wanted, shelf.cartridgeAtPosition(selectedPosition - offset * direction));
            }
            for (ShelfModel.Category other : ShelfModel.Category.values()) add(wanted, shelves.shelf(other).selectedCartridge());
        }
        cache.prioritize(new ArrayList<>(wanted.values()));
    }

    private static void add(LinkedHashMap<String, Cartridge> wanted, Cartridge cartridge) {
        if (cartridge != null && cartridge.rom != null && wanted.size() < MAX_WANTED) wanted.putIfAbsent(cartridge.appearanceKey, cartridge);
    }

    long nextFrameDelay() { return nextDelay; }
}
