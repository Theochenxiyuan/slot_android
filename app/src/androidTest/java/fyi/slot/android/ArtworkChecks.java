package fyi.slot.android;

import android.graphics.Bitmap;
import android.os.SystemClock;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Owned synthetic cards exercise scheduling races using the renderer's material lock. */
final class ArtworkChecks {
    static final int COUNT = 3;
    private final InteractionChecks t;
    ArtworkChecks(InteractionChecks tests) { t = tests; }

    void run() {
        t.check(251, "newSelectionPreemptsQueuedCardsAndPublishesLabelBeforeMaterial", () -> {
            CartridgeRenderer renderer = new CartridgeRenderer(t.getTargetContext().getAssets());
            CartridgeCache cache = new CartridgeCache(t.getTargetContext(), renderer, () -> {});
            List<Cartridge> cards = cards(20);
            try {
                synchronized (renderer) {
                    synchronized (cache) {
                        cache.prioritize(cards.subList(0, 12));
                        cache.prioritize(List.of(cards.get(19), cards.get(18)));
                        require(cache.pendingCount() == 2, "Only current work remains queued");
                    }
                    await(() -> cache.label(cards.get(19)) != null);
                    require(cache.get(cards.get(19)) == null, "Label is available while material is still blocked");
                    require(cache.label(cards.get(0)) == null, "Old queued artwork never started");
                    require(cache.label(cards.get(18)) == null, "Selected artwork precedes its neighbour");
                }
                await(() -> cache.get(cards.get(19)) != null && cache.get(cards.get(18)) != null);
            } finally { cache.release(); }
        });
        t.check(252, "clearDuringRenderRejectsOldResultAndCanReloadSameIdentity", () -> {
            CartridgeRenderer renderer = new CartridgeRenderer(t.getTargetContext().getAssets());
            CartridgeCache cache = new CartridgeCache(t.getTargetContext(), renderer, () -> {});
            Cartridge card = cards(1).get(0);
            try {
                Bitmap oldLabel;
                synchronized (renderer) {
                    cache.prioritize(List.of(card));
                    await(() -> cache.label(card) != null);
                    oldLabel = cache.label(card);
                    cache.clear(); cache.prioritize(List.of(card));
                }
                await(() -> cache.get(card) != null);
                require(cache.label(card) != oldLabel, "Cleared generation has been replaced");
                require(!oldLabel.isRecycled(), "Published labels remain safe for a frame already using them");
            } finally { cache.release(); }
        });
        t.check(253, "idleWindowWarmsBeforeNavigationAndRapidJumpsStayBounded", () -> {
            CartridgeRenderer renderer = new CartridgeRenderer(t.getTargetContext().getAssets());
            CartridgeCache cache = new CartridgeCache(t.getTargetContext(), renderer, () -> {});
            ShelfArtwork artwork = new ShelfArtwork();
            ShelfModel shelves = new ShelfModel(cards(1000));
            try {
                long now = SystemClock.uptimeMillis();
                artwork.prepare(cache, shelves, 0, 0, -3, 3, false, true, now);
                require(artwork.nextFrameDelay() == 400, "A settled shelf schedules one idle warm-up");
                Cartridge ahead = shelves.current().cartridgeAtPosition(10);
                await(() -> {
                    artwork.prepare(cache, shelves, 0, 0, -3, 3, false, true, now + 500);
                    return cache.get(ahead) != null;
                });
                require(artwork.nextFrameDelay() == Long.MAX_VALUE, "Idle preparation does not run a permanent frame loop");
                for (int position = 0; position < 1000; position += 7) {
                    shelves.current().select(position);
                    artwork.prepare(cache, shelves, position, position, position - 3, position + 3, true, true, now + position + 600);
                    require(cache.pendingCount() <= CartridgeCache.MAX_PENDING, "Rapid navigation queue bound");
                }
                Cartridge selected = shelves.current().selectedCartridge();
                await(() -> cache.get(selected) != null);
                require(cache.bitmapBytes() <= CartridgeCache.MAX_CARD_BYTES + CartridgeCache.MAX_LABEL_BYTES,
                        "Byte budget holds across a thousand-game library");
                cache.prioritize(List.of(ahead)); await(() -> cache.get(ahead) != null);
                require(cache.label(ahead) != null, "Reverse navigation restores both card and label");
            } finally { cache.release(); }
        });
    }

    private List<Cartridge> cards(int count) {
        List<Cartridge> cards = new ArrayList<>();
        File root = new File(t.getTargetContext().getFilesDir(), "artwork-check-" + System.nanoTime());
        for (int i = 0; i < count; i++) {
            RomRecord rom = new RomRecord(root.getPath(), "Games/GBA/Artwork " + i + ".gba", Cartridge.Platform.GBA,
                    512, 1, new byte[0x150], null, false);
            cards.add(new Cartridge(rom, ShellConfig.parse("auto 249c60 clear"), null));
        }
        return cards;
    }

    private void await(BooleanSupplier condition) {
        long until = SystemClock.uptimeMillis() + 10_000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < until) SystemClock.sleep(10);
        require(condition.getAsBoolean(), "Artwork timed out");
    }
    private void require(boolean condition, String message) { t.require(condition, message); }
}
