package fyi.slot.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Deletes ROMs and exclusive appearance resources. Save files are never touched. */
final class LibraryFiles {
    private record Staged(File original, File temporary) {}
    private record StagedFile(File base, File file) {}
    private record ShellTarget(File base, File file, String oldText, String nextText) {}

    /** Caller holds ImportWriter.LOCK, shared with uploads and appearance writes. */
    static void delete(File root, Cartridge.Platform platform, File rom) throws IOException {
        String stem = DeviceApi.stemOf(rom.getName());
        boolean sharedPlatform = shared(root, platform, stem, rom);
        boolean sharedShell = false;
        for (Cartridge.Platform p : Cartridge.Platform.values()) sharedShell |= shared(root, p, stem, rom);
        List<StagedFile> files = new ArrayList<>();
        List<File> assetRoots = LibraryPaths.assetRoots(root);
        if (!sharedPlatform) for (String kind : List.of("Labels", "Boxart")) {
            for (File base : assetRoots) {
                File image = LibraryPaths.imagesIn(base, kind, platform).get(LibraryPaths.nfc(stem));
                if (image != null) files.add(new StagedFile(base, image));
            }
        }
        files.add(new StagedFile(root, rom));
        List<ShellTarget> shellTargets = new ArrayList<>();
        for (File base : assetRoots) {
            File shellFile = LibraryPaths.child(LibraryPaths.child(base, "Labels"), "cart_shell.txt");
            String oldText = text(base, shellFile);
            String nextText = sharedShell ? oldText : removeShell(oldText, stem);
            if (!nextText.equals(oldText)) shellTargets.add(new ShellTarget(base, shellFile, oldText, nextText));
        }
        List<Staged> staged = new ArrayList<>();
        try {
            for (StagedFile item : files) {
                LibraryPaths.safe(item.base(), item.file());
                File temporary = Files.createTempFile(item.file().getParentFile().toPath(), ".slot-delete-", ".tmp").toFile();
                try { Files.move(item.file().toPath(), temporary.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException error) { Files.deleteIfExists(temporary.toPath()); throw error; }
                staged.add(new Staged(item.file(), temporary));
            }
            for (ShellTarget target : shellTargets) {
                DeviceApi.commit(target.base(), target.file(), target.nextText().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException error) {
            for (int i = staged.size() - 1; i >= 0; i--) {
                Staged file = staged.get(i);
                try { Files.move(file.temporary().toPath(), file.original().toPath()); }
                catch (IOException rollback) { error.addSuppressed(rollback); }
            }
            throw error;
        }
        for (Staged file : staged) Files.deleteIfExists(file.temporary().toPath());
    }

    private static boolean shared(File root, Cartridge.Platform platform, String stem, File excluded) throws IOException {
        File folder = LibraryPaths.games(root, platform);
        File[] files = folder.listFiles();
        if (files == null) { if (folder.exists()) throw new IOException("Cannot list games"); return false; }
        for (File file : files) if (!file.equals(excluded) && LibraryPaths.accepts(platform, file.getName())
                && file.isFile() && LibraryPaths.nfc(DeviceApi.stemOf(file.getName())).equals(LibraryPaths.nfc(stem))) return true;
        return false;
    }

    private static String text(File root, File file) throws IOException {
        LibraryPaths.safe(root, file);
        if (!file.exists()) return "";
        if (!file.isFile() || file.length() > DeviceApi.SHELLS_MAX) throw new IOException("Invalid shell settings");
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String removeShell(String text, String stem) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("(?<=\n)", -1)) {
            String trimmed = line.trim();
            int equal = trimmed.indexOf('=');
            if (equal > 0 && !trimmed.startsWith("#") && !trimmed.startsWith(";") && !trimmed.startsWith("[")
                    && LibraryPaths.nfc(trimmed.substring(0, equal).trim()).equals(LibraryPaths.nfc(stem))) continue;
            out.append(line);
        }
        return out.toString();
    }
}
