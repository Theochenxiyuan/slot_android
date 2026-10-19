package fyi.slot.android;

import android.os.FileObserver;
import android.os.Handler;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

/** Watches both parents and platform folders, including newly created directories. */
final class LibraryWatcher {
    private static final int EVENTS = FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.MOVED_FROM
            | FileObserver.CREATE | FileObserver.DELETE | FileObserver.DELETE_SELF | FileObserver.MOVE_SELF;
    private final Handler main;
    private final Consumer<Set<Cartridge.Platform>> changed;
    private final EnumSet<Cartridge.Platform> pending = EnumSet.noneOf(Cartridge.Platform.class);
    private final List<FileObserver> observers = new ArrayList<>();
    private final Runnable debounce;
    private boolean active;
    private volatile int generation;
    LibraryWatcher(Handler main, Consumer<Set<Cartridge.Platform>> changed) {
        this.main = main; this.changed = changed;
        debounce = () -> { if (active) { Set<Cartridge.Platform> batch = EnumSet.copyOf(pending); pending.clear(); this.changed.accept(batch); } };
    }
    void start(File root) {
        stop(); active = true;
        try {
            File canonicalRoot = root.getCanonicalFile();
            watch(root, EnumSet.allOf(Cartridge.Platform.class));
            for (Cartridge.Platform platform : Cartridge.Platform.values()) {
                watch(LibraryPaths.child(root, platform.name()), EnumSet.of(platform));
            }
            for (File base : LibraryPaths.assetRoots(root)) {
                if (!base.getCanonicalFile().equals(canonicalRoot)) {
                    watch(base, EnumSet.noneOf(Cartridge.Platform.class));
                }
                File labels = LibraryPaths.child(base, "Labels"), boxart = LibraryPaths.child(base, "Boxart");
                watch(labels, Set.of()); watch(boxart, Set.of());
                watch(LibraryPaths.child(base, "Config"), Set.of()); watch(LibraryPaths.child(base, "System"), Set.of());
                for (Cartridge.Platform platform : Cartridge.Platform.values()) {
                    watch(LibraryPaths.child(labels, platform.name()), Set.of());
                    watch(LibraryPaths.child(boxart, platform.name()), Set.of());
                }
            }
        } catch (IOException error) { android.util.Log.w("SlotLibrary", "Watcher unavailable", error); }
    }
    private void watch(File file, Set<Cartridge.Platform> platforms) {
        if (!file.isDirectory()) return;
        int token = generation;
        FileObserver observer = new FileObserver(file, EVENTS) {
            @Override public void onEvent(int event, String path) {
                // stopWatching also reports IN_IGNORED; restarting a watcher must not rescan the library.
                if ((event & EVENTS) == 0 || token != generation) return;
                // Ignore temporary upload files; appearance disk cache lives outside the root.
                if (path != null && path.startsWith(".")) return;
                main.post(() -> { if (active && token == generation) { pending.addAll(platforms); main.removeCallbacks(debounce); main.postDelayed(debounce, 750); } });
            }
        };
        observer.startWatching(); observers.add(observer);
    }
    void stop() {
        active = false; generation++; main.removeCallbacks(debounce);
        for (FileObserver observer : observers) observer.stopWatching();
        observers.clear();
        pending.clear();
    }
}
