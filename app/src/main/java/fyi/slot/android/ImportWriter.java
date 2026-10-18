package fyi.slot.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Bounded streaming writes shared by document imports and computer uploads. */
final class ImportWriter {
    static final long MAX_BYTES = 64L * 1024 * 1024;
    enum Outcome { ADDED, SKIPPED }
    enum Problem { NAME, FORMAT, SIZE, SHORT, LENGTH, CANCELLED, STORAGE, ACCESS, IO }
    static final class Failure extends IOException {
        final Problem problem;
        Failure(Problem problem) { super(problem.name()); this.problem = problem; }
    }
    record Result(Outcome outcome, Cartridge.Platform platform, File file) {}
    static final Object LOCK = new Object();
    private static final Set<String> ACTIVE = new HashSet<>();

    static boolean validName(String name) {
        return name != null && !name.isBlank() && !name.startsWith(".") && !name.endsWith(".") && !name.endsWith(" ")
                && LibraryPaths.nfc(name).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 240
                && name.codePoints().noneMatch(c -> c < 32 || c == 127 || "/\\:*?\"<>|".indexOf(c) >= 0);
    }

    static Cartridge.Platform platform(String name) throws Failure {
        if (!validName(name)) throw new Failure(Problem.NAME);
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gba")) return Cartridge.Platform.GBA;
        if (lower.endsWith(".gbc")) return Cartridge.Platform.GBC;
        if (lower.endsWith(".gb")) return Cartridge.Platform.GB;
        throw new Failure(Problem.FORMAT);
    }

    static Result write(File root, String name, long expected, InputStream input,
                        BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        Cartridge.Platform platform = platform(name);
        if (expected > MAX_BYTES) throw new Failure(Problem.SIZE);
        if (expected >= 0 && expected < 0x150) throw new Failure(Problem.SHORT);
        check(cancelled);
        if (!root.isDirectory()) throw new Failure(Problem.STORAGE);
        File folder = LibraryPaths.games(root, platform);
        LibraryPaths.safe(root, folder);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new Failure(Problem.STORAGE);
        synchronized (LOCK) {
            File existing = existing(folder, LibraryPaths.nfc(name));
            if (existing != null) return new Result(Outcome.SKIPPED, platform, LibraryPaths.safe(root, existing));
        }
        File target = LibraryPaths.safe(root, new File(folder, LibraryPaths.nfc(name)));
        File part = LibraryPaths.safe(root, new File(folder, "." + target.getName() + ".part"));
        synchronized (LOCK) {
            File existing = existing(folder, target.getName());
            if (existing != null) return new Result(Outcome.SKIPPED, platform, LibraryPaths.safe(root, existing));
            if (ACTIVE.contains(part.getAbsolutePath()) || part.exists()) throw new Failure(Problem.IO);
            ACTIVE.add(part.getAbsolutePath());
        }
        boolean owned = false;
        try {
            if (!part.createNewFile()) throw new Failure(Problem.IO);
            owned = true;
            long total = 0;
            byte[] buffer = new byte[65536];
            try (FileOutputStream output = new FileOutputStream(part)) {
                while (true) {
                    check(cancelled);
                    int count = input.read(buffer);
                    if (count < 0) break;
                    if (count == 0) continue;
                    total += count;
                    if (total > MAX_BYTES) throw new Failure(Problem.SIZE);
                    if (expected >= 0 && total > expected) throw new Failure(Problem.LENGTH);
                    output.write(buffer, 0, count);
                    progress.accept(total);
                }
                if (total < 0x150) throw new Failure(Problem.SHORT);
                if (expected >= 0 && total != expected) throw new Failure(Problem.LENGTH);
                output.getFD().sync();
            }
            synchronized (LOCK) {
                check(cancelled);
                LibraryPaths.safe(root, folder); LibraryPaths.safe(root, part);
                if (!root.isDirectory() || part.length() != total) throw new Failure(Problem.STORAGE);
                File existing = existing(folder, target.getName());
                if (existing != null) return new Result(Outcome.SKIPPED, platform, LibraryPaths.safe(root, existing));
                LibraryPaths.safe(root, target);
                Files.move(part.toPath(), target.toPath());
            }
            return new Result(Outcome.ADDED, platform, target);
        } finally {
            synchronized (LOCK) {
                ACTIVE.remove(part.getAbsolutePath());
                if (owned && part.exists() && !part.delete()) throw new Failure(Problem.IO);
            }
        }
    }

    private static File existing(File folder, String name) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) throw new Failure(Problem.STORAGE);
        for (File file : files) if (LibraryPaths.nfc(file.getName()).equalsIgnoreCase(name)) return file;
        return null;
    }
    private static void check(BooleanSupplier cancelled) throws Failure {
        if (cancelled.getAsBoolean()) throw new Failure(Problem.CANCELLED);
    }
    static void cleanup(File folder) throws IOException {
        synchronized (LOCK) {
            File[] files = folder.listFiles();
            if (files == null) return;
            for (File file : files) {
                String name = file.getName();
                if (!name.startsWith(".") || !name.endsWith(".part") || ACTIVE.contains(file.getAbsolutePath())) continue;
                LibraryPaths.safe(folder, file);
                if (file.isFile() && !file.delete()) throw new Failure(Problem.IO);
            }
        }
    }
}
