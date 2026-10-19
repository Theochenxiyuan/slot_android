package fyi.slot.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Owned ROMs and intercepted external intents; never changes installed RetroArch or user ROMs. */
final class RetroArchFixture implements AutoCloseable {
    final InteractionChecks t;
    final List<Intent> launches = new ArrayList<>();
    boolean sawLaunchingFeedback;
    float informationOpacityAtDispatch;
    private final Map<String, Map<String, ?>> saved = new java.util.HashMap<>();
    private final ShelfModel originalShelf;
    private final AppLanguage.Choice originalLanguage;
    private final File root;
    private final Instrumentation.ActivityMonitor monitor;

    RetroArchFixture(InteractionChecks tests) {
        t = tests; originalShelf = MainActivity.testShelf; originalLanguage = t.view().language().choice();
        for (String name : List.of("library", "shelf", "shelf_collections", "game_cores", "retroarch", "interaction")) {
            saved.put(name, t.getTargetContext().getSharedPreferences(name, 0).getAll());
        }
        root = new File(t.getTargetContext().getFilesDir(), "retroarch-checks-" + System.nanoTime());
        for (String path : List.of("GBA/Garden.gba", "GB/Moon.gb", "GBC/Lake.gbc")) write(path);
        monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (intent.getComponent() != null && RetroArchLauncher.PACKAGES.contains(intent.getComponent().getPackageName())) {
                    launches.add(new Intent(intent));
                    return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                }
                return null;
            }
        };
        t.addMonitor(monitor);
        t.getTargetContext().getSharedPreferences("library", 0).edit().putString("root", root.getAbsolutePath()).commit();
        t.getTargetContext().getSharedPreferences("shelf_collections", 0).edit().clear().commit();
        t.getTargetContext().getSharedPreferences("game_cores", 0).edit().clear().commit();
        t.getTargetContext().getSharedPreferences("retroarch", 0).edit().clear().commit();
        t.getTargetContext().getSharedPreferences("interaction", 0).edit().putBoolean("auto_launch_on_insert", true).commit();
        MainActivity.testShelf = null; t.recreateActivity(); ready(); interceptSuccess();
    }

    void ready() { await(() -> t.home().library().status() == LibraryRepository.Status.READY && t.view().libraryCount() == 3); }
    void write(String path) {
        try {
            File file = new File(root, path);
            t.require(file.getParentFile().isDirectory() || file.getParentFile().mkdirs(), "Owned ROM directory");
            Files.write(file.toPath(), new byte[512]);
        } catch (IOException error) { throw new AssertionError(error); }
    }
    Cartridge cart(String filename) {
        return t.home().library().snapshot().stream().filter(cart -> cart.rom.filename.equals(filename)).findFirst().orElseThrow();
    }
    void select(String filename, boolean inserted) {
        Cartridge cartridge = cart(filename);
        t.runOnMainSync(() -> {
            Bundle state = new Bundle(); state.putString("shelf_category", "ALL");
            state.putString("shelf_selected_ALL", cartridge.id); state.putBoolean("inserted", inserted);
            t.view().restore(state); t.view().resume();
        });
        t.settle(250);
    }
    void interceptSuccess() {
        t.runOnMainSync(() -> t.home().gameLauncher = rom -> {
            sawLaunchingFeedback = t.view().isLaunchingFeedbackShown();
            informationOpacityAtDispatch = t.view().seatedInformationOpacity();
            RetroArchPreferences preferences = new RetroArchPreferences(t.activity());
            t.activity().startActivity(new RetroArchLauncher(t.activity(), preferences).buildIntent(rom, RetroArchLauncher.AARCH64));
            return RetroArchLauncher.Result.STARTED;
        });
    }
    void returnToSlot() {
        t.runOnMainSync(() -> { t.view().pause(); t.view().resume(); t.home().onHome(false); });
        t.settle(150);
    }
    void await(BooleanSupplier predicate) {
        long limit = SystemClock.uptimeMillis() + 8000;
        while (!predicate.getAsBoolean() && SystemClock.uptimeMillis() < limit) t.settle(25);
        t.require(predicate.getAsBoolean(), "Expected state before timeout");
    }
    void nativeKey(int code) { t.sendKeyDownUpSync(code); t.settle(100); }
    void nativeTap(View view) {
        float[] point = new float[2];
        t.runOnMainSync(() -> {
            int[] location = new int[2]; view.getLocationOnScreen(location);
            point[0] = location[0] + view.getWidth() / 2f; point[1] = location[1] + view.getHeight() / 2f;
        });
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, point[0], point[1], 0);
            t.sendPointerSync(event); event.recycle();
        }
        t.settle(150);
    }
    void screenshot(String name) {
        // Native window enter/exit animations outlive Instrumentation's idle barrier.
        t.settle(600);
        android.graphics.Bitmap image = t.getUiAutomation().takeScreenshot();
        t.require(image != null, "Device screenshot");
        File folder = new File(t.getTargetContext().getExternalFilesDir(null), "retroarch-stage6");
        t.require(folder.isDirectory() || folder.mkdirs(), "Screenshot directory");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(folder, name + ".png"))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException error) { throw new AssertionError(error); }
        finally { image.recycle(); }
    }
    @Override public void close() {
        t.removeMonitor(monitor);
        t.runOnMainSync(() -> {
            t.home().cartridgeDetails().dismiss();
            t.view().language().select(originalLanguage); t.home().onLanguageChanged();
            saved.forEach((name, values) -> {
                SharedPreferences.Editor editor = t.getTargetContext().getSharedPreferences(name, 0).edit().clear();
                values.forEach((key, value) -> {
                    if (value instanceof String text) editor.putString(key, text);
                    else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
                });
                editor.commit();
            });
            t.getTargetContext().getSharedPreferences("language", 0).edit().putString("choice", originalLanguage.name()).commit();
            Bundle state = new Bundle(); t.view().restore(state);
        });
        MainActivity.testShelf = originalShelf; t.recreateActivity();
        deleteOwned(root);
    }
    private void deleteOwned(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteOwned(child);
        t.require(file.delete(), "Remove owned fixture " + file);
    }
}
