package fyi.slot.android;

import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process notice that the computer connection changed files in a library. */
final class LibraryEvents {
    interface Listener { void changed(File root, Set<Cartridge.Platform> platforms); }
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LibraryEvents() { }

    static void add(Listener listener) { LISTENERS.addIfAbsent(listener); }
    static void remove(Listener listener) { LISTENERS.remove(listener); }

    /** An empty set means only labels or shell settings changed. */
    static void post(File root, Set<Cartridge.Platform> platforms) {
        Set<Cartridge.Platform> copy = platforms.isEmpty() ? EnumSet.noneOf(Cartridge.Platform.class) : EnumSet.copyOf(platforms);
        MAIN.post(() -> { for (Listener listener : LISTENERS) listener.changed(root, copy); });
    }
}
