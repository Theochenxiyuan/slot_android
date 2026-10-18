package fyi.slot.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Library status and folder actions shown as the settings bay's library tab; folders come from the system picker. */
final class LibraryPanel {
    static final String GROUP = "library";
    private final Activity activity;
    private final AppLanguage language;
    private final SettingsPanel settings;
    private final LibraryRepository repository;
    private final LibraryImportSession imports;
    private final LibraryImportDialog importDialog;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy, released, awaitingPermission;
    private int request;
    private String page = "", pickerRoot;
    private int parentRow, parentScroll, pageOffset, importedCount;
    private static final int PAGE_SIZE = 30;
    static final int PICK_GAMES = 43;
    private Runnable edit = () -> {};
    private OnboardingPanel onboarding;
    void setEditor(Runnable edit) { this.edit = edit; }
    void setOnboarding(OnboardingPanel onboarding) { this.onboarding = onboarding; }

    LibraryPanel(Activity activity, AppLanguage language, SettingsPanel settings, LibraryRepository repository,
                 LibraryImportSession imports) {
        this.activity = activity; this.language = language; this.settings = settings; this.repository = repository;
        this.imports = imports;
        importDialog = new LibraryImportDialog(activity, language, imports);
        imports.listen(this::importChanged);
    }
    static int statusText(LibraryRepository.Status status) {
        return switch (status) {
            case UNCONFIGURED -> R.string.library_unconfigured;
            case PERMISSION -> R.string.library_permission;
            case LOADING -> R.string.apps_loading;
            case READY -> R.string.library_ready;
            case EMPTY -> R.string.library_empty;
            case OFFLINE -> R.string.library_offline;
            case ERROR -> R.string.library_error;
        };
    }
    void open() { settings.showGroup(GROUP); }
    /** The library tab became visible; an unset library looks for one already on storage. */
    void shown() {
        changed();
        if (LibraryStorage.allowed() && repository.root() == null) discover(false);
    }
    void resume() {
        importDialog.resume();
        if (!awaitingPermission) return;
        awaitingPermission = false;
        changed();
        if (LibraryStorage.allowed() && repository.root() == null) discover(true);
    }
    void changed() {
        if (imports.state().running() && !java.util.Objects.equals(repository.root(), imports.state().root())) imports.cancel();
        settings.replaceGroup(GROUP, page.equals("hidden") ? hiddenRows() : rows());
        importDialog.update();
        if (onboarding != null) onboarding.refresh();
    }
    private List<SettingsPanel.Row> rows() {
        List<SettingsPanel.Row> rows = new ArrayList<>();
        boolean allowed = LibraryStorage.allowed();
        LibraryRepository.Status status = repository.status();
        int state = !allowed ? R.string.library_permission : busy ? R.string.apps_loading : statusText(status);
        SettingsPanel.Kind statusKind = status == LibraryRepository.Status.ERROR
                || status == LibraryRepository.Status.OFFLINE ? SettingsPanel.Kind.ENTRY : SettingsPanel.Kind.INFO;
        rows.add(new SettingsPanel.Row("status", state, statusKind, false,
                () -> repository.status() == LibraryRepository.Status.READY ? Integer.toString(repository.snapshot().size()) : "",
                direction -> { if (repository.root() != null) repository.refresh(); }));
        if (!allowed) rows.add(row("permission", R.string.allow_files, this::requestPermission));
        else {
            File root = repository.root();
            if (root != null) rows.add(new SettingsPanel.Row("path", R.string.library_location, SettingsPanel.Kind.INFO, false,
                    () -> LibraryStorage.describe(activity, language, root), direction -> {}));
            rows.add(row("choose", R.string.choose_folder, this::pick));
            if (root != null) {
                rows.add(row("import", R.string.import_games, this::pickGames));
                rows.add(row("studio", R.string.edit_cartridges, edit));
                rows.add(new SettingsPanel.Row("hidden", R.string.hidden_games, SettingsPanel.Kind.ENTRY, false,
                        () -> Integer.toString(repository.hidden().size()), direction -> showPage("hidden")));
                rows.add(row("refresh", R.string.refresh_library, repository::refresh));
            }
        }
        return rows;
    }
    private SettingsPanel.Row row(String id, int label, Runnable action) {
        return new SettingsPanel.Row(id, label, SettingsPanel.Kind.ENTRY, false, null, direction -> action.run());
    }
    void requestPermission() {
        awaitingPermission = true;
        if (!LibraryStorage.request(activity)) { awaitingPermission = false; toast(R.string.library_permission_unavailable); }
    }
    boolean busy() { return busy; }
    void chooseForGuide() {
        if (!LibraryStorage.allowed()) requestPermission();
        else if (repository.root() == null) discover(true);
        else pick();
    }
    private void pick() {
        if (busy) return;
        if (!LibraryStorage.pick(activity, repository.root())) toast(R.string.folder_picker_unavailable);
    }
    /** Uses the only library found on storage; otherwise optionally continues to the picker. */
    private void discover(boolean pickOtherwise) {
        if (busy) return;
        busy = true; changed(); int token = ++request;
        worker.execute(() -> {
            List<File> found;
            try { found = LibraryStorage.discover(activity); }
            catch (IOException | SecurityException error) { found = List.of(); }
            List<File> result = found;
            main.post(() -> {
                if (released || token != request) return;
                busy = false;
                if (repository.root() != null) changed();
                else if (result.size() == 1) choose(result.get(0));
                else { changed(); if (pickOtherwise) pick(); }
            });
        });
    }
    void picked(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || released) return;
        File folder = LibraryStorage.resolve(activity, data.getData());
        if (folder == null) { toast(R.string.library_location_unsupported); return; }
        busy = true; changed(); int token = ++request;
        worker.execute(() -> {
            boolean ready;
            try { LibraryStorage.prepare(folder); ready = true; }
            catch (IOException | SecurityException error) { ready = false; }
            boolean prepared = ready;
            main.post(() -> {
                if (released || token != request) return;
                busy = false;
                if (prepared) choose(folder); else { changed(); toast(R.string.library_create_failed); }
            });
        });
    }
    private void choose(File folder) {
        if (imports.state().running()) imports.cancel();
        try { repository.choose(folder); }
        catch (IOException | SecurityException error) { toast(R.string.library_error); }
        changed();
    }
    void pickGames() {
        if (imports.state().root() != null) { importDialog.show(); return; }
        if (busy || repository.root() == null) return;
        if (!LibraryStorage.allowed()) { toast(R.string.library_permission); return; }
        pickerRoot = repository.root().getAbsolutePath();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { activity.startActivityForResult(intent, PICK_GAMES); }
        catch (android.content.ActivityNotFoundException | SecurityException error) {
            pickerRoot = null; toast(R.string.game_picker_unavailable);
        }
    }
    void gamesPicked(int resultCode, Intent data) {
        String selected = pickerRoot; pickerRoot = null;
        if (released || resultCode != Activity.RESULT_OK || data == null) return;
        if (!LibraryStorage.allowed()) { toast(R.string.library_permission); return; }
        File root = repository.root();
        if (root == null || !root.getAbsolutePath().equals(selected)) { toast(R.string.import_location_changed); return; }
        List<Uri> sources = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) {
            Uri uri = data.getClipData().getItemAt(i).getUri();
            if (uri != null && !sources.contains(uri)) sources.add(uri);
        }
        else if (data.getData() != null) sources.add(data.getData());
        if (sources.isEmpty()) return;
        importedCount = 0; imports.start(root, sources); importDialog.show();
    }
    private void importChanged() {
        LibraryImportSession.State state = imports.state();
        int added = state.count(R.string.import_added);
        if (added > importedCount && java.util.Objects.equals(state.root(), repository.root())) repository.refresh();
        importedCount = added;
        changed();
    }
    private void showPage(String next) {
        if (page.isEmpty()) { parentRow = settings.selectedRow(); parentScroll = settings.scroller().getScrollY(); }
        page = next; pageOffset = 0; changed(); settings.focusRow(0, 0);
    }
    boolean back() {
        if (!GROUP.equals(settings.groupId()) || page.isEmpty()) return false;
        page = ""; changed(); settings.focusRow(parentRow, parentScroll); return true;
    }
    void resetPage() { page = ""; pageOffset = 0; changed(); }
    private List<SettingsPanel.Row> hiddenRows() {
        List<SettingsPanel.Row> rows = new ArrayList<>();
        rows.add(info("title", R.string.hidden_games, () -> Integer.toString(repository.hidden().size())));
        List<RomRecord> hidden = repository.hidden();
        if (hidden.isEmpty()) rows.add(info("empty", R.string.hidden_empty, null));
        int start = pageStart(hidden.size());
        for (int i = start; i < Math.min(start + PAGE_SIZE, hidden.size()); i++) {
            RomRecord rom = hidden.get(i);
            rows.add(new SettingsPanel.Row(rom.id, rom.filename, SettingsPanel.Kind.ENTRY,
                    () -> language.text(R.string.restore_game), direction -> repository.setHidden(rom, false)));
        }
        paging(rows, hidden.size());
        return rows;
    }
    private SettingsPanel.Row info(String id, int label, java.util.function.Supplier<String> value) {
        return new SettingsPanel.Row(id, label, SettingsPanel.Kind.INFO, false, value, direction -> {});
    }
    private int pageStart(int count) {
        pageOffset = Math.min(pageOffset, count == 0 ? 0 : (count - 1) / PAGE_SIZE * PAGE_SIZE); return pageOffset;
    }
    private void paging(List<SettingsPanel.Row> rows, int count) {
        if (pageOffset > 0) rows.add(row("previous", R.string.previous_page, () -> {
            pageOffset -= PAGE_SIZE; changed(); settings.focusRow(0, 0);
        }));
        if (pageOffset + PAGE_SIZE < count) rows.add(row("next", R.string.next_page, () -> {
            pageOffset += PAGE_SIZE; changed(); settings.focusRow(0, 0);
        }));
    }
    void save(Bundle out) {
        out.putBoolean("library_awaiting_permission", awaitingPermission);
        out.putString("library_picker_root", pickerRoot); out.putString("library_page", page);
        out.putInt("library_parent_row", parentRow); out.putInt("library_parent_scroll", parentScroll);
        out.putInt("library_page_offset", pageOffset);
        out.putBoolean("library_import_dialog", importDialog.requested());
    }
    void restore(Bundle saved) {
        awaitingPermission = saved.getBoolean("library_awaiting_permission"); pickerRoot = saved.getString("library_picker_root");
        page = saved.getString("library_page", ""); parentRow = saved.getInt("library_parent_row");
        parentScroll = saved.getInt("library_parent_scroll"); pageOffset = saved.getInt("library_page_offset"); changed();
        importDialog.restore(saved.getBoolean("library_import_dialog"));
    }
    void pause() { importDialog.pause(); }
    void home() {
        importDialog.hide();
        if (!imports.state().running()) imports.clear();
    }
    boolean dispatchImportKey(android.view.KeyEvent event) { return importDialog.dispatchKey(event); }
    boolean importBack() { if (!importDialog.isShowing()) return false; importDialog.activate(); return true; }
    LibraryImportDialog importDialog() { return importDialog; }
    private void toast(int resource) { Toast.makeText(activity, language.text(resource), Toast.LENGTH_SHORT).show(); }
    void release() { released = true; importDialog.release(); imports.listen(null); worker.shutdown(); main.removeCallbacksAndMessages(null); }
}
