package fyi.slot.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** The Studio's device data source: library listing, ROM heads and CRCs, labels, shells and uploads. */
final class DeviceApi {
    record Request(String method, List<String> path, Map<String, String> query, long length, InputStream body) {}
    record Reply(int status, String type, byte[] body) {
        static Reply json(int status, String json) { return new Reply(status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8)); }
        static Reply error(int status, String code) { return json(status, Json.error(code)); }
    }
    static final int HEAD = 0x150, LABEL_MAX = 8 * 1024 * 1024, BOXART_MAX = 4 * 1024 * 1024, SHELLS_MAX = 1024 * 1024;
    private static final Cartridge.Platform[] ORDER = {Cartridge.Platform.GBA, Cartridge.Platform.GBC, Cartridge.Platform.GB};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final Object WRITE_LOCK = ImportWriter.LOCK;
    private final Supplier<File> root;
    private final BiConsumer<File, Set<Cartridge.Platform>> changed;
    private final CrcCache crcs;
    private final long crcWait;

    DeviceApi(Supplier<File> root, BiConsumer<File, Set<Cartridge.Platform>> changed, CrcCache crcs, long crcWait) {
        this.root = root; this.changed = changed; this.crcs = crcs; this.crcWait = crcWait;
    }

    Reply handle(Request request) throws IOException {
        List<String> path = request.path();
        String resource = path.isEmpty() ? "" : path.get(0);
        File library = root.get();
        boolean known = switch (resource) { case "library", "games", "labels", "boxart", "shells", "import" -> true; default -> false; };
        if (!known) return Reply.error(404, "not_found");
        if (library == null || !library.isDirectory()) return Reply.error(503, "library");
        String method = request.method();
        return switch (resource) {
            case "library" -> path.size() == 1 && method.equals("GET") ? library(library) : notAllowed(path.size() == 1);
            case "games" -> games(library, request);
            case "labels" -> path.size() == 3 ? image(library, request, "Labels", LABEL_MAX) : Reply.error(404, "not_found");
            case "boxart" -> path.size() == 3 ? image(library, request, "Boxart", BOXART_MAX) : Reply.error(404, "not_found");
            case "shells" -> path.size() == 1 ? shells(library, request) : Reply.error(404, "not_found");
            default -> path.size() == 3 && method.equals("PUT") ? upload(library, request) : notAllowed(path.size() == 3);
        };
    }

    private static Reply notAllowed(boolean shape) { return shape ? Reply.error(405, "method") : Reply.error(404, "not_found"); }

    private Reply games(File library, Request request) throws IOException {
        List<String> path = request.path();
        if (path.size() == 4 && request.method().equals("GET")) return game(library, path);
        boolean delete = path.size() == 3 && request.method().equals("DELETE");
        if (!delete) return notAllowed(path.size() == 3 || path.size() == 4);
        Cartridge.Platform platform = platform(path.get(1));
        synchronized (WRITE_LOCK) {
            File file = rom(library, platform, path.get(2));
            if (file == null) return Reply.error(404, "not_found");
            LibraryFiles.delete(library, platform, file);
            changed.accept(library, EnumSet.of(platform));
            return Reply.json(200, "{\"result\":\"deleted\"}");
        }
    }

    static Cartridge.Platform platform(String name) {
        for (Cartridge.Platform platform : Cartridge.Platform.values()) if (platform.name().equals(name)) return platform;
        return null;
    }

    private Reply library(File root) throws IOException {
        StringBuilder carts = new StringBuilder("["), labels = new StringBuilder("["), boxarts = new StringBuilder("[");
        for (Cartridge.Platform platform : ORDER) {
            File folder = LibraryPaths.games(root, platform);
            File[] files = folder.listFiles();
            if (files == null && folder.exists()) throw new IOException("Cannot enumerate " + folder);
            List<File> sorted = files == null ? new ArrayList<>() : new ArrayList<>(List.of(files));
            sorted.sort((a, b) -> a.getName().compareTo(b.getName()));
            for (File file : sorted) {
                if (!LibraryPaths.accepts(platform, file.getName()) || !playable(root, file)) continue;
                if (carts.length() > 1) carts.append(',');
                carts.append(Json.object().put("platform", platform.name()).put("file", file.getName())
                        .put("stem", stemOf(file.getName())).put("size", file.length()).end());
            }
            for (String stem : LibraryPaths.labels(root, platform).keySet()) {
                if (labels.length() > 1) labels.append(',');
                labels.append(Json.object().put("platform", platform.name()).put("stem", stem).end());
            }
            for (String stem : LibraryPaths.boxarts(root, platform).keySet()) {
                if (boxarts.length() > 1) boxarts.append(',');
                boxarts.append(Json.object().put("platform", platform.name()).put("stem", stem).end());
            }
        }
        return Reply.json(200, Json.object().raw("carts", carts.append(']').toString())
                .raw("labels", labels.append(']').toString()).raw("boxarts", boxarts.append(']').toString()).end());
    }

    private static boolean playable(File root, File file) throws IOException {
        if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || file.length() < HEAD) return false;
        LibraryPaths.safe(root, file);
        return true;
    }

    static String stemOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** A ROM named by platform and exact file name, or one whose name is the same once composed. */
    static File rom(File root, Cartridge.Platform platform, String name) throws IOException {
        if (platform == null || !ImportWriter.validName(name) || !LibraryPaths.accepts(platform, name)) return null;
        File[] files = LibraryPaths.games(root, platform).listFiles();
        if (files == null) return null;
        File match = null;
        for (File file : files) {
            if (file.getName().equals(name)) { match = file; break; }
            if (match == null && LibraryPaths.nfc(file.getName()).equals(LibraryPaths.nfc(name))) match = file;
        }
        return match != null && playable(root, match) ? match : null;
    }

    private Reply game(File root, List<String> path) throws IOException {
        File file = rom(root, platform(path.get(1)), path.get(2));
        if (file == null) return Reply.error(404, "not_found");
        if (path.get(3).equals("head")) {
            byte[] head = new byte[HEAD];
            try (java.io.DataInputStream input = new java.io.DataInputStream(new java.io.FileInputStream(file))) { input.readFully(head); }
            return new Reply(200, "application/octet-stream", head);
        }
        if (!path.get(3).equals("crc")) return Reply.error(404, "not_found");
        Long crc;
        try { crc = crcs.get(file, crcWait); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
        if (crc == null) return Reply.json(202, Json.object().put("pending", true).end());
        return Reply.json(200, Json.object().put("crc", crc).end());
    }

    /** A PNG under Labels or Boxart, read and written the same way for both. */
    private Reply image(File root, Request request, String kind, int max) throws IOException {
        Cartridge.Platform platform = platform(request.path().get(1));
        String name = request.path().get(2);
        if (platform == null || !name.endsWith(".png") || !ImportWriter.validName(name) || name.length() <= 4)
            return Reply.error(404, "not_found");
        String stem = LibraryPaths.nfc(name.substring(0, name.length() - 4));
        if (request.method().equals("GET")) {
            File label = LibraryPaths.images(root, kind, platform).get(stem);
            if (label == null || Files.isSymbolicLink(label.toPath()) || label.length() > max) return Reply.error(404, "not_found");
            return new Reply(200, "image/png", Files.readAllBytes(label.toPath()));
        }
        File assets = LibraryPaths.assetsRoot(root);
        if (request.method().equals("DELETE")) {
            boolean removed = false;
            synchronized (WRITE_LOCK) {
                for (File base : LibraryPaths.assetRoots(root)) {
                    File existing = LibraryPaths.imagesIn(base, kind, platform).get(stem);
                    if (existing != null) {
                        LibraryPaths.safe(base, existing);
                        if (Files.isSymbolicLink(existing.toPath())) throw new IOException("Symbolic link");
                        removed |= Files.deleteIfExists(existing.toPath());
                    }
                }
            }
            if (removed) changed.accept(root, EnumSet.noneOf(Cartridge.Platform.class));
            return Reply.json(200, Json.object().put("result", "deleted").end());
        }
        if (!request.method().equals("PUT")) return Reply.error(405, "method");
        boolean replace = "1".equals(request.query().get("replace")) || "true".equals(request.query().get("replace"));
        byte[] png = body(request, max);
        if (png == null) return Reply.error(request.length() < 0 ? 411 : 413, request.length() < 0 ? "length" : "size");
        if (png.length < PNG.length || !java.util.Arrays.equals(java.util.Arrays.copyOf(png, PNG.length), PNG))
            return Reply.error(415, "png");
        File labels = folder(assets, kind);
        File folder = LibraryPaths.child(labels, platform.name());
        if (!folder.isDirectory() && !folder.mkdirs()) return Reply.error(507, "storage");
        LibraryPaths.safe(assets, folder);
        String result;
        synchronized (WRITE_LOCK) {
            File existing = LibraryPaths.images(root, kind, platform).get(stem);
            if (existing != null && !replace) result = "skipped";
            else {
                File primaryExisting = LibraryPaths.imagesIn(assets, kind, platform).get(stem);
                commit(assets, primaryExisting != null ? primaryExisting : new File(folder, stem + ".png"), png);
                result = "written";
            }
        }
        if (result.equals("written")) changed.accept(root, EnumSet.noneOf(Cartridge.Platform.class));
        return Reply.json(200, Json.object().put("result", result).end());
    }

    private Reply shells(File root, Request request) throws IOException {
        File assets = LibraryPaths.assetsRoot(root);
        File labels = LibraryPaths.child(LibraryPaths.child(assets, "Labels"), "cart_shell.txt");
        if (request.method().equals("GET")) {
            File config = LibraryPaths.child(LibraryPaths.child(assets, "Config"), "cart_shell.txt");
            if (!config.exists()) config = LibraryPaths.child(LibraryPaths.child(assets, "System"), "cart_shell.txt");
            if (!config.exists() && !assets.equals(root)) {
                config = LibraryPaths.child(LibraryPaths.child(root, "Config"), "cart_shell.txt");
                if (!config.exists()) config = LibraryPaths.child(LibraryPaths.child(root, "System"), "cart_shell.txt");
            }
            byte[] current = read(assets, labels);
            if (current == null && !assets.equals(root)) {
                current = read(root, LibraryPaths.child(LibraryPaths.child(root, "Labels"), "cart_shell.txt"));
            }
            File configRoot = LibraryPaths.within(assets, config) ? assets : root;
            return Reply.json(200, Json.object().put("system", text(read(configRoot, config))).put("labels", text(current))
                    .put("version", version(current)).end());
        }
        if (!request.method().equals("PUT")) return Reply.error(405, "method");
        String expected = request.query().get("version");
        if (expected == null) return Reply.error(400, "version");
        byte[] next = body(request, SHELLS_MAX);
        if (next == null) return Reply.error(request.length() < 0 ? 411 : 413, request.length() < 0 ? "length" : "size");
        synchronized (WRITE_LOCK) {
            File target = LibraryPaths.child(folder(assets, "Labels"), "cart_shell.txt");
            byte[] current = read(assets, target);
            if (current == null && !assets.equals(root)) {
                current = read(root, LibraryPaths.child(LibraryPaths.child(root, "Labels"), "cart_shell.txt"));
            }
            if (!expected.equals(version(current))) return Reply.error(409, "conflict");
            commit(assets, target, next);
        }
        changed.accept(root, EnumSet.noneOf(Cartridge.Platform.class));
        return Reply.json(200, Json.object().put("version", version(next)).end());
    }

    private Reply upload(File root, Request request) {
        Cartridge.Platform platform = platform(request.path().get(1));
        String name = request.path().get(2);
        if (request.length() < 0) return Reply.error(411, "length");
        try {
            if (platform == null || ImportWriter.platform(name) != platform) return Reply.error(400, "format");
            ImportWriter.Result result = ImportWriter.write(root, name, request.length(), request.body(), () -> false, count -> {});
            if (result.outcome() == ImportWriter.Outcome.ADDED) changed.accept(root, EnumSet.of(platform));
            boolean added = result.outcome() == ImportWriter.Outcome.ADDED;
            return Reply.json(added ? 201 : 200, Json.object().put("result", added ? "added" : "skipped")
                    .put("file", result.file().getName()).end());
        } catch (ImportWriter.Failure failure) {
            return switch (failure.problem) {
                case NAME -> Reply.error(400, "name");
                case FORMAT -> Reply.error(400, "format");
                case SIZE -> Reply.error(413, "size");
                case SHORT, LENGTH, CANCELLED -> Reply.error(400, "incomplete");
                case STORAGE -> Reply.error(507, "storage");
                case ACCESS -> Reply.error(403, "access");
                case IO -> Reply.error(500, "io");
            };
        } catch (IOException error) {
            return Reply.error(500, "io");
        }
    }

    private static File folder(File root, String name) throws IOException {
        File folder = LibraryPaths.child(root, name);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create " + folder);
        return LibraryPaths.safe(root, folder);
    }

    /** Exactly the declared body, or null when it is missing or above the limit. */
    private static byte[] body(Request request, int max) throws IOException {
        if (request.length() < 0 || request.length() > max) return null;
        byte[] out = new byte[(int) request.length()];
        int read = 0;
        while (read < out.length) {
            int count = request.body().read(out, read, out.length - read);
            if (count < 0) throw new IOException("Body ended early");
            read += count;
        }
        return out;
    }

    /** Writes beside the target and renames over it, so readers never see a partial file. */
    static void commit(File root, File target, byte[] bytes) throws IOException {
        File folder = target.getParentFile();
        File part = new File(folder, "." + target.getName() + ".part");
        LibraryPaths.safe(root, part);
        if (target.exists()) {
            LibraryPaths.safe(root, target);
            if (Files.isSymbolicLink(target.toPath())) throw new IOException("Symbolic link");
        }
        try {
            try (FileOutputStream output = new FileOutputStream(part)) { output.write(bytes); output.getFD().sync(); }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (part.exists()) Files.deleteIfExists(part.toPath());
        }
    }

    private static byte[] read(File root, File file) throws IOException {
        if (!file.exists()) return null;
        LibraryPaths.safe(root, file);
        if (file.length() > SHELLS_MAX) throw new IOException("INI too large");
        return Files.readAllBytes(file.toPath());
    }

    private static String text(byte[] bytes) { return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8); }

    static String version(byte[] bytes) {
        if (bytes == null) return "none";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) out.append(String.format("%02x", digest[i] & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    static byte[] readAll(InputStream input, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (out.size() + count > max) throw new IOException("Too large");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}
