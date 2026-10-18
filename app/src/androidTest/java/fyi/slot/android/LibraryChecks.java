package fyi.slot.android;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Real Android storage/index/watchers and shelf checks on the connected tablet. */
final class LibraryChecks {
    private final InteractionChecks t;
    private File fixture, root;
    LibraryChecks(InteractionChecks tests) { t = tests; }
    private LibraryRepository library() { return t.home().library(); }
    void run() {
        SharedPreferences preferences = t.getTargetContext().getSharedPreferences("library", 0);
        String originalRoot = preferences.getString("root", null);
        Map<String, ?> originalSelection = t.getTargetContext().getSharedPreferences("shelf", 0).getAll();
        fixture = new File(t.getTargetContext().getFilesDir(), "library-checks-" + System.nanoTime());
        root = new File(fixture, "Slot");
        try {
            require(root.mkdirs(), "Fixture directory");
            try { fixture = fixture.getCanonicalFile(); root = root.getCanonicalFile(); }
            catch (IOException error) { throw new AssertionError(error); }
            preferences.edit().remove("root").commit();
            MainActivity.testShelf = null;
            t.recreateActivity();
            t.check(108, "productionShelfHasNoDemoCartridges", () -> {
                require(t.view().libraryCount() == 0, "No sample games in production");
                t.key(KeyEvent.KEYCODE_BUTTON_X); t.settle(450);
                require(t.view().isAppsOpen(), "Apps remain available before storage setup"); t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(450);
            });
            t.check(109, "emptyRootLoadsWithoutGames", () -> {
                choose(root); await(() -> library().status() == LibraryRepository.Status.EMPTY);
                require(t.view().libraryCount() == 0, "Empty shelf");
            });
            t.check(110, "mixedPlatformSnapshotHasStableFiles", () -> {
                writeRom("GBA", "Garden 10 (Europe).gba", 0); writeRom("GBA", "Garden 2.gba", 0);
                writeRom("GB", "Twin.gb", 0); writeRom("GB", "Twin.gbc", 0x80); writeRom("GBC", "Lake.gbc", 0xc0);
                refresh(); await(() -> library().snapshot().size() == 5);
                require(library().snapshot().get(0).title.equals("Garden 2"), "Natural sorting");
                Cartridge a = cart("Twin.gb"), b = cart("Twin.gbc"); require(!a.id.equals(b.id), "Full filename identity");
            });
            t.check(111, "originalLabelAndGlitterOverrideLoad", () -> {
                writeLabel("GBA", "Garden 2", 0xff387d62);
                writeText("Labels/cart_shell.txt", "Garden 2 = auto 2f8f4e glitter\n");
                refresh(); await(() -> cart("Garden 2.gba").finish == CartridgeRenderer.Finish.GLITTER);
                Cartridge card = cart("Garden 2.gba"); require(card.label.isFile(), "Original stem label");
                require(card.plastic == 0xff2f8f4e, "Original INI color");
                t.runOnMainSync(() -> t.view().cache().get(card));
                await(() -> t.view().cache().get(card) != null);
                CartridgeRenderer renderer = new CartridgeRenderer(t.getTargetContext().getAssets());
                CartridgeRenderer.Spec spec = renderer.spec(card.shape);
                Bitmap bitmap = t.view().cache().get(card);
                require(bitmap.getPixel(spec.labelX + spec.labelWidth / 2, spec.labelY + spec.labelHeight / 2) == 0xff387d62,
                        "Label pixels must not receive a demo title or gradient");
            });
            t.check(112, "outlineIsIndependentFromShelfPlatform", () -> {
                writeText("Labels/cart_shell.txt", "Twin = rounded 123456 clear\nLake = notched abcdef solid\n"); refresh();
                await(() -> cart("Twin.gb").shape == Cartridge.Platform.GBC);
                require(cart("Twin.gb").platform == Cartridge.Platform.GB, "Folder controls platform");
                require(cart("Lake.gbc").shape == Cartridge.Platform.GB, "GBC can have notched outline");
                require(cart("Twin.gb").appearanceKey.compareTo(cart("Twin.gbc").appearanceKey) != 0, "Shared stem, separate identity");
            });
            t.check(113, "configFallbackAndBareAutoMatchUpstream", () -> {
                writeText("System/cart_shell.txt", "Garden 2 = rounded 123456 clear\n");
                writeText("Labels/cart_shell.txt", "Garden 2 = auto\n"); refresh();
                await(() -> cart("Garden 2.gba").plastic == 0xff35353a);
                writeText("Labels/cart_shell.txt", ""); refresh(); await(() -> cart("Garden 2.gba").plastic == 0xff123456);
                writeText("Config/cart_shell.txt", "Garden 2 = auto abcdef solid\n"); refresh();
                await(() -> cart("Garden 2.gba").plastic == 0xffabcdef);
            });
            t.check(114, "watcherDetectsCopiedRomAndNewFolders", () -> {
                writeRom("GBA", "Forest.gba", 0); await(() -> library().snapshot().size() == 6);
                File folder = new File(root, "Games/GBC"); File old = new File(root, "Games/GBC-away");
                require(folder.renameTo(old), "Rename platform folder"); await(() -> library().snapshot().size() == 5);
                require(old.renameTo(folder), "Restore platform folder"); await(() -> library().snapshot().size() == 6);
            });
            t.check(115, "labelChangeInvalidatesOnlyAppearance", () -> {
                String key = cart("Garden 2.gba").appearanceKey;
                writeLabel("GBA", "Garden 2", 0xffd09050);
                await(() -> !cart("Garden 2.gba").appearanceKey.equals(key));
                require(library().snapshot().size() == 6, "Appearance update preserves games");
            });
            t.check(116, "confirmedDeletionRemovesIndexEntry", () -> {
                require(new File(root, "Games/GBA/Forest.gba").delete(), "Delete owned test ROM");
                await(() -> library().snapshot().size() == 5);
            });
            t.check(117, "sqliteMergeIsIdempotentAndCachesCrc", () -> {
                try (LibraryIndex index = new LibraryIndex(t.getTargetContext())) {
                    List<RomRecord> records = new LibraryScanner().scan(root, Cartridge.Platform.GBA, () -> false);
                    require(index.merge(root.getPath(), Cartridge.Platform.GBA, records) == 0, "Repeated scan does not write");
                    RomRecord rom = cart("Garden 2.gba").rom;
                    AtomicReference<String> crc = new AtomicReference<>();
                    t.runOnMainSync(() -> library().crc(rom, crc::set)); await(() -> crc.get() != null);
                    require(crc.get().matches("[0-9A-F]{8}"), "CRC result");
                    require(index.read(root.getPath()).stream().anyMatch(saved -> saved.id.equals(rom.id) && saved.crc != null), "CRC persisted");
                    require(index.merge(root.getPath(), Cartridge.Platform.GBA, records) == 0, "CRC survives unchanged rescan");
                } catch (IOException error) { throw new AssertionError(error); }
            });
            t.check(118, "scanFailurePreservesPlatformIndex", () -> {
                File link = new File(root, "Games/GB/Broken.gb");
                try { Files.createSymbolicLink(link.toPath(), new File(root, "Games/GBA/Garden 2.gba").toPath()); }
                catch (IOException error) { throw new AssertionError(error); }
                try {
                    refresh(); await(() -> library().status() == LibraryRepository.Status.ERROR);
                    require(library().snapshot().size() == 5, "Failure retains indexed games");
                } finally { require(link.delete(), "Remove owned symlink"); refresh(); }
            });
            t.check(119, "offlineRootRetainsIndexAndRemountRecovers", () -> {
                File offline = new File(fixture, "offline"); require(root.renameTo(offline), "Offline fixture");
                try {
                    refresh(); await(() -> library().status() == LibraryRepository.Status.OFFLINE);
                    require(library().snapshot().size() == 5, "Offline index retained");
                } finally { require(offline.renameTo(root), "Restore fixture"); refresh(); }
                await(() -> library().status() == LibraryRepository.Status.READY);
            });
            t.check(120, "manualRefreshAfterStoppedWatcher", () -> {
                t.runOnMainSync(() -> library().stop()); writeRom("GB", "Moon.gb", 0);
                t.runOnMainSync(() -> library().start()); await(() -> library().snapshot().size() == 6);
            });
            t.check(121, "insertedRealCartridgeDefersShelfMutation", () -> {
                java.util.function.Function<RomRecord, RetroArchLauncher.Result> launcher = t.home().gameLauncher;
                try {
                    t.runOnMainSync(() -> {
                        // Synthetic headers must never be handed to an installed emulator.
                        t.home().gameLauncher = rom -> RetroArchLauncher.Result.STARTED;
                        Bundle state = new Bundle(); state.putString("shelf_selected_ALL", cart("Garden 2.gba").id); t.view().restore(state);
                    });
                    t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(1700);
                    require(t.view().currentMode() == SlotView.Mode.PREVIEW, "Real card seated");
                    writeRom("GBA", "Apple.gba", 0); await(() -> library().snapshot().size() == 7);
                    require(t.view().currentCartridge().rom.filename.equals("Garden 2.gba"), "Inserted identity stable during refresh");
                    t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(1800);
                    // Observe the real window without supplying an extra touch, draw or status event.
                    require(t.view().insertionProgress() == 0 && t.view().libraryCount() == 7,
                            "Last scheduled eject frame must apply the pending library snapshot");
                    require(t.view().currentCartridge().rom.filename.equals("Garden 2.gba"), "Selection restored by identity");
                } finally { t.runOnMainSync(() -> t.home().gameLauncher = launcher); }
            });
            t.check(122, "libraryAndSelectionSurviveActivityRecreation", () -> {
                t.key(KeyEvent.KEYCODE_DPAD_RIGHT); t.settle(450); String id = t.view().selectedCartridgeId();
                t.runOnMainSync(() -> t.home().onLibrary()); t.settle(450); t.recreateActivity();
                await(() -> library().status() == LibraryRepository.Status.READY && t.view().libraryCount() == 7);
                require(t.view().selectedCartridgeId().equals(id), "Identity after rebuild");
                require(LibraryPanel.GROUP.equals(t.home().settingsPanel().groupId()), "Library tab restored");
                t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(450);
            });
            t.check(123, "changingRootsCannotReuseFileIdentity", () -> {
                File another = new File(fixture, "another"); require(new File(another, "Games/GBA").mkdirs(), "Second root");
                try { Files.copy(new File(root, "Games/GBA/Garden 2.gba").toPath(), new File(another, "Games/GBA/Garden 2.gba").toPath()); }
                catch (IOException error) { throw new AssertionError(error); }
                String first = cart("Garden 2.gba").id; choose(another); await(() -> library().snapshot().size() == 1);
                require(!cart("Garden 2.gba").id.equals(first), "Root-scoped identity");
                File romsRoot = new File(fixture, "Roms"); require(new File(romsRoot, "gba").mkdirs() && new File(romsRoot, "gb").mkdirs(), "Direct platform folders");
                try {
                    Files.copy(new File(root, "Games/GBA/Garden 2.gba").toPath(), new File(romsRoot, "gba/Garden 2.gba").toPath());
                    ImportWriter.Result imported = ImportWriter.write(romsRoot, "Direct.gb", 512, new java.io.ByteArrayInputStream(new byte[512]), () -> false, c -> {});
                    require(imported.outcome() == ImportWriter.Outcome.ADDED && new File(romsRoot, "gb/Direct.gb").isFile(), "Import follows direct platform layout");
                } catch (IOException error) { throw new AssertionError(error); }
                choose(romsRoot); await(() -> library().snapshot().size() == 2);
                Cartridge decoupled = cart("Garden 2.gba");
                require(decoupled.label != null && decoupled.label.getAbsolutePath().startsWith(root.getAbsolutePath()), "Roms root reads decoupled Slot assets");
                require(decoupled.finish == CartridgeRenderer.Finish.SOLID && decoupled.plastic == 0xffabcdef, "Roms root reads decoupled shell config");
                writeLabel("GBA", "Garden 2", 0xff206090);
                await(() -> !cart("Garden 2.gba").appearanceKey.equals(decoupled.appearanceKey));
                choose(root); await(() -> library().snapshot().size() == 7);
            });
            t.check(124, "thousandRecordsKeepBitmapAndQueueBounded", () -> {
                List<Cartridge> cards = new ArrayList<>();
                for (int i = 0; i < 1000; i++) {
                    RomRecord rom = new RomRecord(root.getPath(), "Games/GBA/Game " + i + ".gba", Cartridge.Platform.GBA,
                            512, 1, new byte[0x150], null, false);
                    cards.add(new Cartridge(rom, ShellConfig.defaults(rom), null));
                }
                SlotView[] isolated = new SlotView[1];
                t.runOnMainSync(() -> {
                    isolated[0] = new SlotView(t.getTargetContext()); isolated[0].layout(0, 0, 960, 640);
                    isolated[0].updateLibrary(LibraryRepository.Status.READY, cards);
                    Bitmap frame = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
                    isolated[0].draw(new Canvas(frame)); frame.recycle();
                    require(isolated[0].cache().pendingCount() <= CartridgeCache.MAX_PENDING, "Visible work bounded");
                    for (Cartridge card : cards) isolated[0].cache().get(card);
                    require(isolated[0].cache().pendingCount() <= CartridgeCache.MAX_PENDING, "1000 requests bounded");
                });
                t.settle(1200);
                require(isolated[0].cache().bitmapBytes() <= CartridgeCache.MAX_CARD_BYTES + CartridgeCache.MAX_LABEL_BYTES,
                        "Artwork stays within its byte budget");
                t.runOnMainSync(() -> isolated[0].onDetachedFromWindow());
            });
            t.check(125, "missingAndMalformedLabelsHaveReadableFallback", () -> {
                writeText("Labels/GBA/Apple.png", "invalid png"); refresh();
                await(() -> library().status() == LibraryRepository.Status.READY);
                Cartridge card = cart("Apple.gba"); t.runOnMainSync(() -> t.view().cache().get(card));
                await(() -> t.view().cache().get(card) != null); require(!t.view().cache().get(card).isRecycled(), "Fallback bitmap available");
                Bitmap bitmap = t.view().cache().get(card);
                CartridgeRenderer.Spec spec = new CartridgeRenderer(t.getTargetContext().getAssets()).spec(card.shape);
                int first = spec.labelWidth, last = -1;
                for (int y = 0; y < spec.labelHeight; y++) for (int x = 0; x < spec.labelWidth; x++) {
                    if (bitmap.getPixel(spec.labelX + x, spec.labelY + y) == 0xfff4f1ea) { first = Math.min(first, x); last = Math.max(last, x); }
                }
                require(last >= first && Math.abs((first + last) / 2f - spec.labelWidth / 2f) < 12,
                        "Fallback name must be centered and unclipped");
            });
            t.check(126, "libraryNavigationAndLanguageStayConsistent", () -> {
                t.runOnMainSync(() -> t.home().onLibrary()); t.settle(450);
                require(LibraryPanel.GROUP.equals(t.home().settingsPanel().groupId()), "Library tab shown");
                require(t.home().settingsPanel().rowCount() > 1, "Library tab lists its actions directly");
                AppLanguage.Choice original = t.view().language().choice();
                try {
                    for (AppLanguage.Choice choice : List.of(AppLanguage.Choice.CHINESE, AppLanguage.Choice.ENGLISH)) {
                        t.runOnMainSync(() -> { t.view().language().select(choice); t.home().onLanguageChanged(); });
                        require(((android.widget.TextView) t.home().settingsPanel().tabView(2)).getText().toString()
                                .equals(t.view().language().text(R.string.settings_library)), "Localized tab");
                        require(LibraryPanel.GROUP.equals(t.home().settingsPanel().groupId()), "Language preserves tab");
                    }
                } finally { t.runOnMainSync(() -> { t.view().language().select(original); t.home().onLanguageChanged(); }); }
                t.key(KeyEvent.KEYCODE_BUTTON_X); t.settle(100); require(t.view().isAppsOpen(), "Switch apps without closing machine");
                t.key(KeyEvent.KEYCODE_BUTTON_Y); t.settle(100); require(LibraryPanel.GROUP.equals(t.home().settingsPanel().groupId()), "Return to library tab");
                t.key(KeyEvent.KEYCODE_BUTTON_A); require(t.home().settingsPanel().isInRows(), "Enter library rows");
                t.key(KeyEvent.KEYCODE_BUTTON_B); require(!t.home().settingsPanel().isInRows() && t.view().isSettingsOpen(), "Back to tabs");
                t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(450);
            });
            t.check(127, "coldSelectionPreferencesContainGameIdentity", () -> {
                t.key(KeyEvent.KEYCODE_DPAD_RIGHT); t.settle(450);
                String saved = t.getTargetContext().getSharedPreferences("shelf", 0).getString("ALL", null);
                require(t.view().selectedCartridgeId().equals(saved), "Persist full game identity");
            });
            t.check(128, "permissionLossPreservesAndRecoversIndex", () -> {
                try {
                    t.runOnMainSync(() -> library().permissionForChecks(() -> false)); refresh();
                    require(library().status() == LibraryRepository.Status.PERMISSION && library().snapshot().size() == 7, "Permission loss retains index");
                    t.runOnMainSync(() -> library().permissionForChecks(() -> true)); refresh();
                    require(library().status() == LibraryRepository.Status.READY && library().snapshot().size() == 7, "Permission recovery");
                } finally {
                    t.runOnMainSync(() -> library().permissionForChecks(null)); refresh();
                }
            });
            t.check(129, "realCartridgeLongPressDetailsAndControllerBack", () -> {
                CartridgeDetails details = t.home().cartridgeDetails();
                try {
                    t.dragStart(t.view().homeLayout().centerX(), t.view().selectedCardTop() + t.view().selectedCardHeight() / 2);
                    t.settle(700); require(details.isShowing(), "Long press opens cartridge menu");
                    t.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A); t.settle(300);
                    require(details.isDetailsPage(), "Native dialog accepts gamepad A");
                    t.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_B); t.settle(200);
                    require(!details.isShowing(), "Gamepad B closes details");
                } finally { t.runOnMainSync(details::dismiss); }
            });
            new EjectTimingChecks(t).run();
        } finally {
            SharedPreferences.Editor rootRestore = preferences.edit();
            if (originalRoot == null) rootRestore.remove("root"); else rootRestore.putString("root", originalRoot);
            rootRestore.commit();
            SharedPreferences.Editor selectionRestore = t.getTargetContext().getSharedPreferences("shelf", 0).edit().clear();
            originalSelection.forEach((key, value) -> { if (value instanceof String text) selectionRestore.putString(key, text); });
            selectionRestore.commit();
            MainActivity.testShelf = null;
            t.runOnMainSync(() -> { Bundle state = new Bundle(); t.view().restore(state); });
            t.recreateActivity();
            deleteOwned(fixture);
        }
    }
    private void choose(File folder) {
        t.runOnMainSync(() -> { try { library().choose(folder); } catch (IOException error) { throw new AssertionError(error); } });
        await(() -> library().status() != LibraryRepository.Status.LOADING);
    }
    private void refresh() { t.runOnMainSync(() -> library().refresh()); await(() -> library().status() != LibraryRepository.Status.LOADING); }
    private Cartridge cart(String filename) {
        for (Cartridge card : library().snapshot()) if (card.rom.filename.equals(filename)) return card;
        throw new AssertionError("Missing " + filename);
    }
    private void writeRom(String platform, String filename, int flag) {
        byte[] head = new byte[512]; head[0x143] = (byte) flag;
        try {
            File file = new File(root, "Games/" + platform + "/" + filename);
            require(file.getParentFile().isDirectory() || file.getParentFile().mkdirs(), "ROM folder"); Files.write(file.toPath(), head);
        } catch (IOException error) { throw new AssertionError(error); }
    }
    private void writeText(String path, String text) {
        try {
            File file = new File(root, path); require(file.getParentFile().isDirectory() || file.getParentFile().mkdirs(), "Text folder");
            Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) { throw new AssertionError(error); }
    }
    private void writeLabel(String platform, String stem, int color) {
        File file = new File(root, "Labels/" + platform + "/" + stem + ".png");
        require(file.getParentFile().isDirectory() || file.getParentFile().mkdirs(), "Label folder");
        Bitmap image = Bitmap.createBitmap(1280, 661, Bitmap.Config.ARGB_8888); image.eraseColor(color);
        try (FileOutputStream output = new FileOutputStream(file)) { image.compress(Bitmap.CompressFormat.PNG, 100, output); }
        catch (IOException error) { throw new AssertionError(error); } finally { image.recycle(); }
    }
    private void await(BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 8000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < deadline) { t.waitForIdleSync(); SystemClock.sleep(50); }
        require(condition.getAsBoolean(), "Timed out, library=" + library().status()); t.waitForIdleSync();
    }
    private void require(boolean condition, String message) { t.require(condition, message); }
    private void deleteOwned(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) deleteOwned(child);
        if (file.exists() && !file.delete()) android.util.Log.w("SlotChecks", "Fixture remains: " + file);
    }
}
