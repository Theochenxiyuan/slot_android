package fyi.slot.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;

/** A failed or cancelled enumeration never produces a successful empty snapshot. */
final class LibraryScanner {
    List<RomRecord> scan(File root, Cartridge.Platform platform, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
        if (!root.isDirectory() || !root.canRead()) throw new IOException("Root unavailable");
        File folder = LibraryPaths.games(root, platform);
        if (!folder.exists()) return List.of();
        ImportWriter.cleanup(folder);
        File[] files = folder.listFiles();
        if (files == null) throw new IOException("Cannot enumerate platform");
        List<RomRecord> records = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (File file : files) {
            if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
            if (!LibraryPaths.accepts(platform, file.getName()) || !file.isFile()) continue;
            LibraryPaths.safe(folder, file);
            long size = file.length(), modified = file.lastModified();
            if (size < 0x150) continue;
            byte[] head = new byte[0x150];
            try (java.io.DataInputStream input = new java.io.DataInputStream(new FileInputStream(file))) { input.readFully(head); }
            if (size != file.length() || modified != file.lastModified()) throw new IOException("File changed during scan");
            String path = root.toPath().relativize(file.toPath()).toString();
            RomRecord record = new RomRecord(root.getAbsolutePath(), path, platform, size, modified, head, null, false);
            if (!identities.add(record.id)) throw new IOException("Ambiguous NFC filename");
            records.add(record);
        }
        records.sort((a, b) -> { int order = RomNaming.compare(a.title, b.title);
            return order == 0 ? a.filename.compareTo(b.filename) : order; });
        if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
        return records;
    }
    static long crc(RomRecord record, BooleanSupplier cancelled) throws IOException {
        File file = record.file();
        CRC32 crc = new CRC32(); byte[] buffer = new byte[65536]; long total = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
                crc.update(buffer, 0, count); total += count;
            }
        }
        if (total != record.size || file.length() != record.size || file.lastModified() != record.modified)
            throw new IOException("File changed during CRC");
        return crc.getValue();
    }
}
