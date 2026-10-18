package fyi.slot.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.regex.Pattern;

/** Forwards Studio art reads to the one published art host, which refuses cross-origin LAN pages. */
final class ArtProxy {
    static final String HOST = "art.slot-cfw.fyi";
    static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");

    record Fetched(int status, String type, byte[] body) {}
    interface Fetcher { Fetched get(URI uri) throws IOException; }

    private final Fetcher fetcher;

    ArtProxy(Fetcher fetcher) { this.fetcher = fetcher; }
    static ArtProxy network() { return new ArtProxy(ArtProxy::download); }

    /** Null when the path is not one the art set could name. */
    static URI target(List<String> segments) {
        if (segments.isEmpty() || segments.size() > 6) return null;
        StringBuilder path = new StringBuilder();
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches() || segment.contains("..")) return null;
            path.append('/').append(segment);
        }
        try { return new URI("https", HOST, path.toString(), null); }
        catch (URISyntaxException error) { return null; }
    }

    Fetched get(URI uri) throws IOException { return fetcher.get(uri); }

    private static Fetched download(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        try {
            int status = connection.getResponseCode();
            if (status != 200) return new Fetched(status == 404 ? 404 : 502, "text/plain", new byte[0]);
            if (connection.getContentLengthLong() > MAX_BYTES) return new Fetched(502, "text/plain", new byte[0]);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (out.size() + count > MAX_BYTES) return new Fetched(502, "text/plain", new byte[0]);
                    out.write(buffer, 0, count);
                }
            }
            String type = connection.getContentType();
            return new Fetched(200, type == null ? "application/octet-stream" : type, out.toByteArray());
        } finally { connection.disconnect(); }
    }
}
