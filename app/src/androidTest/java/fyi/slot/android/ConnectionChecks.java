package fyi.slot.android;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Stage 4: the bay's computer page, the foreground service and the LAN server on the real device. */
final class ConnectionChecks {
    private final InteractionChecks t;
    private File fixture, root;

    record Reply(int status, String type, byte[] body) {
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    ConnectionChecks(InteractionChecks tests) { t = tests; }

    private LibraryRepository library() { return t.home().library(); }
    private SettingsPanel settings() { return t.home().settingsPanel(); }

    void run() {
        SharedPreferences prefs = t.getTargetContext().getSharedPreferences("library", 0);
        String originalRoot = prefs.getString("root", null);
        Map<String, ?> originalSelection = t.getTargetContext().getSharedPreferences("shelf", 0).getAll();
        fixture = new File(t.getTargetContext().getFilesDir(), "connection-checks-" + System.nanoTime());
        root = new File(fixture, "Slot");
        try {
            require(new File(root, "GBA").mkdirs() && new File(root, "GB").mkdirs(), "Owned fixture");
            try { root = root.getCanonicalFile(); fixture = fixture.getCanonicalFile(); }
            catch (IOException error) { throw new AssertionError(error); }
            write(new File(root, "GBA/Garden.gba"), rom(512));
            MainActivity.testShelf = null;
            t.getUiAutomation().grantRuntimePermission(t.getTargetContext().getPackageName(), android.Manifest.permission.POST_NOTIFICATIONS);
            t.recreateActivity();
            t.check(166, "connectionNeedsLibraryThenOpensIt", () -> {
                t.runOnMainSync(() -> { try { library().choose(fixture); } catch (IOException error) { throw new AssertionError(error); } });
                t.runOnMainSync(() -> library().permissionForChecks(() -> false));
                t.runOnMainSync(() -> library().refresh());
                await(() -> library().status() == LibraryRepository.Status.PERMISSION);
                openConnection();
                require(rowCount() == 1 && hasRow(R.string.connection_needs_library), "Turning on waits for a library");
                t.runOnMainSync(() -> library().permissionForChecks(null));
                clickRow(R.string.connection_needs_library);
                require(LibraryPanel.GROUP.equals(settings().groupId()), "Library page opens");
                choose(root);
                await(() -> library().snapshot().size() == 1);
            });
            t.check(167, "connectionTurnsOnWithCodeAddressAndForegroundService", () -> {
                openConnection();
                require(hasRow(R.string.connection_off) && hasRow(R.string.connection_start), "Off state offers turning on");
                clickRow(R.string.connection_start);
                await(() -> ConnectionState.phase() == ConnectionState.Phase.RUNNING);
                t.settle(200);
                require(ConnectionState.pin().matches("[" + DeviceAuth.ALPHABET + "]{6}"), "Six character pairing code");
                require(hasRow(R.string.connection_stop), "Running page offers turning off");
                List<String> addresses = ConnectionState.addresses();
                if (addresses.isEmpty()) require(hasRow(R.string.connection_no_network), "No network state");
                else {
                    require(cardText().contains(ConnectionState.pin()), "Card shows the pairing code");
                    require(cardText().contains(addresses.get(0).replace("http://", "")), "Card shows the first address");
                }
                t.note("connection addresses: " + addresses);
            });
            t.check(168, "controllerSkipsCodeCardAndBackKeepsServiceRunning", () -> {
                openConnection();
                t.key(KeyEvent.KEYCODE_BUTTON_A);
                require(settings().isInRows(), "Rows entered");
                require(rowLabel(settings().selectedRow()) != null, "Focus lands on an actionable row, never the card");
                t.key(KeyEvent.KEYCODE_DPAD_UP);
                require(rowLabel(settings().selectedRow()) != null, "Up stays on actionable rows");
                t.key(KeyEvent.KEYCODE_BUTTON_X); t.settle(450);
                require(t.view().isAppsOpen(), "Apps open while connected");
                t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(700);
                require(!t.view().isSettingsOpen() && !t.view().isAppsOpen(), "Machine closes");
                require(ConnectionState.phase() == ConnectionState.Phase.RUNNING, "Connection keeps running on the shelf");
            });
            t.check(169, "serverServesStudioAndRequiresPairingCode", () -> {
                Reply page = http("GET", "/", null, null);
                require(page.status() == 200 && page.type().startsWith("text/html") && page.text().contains("studio.js"), "Studio page bundled");
                Reply wasm = http("GET", "/pkg/slot_cart_studio_bg.wasm", null, null);
                require(wasm.status() == 200 && "application/wasm".equals(wasm.type()) && wasm.body().length > 100_000, "WASM served");
                require(http("GET", "/api/library", null, null).status() == 401, "No code, no library");
                require(http("GET", "/api/library", "AAAAAA", null).status() == 401, "Wrong code, no library");
                Reply listing = http("GET", "/api/library", ConnectionState.pin(), null);
                require(listing.status() == 200 && listing.text().contains("Garden.gba"), "Paired listing reads the chosen library");
            });
            t.check(170, "computerUploadAppearsOnShelfWithoutLeavingIt", () -> {
                Reply added = http("PUT", "/api/import/GB/Upload%20Moon.gb", ConnectionState.pin(), rom(4096));
                require(added.status() == 201, "Upload accepted: " + added.status());
                await(() -> library().snapshot().stream().anyMatch(cart -> cart.rom.filename.equals("Upload Moon.gb"))
                        && t.view().libraryCount() == 2);
                require(Files.isRegularFile(new File(root, "GB/Upload Moon.gb").toPath()), "ROM written into GB");
                Reply again = http("PUT", "/api/import/GB/Upload%20Moon.gb", ConnectionState.pin(), rom(4096));
                require(again.status() == 200 && again.text().contains("skipped"), "Same name skipped");
                noParts();
            });
            t.check(171, "studioLabelAndShellSavesRedrawTheCartridge", () -> {
                Cartridge before = cart("Garden.gba");
                Reply label = http("PUT", "/api/labels/GBA/Garden.png", ConnectionState.pin(), png());
                require(label.status() == 200 && label.text().contains("written"), "Label written");
                await(() -> cart("Garden.gba").label != null);
                Reply shells = http("GET", "/api/shells", ConnectionState.pin(), null);
                String version = shells.text().replaceAll(".*\"version\":\"([^\"]+)\".*", "$1");
                Reply saved = http("PUT", "/api/shells?version=" + version, ConnectionState.pin(),
                        "Garden = rounded 2f8f4e glitter\n".getBytes(StandardCharsets.UTF_8));
                require(saved.status() == 200, "Shell layer saved");
                await(() -> cart("Garden.gba").finish == CartridgeRenderer.Finish.GLITTER && cart("Garden.gba").plastic == 0xff2f8f4e);
                require(!cart("Garden.gba").appearanceKey.equals(before.appearanceKey), "Appearance cache key changes");
                require(http("PUT", "/api/shells?version=" + version, ConnectionState.pin(), new byte[0]).status() == 409, "Stale save refused");
                String keyBefore = cart("Garden.gba").appearanceKey;
                Reply boxart = http("PUT", "/api/boxart/GBA/Garden.png", ConnectionState.pin(), png());
                require(boxart.status() == 200 && boxart.text().contains("written"), "Box art written");
                await(() -> cart("Garden.gba").boxart != null);
                require(Files.isRegularFile(new File(root, "Boxart/GBA/Garden.png").toPath()), "Box art kept beside the labels");
                require(cart("Garden.gba").appearanceKey.equals(keyBefore) && cart("Garden.gba").label != null,
                        "Box art leaves the cartridge's own appearance alone");
                require(http("GET", "/api/library", ConnectionState.pin(), null).text().contains("\"boxarts\":[{"), "Listing names the box art");
                require(http("PUT", "/api/boxart/GBA/Garden.png", ConnectionState.pin(), png()).text().contains("skipped"), "Box art is not overwritten");
            });
            t.check(236, "computerDeletesExclusiveAppearanceKeepsSavesAndRefreshesEmptyShelf", () -> {
                File save = new File(root, "GBA/Garden.srm");
                write(save, new byte[]{1, 2, 3});
                require(http("DELETE", "/api/games/GBA/Garden.gba", null, null).status() == 401, "Deletion requires pairing");
                require(http("DELETE", "/api/games/GBA/Garden.gba", ConnectionState.pin(), null).status() == 200, "Deletion succeeds");
                await(() -> library().snapshot().size() == 1 && t.view().libraryCount() == 1);
                require(!new File(root, "GBA/Garden.gba").exists(), "Game deleted");
                require(!new File(root, "Labels/GBA/Garden.png").exists() && !new File(root, "Boxart/GBA/Garden.png").exists(), "Appearance deleted");
                require(save.isFile(), "Save retained");
                require(!http("GET", "/api/shells", ConnectionState.pin(), null).text().contains("Garden ="), "Shell override deleted");
                require(http("DELETE", "/api/games/GB/Upload%20Moon.gb", ConnectionState.pin(), null).status() == 200, "Last game deleted");
                await(() -> library().status() == LibraryRepository.Status.EMPTY && t.view().libraryCount() == 0);
            });
            t.check(172, "statusBarShowsConnectionOnlyWhileOn", () -> t.runOnMainSync(() -> {
                SlotView view = new SlotView(t.getTargetContext(), ShelfModel.demo());
                try {
                    view.layout(0, 0, 960, 640);
                    Bitmap on = draw(view);
                    ConnectionState.Phase phase = ConnectionState.phase();
                    ConnectionState.set(ConnectionState.Phase.STOPPED);
                    Bitmap off = draw(view);
                    ConnectionState.set(phase);
                    HomeLayout layout = view.homeLayout();
                    int changed = 0;
                    int left = Math.round(layout.toScreenX(layout.width - 174)), right = Math.round(layout.toScreenX(layout.width - 136));
                    int top = Math.round(layout.toScreenY(26)), bottom = Math.round(layout.toScreenY(47));
                    for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) if (on.getPixel(x, y) != off.getPixel(x, y)) changed++;
                    on.recycle(); off.recycle();
                    t.require(changed > 20, "Connection icon drawn beside the battery, changed=" + changed);
                } finally { view.onDetachedFromWindow(); }
            }));
            t.check(173, "turningOffClosesThePortAndOffersTurningOnAgain", () -> {
                openConnection();
                clickRow(R.string.connection_stop);
                await(() -> ConnectionState.phase() == ConnectionState.Phase.STOPPED);
                awaitStopped();
                require(hasRow(R.string.connection_start), "Off page offers turning on again");
                String previous = ConnectionState.pin();
                require(previous.isEmpty(), "Code forgotten while off");
            });
            t.check(174, "notificationStopActionEndsConnection", () -> {
                openConnection();
                clickRow(R.string.connection_start);
                await(() -> ConnectionState.phase() == ConnectionState.Phase.RUNNING);
                android.app.NotificationManager manager = t.getTargetContext().getSystemService(android.app.NotificationManager.class);
                android.app.Notification shown = null;
                long until = SystemClock.uptimeMillis() + 5000;
                while (shown == null && SystemClock.uptimeMillis() < until) {
                    for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications())
                        if (active.getId() == 1092) shown = active.getNotification();
                    if (shown == null) SystemClock.sleep(50);
                }
                require(shown != null && shown.actions != null && shown.actions.length == 1, "Ongoing notification with one action");
                require(t.view().language().text(R.string.connection_stop).contentEquals(shown.actions[0].title), "Action turns the connection off");
                String text = String.valueOf(shown.extras.getCharSequence(android.app.Notification.EXTRA_TEXT));
                require(text.contains(ConnectionState.pin()), "Notification repeats the pairing code");
                try { shown.actions[0].actionIntent.send(); }
                catch (android.app.PendingIntent.CanceledException error) { throw new AssertionError(error); }
                await(() -> ConnectionState.phase() == ConnectionState.Phase.STOPPED);
                awaitStopped();
                require(hasRow(R.string.connection_start), "Page offers turning on again");
            });
            t.check(175, "connectionPageUsesBothLanguages", () -> {
                AppLanguage.Choice original = t.view().language().choice();
                try {
                    for (AppLanguage.Choice choice : List.of(AppLanguage.Choice.CHINESE, AppLanguage.Choice.ENGLISH)) {
                        t.runOnMainSync(() -> { t.view().language().select(choice); t.home().onLanguageChanged(); });
                        openConnection();
                        require(hasRow(R.string.connection_start), "Localized start row " + choice);
                        int tab = -1;
                        for (int i = 0; i < settings().groupCount(); i++) {
                            int index = i;
                            String[] text = new String[1];
                            t.runOnMainSync(() -> text[0] = ((TextView) settings().tabView(index)).getText().toString());
                            if (text[0].equals(t.view().language().text(R.string.settings_connection))) tab = i;
                        }
                        require(tab == 3, "Computer tab between library and emulator");
                    }
                } finally {
                    t.runOnMainSync(() -> { t.view().language().select(original); t.home().onLanguageChanged(); });
                    t.getTargetContext().getSharedPreferences("language", 0).edit().putString("choice", original.name()).commit();
                }
            });
        } finally {
            t.runOnMainSync(() -> ConnectionState.stop(t.getTargetContext()));
            awaitStopped();
            t.runOnMainSync(() -> library().permissionForChecks(null));
            SharedPreferences.Editor restore = prefs.edit();
            if (originalRoot == null) restore.remove("root"); else restore.putString("root", originalRoot);
            restore.commit();
            SharedPreferences.Editor selected = t.getTargetContext().getSharedPreferences("shelf", 0).edit().clear();
            originalSelection.forEach((key, value) -> { if (value instanceof String text) selected.putString(key, text); });
            selected.commit();
            MainActivity.testShelf = null; t.recreateActivity(); deleteOwned(fixture);
        }
    }

    private static Bitmap draw(SlotView view) {
        Bitmap frame = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(frame));
        return frame;
    }

    private void openConnection() {
        t.runOnMainSync(() -> { t.home().libraryPanel().back(); t.home().onLibrary(); settings().showGroup(ConnectionPanel.GROUP); });
        t.settle(450);
        require(ConnectionPanel.GROUP.equals(settings().groupId()), "Computer page visible");
    }

    /** The row's label, or null for the read-only code card. */
    private String rowLabel(int index) {
        String[] label = new String[1];
        t.runOnMainSync(() -> {
            View row = settings().rowView(index);
            if (row instanceof LinearLayout group && group.getChildAt(0) instanceof TextView text && group.getChildCount() == 2
                    && group.getChildAt(1) instanceof TextView) label[0] = text.getText().toString();
        });
        return label[0];
    }

    private int rowCount() { int[] count = new int[1]; t.runOnMainSync(() -> count[0] = settings().rowCount()); return count[0]; }

    private int findRow(int text) {
        String wanted = t.view().language().text(text);
        for (int i = 0; i < rowCount(); i++) if (wanted.equals(rowLabel(i))) return i;
        return -1;
    }

    private boolean hasRow(int text) { return findRow(text) >= 0; }

    private void clickRow(int text) {
        int row = findRow(text);
        require(row >= 0, "Row " + t.view().language().text(text));
        t.tapSettingsRow(row);
        t.settle(150);
    }

    private String cardText() {
        StringBuilder out = new StringBuilder();
        t.runOnMainSync(() -> { for (int i = 0; i < settings().rowCount(); i++) collect(settings().rowView(i), out); });
        return out.toString();
    }

    private static void collect(View view, StringBuilder out) {
        if (view instanceof TextView text) out.append(text.getText()).append('\n');
        if (view instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
    }

    /** Raw HTTP/1.1 over a socket, as a browser on the LAN would send it; the app itself never opens cleartext URLs. */
    private Reply http(String method, String path, String pin, byte[] body) {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", DeviceServer.PORT)) {
            socket.setSoTimeout(15000);
            StringBuilder head = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + DeviceServer.PORT + "\r\n");
            if (pin != null) head.append("Authorization: Bearer ").append(pin).append("\r\n");
            if (body != null) head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("\r\n");
            OutputStream output = socket.getOutputStream();
            output.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if (body != null) output.write(body);
            output.flush();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) all.write(buffer, 0, count);
            byte[] bytes = all.toByteArray();
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            int split = text.indexOf("\r\n\r\n");
            String[] lines = text.substring(0, split).split("\r\n");
            String type = "";
            for (String line : lines) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-type:")) type = line.substring(13).trim();
            return new Reply(Integer.parseInt(lines[0].split(" ")[1]), type, java.util.Arrays.copyOfRange(bytes, split + 4, bytes.length));
        } catch (IOException error) { throw new AssertionError(method + " " + path, error); }
    }

    private void awaitStopped() {
        long until = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < until) {
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", DeviceServer.PORT)) { SystemClock.sleep(100); }
            catch (ConnectException closed) { return; }
            catch (IOException other) { return; }
        }
        throw new AssertionError("Port still open after turning off");
    }

    private static byte[] rom(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 13);
        return bytes;
    }

    private static byte[] png() {
        Bitmap image = Bitmap.createBitmap(320, 165, Bitmap.Config.ARGB_8888);
        image.eraseColor(0xffd65224);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.PNG, 100, out);
        image.recycle();
        return out.toByteArray();
    }

    private Cartridge cart(String name) {
        return library().snapshot().stream().filter(cart -> cart.rom.filename.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + name));
    }

    private void choose(File next) {
        t.runOnMainSync(() -> { try { library().choose(next); } catch (IOException error) { throw new AssertionError(error); } });
        await(() -> library().status() != LibraryRepository.Status.LOADING);
    }

    private void noParts() {
        try (var paths = Files.walk(root.toPath())) { require(paths.noneMatch(path -> path.toString().endsWith(".part")), "No residual partial files"); }
        catch (IOException error) { throw new AssertionError(error); }
    }

    private static void write(File file, byte[] bytes) {
        try { Files.write(file.toPath(), bytes); } catch (IOException error) { throw new AssertionError(error); }
    }

    private void await(BooleanSupplier condition) {
        long until = SystemClock.uptimeMillis() + 10000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < until) { t.waitForIdleSync(); SystemClock.sleep(40); }
        require(condition.getAsBoolean(), "Timeout, library=" + library().status() + ", connection=" + ConnectionState.phase());
        t.waitForIdleSync();
    }

    private void require(boolean condition, String message) { t.require(condition, message); }

    private void deleteOwned(File directory) {
        File[] children = directory.listFiles();
        if (children != null) for (File file : children) deleteOwned(file);
        if (directory.exists() && !directory.delete()) android.util.Log.w("SlotChecks", "Fixture remains: " + directory);
    }
}
