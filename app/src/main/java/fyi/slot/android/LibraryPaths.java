package fyi.slot.android;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Slot directory conventions shared by scanning, appearance and future import. */
final class LibraryPaths {
    static String nfc(String text) { return Normalizer.normalize(text, Normalizer.Form.NFC); }

    static File child(File parent, String name) throws IOException {
        File[] children = parent.listFiles();
        if (children == null) {
            if (parent.exists()) throw new IOException("Cannot enumerate " + parent);
            return new File(parent, name);
        }
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File file : children) if (file.getName().equalsIgnoreCase(name)) return safe(parent, file);
        return new File(parent, name);
    }

    static File safe(File root, File file) throws IOException {
        String base = root.getCanonicalPath() + File.separator;
        String expected = root.getCanonicalFile().toPath().resolve(root.getAbsoluteFile().toPath()
                .relativize(file.getAbsoluteFile().toPath())).normalize().toString();
        if (!file.getCanonicalPath().startsWith(base)
                || !expected.equals(file.getCanonicalPath())) throw new IOException("Unsafe path " + file);
        return file;
    }

    static boolean within(File root, File file) {
        try { return file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator); }
        catch (IOException error) { return false; }
    }

    /** Resolves the Slot asset directory (Labels, Boxart, Config) on the volume containing root, or root itself. */
    static File assetsRoot(File root) {
        if (root == null) return null;
        try {
            File current = root.getCanonicalFile();
            if ("Slot".equalsIgnoreCase(current.getName())) return current;
            for (File dir = current; dir != null; dir = dir.getParentFile()) {
                String name = dir.getName();
                if ("Roms".equalsIgnoreCase(name) || "Games".equalsIgnoreCase(name)) {
                    File parent = dir.getParentFile();
                    if (parent != null) {
                        if ("Emulation".equalsIgnoreCase(parent.getName()) && parent.getParentFile() != null) {
                            parent = parent.getParentFile();
                        }
                        return child(parent, "Slot");
                    }
                }
                if (new File(dir, "Slot").isDirectory() && new File(dir, "Android").isDirectory()) {
                    return child(dir, "Slot");
                }
            }
            return current;
        } catch (IOException error) {
            return root;
        }
    }

    static List<File> assetRoots(File root) {
        File primary = assetsRoot(root);
        try {
            if (primary != null && root != null && !primary.getCanonicalFile().equals(root.getCanonicalFile())) {
                return List.of(primary, root);
            }
        } catch (IOException ignored) { }
        return primary != null ? List.of(primary) : List.of();
    }

    static boolean hasGamesLayout(File folder) throws IOException {
        if (folder == null || !folder.isDirectory()) return false;
        if (child(folder, "Games").isDirectory()) return true;
        for (Cartridge.Platform platform : Cartridge.Platform.values()) {
            if (child(folder, platform.name()).isDirectory()) return true;
        }
        return false;
    }

    static File games(File root, Cartridge.Platform platform) throws IOException {
        File gamesDir = child(root, "Games");
        File nested = child(gamesDir, platform.name());
        if (nested.isDirectory()) return nested;
        File direct = child(root, platform.name());
        if (direct.isDirectory()) return direct;
        if (gamesDir.isDirectory()) return nested;
        for (Cartridge.Platform other : Cartridge.Platform.values()) {
            if (child(root, other.name()).isDirectory()) return new File(root, platform.name().toLowerCase(java.util.Locale.ROOT));
        }
        return nested;
    }

    static File label(File root, Cartridge.Platform platform, String stem) throws IOException {
        return labels(root, platform).get(nfc(stem));
    }
    static Map<String, File> labels(File root, Cartridge.Platform platform) throws IOException {
        return images(root, "Labels", platform);
    }
    static Map<String, File> boxarts(File root, Cartridge.Platform platform) throws IOException {
        return images(root, "Boxart", platform);
    }
    /** PNG files of one platform folder under Labels or Boxart, keyed by composed name. */
    static Map<String, File> images(File root, String folderName, Cartridge.Platform platform) throws IOException {
        Map<String, File> images = new HashMap<>();
        List<File> roots = assetRoots(root);
        for (int i = roots.size() - 1; i >= 0; i--) {
            images.putAll(imagesIn(roots.get(i), folderName, platform));
        }
        return images;
    }
    static Map<String, File> imagesIn(File base, String folderName, Cartridge.Platform platform) throws IOException {
        File folder = child(child(base, folderName), platform.name());
        Map<String, File> images = new HashMap<>();
        File[] files = folder.listFiles();
        if (files == null && folder.exists()) throw new IOException("Cannot enumerate " + folderName);
        if (files != null) for (File file : files) {
            if (file.getName().startsWith(".") || !file.getName().endsWith(".png") || !file.isFile()) continue;
            String stem = nfc(file.getName().substring(0, file.getName().length() - 4));
            if (images.put(stem, safe(folder, file)) != null) throw new IOException("Ambiguous " + folderName + " name");
        }
        return images;
    }

    static boolean accepts(Cartridge.Platform platform, String name) {
        if (name.startsWith(".")) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return platform == Cartridge.Platform.GBA ? lower.endsWith(".gba")
                : lower.endsWith(".gb") || lower.endsWith(".gbc");
    }

    static String identity(File root, Cartridge.Platform platform, String filename) {
        return root.getAbsolutePath() + "|" + platform.name() + "/" + nfc(filename);
    }
}
