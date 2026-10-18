package fyi.slot.android;

import java.io.IOException;
import java.net.URLEncoder;
import java.security.SecureRandom;

/** One unadvertised, loopback-only Studio run. Independent of the computer connection. */
final class LocalStudioSession implements AutoCloseable {
    private final CrcCache crcs = new CrcCache();
    private final DeviceServer server;
    private final String token;

    LocalStudioSession(java.util.function.Supplier<java.io.File> root, DeviceServer.Assets assets,
                       java.util.function.BiConsumer<java.io.File, java.util.Set<Cartridge.Platform>> events, ArtProxy art) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02X", b & 255));
        token = value.toString();
        DeviceApi api = new DeviceApi(root, events, crcs, 2500);
        server = new DeviceServer("127.0.0.1", 0, new DeviceAuth(token, () -> System.nanoTime() / 1_000_000),
                api, assets, art, line -> {});
    }

    void start() throws IOException { server.startServer(); }
    String origin() { return "http://127.0.0.1:" + server.getListeningPort(); }
    String url(String language, RomRecord rom) {
        return origin() + "/?local=1&pin=" + token + "&lang=" + encode(language)
                + (rom == null ? "" : "&platform=" + rom.platform.name() + "&file=" + encode(rom.filename));
    }
    private static String encode(String text) {
        try { return URLEncoder.encode(text, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    @Override public void close() { server.stop(); crcs.close(); }
}
