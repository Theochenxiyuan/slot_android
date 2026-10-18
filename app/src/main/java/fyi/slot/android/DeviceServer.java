package fyi.slot.android;

import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** LAN HTTP server: the bundled Studio page, the paired API and the art proxy, all from one origin. */
final class DeviceServer extends NanoHTTPD {
    interface Assets { InputStream open(String path) throws IOException; }

    static final int PORT = 1092, MAX_CLIENTS = 8, SOCKET_TIMEOUT = 30_000;
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern ASSET = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*");
    private final DeviceAuth auth;
    private final DeviceApi api;
    private final Assets assets;
    private final ArtProxy art;
    private final Consumer<String> log;
    private final boolean loopback;

    DeviceServer(int port, DeviceAuth auth, DeviceApi api, Assets assets, ArtProxy art, Consumer<String> log) {
        this(null, port, auth, api, assets, art, log);
    }

    DeviceServer(String address, int port, DeviceAuth auth, DeviceApi api, Assets assets, ArtProxy art, Consumer<String> log) {
        super(address, port);
        loopback = "127.0.0.1".equals(address);
        this.auth = auth; this.api = api; this.assets = assets; this.art = art; this.log = log;
        setAsyncRunner(new BoundedRunner(MAX_CLIENTS));
    }

    void startServer() throws IOException { start(SOCKET_TIMEOUT, true); }

    @Override protected boolean useGzipWhenAccepted(Response response) { return false; }

    @Override public Response serve(IHTTPSession session) {
        long started = System.currentTimeMillis();
        Response response;
        long length = length(session.getHeaders().get("content-length"));
        Bounded body = new Bounded(session.getInputStream(), Math.max(0, length));
        try { response = dispatch(session, length, body); }
        catch (Exception error) {
            log.accept("failed " + session.getUri() + ": " + error);
            response = json(500, Json.error("internal"));
        }
        // A reply sent before the body is read (a skipped upload, a stale code) must not close on
        // unread bytes, or the browser sees a reset instead of the answer.
        body.drain(ImportWriter.MAX_BYTES);
        response.addHeader("Connection", "close");
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("Referrer-Policy", "no-referrer");
        if (loopback) response.addHeader("Content-Security-Policy", "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' https://raw.githubusercontent.com; "
                + "frame-src 'none'; object-src 'none'; base-uri 'none'");
        response.closeConnection(true);
        log.accept(session.getRemoteIpAddress() + " " + session.getMethod() + " " + session.getUri()
                + " -> " + response.getStatus().getRequestStatus() + " (" + (System.currentTimeMillis() - started) + "ms)");
        return response;
    }

    private Response dispatch(IHTTPSession session, long length, InputStream body) throws IOException {
        Map<String, String> headers = session.getHeaders();
        String host = headers.get("host");
        if (!hostAllowed(host, getListeningPort())) return json(403, Json.error("host"));
        if (loopback && !host.equals("127.0.0.1:" + getListeningPort())) return json(403, Json.error("host"));
        String origin = headers.get("origin");
        if (origin != null && !origin.equals("http://" + host)) return json(403, Json.error("origin"));
        String method = session.getMethod() == null ? "" : session.getMethod().name();
        List<String> path = segments(session.getUri());
        if (path == null) return json(400, Json.error("path"));
        boolean apiRequest = !path.isEmpty() && path.get(0).equals("api");
        boolean artRequest = !path.isEmpty() && path.get(0).equals("art");
        if (!apiRequest && !artRequest) return method.equals("GET") || method.equals("HEAD") ? asset(path) : json(405, Json.error("method"));
        List<String> rest = path.subList(1, path.size());
        if (apiRequest && rest.equals(List.of("auth")) && method.equals("GET"))
            return noStore(json(200, Json.object().put("required", true).end()));
        DeviceAuth.Result result = auth.check(headers.get("authorization"));
        if (result == DeviceAuth.Result.LOCKED) return json(429, Json.error("locked"));
        if (result != DeviceAuth.Result.OK) return json(401, Json.error("pin"));
        if (artRequest) return method.equals("GET") ? art(rest) : json(405, Json.error("method"));
        Map<String, String> query = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : session.getParameters().entrySet())
            if (!entry.getValue().isEmpty()) query.put(entry.getKey(), entry.getValue().get(0));
        DeviceApi.Reply reply = api.handle(new DeviceApi.Request(method, rest, query, length, body));
        return noStore(bytes(reply.status(), reply.type(), reply.body()));
    }

    /** Only literal addresses and localhost, which keeps rebinding domain names out. */
    static boolean hostAllowed(String host, int port) {
        if (host == null) return false;
        String name;
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end < 0 || !host.substring(end + 1).equals(":" + port)) return false;
            name = host.substring(1, end);
            return name.matches("[0-9A-Fa-f:.%a-zA-Z0-9]+") && name.contains(":");
        }
        int colon = host.lastIndexOf(':');
        if (colon < 0 || !host.substring(colon + 1).equals(Integer.toString(port))) return false;
        name = host.substring(0, colon).toLowerCase(Locale.ROOT);
        if (name.equals("localhost")) return true;
        java.util.regex.Matcher matcher = IPV4.matcher(name);
        if (!matcher.matches()) return false;
        for (int i = 1; i <= 4; i++) if (Integer.parseInt(matcher.group(i)) > 255) return false;
        return true;
    }

    /** Decoded path segments, or null when any segment could step outside its folder. */
    static List<String> segments(String uri) {
        if (uri == null || !uri.startsWith("/")) return null;
        List<String> out = new ArrayList<>(Arrays.asList(uri.substring(1).split("/", -1)));
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        for (String segment : out) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.indexOf('\\') >= 0
                    || segment.indexOf('\0') >= 0) return null;
        }
        return out;
    }

    private static long length(String header) {
        if (header == null) return -1;
        try { long value = Long.parseLong(header.trim()); return value < 0 ? -1 : value; }
        catch (NumberFormatException error) { return -1; }
    }

    private Response asset(List<String> path) {
        List<String> parts = path.isEmpty() ? List.of("index.html") : path;
        if (parts.size() > 3) return json(404, Json.error("not_found"));
        for (String part : parts) if (!ASSET.matcher(part).matches()) return json(404, Json.error("not_found"));
        String name = String.join("/", parts);
        try (InputStream input = assets.open(name)) {
            Response response = bytes(200, mime(name), DeviceApi.readAll(input, 32 * 1024 * 1024));
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (IOException missing) {
            return json(404, Json.error("not_found"));
        }
    }

    private Response art(List<String> path) {
        URI target = ArtProxy.target(path);
        if (target == null) return json(404, Json.error("not_found"));
        try {
            ArtProxy.Fetched fetched = art.get(target);
            if (fetched.status() != 200) return json(fetched.status() == 404 ? 404 : 502, Json.error("art"));
            Response response = bytes(200, fetched.type(), fetched.body());
            response.addHeader("Cache-Control", "private, max-age=3600");
            return response;
        } catch (IOException error) {
            return json(502, Json.error("art"));
        }
    }

    static String mime(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static Response noStore(Response response) { response.addHeader("Cache-Control", "no-store"); return response; }

    private static Response json(int status, String body) {
        return bytes(status, "application/json; charset=utf-8", body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Response bytes(int status, String type, byte[] body) {
        return newFixedLengthResponse(status(status), type, new ByteArrayInputStream(body), body.length);
    }

    private static Response.IStatus status(int code) {
        for (Response.Status status : Response.Status.values()) if (status.getRequestStatus() == code) return status;
        return new Response.IStatus() {
            @Override public String getDescription() { return code + " " + switch (code) {
                case 411 -> "Length Required"; case 413 -> "Payload Too Large"; case 415 -> "Unsupported Media Type";
                case 429 -> "Too Many Requests"; case 502 -> "Bad Gateway"; case 503 -> "Service Unavailable";
                case 507 -> "Insufficient Storage"; default -> "Status"; }; }
            @Override public int getRequestStatus() { return code; }
        };
    }

    /** A request body that ends where Content-Length says, so readers never wait on the socket. */
    private static final class Bounded extends FilterInputStream {
        private long left;
        Bounded(InputStream input, long length) { super(input); left = length; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int value = super.read();
            if (value >= 0) left--;
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            if (left <= 0) return -1;
            int read = super.read(buffer, offset, (int) Math.min(count, left));
            if (read > 0) left -= read;
            return read;
        }
        @Override public void close() { }
        void drain(long limit) {
            if (left <= 0 || left > limit) return;
            byte[] buffer = new byte[65536];
            try { while (read(buffer, 0, buffer.length) > 0) { } }
            catch (IOException gone) { left = 0; }
        }
    }

    /** At most a few requests at once; extra connections are closed instead of queued. */
    private static final class BoundedRunner implements AsyncRunner {
        private final java.util.concurrent.ThreadPoolExecutor pool;
        private final java.util.Set<ClientHandler> running = java.util.concurrent.ConcurrentHashMap.newKeySet();

        BoundedRunner(int clients) {
            pool = new java.util.concurrent.ThreadPoolExecutor(clients, clients, 30, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<>(), runnable -> {
                        Thread thread = new Thread(runnable, "slot-http");
                        thread.setDaemon(true);
                        return thread;
                    });
            pool.allowCoreThreadTimeOut(true);
        }

        @Override public void closeAll() { for (ClientHandler handler : new ArrayList<>(running)) handler.close(); }
        @Override public void closed(ClientHandler handler) { running.remove(handler); }
        @Override public void exec(ClientHandler handler) {
            running.add(handler);
            try { pool.execute(handler); }
            catch (java.util.concurrent.RejectedExecutionException busy) { running.remove(handler); handler.close(); }
        }
    }
}
