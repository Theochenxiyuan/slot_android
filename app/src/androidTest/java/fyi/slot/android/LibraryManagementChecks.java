package fyi.slot.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Stage 3 checks use the real result receiver, ContentResolver streams, SQLite and native bay. */
final class LibraryManagementChecks {
    private final InteractionChecks t;
    private File fixture, root;
    LibraryManagementChecks(InteractionChecks tests) { t = tests; }
    private LibraryRepository library() { return t.home().library(); }
    private LibraryImportSession imports() { return t.home().importSession(); }
    void run() {
        SharedPreferences prefs = t.getTargetContext().getSharedPreferences("library", 0);
        String originalRoot = prefs.getString("root", null);
        Map<String, ?> originalSelection = t.getTargetContext().getSharedPreferences("shelf", 0).getAll();
        fixture = new File(t.getTargetContext().getFilesDir(), "management-checks-" + System.nanoTime());
        root = new File(fixture, "Slot");
        try {
            require(root.mkdirs(), "Owned fixture");
            try { root = root.getCanonicalFile(); fixture = fixture.getCanonicalFile(); }
            catch (IOException error) { throw new AssertionError(error); }
            MainActivity.testShelf = null; t.recreateActivity(); choose(root);
            t.check(133, "importEntryUsesMultiSelectDocumentPicker", () -> {
                Intent[] opened = {null};
                Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
                    @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                        opened[0] = intent; return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                    }
                };
                t.addMonitor(monitor);
                try { openLibrary(); click(R.string.import_games); t.settle(150); }
                finally { t.removeMonitor(monitor); }
                require(opened[0] != null && Intent.ACTION_OPEN_DOCUMENT.equals(opened[0].getAction()), "System document picker");
                require(opened[0].getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false), "Multiple files enabled");
                require(opened[0].hasCategory(Intent.CATEGORY_OPENABLE), "Readable documents only");
            });
            t.check(134, "multiDocumentBatchImportsAllPlatformsAndRefreshesShelf", () -> {
                batch(source("Garden.gba"), source("Moon.gb"), source("Lake.gbc")); finishBatch();
                require(imports().state().count(R.string.import_added) == 3, "Three successful results");
                await(() -> library().snapshot().size() == 3 && t.view().libraryCount() == 3);
                for (String path : List.of("GBA/Garden.gba", "GB/Moon.gb", "GBC/Lake.gbc"))
                    require(new File(root, "Games/" + path).length() == 512, "Complete ROM " + path);
                require(importDialog().isShowing(), "Result dialog shown");
                require(!hasRow(R.string.import_results) && !hasRow(R.string.import_progress), "No persistent import entry");
                require(dialogContains(t.view().language().text(R.string.import_summary, 3, 0, 0)), "Compact success summary");
            });
            t.check(135, "sameNameAndEquivalentNameSkipWithoutOverwriting", () -> {
                byte[] original;
                try { original = Files.readAllBytes(new File(root, "Games/GBA/Garden.gba").toPath()); }
                catch (IOException error) { throw new AssertionError(error); }
                batch(source("Garden.gba"), source("GARDEN.GBA"), source("Café.gba"), source("Cafe\u0301.gba")); finishBatch();
                require(imports().state().count(R.string.import_skipped) == 3, "Same, case and NFC duplicates skip");
                require(imports().state().count(R.string.import_added) == 1, "One new file");
                try { require(java.util.Arrays.equals(original, Files.readAllBytes(new File(root, "Games/GBA/Garden.gba").toPath())), "Original unchanged"); }
                catch (IOException error) { throw new AssertionError(error); }
                noParts(root);
            });
            t.check(136, "invalidAndShortFilesDoNotStopBatch", () -> {
                batch(source("../Escape.gba"), source("Archive.zip"), source("Short.gb", 10, "10", ""), source("Forest.gb")); finishBatch();
                require(imports().state().count(R.string.import_name) == 1, "Unsafe name rejected");
                require(imports().state().count(R.string.import_format) == 1, "Archive rejected");
                require(imports().state().count(R.string.import_incomplete) == 1, "Short header rejected");
                require(imports().state().count(R.string.import_added) == 1, "Later file succeeds"); noParts(root);
                require(dialogContains("../Escape.gba\n" + t.view().language().text(R.string.import_name)), "Failed filename and reason visible");
            });
            t.check(137, "unknownAndMismatchedSourceSizesAreHandled", () -> {
                batch(source("Unknown.gba", 512, "unknown", ""), source("Truncated.gba", 512, "1024", ""),
                        source("Large.gba", 512, Long.toString(ImportWriter.MAX_BYTES + 1), "")); finishBatch();
                require(imports().state().count(R.string.import_added) == 1, "Unknown size streams successfully");
                require(imports().state().count(R.string.import_incomplete) == 1, "Length mismatch reported");
                require(imports().state().count(R.string.import_size) == 1, "Size cap reported"); noParts(root);
            });
            t.check(138, "cancelBatchKeepsCompletedFilesAndDiscardsPartialFile", () -> {
                batch(source("Completed.gba"), source("Cancelled.gba", 8 * 1024 * 1024, "8388608", "slow"), source("Unstarted.gb"));
                await(() -> imports().state().bytes() > 0 && imports().state().current().equals("Cancelled.gba"));
                click(R.string.cancel_import); finishBatch();
                require(imports().state().cancelled(), "Cancelled state"); require(imports().state().count(R.string.import_added) == 1, "Completed file retained");
                require(!new File(root, "Games/GBA/Cancelled.gba").exists() && !new File(root, "Games/GB/Unstarted.gb").exists(), "No unfinished ROM"); noParts(root);
            });
            t.check(139, "activeImportAndDialogSurviveRecreation", () -> {
                batch(source("Retained.gba", 1024 * 1024, "1048576", "slow"));
                await(() -> imports().state().bytes() > 0); LibraryImportSession session = imports(); t.recreateActivity();
                require(session == imports(), "Retained Activity-independent session"); finishBatch();
                require(imports().state().count(R.string.import_added) == 1, "Copy survives recreation");
                require(importDialog().isShowing(), "Result dialog restored"); noParts(root);
            });
            t.check(140, "importDialogBlocksBackgroundInputAndResumesAfterBackground", () -> {
                batch(source("Background.gb", 1024 * 1024, "1048576", "slow"));
                t.key(KeyEvent.KEYCODE_BUTTON_X); t.settle(120); require(t.view().isSettingsOpen(), "Modal blocks app shortcut");
                t.runOnMainSync(() -> t.home().stop()); require(!importDialog().isShowing(), "No window shown while stopped");
                finishBatch(); require(imports().state().count(R.string.import_added) == 1, "Background import completes");
                t.runOnMainSync(() -> { t.home().start(); t.home().resume(); }); require(importDialog().isShowing(), "Return shows completed result");
                await(() -> library().snapshot().stream().anyMatch(cart -> cart.rom.filename.equals("Background.gb")));
            });
            t.check(141, "changingRootCancelsOldDestinationWithoutRedirecting", () -> {
                batch(source("OldDestination.gba", 8 * 1024 * 1024, "8388608", "slow")); await(() -> imports().state().bytes() > 0);
                File another = new File(fixture, "Other"); require(another.mkdir(), "Second root"); choose(another); finishBatch();
                require(imports().state().cancelled(), "Changing root cancels batch");
                require(!new File(another, "Games/GBA/OldDestination.gba").exists(), "No redirect to new root"); noParts(root); choose(root);
            });
            t.check(142, "pickerCancellationPreservesLibraryAndResults", () -> {
                openLibrary(); LibraryImportSession.State before = imports().state();
                stubPicker(() -> t.activity().onActivityResult(LibraryPanel.PICK_GAMES, Activity.RESULT_CANCELED, null));
                require(imports().state() == before, "Picker cancel creates no batch"); require(LibraryPanel.GROUP.equals(t.home().settingsPanel().groupId()), "Library still visible");
            });
            t.check(143, "cartridgeMenuHidesViaControllerWithoutDeletingRom", () -> {
                t.runOnMainSync(() -> t.home().onHome(false)); t.settle(100);
                Cartridge garden = cart("Garden.gba");
                t.runOnMainSync(() -> t.home().onCartridgeMenu(garden)); t.settle(100);
                for (int i = 0; i < 4; i++) t.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
                t.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A);
                await(() -> library().hidden().stream().anyMatch(rom -> rom.id.equals(garden.id)));
                require(new File(root, "Games/GBA/Garden.gba").isFile(), "Hide leaves ROM on disk");
                require(library().snapshot().stream().noneMatch(card -> card.id.equals(garden.id)), "Hidden removed from shelf");
            });
            t.check(144, "hiddenGamesRestoreInBayAndReturnToParentFocus", () -> {
                openLibrary(); click(R.string.hidden_games); require(hasRow(R.string.hidden_games), "Hidden page");
                clickText("Garden.gba"); await(() -> library().hidden().isEmpty());
                require(hasRow(R.string.hidden_empty), "Empty state after restore");
                t.key(KeyEvent.KEYCODE_BUTTON_B); require(hasRow(R.string.import_games), "B returns to library actions");
                require(t.home().settingsPanel().selectedRow() == 5, "Restore parent row focus");
            });
            t.check(145, "hiddenIdentitySeparatesSameStemExtensionsAndRoots", () -> {
                try {
                    File folder = new File(root, "Games/GB"); Files.write(new File(folder, "Twin.gb").toPath(), new byte[512]);
                    Files.write(new File(folder, "Twin.gbc").toPath(), new byte[512]);
                } catch (IOException error) { throw new AssertionError(error); }
                refresh(); await(() -> library().snapshot().stream().anyMatch(card -> card.rom.filename.equals("Twin.gbc")));
                RomRecord twin = cart("Twin.gb").rom; t.runOnMainSync(() -> library().setHidden(twin, true));
                await(() -> library().hidden().stream().anyMatch(rom -> rom.id.equals(twin.id)));
                require(cart("Twin.gbc") != null, "Other extension remains visible");
                File other = new File(fixture, "Other");
                try { File folder = new File(other, "Games/GB"); require(folder.mkdirs(), "Second library platform"); Files.write(new File(folder, "Twin.gb").toPath(), new byte[512]); }
                catch (IOException error) { throw new AssertionError(error); }
                choose(other); await(() -> library().snapshot().size() == 1); require(library().hidden().isEmpty(), "Other root unaffected"); choose(root);
                await(() -> library().hidden().size() == 1);
            });
            t.check(146, "hiddenStateSurvivesRecreationAndRescan", () -> {
                RomRecord cafe = cart("Café.gba").rom;
                t.runOnMainSync(() -> library().setHidden(cafe, true)); await(() -> library().hidden().size() == 2);
                require(new File(root, "Games/GBA/Café.gba").renameTo(new File(root, "Games/GBA/Cafe\u0301.gba")), "Equivalent Unicode filename rename");
                refresh(); require(library().hidden().stream().anyMatch(rom -> rom.id.equals(cafe.id)), "NFC identity preserves hidden state after rename");
                t.runOnMainSync(() -> library().setHidden(cafe, false)); await(() -> library().hidden().size() == 1);
                t.runOnMainSync(() -> library().setHidden(cafe, true)); await(() -> library().hidden().size() == 2);
                String id = library().hidden().get(0).id; t.recreateActivity();
                await(() -> library().hidden().stream().anyMatch(rom -> rom.id.equals(id)));
                refresh(); require(library().hidden().size() == 2, "Hidden state kept on unchanged scan");
            });
            t.check(147, "offlineAndPermissionFailuresKeepHiddenStateRecoverable", () -> {
                String id = library().hidden().get(0).id;
                File offline = new File(fixture, "Offline"); require(root.renameTo(offline), "Offline fixture");
                try { refresh(); require(library().status() == LibraryRepository.Status.OFFLINE && library().hidden().get(0).id.equals(id), "Offline hidden index kept"); }
                finally { require(offline.renameTo(root), "Remount fixture"); refresh(); }
                try {
                    t.runOnMainSync(() -> library().permissionForChecks(() -> false)); refresh();
                    require(library().status() == LibraryRepository.Status.PERMISSION && library().hidden().size() == 2, "Permission failure preserves hidden");
                } finally { t.runOnMainSync(() -> library().permissionForChecks(null)); refresh(); }
                List<RomRecord> hidden = library().hidden();
                t.runOnMainSync(() -> hidden.forEach(rom -> library().setHidden(rom, false))); await(() -> library().hidden().isEmpty());
            });
            t.check(148, "unreadableSourceDoesNotPreventLaterImports", () -> {
                batch(source("Denied.gba", 512, "512", "denied"), source("AfterDenied.gba")); finishBatch();
                require(imports().state().count(R.string.import_failed) == 1, "Unreadable source reported");
                require(imports().state().count(R.string.import_added) == 1, "Next source succeeds"); noParts(root);
            });
            t.check(149, "hiddenListPagingRemainsBoundedAndControllerNavigable", () -> {
                try {
                    for (int i = 0; i < 35; i++) Files.write(new File(root, "Games/GBA/Hidden " + i + ".gba").toPath(), new byte[512]);
                } catch (IOException error) { throw new AssertionError(error); }
                refresh(); await(() -> library().snapshot().stream().filter(cart -> cart.title.startsWith("Hidden ")).count() == 35);
                List<RomRecord> hide = library().snapshot().stream().filter(cart -> cart.title.startsWith("Hidden ")).map(cart -> cart.rom)
                        .collect(java.util.stream.Collectors.toList());
                t.runOnMainSync(() -> hide.forEach(rom -> library().setHidden(rom, true))); await(() -> library().hidden().size() == 35);
                openLibrary(); click(R.string.hidden_games); require(t.home().settingsPanel().rowCount() == 32, "30 games plus heading and paging");
                click(R.string.next_page); require(t.home().settingsPanel().rowCount() == 7, "Last page"); require(hasRow(R.string.previous_page), "Previous page reachable");
                t.key(KeyEvent.KEYCODE_BUTTON_B); require(hasRow(R.string.import_games), "Controller returns to library");
            });
            t.check(150, "importDialogAndHiddenRowsUseBothLanguages", () -> {
                AppLanguage.Choice original = t.view().language().choice();
                try {
                    for (AppLanguage.Choice choice : List.of(AppLanguage.Choice.CHINESE, AppLanguage.Choice.ENGLISH)) {
                        t.runOnMainSync(() -> { t.view().language().select(choice); t.home().onLanguageChanged(); });
                        batch(source("Garden.gba")); finishBatch();
                        require(dialogContains(t.view().language().text(R.string.import_results)), "Localized dialog title");
                        require(dialogContains(t.view().language().text(R.string.close)), "Localized close button");
                        closeResult(); click(R.string.hidden_games); require(hasRow(R.string.hidden_games), "Localized hidden title");
                        t.key(KeyEvent.KEYCODE_BUTTON_B);
                    }
                } finally {
                    t.runOnMainSync(() -> { t.view().language().select(original); t.home().onLanguageChanged(); });
                    t.getTargetContext().getSharedPreferences("language", 0).edit().putString("choice", original.name()).commit();
                }
            });
            t.check(151, "oneDialogTransitionsFromProgressToResultAndDiscardsClosedState", () -> {
                batch(source("SameWindow.gba", 1024 * 1024, "1048576", "slow"));
                await(() -> imports().state().bytes() > 0);
                android.app.AlertDialog window = importDialog().window();
                require(dialogContains(t.view().language().text(R.string.import_progress)), "Progress title");
                finishBatch(); require(window == importDialog().window(), "Same window updated in place");
                require(dialogContains(t.view().language().text(R.string.import_results)), "Result title in same window");
                t.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_B); t.settle(100);
                require(!importDialog().isShowing() && imports().state().root() == null && imports().state().items().isEmpty(), "Close clears result data");
                require(!hasRow(R.string.import_results) && !hasRow(R.string.import_progress), "No history entry left behind");
            });
            t.check(152, "controllerBackCancelsInPlaceAndConfirmClosesResult", () -> {
                batch(source("BackCancelled.gba", 8 * 1024 * 1024, "8388608", "slow"));
                await(() -> imports().state().bytes() > 0);
                android.app.AlertDialog window = importDialog().window();
                t.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_B); finishBatch();
                require(window == importDialog().window() && imports().state().cancelled(), "Back cancels without closing window");
                require(!new File(root, "Games/GBA/BackCancelled.gba").exists(), "No partial ROM after back"); noParts(root);
                t.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A); t.settle(100);
                require(!importDialog().isShowing() && imports().state().root() == null, "A closes cancelled result and clears state");
            });
        } finally {
            t.runOnMainSync(() -> imports().cancel()); await(() -> !imports().state().running());
            SharedPreferences.Editor restore = prefs.edit(); if (originalRoot == null) restore.remove("root"); else restore.putString("root", originalRoot); restore.commit();
            SharedPreferences.Editor selected = t.getTargetContext().getSharedPreferences("shelf", 0).edit().clear();
            originalSelection.forEach((key, value) -> { if (value instanceof String text) selected.putString(key, text); }); selected.commit();
            MainActivity.testShelf = null; t.recreateActivity(); deleteOwned(fixture);
        }
    }
    private Uri source(String name) { return source(name, 512, "512", ""); }
    private Uri source(String name, int bytes, String report, String mode) {
        return Uri.parse("content://fyi.slot.android.test.imports/sample").buildUpon().appendQueryParameter("name", name)
                .appendQueryParameter("bytes", Integer.toString(bytes)).appendQueryParameter("reported", report).appendQueryParameter("mode", mode).build();
    }
    private void batch(Uri... sources) {
        openLibrary();
        ClipData clip = new ClipData("Samples", new String[]{"application/octet-stream"}, new ClipData.Item(sources[0]));
        for (int i = 1; i < sources.length; i++) clip.addItem(new ClipData.Item(sources[i]));
        Intent result = new Intent(); result.setClipData(clip);
        stubPicker(() -> t.activity().onActivityResult(LibraryPanel.PICK_GAMES, Activity.RESULT_OK, result));
    }
    private void stubPicker(Runnable result) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);
        filter.addCategory(Intent.CATEGORY_OPENABLE);
        try { filter.addDataType("*/*"); } catch (IntentFilter.MalformedMimeTypeException error) { throw new AssertionError(error); }
        Instrumentation.ActivityMonitor monitor = t.addMonitor(filter, null, true);
        try { click(R.string.import_games); require(monitor.getHits() == 1, "Picker requested"); t.runOnMainSync(result); }
        finally { t.removeMonitor(monitor); }
    }
    private void finishBatch() { await(() -> !imports().state().running()); t.settle(150); }
    private void openLibrary() {
        closeResult();
        t.runOnMainSync(() -> { t.home().libraryPanel().back(); t.home().onLibrary(); }); t.settle(450);
    }
    private LibraryImportDialog importDialog() { return t.home().libraryPanel().importDialog(); }
    private void closeResult() {
        if (imports().state().root() != null && !imports().state().running()) t.runOnMainSync(() -> {
            t.home().libraryPanel().importDialog().hide(); imports().clear();
        });
    }
    private boolean dialogContains(String text) {
        return importDialog().isShowing() && containsText(importDialog().window().getWindow().getDecorView(), text);
    }
    private boolean containsText(android.view.View view, String text) {
        if (view instanceof android.widget.TextView label && text.contentEquals(label.getText())) return true;
        if (view instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++)
            if (containsText(group.getChildAt(i), text)) return true;
        return false;
    }
    private boolean hasRow(int text) { return findRow(t.view().language().text(text)) >= 0; }
    private int findRow(String text) {
        SettingsPanel panel = t.home().settingsPanel();
        for (int i = 0; i < panel.rowCount(); i++) {
            String label = ((android.widget.TextView) ((android.widget.LinearLayout) panel.rowView(i)).getChildAt(0)).getText().toString();
            if (text.equals(label)) return i;
        }
        return -1;
    }
    private void click(int text) { clickText(t.view().language().text(text)); }
    private void clickText(String text) {
        t.runOnMainSync(() -> {
            if (importDialog().isShowing()) {
                android.widget.Button button = importDialog().window().getButton(android.app.AlertDialog.BUTTON_POSITIVE);
                require(text.contentEquals(button.getText()), "Dialog button " + text); button.performClick(); return;
            }
            int row = findRow(text); require(row >= 0, "Row " + text); t.home().settingsPanel().rowView(row).performClick();
        }); t.waitForIdleSync();
    }
    private Cartridge cart(String name) {
        return library().snapshot().stream().filter(cart -> cart.rom.filename.equals(name)).findFirst().orElseThrow(() -> new AssertionError("Missing " + name));
    }
    private void choose(File next) {
        t.runOnMainSync(() -> { try { library().choose(next); } catch (IOException error) { throw new AssertionError(error); } });
        await(() -> library().status() != LibraryRepository.Status.LOADING);
    }
    private void refresh() { t.runOnMainSync(() -> library().refresh()); await(() -> library().status() != LibraryRepository.Status.LOADING); }
    private void noParts(File directory) {
        try (var paths = Files.walk(directory.toPath())) { require(paths.noneMatch(path -> path.toString().endsWith(".part")), "No residual partial files"); }
        catch (IOException error) { throw new AssertionError(error); }
    }
    private void await(BooleanSupplier condition) {
        long until = SystemClock.uptimeMillis() + 10000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < until) { t.waitForIdleSync(); SystemClock.sleep(40); }
        require(condition.getAsBoolean(), "Timeout, library=" + library().status()); t.waitForIdleSync();
    }
    private void require(boolean condition, String message) { t.require(condition, message); }
    private void deleteOwned(File directory) {
        File[] children = directory.listFiles(); if (children != null) for (File file : children) deleteOwned(file);
        if (directory.exists() && !directory.delete()) android.util.Log.w("SlotChecks", "Fixture remains: " + directory);
    }
}
