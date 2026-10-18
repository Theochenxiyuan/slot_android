package fyi.slot.android;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import static org.junit.Assert.*;

/** Runs the real NanoHTTPD server on a temporary library, as the Studio and a browser would reach it. */
public final class DeviceServerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String PIN = "K7M2QD";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3};
    private final AtomicLong clock = new AtomicLong(1_000);
    private final List<Set<Cartridge.Platform>> events = Collections.synchronizedList(new ArrayList<>());
    private final List<URI> artRequests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<File> root = new AtomicReference<>();
    private DeviceServer server;
    private CrcCache crcs;
    private int port;

    record Reply(int status, Map<String, String> headers, byte[] body) {
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    @Before public void start() throws Exception {
        root.set(temp.newFolder("Slot"));
        crcs = new CrcCache();
        DeviceApi api = new DeviceApi(root::get, (changed, platforms) -> events.add(platforms), crcs, 5000);
        DeviceServer.Assets assets = path -> {
            if (path.equals("index.html")) return new java.io.ByteArrayInputStream("<p>studio</p>".getBytes(StandardCharsets.UTF_8));
            if (path.equals("pkg/studio.wasm")) return new java.io.ByteArrayInputStream(new byte[]{0, 'a', 's', 'm'});
            throw new IOException("missing");
        };
        ArtProxy art = new ArtProxy(uri -> {
            artRequests.add(uri);
            return uri.getPath().endsWith("missing.png") ? new ArtProxy.Fetched(404, "text/plain", new byte[0])
                    : new ArtProxy.Fetched(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        });
        server = new DeviceServer(0, new DeviceAuth(PIN, clock::get), api, assets, art, line -> {
            if (line.startsWith("failed")) System.err.println(line);
        });
        server.startServer();
        port = server.getListeningPort();
    }

    @After public void stop() { server.stop(); crcs.close(); }

    private File rom(String platform, String name, int size) throws IOException {
        File file = new File(root.get(), "Games/" + platform + "/" + name);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 7);
        Files.write(file.toPath(), bytes);
        return file;
    }

    private Reply request(String method, String path, Map<String, String> headers, byte[] body) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(20_000);
            StringBuilder head = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
            Map<String, String> all = new LinkedHashMap<>();
            all.put("Host", "127.0.0.1:" + port);
            all.putAll(headers);
            if (body != null && !all.containsKey("Content-Length")) all.put("Content-Length", Integer.toString(body.length));
            for (Map.Entry<String, String> header : all.entrySet())
                if (header.getValue() != null) head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            head.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if (body != null) out.write(body);
            out.flush();
            return parse(socket.getInputStream());
        }
    }

    private Reply get(String path) throws IOException { return request("GET", path, Map.of("Authorization", "Bearer " + PIN), null); }
    private Reply put(String path, byte[] body) throws IOException { return request("PUT", path, Map.of("Authorization", "Bearer " + PIN), body); }
    private Reply delete(String path) throws IOException { return request("DELETE", path, auth(PIN), null); }

    private static Reply parse(InputStream input) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) all.write(buffer, 0, count);
        byte[] bytes = all.toByteArray();
        int split = new String(bytes, StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n");
        String[] lines = new String(bytes, 0, split, StandardCharsets.ISO_8859_1).split("\r\n");
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(), lines[i].substring(colon + 1).trim());
        }
        return new Reply(Integer.parseInt(lines[0].split(" ")[1]), headers, java.util.Arrays.copyOfRange(bytes, split + 4, bytes.length));
    }

    private static void write(File file, String text) throws IOException { Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    private static String read(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }

    private static Map<String, String> auth(String pin) { return Map.of("Authorization", "Bearer " + pin); }

    @Test public void authStatusIsOpenAndEveryApiCallNeedsThePin() throws Exception {
        Reply status = request("GET", "/api/auth", Map.of(), null);
        assertEquals(200, status.status());
        assertTrue(status.text().contains("\"required\":true"));
        assertEquals(401, request("GET", "/api/library", Map.of(), null).status());
        assertEquals(401, request("GET", "/api/library", auth("AAAAAA"), null).status());
        assertEquals(401, request("GET", "/api/library", Map.of("Authorization", PIN), null).status());
        assertEquals(200, request("GET", "/api/library", auth(PIN.toLowerCase()), null).status());
        assertEquals(401, request("GET", "/art/index.json", Map.of(), null).status());
    }

    @Test public void repeatedFailuresPauseChecksForAMinute() throws Exception {
        for (int i = 0; i < DeviceAuth.MAX_FAILURES; i++) assertEquals(401, request("GET", "/api/library", auth("WRONG1"), null).status());
        assertEquals(429, get("/api/library").status());
        clock.addAndGet(DeviceAuth.LOCK_MILLIS + 1);
        assertEquals(200, get("/api/library").status());
    }

    @Test public void sameOriginOnlyWithLiteralHostsAndNoCrossOriginHeaders() throws Exception {
        Map<String, String> evil = new LinkedHashMap<>(auth(PIN));
        evil.put("Host", "evil.example:" + port);
        assertEquals(403, request("GET", "/api/library", evil, null).status());
        Map<String, String> wrongPort = new LinkedHashMap<>(auth(PIN));
        wrongPort.put("Host", "127.0.0.1:1");
        assertEquals(403, request("GET", "/", wrongPort, null).status());
        Map<String, String> origin = new LinkedHashMap<>(auth(PIN));
        origin.put("Origin", "http://evil.example");
        assertEquals(403, request("GET", "/api/library", origin, null).status());
        Map<String, String> same = new LinkedHashMap<>(auth(PIN));
        same.put("Origin", "http://127.0.0.1:" + port);
        Reply ok = request("GET", "/api/library", same, null);
        assertEquals(200, ok.status());
        assertNull(ok.headers().get("access-control-allow-origin"));
        assertEquals("close", ok.headers().get("connection"));
        assertEquals(405, request("OPTIONS", "/api/library", auth(PIN), null).status());
        assertTrue(DeviceServer.hostAllowed("192.168.1.20:1092", 1092));
        assertTrue(DeviceServer.hostAllowed("[fe80::1]:1092", 1092));
        assertFalse(DeviceServer.hostAllowed("192.168.1.300:1092", 1092));
        assertFalse(DeviceServer.hostAllowed("slot.local:1092", 1092));
        assertFalse(DeviceServer.hostAllowed(null, 1092));
    }

    @Test public void libraryListsPlayableCartsAndLabelsPerPlatform() throws Exception {
        rom("GBA", "Metroid (USA).gba", 512);
        rom("GB", "Tetris.gb", 0x150);
        rom("GBC", "Twin.gbc", 512);
        rom("GBC", ".hidden.gbc", 512);
        rom("GBA", "Short.gba", 0x14f);
        rom("GB", "Wrong.gba", 512);
        File label = new File(root.get(), "Labels/GBA/Metroid (USA).png");
        assertTrue(label.getParentFile().mkdirs());
        Files.write(label.toPath(), PNG);
        String json = get("/api/library").text();
        assertTrue(json, json.contains("{\"platform\":\"GBA\",\"file\":\"Metroid (USA).gba\",\"stem\":\"Metroid (USA)\",\"size\":512}"));
        assertTrue(json.contains("\"file\":\"Tetris.gb\""));
        assertTrue(json.contains("\"file\":\"Twin.gbc\""));
        assertFalse(json.contains("hidden"));
        assertFalse(json.contains("Short"));
        assertFalse(json.contains("Wrong"));
        assertTrue(json.contains("\"labels\":[{\"platform\":\"GBA\",\"stem\":\"Metroid (USA)\"}]"));
    }

    @Test public void headAndCrcComeFromTheDeviceWithoutDownloadingTheRom() throws Exception {
        File file = rom("GBA", "Game + Bonus.gba", 300_000);
        byte[] bytes = Files.readAllBytes(file.toPath());
        Reply head = get("/api/games/GBA/Game%20%2B%20Bonus.gba/head");
        assertEquals(200, head.status());
        assertArrayEquals(java.util.Arrays.copyOf(bytes, 0x150), head.body());
        CRC32 expected = new CRC32();
        expected.update(bytes);
        Reply crc = get("/api/games/GBA/Game%20%2B%20Bonus.gba/crc");
        assertEquals(200, crc.status());
        assertEquals("{\"crc\":" + expected.getValue() + "}", crc.text());
        assertEquals(404, get("/api/games/GBA/Absent.gba/head").status());
        assertEquals(404, get("/api/games/GB/Game%20%2B%20Bonus.gba/head").status());
    }

    @Test public void pathsCannotLeaveTheLibrary() throws Exception {
        rom("GBA", "Game.gba", 512);
        File outside = new File(root.get().getParentFile(), "secret.gba");
        Files.write(outside.toPath(), new byte[512]);
        for (String path : new String[]{"/api/games/GBA/..%2F..%2Fsecret.gba/head", "/api/games/GBA/../../secret.gba/head",
                "/api/games/GBA/.Game.gba/head", "/api/games/gba/Game.gba/head", "/api/games/GBA/Game.gba%00/head",
                "/api/labels/GBA/..%2F..%2Fx.png", "/api/games/GBA/Game.gba%5C..%5Cx/head"}) {
            int status = get(path).status();
            assertTrue(path + " -> " + status, status == 400 || status == 404);
        }
        assertEquals(400, request("GET", "/../secret.gba", Map.of(), null).status());
        assertEquals(400, put("/api/import/GBA/..%2Fescape.gba", new byte[512]).status());
        assertFalse(new File(root.get(), "Games/escape.gba").exists());
        assertEquals(400, put("/api/import/GBA/.dot.gba", new byte[512]).status());
    }

    @Test public void symbolicLinksAreNotServed() throws Exception {
        File outside = new File(root.get().getParentFile(), "outside.gba");
        Files.write(outside.toPath(), new byte[512]);
        File link = new File(root.get(), "Games/GBA/Link.gba");
        assertTrue(link.getParentFile().mkdirs());
        try { Files.createSymbolicLink(link.toPath(), outside.toPath()); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) { Assume.assumeNoException(unavailable); }
        assertEquals(404, get("/api/games/GBA/Link.gba/head").status());
        assertFalse(get("/api/library").text().contains("Link.gba"));
    }

    @Test public void uploadsAddOnceThenSkipAndRescanTheirPlatform() throws Exception {
        byte[] rom = new byte[1024];
        rom[0] = 42;
        Reply added = put("/api/import/GBC/New%20Game.gbc", rom);
        assertEquals(201, added.status());
        assertTrue(added.text().contains("\"result\":\"added\""));
        File file = new File(root.get(), "Games/GBC/New Game.gbc");
        assertArrayEquals(rom, Files.readAllBytes(file.toPath()));
        assertEquals(List.of(EnumSet.of(Cartridge.Platform.GBC)), events);
        byte[] other = new byte[1024];
        Reply skipped = put("/api/import/GBC/NEW%20GAME.GBC", other);
        assertEquals(200, skipped.status());
        assertTrue(skipped.text().contains("\"result\":\"skipped\""));
        assertEquals(42, Files.readAllBytes(file.toPath())[0]);
        assertEquals(1, events.size());
        assertEquals(400, put("/api/import/GBA/New.gbc", rom).status());
        assertEquals(400, put("/api/import/GBA/New.zip", rom).status());
        assertEquals(400, put("/api/import/GBA/Short.gba", new byte[0x14f]).status());
        assertFalse(new File(root.get(), "Games/GBA/Short.gba").exists());
    }

    @Test public void deletingAGameRemovesOnlyItsExclusiveAppearanceAndKeepsSaves() throws Exception {
        File game = rom("GBA", "Game.gba", 512);
        File other = rom("GBA", "Other.gba", 512);
        put("/api/labels/GBA/Game.png", PNG);
        put("/api/boxart/GBA/Game.png", PNG);
        put("/api/labels/GBA/Other.png", PNG);
        put("/api/shells?version=none", "# keep comment\nGame = auto 123456 solid\nOther = auto 654321 clear\n".getBytes(StandardCharsets.UTF_8));
        File save = new File(game.getParentFile(), "Game.srm");
        File state = new File(game.getParentFile(), "Game.state1");
        write(save, "save"); write(state, "state");
        events.clear();
        assertEquals(200, delete("/api/games/GBA/Game.gba").status());
        assertFalse(game.exists());
        assertTrue(other.isFile());
        assertEquals(404, get("/api/labels/GBA/Game.png").status());
        assertEquals(404, get("/api/boxart/GBA/Game.png").status());
        assertEquals(200, get("/api/labels/GBA/Other.png").status());
        assertEquals("save", read(save));
        assertEquals("state", read(state));
        String shells = read(new File(root.get(), "Labels/cart_shell.txt"));
        assertEquals("# keep comment\nOther = auto 654321 clear\n", shells);
        assertEquals(List.of(EnumSet.of(Cartridge.Platform.GBA)), events);
        assertEquals(404, delete("/api/games/GBA/Game.gba").status());
        assertEquals(1, events.size());
        assertFalse(get("/api/library").text().contains("Game.gba"));
        assertEquals(0, game.getParentFile().list((dir, name) -> name.startsWith(".slot-delete-")).length);
    }

    @Test public void deletingRequiresPairingAndNeverExposesRename() throws Exception {
        File game = rom("GB", "Game.gb", 512);
        assertEquals(401, request("DELETE", "/api/games/GB/Game.gb", Map.of(), null).status());
        assertEquals(405, request("POST", "/api/games/GB/Game.gb/rename", auth(PIN), "New".getBytes(StandardCharsets.UTF_8)).status());
        assertEquals(404, delete("/api/games/GBA/Game.gb").status());
        assertTrue(game.exists());
        int unsafe = delete("/api/games/GB/..%2FGame.gb").status();
        assertTrue(unsafe == 400 || unsafe == 404);
    }

    @Test public void sameStemInAnotherPlatformKeepsSharedShellSettingsButNotPlatformImages() throws Exception {
        rom("GB", "Twin.gb", 512);
        rom("GBA", "Twin.gba", 512);
        put("/api/labels/GB/Twin.png", PNG);
        put("/api/labels/GBA/Twin.png", PNG);
        put("/api/shells?version=none", "Twin = auto 123456 solid\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, delete("/api/games/GB/Twin.gb").status());
        assertEquals(404, get("/api/labels/GB/Twin.png").status());
        assertEquals(200, get("/api/labels/GBA/Twin.png").status());
        assertTrue(get("/api/shells").text().contains("Twin = auto 123456 solid"));
        assertEquals(200, delete("/api/games/GBA/Twin.gba").status());
        assertFalse(get("/api/shells").text().contains("Twin ="));
        assertTrue(get("/api/library").text().contains("\"carts\":[]"));
    }

    @Test public void sharedImagesAreKeptForAnotherRomWithTheSameStemInTheSameFolder() throws Exception {
        rom("GB", "Twin.gb", 512);
        rom("GB", "Twin.gbc", 512);
        put("/api/labels/GB/Twin.png", PNG);
        put("/api/boxart/GB/Twin.png", PNG);
        assertEquals(200, delete("/api/games/GB/Twin.gb").status());
        assertEquals(200, get("/api/labels/GB/Twin.png").status());
        assertEquals(200, get("/api/boxart/GB/Twin.png").status());
        assertTrue(new File(root.get(), "Games/GB/Twin.gbc").isFile());
    }

    @Test public void invalidAppearanceConfigurationPreventsDeletionBeforeAnyFileIsMoved() throws Exception {
        File game = rom("GBA", "Game.gba", 512);
        put("/api/labels/GBA/Game.png", PNG);
        assertTrue(new File(root.get(), "Labels/cart_shell.txt").mkdir());
        assertEquals(500, delete("/api/games/GBA/Game.gba").status());
        assertTrue(game.isFile());
        assertEquals(200, get("/api/labels/GBA/Game.png").status());
        assertEquals(0, game.getParentFile().list((dir, name) -> name.startsWith(".slot-delete-")).length);
    }

    @Test public void deletingComposedNameRemovesDecomposedResourcesAndSettings() throws Exception {
        File game = rom("GB", "Cafe\u0301.gb", 512);
        put("/api/labels/GB/Caf%C3%A9.png", PNG);
        put("/api/shells?version=none", "Cafe\u0301 = auto 123456 solid\r\nOther = auto\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, delete("/api/games/GB/Caf%C3%A9.gb").status());
        assertFalse(game.exists());
        assertEquals(404, get("/api/labels/GB/Caf%C3%A9.png").status());
        assertEquals("Other = auto\r\n", read(new File(root.get(), "Labels/cart_shell.txt")));
    }

    @Test public void shellCommitFailureRollsBackAllStagedFiles() throws Exception {
        File game = rom("GBA", "Game.gba", 512);
        put("/api/labels/GBA/Game.png", PNG);
        put("/api/boxart/GBA/Game.png", PNG);
        String original = "Game = auto 123456 solid\n";
        put("/api/shells?version=none", original.getBytes(StandardCharsets.UTF_8));
        assertTrue(new File(root.get(), "Labels/.cart_shell.txt.part").mkdir());
        events.clear();
        assertEquals(500, delete("/api/games/GBA/Game.gba").status());
        assertTrue(game.isFile());
        assertEquals(200, get("/api/labels/GBA/Game.png").status());
        assertEquals(200, get("/api/boxart/GBA/Game.png").status());
        assertEquals(original, read(new File(root.get(), "Labels/cart_shell.txt")));
        assertTrue(events.isEmpty());
        for (String folder : List.of("Games/GBA", "Labels/GBA", "Boxart/GBA"))
            assertEquals(0, new File(root.get(), folder).list((dir, name) -> name.startsWith(".slot-delete-")).length);
    }

    @Test public void skippedLargeUploadStillGetsItsAnswer() throws Exception {
        rom("GBA", "Big.gba", 512);
        Reply skipped = put("/api/import/GBA/Big.gba", new byte[4 * 1024 * 1024]);
        assertEquals(200, skipped.status());
        assertTrue(skipped.text().contains("\"skipped\""));
        assertEquals(512, new File(root.get(), "Games/GBA/Big.gba").length());
        assertEquals(401, request("PUT", "/api/import/GBA/Other.gba", auth("WRONG1"), new byte[4 * 1024 * 1024]).status());
    }

    @Test public void uploadLimitsAndInterruptedTransfersLeaveNothingBehind() throws Exception {
        Map<String, String> oversized = new LinkedHashMap<>(auth(PIN));
        oversized.put("Content-Length", Long.toString(ImportWriter.MAX_BYTES + 1));
        assertEquals(413, request("PUT", "/api/import/GBA/Big.gba", oversized, null).status());
        assertEquals(411, request("PUT", "/api/import/GBA/NoLength.gba", auth(PIN), null).status());
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String head = "PUT /api/import/GBA/Cut.gba HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nAuthorization: Bearer " + PIN
                    + "\r\nContent-Length: 100000\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(new byte[4096]);
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            Reply reply = parse(socket.getInputStream());
            assertEquals(400, reply.status());
        }
        File folder = new File(root.get(), "Games/GBA");
        String[] left = folder.list();
        assertTrue(left == null || left.length == 0);
        assertTrue(events.isEmpty());
    }

    @Test public void labelsFollowTheStudioWriteRule() throws Exception {
        rom("GBA", "Game.gba", 512);
        Reply written = put("/api/labels/GBA/Game.png", PNG);
        assertEquals(200, written.status());
        assertTrue(written.text().contains("\"written\""));
        File label = new File(root.get(), "Labels/GBA/Game.png");
        assertArrayEquals(PNG, Files.readAllBytes(label.toPath()));
        byte[] next = PNG.clone();
        next[next.length - 1] = 9;
        assertTrue(put("/api/labels/GBA/Game.png", next).text().contains("\"skipped\""));
        assertArrayEquals(PNG, Files.readAllBytes(label.toPath()));
        assertTrue(put("/api/labels/GBA/Game.png?replace=1", next).text().contains("\"written\""));
        assertArrayEquals(next, get("/api/labels/GBA/Game.png").body());
        assertEquals(415, put("/api/labels/GBA/Other.png", "not png".getBytes(StandardCharsets.UTF_8)).status());
        assertFalse(new File(root.get(), "Labels/GBA/Other.png").exists());
        assertEquals(404, get("/api/labels/GBA/Absent.png").status());
        assertEquals(2, events.size());
        assertTrue(events.get(0).isEmpty());
        String[] parts = label.getParentFile().list((dir, name) -> name.endsWith(".part"));
        assertEquals(0, parts.length);
        assertEquals(200, delete("/api/labels/GBA/Game.png").status());
        assertFalse(label.exists());
        assertEquals(404, get("/api/labels/GBA/Game.png").status());
        assertEquals(3, events.size());
    }

    @Test public void boxArtFollowsTheSameWriteRuleAndKeepsLabelsApart() throws Exception {
        rom("GBA", "Game.gba", 512);
        assertTrue(get("/api/library").text().contains("\"boxarts\":[]"));
        assertEquals(404, get("/api/boxart/GBA/Game.png").status());
        Reply written = put("/api/boxart/GBA/Game.png", PNG);
        assertEquals(200, written.status());
        assertTrue(written.text().contains("\"written\""));
        File art = new File(root.get(), "Boxart/GBA/Game.png");
        assertArrayEquals(PNG, Files.readAllBytes(art.toPath()));
        assertFalse(new File(root.get(), "Labels/GBA/Game.png").exists());
        byte[] next = PNG.clone();
        next[next.length - 1] = 7;
        assertTrue(put("/api/boxart/GBA/Game.png", next).text().contains("\"skipped\""));
        assertArrayEquals(PNG, get("/api/boxart/GBA/Game.png").body());
        assertTrue(put("/api/boxart/GBA/Game.png?replace=1", next).text().contains("\"written\""));
        assertArrayEquals(next, get("/api/boxart/GBA/Game.png").body());
        assertEquals(415, put("/api/boxart/GBA/Other.png", "not png".getBytes(StandardCharsets.UTF_8)).status());
        assertFalse(new File(root.get(), "Boxart/GBA/Other.png").exists());
        assertEquals(404, get("/api/labels/GBA/Game.png").status());
        assertTrue(get("/api/library").text().contains("\"boxarts\":[{\"platform\":\"GBA\",\"stem\":\"Game\"}]"));
        assertEquals(2, events.size());
        String[] parts = art.getParentFile().list((dir, name) -> name.endsWith(".part"));
        assertEquals(0, parts.length);
        assertEquals(200, delete("/api/boxart/GBA/Game.png").status());
        assertFalse(art.exists());
        assertEquals(404, get("/api/boxart/GBA/Game.png").status());
        assertEquals(3, events.size());
    }

    @Test public void oversizedAndUnsafeBoxArtIsRefused() throws Exception {
        assertEquals(413, put("/api/boxart/GBA/Big.png", new byte[DeviceApi.BOXART_MAX + 1]).status());
        int unsafe = get("/api/boxart/GBA/..%2F..%2Fx.png").status();
        assertTrue("unsafe name -> " + unsafe, unsafe == 400 || unsafe == 404);
        int unknown = get("/api/boxart/NES/Game.png").status();
        assertTrue("unknown platform -> " + unknown, unknown == 400 || unknown == 404);
        assertFalse(new File(root.get(), "Boxart/GBA/Big.png").exists());
    }

    @Test public void composedAndDecomposedLabelNamesAreOneLabel() throws Exception {
        File label = new File(root.get(), "Labels/GB/Cafe\u0301.png");
        assertTrue(label.getParentFile().mkdirs());
        Files.write(label.toPath(), PNG);
        assertArrayEquals(PNG, get("/api/labels/GB/Caf%C3%A9.png").body());
        assertTrue(put("/api/labels/GB/Caf%C3%A9.png", PNG).text().contains("\"skipped\""));
    }

    @Test public void shellsAreLayeredAndWritesRefuseStaleVersions() throws Exception {
        File system = new File(root.get(), "System/cart_shell.txt");
        assertTrue(system.getParentFile().mkdirs());
        write(system, "A = notched 445566 clear\n");
        String first = get("/api/shells").text();
        assertTrue(first, first.contains("\"system\":\"A = notched 445566 clear\\n\""));
        assertTrue(first.contains("\"labels\":\"\""));
        assertTrue(first.contains("\"version\":\"none\""));
        assertEquals(400, put("/api/shells", "A = auto\n".getBytes(StandardCharsets.UTF_8)).status());
        Reply saved = put("/api/shells?version=none", "A = auto\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, saved.status());
        assertEquals("A = auto\n", new String(Files.readAllBytes(new File(root.get(), "Labels/cart_shell.txt").toPath()), StandardCharsets.UTF_8));
        assertEquals(409, put("/api/shells?version=none", "A = rounded 112233 solid\n".getBytes(StandardCharsets.UTF_8)).status());
        String version = DeviceApi.version("A = auto\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(saved.text().contains(version));
        assertEquals(200, put("/api/shells?version=" + version, "A = rounded 112233 solid\n".getBytes(StandardCharsets.UTF_8)).status());
        File config = new File(root.get(), "Config/cart_shell.txt");
        assertTrue(config.getParentFile().mkdirs());
        write(config, "");
        assertTrue(get("/api/shells").text().contains("\"system\":\"\""));
        assertEquals(2, events.size());
    }

    @Test public void studioPageIsServedWithoutPairing() throws Exception {
        Reply page = request("GET", "/", Map.of(), null);
        assertEquals(200, page.status());
        assertEquals("<p>studio</p>", page.text());
        assertTrue(page.headers().get("content-type").startsWith("text/html"));
        assertEquals("application/wasm", request("GET", "/pkg/studio.wasm", Map.of(), null).headers().get("content-type"));
        assertEquals(404, request("GET", "/missing.js", Map.of(), null).status());
        assertEquals(404, request("GET", "/.hidden", Map.of(), null).status());
        assertEquals(405, request("POST", "/", Map.of(), new byte[0]).status());
    }

    @Test public void artProxyForwardsOnlyToTheArtSet() throws Exception {
        Reply index = get("/art/index.json");
        assertEquals(200, index.status());
        assertEquals("{}", index.text());
        assertEquals(URI.create("https://art.slot-cfw.fyi/index.json"), artRequests.get(0));
        assertEquals(200, get("/art/GBA/wheel/000206C0.png").status());
        assertEquals("/GBA/wheel/000206C0.png", artRequests.get(1).getPath());
        assertEquals(404, get("/art/GBA/wheel/missing.png").status());
        assertEquals(400, get("/art/..%2F..%2Fetc").status());
        assertEquals(404, get("/art/a@b").status());
        assertEquals(3, artRequests.size());
        assertNull(ArtProxy.target(List.of("evil.example", "..")));
    }

    @Test public void missingLibraryAnswersUnavailable() throws Exception {
        root.set(null);
        assertEquals(503, get("/api/library").status());
        assertEquals(404, get("/api/unknown").status());
    }

    @Test public void loopbackBindingAcceptsOnlyItsLiteralHostAndKeepsAuthentication() throws Exception {
        server.stop();
        server = new DeviceServer("127.0.0.1", 0, new DeviceAuth(PIN, clock::get),
                new DeviceApi(root::get, (file, platforms) -> {}, crcs, 5000),
                path -> new java.io.ByteArrayInputStream(new byte[0]),
                new ArtProxy(uri -> new ArtProxy.Fetched(404, "text/plain", new byte[0])), line -> {});
        server.startServer(); port = server.getListeningPort();
        assertEquals("127.0.0.1", server.getHostname());
        assertEquals(200, get("/api/library").status());
        assertEquals(401, request("GET", "/api/library", Map.of(), null).status());
        assertEquals(403, request("GET", "/api/library", Map.of("Host", "192.168.1.9:" + port, "Authorization", "Bearer " + PIN), null).status());
    }

    @Test public void stoppedServerRefusesConnections() throws Exception {
        server.stop();
        assertThrows(ConnectException.class, () -> new Socket("127.0.0.1", port).close());
    }

    @Test public void addressesPreferWifiThenWiredThenVpn() {
        List<String> urls = NetworkAddresses.order(List.of(
                new NetworkAddresses.Candidate("tun0", "10.8.0.2"),
                new NetworkAddresses.Candidate("rmnet0", "100.64.1.2"),
                new NetworkAddresses.Candidate("eth0", "192.168.2.5"),
                new NetworkAddresses.Candidate("wlan0", "192.168.1.5"),
                new NetworkAddresses.Candidate("wlan0", "192.168.1.5")), 1092);
        assertEquals(List.of("http://192.168.1.5:1092", "http://192.168.2.5:1092", "http://100.64.1.2:1092", "http://10.8.0.2:1092"), urls);
    }

    @Test public void pinsUseTheUnambiguousAlphabet() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 200; i++) {
            String pin = DeviceAuth.newPin(random);
            assertEquals(6, pin.length());
            assertTrue(pin, pin.chars().allMatch(c -> DeviceAuth.ALPHABET.indexOf(c) >= 0));
            assertFalse(pin.matches(".*[01IO].*"));
        }
    }
}
