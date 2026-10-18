package fyi.slot.android;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Activity-independent batch state, retained across Activity recreation. */
final class LibraryImportSession {
    record Item(String name, int message) {}
    record State(File root, boolean running, boolean cancelled, int total, String current,
                 long bytes, long size, List<Item> items) {
        int count(int message) { return (int) items.stream().filter(item -> item.message == message).count(); }
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled, released;
    private volatile State state = new State(null, false, false, 0, "", 0, -1, List.of());
    private Runnable listener;
    LibraryImportSession(Context context) { this.context = context.getApplicationContext(); }
    State state() { return state; }
    void listen(Runnable listener) { this.listener = listener; }
    void cancel() { cancelled = true; }
    boolean cancellationRequested() { return cancelled; }
    void clear() {
        if (!state.running) publish(new State(null, false, false, 0, "", 0, -1, List.of()));
    }
    void start(File root, List<Uri> sources) {
        if (state.running || released || sources.isEmpty()) return;
        cancelled = false;
        publish(new State(root, true, false, sources.size(), "", 0, -1, List.of()));
        worker.execute(() -> run(root, List.copyOf(sources)));
    }
    private void run(File root, List<Uri> sources) {
        List<Item> results = new ArrayList<>();
        for (Uri uri : sources) {
            if (cancelled || released) break;
            String name = uri.getLastPathSegment(); long size = -1;
            int message;
            try {
                if (!"content".equals(uri.getScheme())) throw new ImportWriter.Failure(ImportWriter.Problem.ACCESS);
                try (Cursor cursor = context.getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int titleColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (titleColumn >= 0) name = cursor.getString(titleColumn);
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
                    }
                }
                ImportWriter.platform(name);
                ensureDestination(root);
                String current = name; long length = size;
                publish(new State(root, true, false, sources.size(), current, 0, size, List.copyOf(results)));
                try (InputStream source = context.getContentResolver().openInputStream(uri)) {
                    if (source == null) throw new ImportWriter.Failure(ImportWriter.Problem.ACCESS);
                    try (InputStream input = new java.io.FilterInputStream(source) {
                        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                            ensureDestination(root); return super.read(buffer, offset, length);
                        }
                    }) {
                        long[] last = {0};
                        ImportWriter.Result written = ImportWriter.write(root, name, size, input,
                                () -> cancelled || released,
                                bytes -> {
                                    long now = android.os.SystemClock.uptimeMillis();
                                    if (now - last[0] >= 150) {
                                        last[0] = now;
                                        publish(new State(root, true, false, sources.size(), current, bytes, length, List.copyOf(results)));
                                    }
                                });
                        message = written.outcome() == ImportWriter.Outcome.ADDED ? R.string.import_added : R.string.import_skipped;
                    }
                }
            } catch (ImportWriter.Failure error) { message = problemText(error.problem); }
            catch (SecurityException error) { message = R.string.import_access; }
            catch (IOException | RuntimeException error) { message = R.string.import_failed; }
            results.add(new Item(name == null ? "—" : name, message));
            publish(new State(root, true, cancelled, sources.size(), "", 0, -1, List.copyOf(results)));
        }
        publish(new State(root, false, cancelled || released, sources.size(), "", 0, -1, List.copyOf(results)));
    }
    static int problemText(ImportWriter.Problem problem) {
        return switch (problem) {
            case NAME -> R.string.import_name;
            case FORMAT -> R.string.import_format;
            case SIZE -> R.string.import_size;
            case SHORT, LENGTH -> R.string.import_incomplete;
            case CANCELLED -> R.string.import_cancelled;
            case ACCESS -> R.string.import_access;
            case STORAGE -> R.string.library_offline;
            case IO -> R.string.import_failed;
        };
    }
    private void ensureDestination(File root) throws ImportWriter.Failure {
        if (!LibraryStorage.allowed() && !LibraryPaths.within(context.getFilesDir(), root))
            throw new ImportWriter.Failure(ImportWriter.Problem.ACCESS);
        if (!root.isDirectory() || !LibraryStorage.online(context, root))
            throw new ImportWriter.Failure(ImportWriter.Problem.STORAGE);
    }
    private void publish(State next) {
        state = next;
        main.post(() -> { if (!released && listener != null) listener.run(); });
    }
    void release() { released = true; cancel(); listener = null; worker.shutdown(); main.removeCallbacksAndMessages(null); }
}
