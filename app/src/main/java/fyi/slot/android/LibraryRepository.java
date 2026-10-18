package fyi.slot.android;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Owns permissions/storage state, scan jobs and immutable shelf snapshots. */
final class LibraryRepository {
    enum Status { UNCONFIGURED, PERMISSION, LOADING, READY, EMPTY, OFFLINE, ERROR }
    interface Listener { void changed(Status status, List<Cartridge> carts); }
    private final Activity activity;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LibraryIndex index;
    private final ScanScheduler scheduler;
    private final LibraryWatcher watcher;
    private final StorageManager storage;
    private final StorageManager.StorageVolumeCallback volumeCallback = new StorageManager.StorageVolumeCallback() {
        @Override public void onStateChanged(StorageVolume volume) { refresh(); }
    };
    private final LibraryEvents.Listener uploads = this::uploaded;
    private volatile File root;
    private volatile int generation;
    private volatile boolean released;
    private java.util.function.BooleanSupplier permissionForChecks;
    private boolean started;
    private Listener listener;
    private Status status = Status.UNCONFIGURED;
    private volatile List<Cartridge> carts = List.of();
    private volatile List<RomRecord> hidden = List.of();

    LibraryRepository(Activity activity) {
        this.activity = activity;
        preferences = activity.getSharedPreferences("library", 0);
        String saved = preferences.getString("root", null);
        root = saved == null ? null : new File(saved);
        index = new LibraryIndex(activity); scheduler = new ScanScheduler(worker, this::scan);
        watcher = new LibraryWatcher(main, platforms -> {
            if (root != null && started) watcherRestart();
            scheduler.request(platforms);
        });
        storage = activity.getSystemService(StorageManager.class);
    }
    void setListener(Listener listener) { this.listener = listener; }
    File root() { return root; }
    Status status() { return status; }
    List<Cartridge> snapshot() { return carts; }
    List<RomRecord> hidden() { return hidden; }
    void setHidden(RomRecord rom, boolean value) {
        if (released || root == null || !rom.root.equals(root.getAbsolutePath())) return;
        int token = generation;
        worker.execute(() -> {
            if (released || token != generation) return;
            try {
                index.setHidden(rom, value);
                scheduler.request(EnumSet.allOf(Cartridge.Platform.class));
            } catch (RuntimeException error) {
                main.post(() -> { if (!released && token == generation) publish(Status.ERROR, carts); });
            }
        });
    }
    void start() {
        if (started) return;
        started = true;
        LibraryEvents.add(uploads);
        if (storage != null) storage.registerStorageVolumeCallback(activity.getMainExecutor(), volumeCallback);
        refresh();
    }
    void stop() {
        started = false; watcher.stop(); LibraryEvents.remove(uploads);
        if (storage != null) storage.unregisterStorageVolumeCallback(volumeCallback);
    }
    /** Computer uploads and Studio saves rescan at once instead of waiting for the folder watcher. */
    private void uploaded(File changedRoot, Set<Cartridge.Platform> platforms) {
        if (started && root != null && root.equals(changedRoot)) scheduler.request(platforms);
    }
    void choose(File next) throws IOException {
        File canonical = next.getCanonicalFile();
        if (!canonical.isDirectory() || !canonical.canRead()) throw new IOException("Root unavailable");
        root = canonical; generation++;
        preferences.edit().putString("root", root.getAbsolutePath()).apply();
        carts = List.of(); hidden = List.of(); refresh();
    }
    void refresh() {
        if (released) return;
        if (root == null) { publish(Status.UNCONFIGURED, List.of()); return; }
        if (started && permitted(root) && LibraryStorage.online(activity, root)) watcher.start(root);
        else watcher.stop();
        publish(Status.LOADING, carts);
        scheduler.request(EnumSet.allOf(Cartridge.Platform.class));
    }
    private void watcherRestart() {
        if (root != null && permitted(root) && LibraryStorage.online(activity, root)) watcher.start(root);
    }
    private boolean permitted(File selected) {
        return permissionForChecks != null ? permissionForChecks.getAsBoolean()
                : LibraryStorage.allowed() || LibraryPaths.within(activity.getFilesDir(), selected);
    }
    void permissionForChecks(java.util.function.BooleanSupplier permission) { permissionForChecks = permission; }
    private void scan(Set<Cartridge.Platform> platforms) {
        File selected = root; int token = generation;
        if (selected == null || released) return;
        try {
            Status next;
            boolean permitted = permitted(selected);
            if (!permitted) next = Status.PERMISSION;
            else if (!LibraryStorage.online(activity, selected) || !selected.isDirectory()) next = Status.OFFLINE;
            else {
                next = Status.READY;
                LibraryScanner scanner = new LibraryScanner();
                for (Cartridge.Platform platform : platforms) {
                    try {
                        List<RomRecord> scanned = scanner.scan(selected, platform, () -> released || generation != token);
                        if (released || generation != token) return;
                        if (!LibraryStorage.online(activity, selected)) { next = Status.OFFLINE; break; }
                        if (!permitted(selected)) {
                            next = Status.PERMISSION; break;
                        }
                        index.merge(selected.getAbsolutePath(), platform, scanned);
                    } catch (IOException | SecurityException error) { next = Status.ERROR; android.util.Log.w("SlotLibrary", "Platform scan failed", error); }
                }
            }
            List<RomRecord> records = index.read(selected.getAbsolutePath());
            Map<String, ShellConfig.Choice> shells = Map.of();
            Map<Cartridge.Platform, Map<String, File>> labels = new java.util.EnumMap<>(Cartridge.Platform.class);
            Map<Cartridge.Platform, Map<String, File>> boxarts = new java.util.EnumMap<>(Cartridge.Platform.class);
            if (next == Status.READY || next == Status.ERROR) {
                try { shells = loadShells(selected); }
                catch (IOException error) { next = Status.ERROR; }
                for (Cartridge.Platform platform : Cartridge.Platform.values()) {
                    try { labels.put(platform, LibraryPaths.labels(selected, platform)); }
                    catch (IOException error) { next = Status.ERROR; }
                    try { boxarts.put(platform, LibraryPaths.boxarts(selected, platform)); }
                    catch (IOException error) { android.util.Log.w("SlotLibrary", "Box art unavailable", error); }
                }
            }
            List<Cartridge> result = new ArrayList<>();
            for (RomRecord rom : records) if (!rom.hidden) {
                if (next == Status.OFFLINE || next == Status.PERMISSION) {
                    Cartridge previous = null;
                    for (Cartridge cart : carts) if (cart.id.equals(rom.id)) { previous = cart; break; }
                    if (previous != null) { result.add(previous); continue; }
                }
                File label = labels.getOrDefault(rom.platform, Map.of()).get(RomNaming.stem(rom.filename));
                ShellConfig.Choice choice = shells.get(RomNaming.stem(rom.filename));
                if (choice == null) choice = ShellConfig.defaults(rom);
                File boxart = boxarts.getOrDefault(rom.platform, Map.of()).get(RomNaming.stem(rom.filename));
                result.add(new Cartridge(rom, choice, label, boxart));
            }
            if (next == Status.READY && result.isEmpty()) next = Status.EMPTY;
            Status resolved = next;
            List<RomRecord> hiddenResult = records.stream().filter(rom -> rom.hidden).collect(java.util.stream.Collectors.toList());
            main.post(() -> {
                if (!released && generation == token && selected.equals(root)) {
                    hidden = hiddenResult; publish(resolved, result);
                }
            });
        } catch (RuntimeException error) {
            android.util.Log.e("SlotLibrary", "Index failed", error);
            main.post(() -> { if (!released && generation == token) publish(Status.ERROR, carts); });
        }
    }
    static Map<String, ShellConfig.Choice> loadShells(File root) throws IOException {
        StringBuilder configText = new StringBuilder(), labelsText = new StringBuilder();
        List<File> roots = LibraryPaths.assetRoots(root);
        for (int i = roots.size() - 1; i >= 0; i--) {
            File base = roots.get(i);
            File config = LibraryPaths.child(LibraryPaths.child(base, "Config"), "cart_shell.txt");
            if (!config.exists()) config = LibraryPaths.child(LibraryPaths.child(base, "System"), "cart_shell.txt");
            File labels = LibraryPaths.child(LibraryPaths.child(base, "Labels"), "cart_shell.txt");
            String c = readText(config), l = readText(labels);
            if (!c.isEmpty()) configText.append(c).append('\n');
            if (!l.isEmpty()) labelsText.append(l).append('\n');
        }
        return ShellConfig.layered(configText.toString(), labelsText.toString());
    }
    private static String readText(File file) throws IOException {
        if (!file.exists()) return "";
        if (file.length() > 1024 * 1024) throw new IOException("INI too large");
        return new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }
    void crc(RomRecord rom, Consumer<String> result) {
        if (released) return;
        int token = generation;
        worker.execute(() -> {
            String value;
            try {
                LibraryPaths.safe(new File(rom.root), rom.file());
                Long cached = rom.crc;
                for (RomRecord saved : index.read(rom.root)) if (saved.path.equals(rom.path) && saved.size == rom.size && saved.modified == rom.modified) {
                    cached = saved.crc; break;
                }
                long crc = cached != null ? cached : LibraryScanner.crc(rom, () -> released || generation != token);
                if (rom.crc == null) index.saveCrc(rom, crc);
                value = String.format(java.util.Locale.ROOT, "%08X", crc);
            } catch (IOException | SecurityException error) { value = "—"; }
            String resolved = value;
            main.post(() -> { if (!released && generation == token) result.accept(resolved); });
        });
    }
    private void publish(Status next, List<Cartridge> result) {
        status = next; carts = List.copyOf(result);
        if (listener != null) listener.changed(next, carts);
    }
    void release() {
        stop(); released = true; generation++; scheduler.close();
        worker.execute(index::close); worker.shutdown(); main.removeCallbacksAndMessages(null);
    }
}
