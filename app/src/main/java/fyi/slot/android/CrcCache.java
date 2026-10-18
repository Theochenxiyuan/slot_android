package fyi.slot.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/** Background CRC32 for uploaded and listed ROMs; the newest request is computed first. */
final class CrcCache {
    private final Map<String, Long> done = new HashMap<>();
    private final Map<String, Boolean> failed = new HashMap<>();
    private final Deque<File> queue = new ArrayDeque<>();
    private final Map<String, File> queued = new HashMap<>();
    private final Thread worker;
    private boolean closed;

    CrcCache() {
        worker = new Thread(this::run, "slot-crc");
        worker.setDaemon(true);
        worker.start();
    }

    private static String key(File file) { return file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified(); }

    /** The CRC when known within the wait, otherwise null while it is still being computed. */
    Long get(File file, long waitMillis) throws InterruptedException, IOException {
        String key = key(file);
        long deadline = System.currentTimeMillis() + waitMillis;
        synchronized (this) {
            Long known = done.get(key);
            if (known != null) return known;
            if (failed.remove(key) != null) throw new IOException("Unreadable ROM");
            if (closed) return null;
            if (queued.containsKey(key)) queue.remove(queued.get(key));
            queue.addFirst(file);
            queued.put(key, file);
            notifyAll();
            for (long left = waitMillis; left > 0; left = deadline - System.currentTimeMillis()) {
                wait(left);
                known = done.get(key);
                if (known != null || closed) return known;
                if (failed.remove(key) != null) throw new IOException("Unreadable ROM");
            }
            return null;
        }
    }

    private void run() {
        while (true) {
            File file;
            synchronized (this) {
                while (!closed && queue.isEmpty()) {
                    try { wait(); } catch (InterruptedException interrupted) { return; }
                }
                if (closed) return;
                file = queue.pollFirst();
            }
            String key = key(file);
            Long value = null;
            try { value = compute(file); } catch (IOException ignored) { }
            synchronized (this) {
                queued.remove(key);
                if (done.size() > 4096) { done.clear(); failed.clear(); }
                if (value == null) failed.put(key, true);
                else if (key.equals(key(file))) done.put(key, value);
                notifyAll();
            }
        }
    }

    private static long compute(File file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[65536];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) crc.update(buffer, 0, count);
        }
        return crc.getValue();
    }

    synchronized void close() { closed = true; queue.clear(); queued.clear(); notifyAll(); worker.interrupt(); }
}
