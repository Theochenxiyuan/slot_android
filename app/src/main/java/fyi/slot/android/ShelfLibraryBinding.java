package fyi.slot.android;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Applies library snapshots only at a safe shelf boundary and presents library states. */
final class ShelfLibraryBinding {
    private final ShelfModel shelves;
    private final BooleanSupplier idle;
    private final Runnable resetGeometry, changed;
    private final Consumer<Bundle> restore;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LibraryRepository.Status status = LibraryRepository.Status.UNCONFIGURED;
    private List<Cartridge> pending;
    private Set<String> pendingFavorites;
    private List<String> pendingRecents;
    private String pendingLaunchedId;
    private Bundle deferred;

    ShelfLibraryBinding(ShelfModel shelves, BooleanSupplier idle, Runnable resetGeometry,
                        Consumer<Bundle> restore, Runnable changed) {
        this.shelves = shelves; this.idle = idle; this.resetGeometry = resetGeometry;
        this.restore = restore; this.changed = changed;
    }
    void update(LibraryRepository.Status next, List<Cartridge> snapshot) {
        status = next;
        if (next != LibraryRepository.Status.LOADING) pending = List.copyOf(snapshot);
        apply();
    }
    void updateCollections(Set<String> favorites, List<String> recents, String launchedId) {
        pendingFavorites = Set.copyOf(favorites);
        pendingRecents = List.copyOf(recents);
        if (launchedId != null) pendingLaunchedId = launchedId;
        apply();
    }
    String pendingLaunchedId() { return pendingLaunchedId; }
    void defer(Bundle saved) { deferred = new Bundle(saved); }
    void apply() {
        if ((pending == null && pendingFavorites == null && pendingRecents == null) || !idle.getAsBoolean()) return;
        List<Cartridge> nextCarts = pending != null ? pending : shelves.shelf(ShelfModel.Category.ALL).cartridges;
        Set<String> nextFavorites = pendingFavorites != null ? pendingFavorites : shelves.favorites();
        List<String> nextRecents = pendingRecents != null ? pendingRecents : shelves.recents();
        String launchedId = pendingLaunchedId;
        pending = null; pendingFavorites = null; pendingRecents = null; pendingLaunchedId = null;

        ShelfModel.Category previousCategory = shelves.category();
        List<Cartridge> previousCurrent = shelves.current().cartridges;
        int previousSelected = shelves.current().selectedIndex();
        shelves.replace(nextCarts, nextFavorites, nextRecents, launchedId);
        boolean same = shelves.category() == previousCategory
                && shelves.current().selectedIndex() == previousSelected
                && sameAppearance(previousCurrent, shelves.current().cartridges);
        if (deferred != null && shelves.shelf(ShelfModel.Category.ALL).size() > 0) {
            Bundle saved = deferred; deferred = null;
            restore.accept(saved);
        } else if (!same) {
            resetGeometry.run();
        }
        changed.run();
    }
    private static boolean sameAppearance(List<Cartridge> left, List<Cartridge> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).appearanceKey.equals(right.get(i).appearanceKey)) return false;
        }
        return true;
    }
    void draw(Canvas canvas, HomeLayout layout, AppLanguage language) {
        boolean empty = shelves.current().size() == 0;
        if (!empty && (status == LibraryRepository.Status.READY || status == LibraryRepository.Status.LOADING)) return;
        float y = empty ? layout.height * .40f : 85;
        text(canvas, language.text(LibraryPanel.statusText(status)), layout.centerX(), y, 18, 0xffb9b9c0);
        if (empty && status != LibraryRepository.Status.LOADING) {
            RectF box = new RectF(layout.centerX() - 90, y + 22, layout.centerX() + 90, y + 66);
            paint.setColor(0xff29292f); canvas.drawRoundRect(box, 8, 8, paint);
            text(canvas, language.text(status == LibraryRepository.Status.UNCONFIGURED || status == LibraryRepository.Status.EMPTY
                    ? R.string.add_games : R.string.library), layout.centerX(), y + 50, 17, 0xffe0e0e1);
        }
    }
    boolean actionContains(HomeLayout layout, float x, float y) {
        float top = layout.height * .40f + 22;
        return shelves.current().size() == 0 && status != LibraryRepository.Status.LOADING
                && Math.abs(x - layout.centerX()) <= 90 && y >= top && y <= top + 44;
    }
    private void text(Canvas canvas, String text, float x, float y, float size, int color) {
        paint.setTextSize(size); paint.setColor(color); paint.setTypeface(Typeface.DEFAULT); paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, x, y, paint);
    }
}
