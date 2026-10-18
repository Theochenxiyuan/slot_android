package fyi.slot.android;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class LocalStudioSessionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void localSessionsUseRandomPortsStrongTokensAndIndependentAuthentication() throws Exception {
        java.io.File root = temp.newFolder("Slot");
        try (LocalStudioSession first = session(root); LocalStudioSession second = session(root)) {
            first.start(); second.start();
            assertNotEquals(first.origin(), second.origin());
            assertTrue(first.origin().startsWith("http://127.0.0.1:"));
            String firstToken = token(first), secondToken = token(second);
            assertEquals(64, firstToken.length()); assertNotEquals(firstToken, secondToken);
            assertEquals(401, get(first.origin(), "api/library", null).getResponseCode());
            assertEquals(401, get(second.origin(), "api/library", firstToken).getResponseCode());
            HttpURLConnection response = get(first.origin(), "api/library", firstToken);
            assertEquals(200, response.getResponseCode());
            assertTrue(new String(response.getInputStream().readAllBytes(), StandardCharsets.UTF_8).contains("\"carts\":[]"));
            assertTrue(response.getHeaderField("Content-Security-Policy").contains("frame-src 'none'"));
            assertEquals("no-store", response.getHeaderField("Cache-Control"));
        }
    }
    @Test public void closingSessionStopsItsSocketAndUnavailableRootIsReported() throws Exception {
        LocalStudioSession studio = session(null);
        studio.start(); String origin = studio.origin();
        assertEquals(503, get(origin, "api/library", token(studio)).getResponseCode());
        studio.close();
        assertThrows(java.net.ConnectException.class, () -> get(origin, "", null).getResponseCode());
    }
    @Test public void directCartUrlEncodesFilenameAndKeepsPlatformAndLanguage() throws Exception {
        try (LocalStudioSession studio = session(temp.newFolder("Encoded"))) {
            studio.start();
            RomRecord rom = new RomRecord(temp.getRoot().getAbsolutePath(), "Games/GBA/\u6E38\u620F + Bonus.gba",
                    Cartridge.Platform.GBA, 512, 0, new byte[0x150], null, false);
            String query = URI.create(studio.url("zh", rom)).getRawQuery();
            assertTrue(query.contains("platform=GBA")); assertTrue(query.contains("lang=zh"));
            String file = java.util.Arrays.stream(query.split("&")).filter(value -> value.startsWith("file=")).findFirst().orElseThrow().substring(5);
            assertEquals(rom.filename, java.net.URLDecoder.decode(file, StandardCharsets.UTF_8));
        }
    }
    private LocalStudioSession session(java.io.File root) {
        return new LocalStudioSession(() -> root, path -> new ByteArrayInputStream("studio".getBytes(StandardCharsets.UTF_8)),
                (file, platforms) -> {}, new ArtProxy(uri -> new ArtProxy.Fetched(404, "text/plain", new byte[0])));
    }
    private static String token(LocalStudioSession session) {
        return java.util.Arrays.stream(URI.create(session.url("zh", null)).getQuery().split("&"))
                .filter(value -> value.startsWith("pin=")).findFirst().orElseThrow().substring(4);
    }
    private static HttpURLConnection get(String origin, String path, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(origin + "/" + path).toURL().openConnection();
        connection.setConnectTimeout(2000); connection.setReadTimeout(3000);
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }
}
