package fyi.slot.android;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Coalesces queued requests and at most one rescan while a platform is active. */
final class ScanScheduler {
    private final Executor executor;
    private final Consumer<Set<Cartridge.Platform>> scan;
    private final EnumSet<Cartridge.Platform> pending = EnumSet.noneOf(Cartridge.Platform.class);
    private boolean running, closed, appearance;
    ScanScheduler(Executor executor, Consumer<Set<Cartridge.Platform>> scan) { this.executor = executor; this.scan = scan; }
    synchronized void request(Set<Cartridge.Platform> platforms) {
        if (closed) return;
        pending.addAll(platforms);
        if (platforms.isEmpty()) appearance = true;
        if (!running) { running = true; executor.execute(this::drain); }
    }
    private void drain() {
        for (;;) {
            Set<Cartridge.Platform> batch;
            synchronized (this) {
                if (closed || pending.isEmpty() && !appearance) { running = false; return; }
                batch = EnumSet.copyOf(pending); pending.clear(); appearance = false;
            }
            try { scan.accept(batch); }
            catch (RuntimeException error) { android.util.Log.e("SlotLibrary", "Scan failed", error); }
        }
    }
    synchronized void close() { closed = true; pending.clear(); }
}
