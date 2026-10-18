package fyi.slot.android;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shelf selection is separate from the view's continuous animation coordinates. */
final class ShelfModel {
    enum Category {
        ALL(null), GBA(Cartridge.Platform.GBA), GBC(Cartridge.Platform.GBC), GB(Cartridge.Platform.GB),
        FAVORITES(null), RECENTS(null);

        final Cartridge.Platform platform;

        Category(Cartridge.Platform platform) { this.platform = platform; }
    }

    static final class Shelf {
        final Category category;
        final List<Cartridge> cartridges;
        private final ShelfLetterIndex letters;
        private final int defaultIndex;
        private int selectedIndex;

        Shelf(Category category, List<Cartridge> cartridges) {
            this.category = category;
            this.cartridges = List.copyOf(cartridges);
            letters = new ShelfLetterIndex(cartridges.stream().map(cart -> cart.title).collect(java.util.stream.Collectors.toList()));
            int moss = category == Category.RECENTS ? 0 : indexOf("moss");
            defaultIndex = cartridges.isEmpty() ? -1 : Math.max(0, moss);
            selectedIndex = defaultIndex;
        }

        int size() { return cartridges.size(); }
        int selectedIndex() { return selectedIndex; }
        int letterJumpDelta(int direction) { return letters.jumpDelta(selectedIndex, direction); }
        Cartridge selectedCartridge() { return selectedIndex < 0 ? null : cartridges.get(selectedIndex); }

        int indexAtPosition(int position) { return size() == 0 ? -1 : Math.floorMod(position, size()); }
        Cartridge cartridgeAtPosition(int position) {
            int index = indexAtPosition(position);
            return index < 0 ? null : cartridges.get(index);
        }

        void select(int index) {
            selectedIndex = indexAtPosition(index);
        }

        void restoreSelection(String id) {
            int index = indexOf(id);
            selectedIndex = index >= 0 ? index : defaultIndex;
        }

        private int indexOf(String id) {
            for (int i = 0; i < size(); i++) if (cartridges.get(i).id.equals(id)) return i;
            return -1;
        }
    }

    private final EnumMap<Category, Shelf> shelves = new EnumMap<>(Category.class);
    private Set<String> favorites = Set.of();
    private List<String> recents = List.of();
    private Category category = Category.ALL;

    ShelfModel(List<Cartridge> cartridges) {
        this(cartridges, Set.of(), List.of());
    }

    ShelfModel(List<Cartridge> cartridges, Set<String> favorites, List<String> recents) {
        Set<String> ids = new HashSet<>();
        Map<String, Cartridge> byId = new HashMap<>();
        for (Cartridge cartridge : cartridges) {
            if (cartridge.id == null || cartridge.id.isEmpty() || !ids.add(cartridge.id)) {
                throw new IllegalArgumentException("Cartridge IDs must be nonempty and unique");
            }
            byId.put(cartridge.id, cartridge);
        }
        this.favorites = Set.copyOf(favorites);
        LinkedHashSet<String> boundedRecents = new LinkedHashSet<>();
        for (String id : recents) {
            if (id != null && !id.isEmpty()) {
                boundedRecents.add(id);
                if (boundedRecents.size() >= ShelfCollections.MAX_RECENTS) break;
            }
        }
        this.recents = List.copyOf(boundedRecents);
        for (Category item : Category.values()) {
            List<Cartridge> filtered = new ArrayList<>();
            if (item == Category.FAVORITES) {
                for (Cartridge cartridge : cartridges) {
                    if (this.favorites.contains(cartridge.id)) filtered.add(cartridge);
                }
            } else if (item == Category.RECENTS) {
                for (String id : this.recents) {
                    Cartridge match = byId.get(id);
                    if (match != null) filtered.add(match);
                }
            } else {
                for (Cartridge cartridge : cartridges) {
                    if (item.platform == null || cartridge.platform == item.platform) filtered.add(cartridge);
                }
            }
            shelves.put(item, new Shelf(item, filtered));
        }
    }

    Shelf shelf(Category item) { return shelves.get(item); }
    Shelf current() { return shelf(category); }
    Category category() { return category; }
    Set<String> favorites() { return favorites; }
    List<String> recents() { return recents; }

    void replace(List<Cartridge> cartridges) {
        replace(cartridges, favorites, recents, null);
    }

    void replace(List<Cartridge> cartridges, Set<String> nextFavorites, List<String> nextRecents, String newlyLaunchedId) {
        ShelfModel next = new ShelfModel(cartridges, nextFavorites, nextRecents);
        favorites = next.favorites;
        recents = next.recents;
        for (Category item : Category.values()) {
            Shelf previous = shelf(item);
            Cartridge selected = previous.selectedCartridge();
            Shelf replacement = next.shelf(item);
            if (item == Category.RECENTS && newlyLaunchedId != null && replacement.indexOf(newlyLaunchedId) >= 0) {
                replacement.restoreSelection(newlyLaunchedId);
            } else if (selected != null) {
                replacement.restoreSelection(selected.id);
                if (replacement.indexOf(selected.id) < 0 && replacement.size() > 0)
                    replacement.select(Math.min(previous.selectedIndex(), replacement.size() - 1));
            }
            shelves.put(item, replacement);
        }
        if (current().size() == 0) category = Category.ALL;
    }

    boolean selectCategory(Category item) {
        if (item == category || shelf(item).size() == 0) return false;
        category = item;
        return true;
    }

    static ShelfModel demo() {
        return new ShelfModel(List.of(
                new Cartridge("night-drive", "NIGHT DRIVE", "01", 0xff35353a, 0xffdfab61, 0, Cartridge.Platform.GBA, CartridgeRenderer.Finish.SOLID),
                new Cartridge("moss", "MOSS", "02", 0xffc4ab59, 0xffdcdfa6, 1, Cartridge.Platform.GBA, CartridgeRenderer.Finish.SOLID),
                new Cartridge("orbit", "ORBIT", "03", 0xff575c87, 0xffb3c3e5, 2, Cartridge.Platform.GBA, CartridgeRenderer.Finish.TRANSLUCENT),
                new Cartridge("tide", "TIDE", "04", 0xff738d8c, 0xffc2e4df, 3, Cartridge.Platform.GBA, CartridgeRenderer.Finish.TRANSLUCENT),
                new Cartridge("ember", "EMBER", "05", 0xffa45343, 0xffefc197, 4, Cartridge.Platform.GBA, CartridgeRenderer.Finish.SOLID),
                new Cartridge("grove", "GROVE", "06", 0xff9a978f, 0xffdce5a7, 1, Cartridge.Platform.GB, CartridgeRenderer.Finish.SOLID),
                new Cartridge("moon", "MOON", "07", 0xffa9aaa7, 0xffe2e7ad, 2, Cartridge.Platform.GB, CartridgeRenderer.Finish.SOLID),
                new Cartridge("lagoon", "LAGOON", "08", 0xff86b9bf, 0xffdfedc2, 3, Cartridge.Platform.GBC, CartridgeRenderer.Finish.GLITTER),
                new Cartridge("dusk", "DUSK", "09", 0xff7c7a8a, 0xffe9bfa7, 4, Cartridge.Platform.GBC, CartridgeRenderer.Finish.TRANSLUCENT)));
    }
}
