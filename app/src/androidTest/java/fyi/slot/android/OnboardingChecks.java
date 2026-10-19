package fyi.slot.android;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Owns its samples and restores user preferences after testing the spotlight guide and WebView. */
final class OnboardingChecks {
    static final int COUNT = 12;
    private final InteractionChecks t;
    private final Map<String, Map<String, ?>> saved = new LinkedHashMap<>();
    private File root;
    OnboardingChecks(InteractionChecks tests) { t = tests; }

    void run() {
        ShelfModel previousFixture = MainActivity.testShelf;
        for (String name : List.of("library", "onboarding", "shelf", "language")) saved.put(name, preferences(name).getAll());
        try {
            root = new File(t.getTargetContext().getFilesDir(), "onboarding-checks-" + System.nanoTime());
            require(new File(root, "GBA").mkdirs(), "Owned library");
            try {
                byte[] label = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAIAAAB7QOjdAAAAD0lEQVR4nGO0snJiYGAAAAOUALgNWObMAAAAAElFTkSuQmCC", 0);
                require(new File(root, "Labels/GBA").mkdirs(), "Sample labels");
                for (String name : List.of("Alpha", "Beta")) {
                    Files.write(new File(root, "GBA/" + name + ".gba").toPath(), new byte[512]);
                    Files.write(new File(root, "Labels/GBA/" + name + ".png").toPath(), label);
                }
            } catch (java.io.IOException error) { throw new AssertionError(error); }
            t.check(239, "firstUseAutomaticallyLiftsBayAndBackPersistsDismissal", () -> {
                preferences("library").edit().remove("root").commit(); preferences("onboarding").edit().clear().commit();
                MainActivity.testShelf = null; cold();
                require(t.view().panel().isTargetOpen() && t.home().onboarding().isShowing(), "Automatic floating guide");
                require(!t.home().onboarding().cutouts().isEmpty() && has(R.string.guide_next), "Spotlight and step controls");
                screenshot("guide-first-use");
                t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(450);
                require(!t.home().onboarding().isShowing() && !t.view().panel().isTargetOpen()
                        && new OnboardingPreferences(t.getTargetContext()).isDismissed(), "B dismisses first use");
                cold(); require(!t.home().onboarding().isShowing() && !t.view().panel().isTargetOpen(), "Dismissed guide stays closed");
            });
            choose();
            t.check(240, "guideRulesFourStepsAndCompletionAreTouchReachable", () -> {
                openGuide();
                require(has(R.string.guide_rules_text) && !t.home().onboarding().cutouts().isEmpty(), "Step 1 rules and spotlight");
                screenshot("guide-step-1"); click(R.string.guide_next);
                require(LibraryPanel.GROUP.equals(t.home().settingsPanel().groupId()) && !t.home().onboarding().cutouts().isEmpty(), "Step 2 library spotlight");
                screenshot("guide-step-2"); click(R.string.guide_next);
                require(RetroArchSettings.GROUP.equals(t.home().settingsPanel().groupId()) && !t.home().onboarding().cutouts().isEmpty(), "Step 3 emulator spotlight");
                screenshot("guide-step-3"); click(R.string.guide_next);
                t.settle(450);
                require(!t.view().panel().isTargetOpen() && !t.home().onboarding().bodyText().isEmpty()
                        && t.home().onboarding().cutouts().size() == 2, "Step 4 returns to shelf with category and slot spotlights");
                screenshot("guide-step-4");
                click(R.string.guide_done);
                require(!t.home().onboarding().isShowing() && !t.view().panel().isTargetOpen(), "Completion closes guide on shelf");
            });
            t.check(241, "guideStepRestoresAndAppsShortcutRemainsAvailable", () -> {
                openGuide(); click(R.string.guide_next); click(R.string.guide_next); t.recreateActivity();
                require(t.home().onboarding().isShowing() && t.home().onboarding().step() == 2 && has(R.string.guide_core_text), "Step restored");
                t.runOnMainSync(() -> t.home().onEntry(MachinePanelController.Content.APPS, false));
                require(t.view().panel().content() == MachinePanelController.Content.APPS && !t.home().onboarding().isShowing(), "Apps accessible");
                openGuide(); click(R.string.guide_skip);
                require(!t.home().onboarding().isShowing() && !t.view().panel().isTargetOpen(), "Skip lowers machine");
            });
            t.check(242, "guideGameActionsAndLibraryStudioShowCompleteLibrary", () -> {
                openGuide(); click(R.string.guide_next); require(has(R.string.import_games) && has(R.string.guide_connect), "Game actions");
                click(R.string.edit_cartridges); loaded();
                require("2".equals(js("document.querySelectorAll('.cart').length")), "Whole library mode");
                require("false".equals(js("slotStudioBack()")), "Root back delegates to native");
                nativeKey(KeyEvent.KEYCODE_BUTTON_B); require(!studio().isShowing(), "B closes local library");
                t.runOnMainSync(() -> { t.home().onPanelBack(); t.home().onLibrary(); });
                require(hasInSettings(R.string.edit_cartridges), "Library keeps its edit entry");
                t.runOnMainSync(() -> t.home().settingsPanel().showGroup(HomeCoordinator.ABOUT_GROUP));
                require(hasInSettings(R.string.usage_guide), "About tab holds the guide entry");
                require(!hasInSettings(R.string.edit_cartridges), "Guide entry no longer mixed into the library list");
                click(R.string.usage_guide);
                require(t.home().onboarding().isShowing() && has(R.string.guide_rules_text), "About entry reopens the guide");
            });
            boolean[] directEditorReady = {false}, draftReady = {false};
            t.check(243, "cartridgeMenuDirectlyOpensOnlySelectedCartWithoutPairing", () -> {
                t.runOnMainSync(() -> { t.home().onHome(false); t.home().onCartridgeMenu(cart()); });
                t.settle(350);
                nativeKey(KeyEvent.KEYCODE_DPAD_DOWN); holdConfirmAcrossDialogTransfer(); editor();
                require("\"Alpha\"".equals(js("window.__studio.editing()")), "Direct target");
                require("1".equals(js("document.querySelectorAll('.cart').length")), "Only selected cart");
                require("true".equals(js("getComputedStyle(document.querySelector('[data-kind=customLogo]')).display!=='none' && getComputedStyle(document.querySelector('[data-kind=customLabel]')).display!=='none' && getComputedStyle(document.querySelector('#ed-box-tiles [data-kind=custom]')).display!=='none' && getComputedStyle(document.querySelector('#upload-label')).display==='none'")), "Local custom image pickers enabled while ROM upload stays hidden");
                require("true".equals(js("document.getElementById('pair').hidden")), "No pairing step");
                assertCompactLayout(); screenshot("local-single-cart");
                directEditorReady[0] = true;
            });
            t.check(244, "nativeBackClosesPaletteThenOffersUnsavedDecision", () -> {
                require(directEditorReady[0], "Editor entry failed; dependent draft check cannot run");
                editor(); js("document.getElementById('ed-shell').click()"); nativeKey(KeyEvent.KEYCODE_BUTTON_B);
                require("true".equals(js("document.getElementById('ed-shell-pal').hidden")), "First B closes palette");
                js("document.getElementById('ed-shell').click();document.querySelector('#ed-shell-presets button[data-value=\"auto 249c60 clear\"]').click()");
                nativeKey(KeyEvent.KEYCODE_BUTTON_B); await(() -> "true".equals(js("document.getElementById('decision').open")), "Unsaved decision");
                screenshot("local-unsaved"); nativeKey(KeyEvent.KEYCODE_BUTTON_B);
                require("false".equals(js("document.getElementById('decision').open")) && studio().isShowing(), "B keeps draft");
                draftReady[0] = true;
            });
            t.check(245, "savingSingleCartReturnsAndRefreshesShelfImmediately", () -> {
                require(draftReady[0], "Draft setup failed; dependent save check cannot run");
                File customCover = new File(root, "custom-cover.png");
                Bitmap sample = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
                sample.eraseColor(0xffd04030);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(customCover)) {
                    require(sample.compress(Bitmap.CompressFormat.PNG, 100, out), "Write custom image");
                } catch (java.io.IOException error) { throw new AssertionError(error); }
                finally { sample.recycle(); }
                android.content.IntentFilter filter = new android.content.IntentFilter(android.content.Intent.ACTION_OPEN_DOCUMENT);
                filter.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                try { filter.addDataType("image/*"); } catch (android.content.IntentFilter.MalformedMimeTypeException error) { throw new AssertionError(error); }
                android.app.Instrumentation.ActivityMonitor picker = t.addMonitor(filter, null, true);
                try {
                    js("document.querySelector('[data-kind=customLogo]').click()");
                    await(() -> picker.getHits() == 1, "Custom image picker launched");
                    WebView originalPage = studio().page();
                    t.runOnMainSync(() -> t.home().stop());
                    require(studio().isPickingFile() && studio().isShowing() && studio().page() == originalPage,
                            "Picker departure preserves the editor and its draft");
                    t.runOnMainSync(() -> {
                        t.home().onStudioImagePicked(android.app.Activity.RESULT_CANCELED, null);
                        t.home().start(); t.home().resume();
                    });
                    require(!studio().isPickingFile() && studio().page() == originalPage, "Picker cancellation returns to the same editor");
                    require("false".equals(js("document.querySelector('[data-kind=customLogo]').classList.contains('has-file')")), "Cancellation does not change the custom logo");
                    js("document.querySelector('[data-kind=customLogo]').click()");
                    await(() -> picker.getHits() == 2, "Custom image picker reopens after cancellation");
                    t.runOnMainSync(() -> t.home().onStudioImagePicked(android.app.Activity.RESULT_OK,
                            new android.content.Intent().setData(android.net.Uri.fromFile(customCover))));
                    await(() -> "true".equals(js("document.querySelector('[data-kind=customLogo]').classList.contains('has-file')")), "Custom logo loaded into local editor");
                    js("document.getElementById('ed-box-on').click()");
                    await(() -> picker.getHits() == 3, "Custom box art picker launched");
                    t.runOnMainSync(() -> t.home().onStudioImagePicked(android.app.Activity.RESULT_OK,
                            new android.content.Intent().setData(android.net.Uri.fromFile(customCover))));
                    await(() -> "true".equals(js("document.querySelector('#ed-box-tiles [data-kind=custom]').classList.contains('has-file')")), "Custom box art loaded into local editor");
                } finally { t.removeMonitor(picker); }
                js("document.getElementById('ed-save').click()"); await(() -> !studio().isShowing(), "Save returns to shelf");
                await(() -> cart().plastic == 0xff249c60 && cart().boxart != null, "Saved shell and custom cover on shelf");
                try {
                    require(new String(Files.readAllBytes(new File(root, "Labels/cart_shell.txt").toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("Alpha = auto 249c60 clear"), "Shell written");
                    require(new File(root, "Boxart/GBA/Alpha.png").isFile(), "Custom boxart written");
                } catch (java.io.IOException error) { throw new AssertionError(error); }
            });
            t.check(246, "discardingNativeDraftDoesNotWriteAndReturnsToShelf", () -> {
                t.runOnMainSync(() -> studio().show(cart().rom)); editor();
                js("{const e=document.getElementById('ed-shell-custom');e.value='#abcdef';e.dispatchEvent(new Event('input'));e.dispatchEvent(new Event('change'));}");
                nativeKey(KeyEvent.KEYCODE_BUTTON_B); await(() -> "true".equals(js("document.getElementById('decision').open")), "Discard prompt");
                js("document.getElementById('decision-discard').click()"); await(() -> !studio().isShowing(), "Discard closes");
                require(cart().plastic == 0xff249c60, "Saved shell unchanged");
            });
            t.check(247, "stoppingActivityReleasesNativePageAndLoopbackSocket", () -> {
                t.runOnMainSync(() -> studio().show(null)); loaded(); String origin = js("location.origin").replace("\"", "");
                t.runOnMainSync(() -> t.home().stop()); require(!studio().isShowing() && studio().page() == null, "Page released");
                await(() -> {
                    try (java.net.Socket socket = new java.net.Socket("127.0.0.1", java.net.URI.create(origin).getPort())) { return false; }
                    catch (java.io.IOException closed) { return true; }
                }, "Socket closed");
                t.runOnMainSync(() -> { t.home().start(); t.home().resume(); });
            });
            t.check(248, "nativeStudioUsesSlotLanguageAndControllerCanActivate", () -> {
                for (AppLanguage.Choice choice : List.of(AppLanguage.Choice.ENGLISH, AppLanguage.Choice.CHINESE)) {
                    t.runOnMainSync(() -> { t.view().language().select(choice); t.home().onLanguageChanged(); studio().show(cart().rom); }); editor();
                    require(js("document.getElementById('ed-save').textContent").contains(choice == AppLanguage.Choice.ENGLISH ? "Save" : "\u4FDD\u5B58"), "Slot language override");
                    assertCompactLayout();
                    t.runOnMainSync(() -> studio().page().requestFocus());
                    js("document.getElementById('editor').focus()"); nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                    require("true".equals(js("document.activeElement !== document.getElementById('editor')")), "D-pad moves into web controls");
                    js("document.getElementById('ed-shell').focus()"); nativeKey(KeyEvent.KEYCODE_BUTTON_A);
                    require("false".equals(js("document.getElementById('ed-shell-pal').hidden")), "Gamepad A activates focused web control");
                    screenshot(choice == AppLanguage.Choice.ENGLISH ? "local-en" : "local-zh"); t.runOnMainSync(() -> studio().dismiss());
                }
            });
            t.check(249, "guideBilingualCopyFitsFourAspectRatios", () -> t.runOnMainSync(() -> {
                for (AppLanguage.Choice choice : List.of(AppLanguage.Choice.CHINESE, AppLanguage.Choice.ENGLISH))
                    for (int[] size : new int[][]{{960, 640}, {1024, 768}, {720, 720}, {1280, 720}}) {
                        SlotView shelf = new SlotView(t.activity(), ShelfModel.demo()); HomeCoordinator home = new HomeCoordinator(t.activity(), shelf);
                        try {
                            shelf.language().select(choice); home.onLanguageChanged(); home.onboarding().open();
                            FrameLayout content = home.createContent();
                            for (int step = 0; step < 4; step++) {
                                content.measure(View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
                                content.layout(0, 0, size[0], size[1]);
                                assertTextFits(home.onboarding());
                                RectF card = home.onboarding().cardBounds();
                                require(card.left >= 0 && card.top >= 0 && card.right <= size[0] && card.bottom <= size[1], "Floating card inside screen");
                                require(!home.onboarding().cutouts().isEmpty(), "Step has spotlight cutouts");
                                for (RectF cutout : home.onboarding().cutouts()) {
                                    require(!RectF.intersects(card, cutout), "Floating card must not overlap spotlight cutout at step " + (step + 1));
                                }
                                if (step < 3) invoke(home, R.string.guide_next, shelf.language());
                            }
                        } finally { home.release(); shelf.onDetachedFromWindow(); }
                    }
            }));
            t.check(250, "missingDirectCartHasErrorAndSafeNativeExit", () -> {
                RomRecord target = cart().rom; File file = target.file(), moved = new File(file.getParentFile(), "Alpha.held");
                require(file.renameTo(moved), "Move owned sample");
                try {
                    t.runOnMainSync(() -> studio().show(target)); loaded();
                    require("false".equals(js("document.getElementById('banner').hidden")), "Missing game message");
                    nativeKey(KeyEvent.KEYCODE_BUTTON_B); require(!studio().isShowing(), "Safe exit");
                } finally { require(moved.renameTo(file), "Restore owned sample"); }
            });
        } finally {
            t.runOnMainSync(() -> { studio().dismiss(); t.home().onHome(false); });
            saved.forEach((name, values) -> {
                SharedPreferences.Editor editor = preferences(name).edit().clear();
                values.forEach((key, value) -> {
                    if (value instanceof String text) editor.putString(key, text);
                    else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
                    else if (value instanceof Integer number) editor.putInt(key, number);
                }); editor.commit();
            });
            MainActivity.testShelf = previousFixture; t.recreateActivity(); deleteOwned(root);
        }
    }
    private SharedPreferences preferences(String name) { return t.getTargetContext().getSharedPreferences(name, 0); }
    private LocalStudioDialog studio() { return t.home().localStudio(); }
    private void cold() { t.runOnMainSync(() -> { t.home().onHome(false); t.home().restore(new Bundle()); }); t.recreateActivity(); }
    private void choose() {
        t.runOnMainSync(() -> { try { t.home().library().choose(root); } catch (java.io.IOException error) { throw new AssertionError(error); } });
        await(() -> t.home().library().status() == LibraryRepository.Status.READY, "Sample library ready");
    }
    private Cartridge cart() { return t.home().library().snapshot().stream().filter(c -> c.rom.filename.equals("Alpha.gba")).findFirst().orElseThrow(); }
    private void openGuide() { t.runOnMainSync(() -> t.home().onboarding().open()); t.settle(450); }
    private void click(int label) { t.runOnMainSync(() -> invoke(t.home(), label, t.view().language())); t.settle(150); }
    private void invoke(HomeCoordinator home, int label, AppLanguage language) {
        String text = language.text(label);
        if (home.onboarding().isShowing()) {
            View button = findText(home.onboarding(), text);
            if (button != null && button.isClickable()) { button.performClick(); return; }
        }
        for (int i = 0; i < home.settingsPanel().rowCount(); i++) {
            View row = home.settingsPanel().rowView(i);
            if (row instanceof ViewGroup group && contains(group, text)) { row.performClick(); return; }
        }
        throw new AssertionError("Missing action " + text);
    }
    private boolean has(int label) {
        String text = t.view().language().text(label);
        return contains(t.home().onboarding(), text);
    }
    private boolean hasInSettings(int label) {
        return contains(t.home().settingsPanel(), t.view().language().text(label));
    }
    private View findText(View view, String text) {
        if (view.getVisibility() == View.GONE) return null;
        if (view instanceof TextView value && text.contentEquals(value.getText())) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
    private boolean contains(View view, String text) { return findText(view, text) != null; }
    private void assertTextFits(View view) {
        if (view.getVisibility() == View.GONE) return;
        if (view instanceof TextView text && text.getLayout() != null) for (int i = 0; i < text.getLayout().getLineCount(); i++)
            require(text.getLayout().getEllipsisCount(i) == 0, "Text clips: " + text.getText());
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) assertTextFits(group.getChildAt(i));
    }
    private void loaded() {
        require(studio().isShowing(), "Expected Studio container");
        await(() -> studio().page() != null && "\"true\"".equals(js("document.body?.dataset?.ready")), "Studio ready");
    }
    private void editor() {
        loaded();
        await(() -> "true".equals(js("document.getElementById('editor').open && !document.getElementById('ed-shell').disabled")),
                "Editor ready", 10_000);
    }
    private void assertCompactLayout() {
        require("true".equals(js("(() => { if(innerWidth < 480 || innerWidth > 1100 && innerHeight > 600) return true;"
                + "const body=document.querySelector('.ed-body');body.scrollTop=0;"
                + "const rect=s=>document.querySelector(s).getBoundingClientRect();"
                + "const face=rect('#ed-face'),props=rect('.props'),finish=rect('#ed-finish'),save=rect('#ed-save');"
                + "return face.right<=props.left && face.width<=320 && face.height<=320 && finish.bottom<=save.top"
                + "&& save.bottom<=innerHeight && body.scrollWidth<=body.clientWidth;})()")),
                "Compact editor keeps preview beside readable controls and save visible");
    }
    private String js(String script) {
        String[] result = {""}; CountDownLatch latch = new CountDownLatch(1);
        t.runOnMainSync(() -> {
            WebView page = studio().page(); if (page == null) { latch.countDown(); return; }
            page.evaluateJavascript(script, value -> { result[0] = value; latch.countDown(); });
        });
        try { require(latch.await(4, TimeUnit.SECONDS), "JavaScript callback"); }
        catch (InterruptedException error) { throw new AssertionError(error); }
        return result[0];
    }
    private void nativeKey(int key) { t.sendKeyDownUpSync(key); t.settle(200); }
    private void holdConfirmAcrossDialogTransfer() {
        long down = SystemClock.uptimeMillis();
        t.sendKeySync(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0));
        t.settle(200);
        long up = SystemClock.uptimeMillis();
        t.sendKeySync(new KeyEvent(down, up, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, 0));
        t.settle(200);
    }
    private void await(BooleanSupplier condition, String message) {
        await(condition, message, 60_000);
    }
    private void await(BooleanSupplier condition, String message, long timeout) {
        long until = SystemClock.uptimeMillis() + timeout;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < until) { t.waitForIdleSync(); SystemClock.sleep(50); }
        if (condition.getAsBoolean()) return;
        String state = "showing=" + studio().isShowing() + ", page=" + (studio().page() != null);
        if (studio().page() != null) state += ", " + js("JSON.stringify({url:location.href,ready:document.body.dataset.ready,open:document.getElementById('editor')?.open,disabled:document.getElementById('ed-shell')?.disabled,editing:window.__studio?.editing(),states:window.__studio?.states(),banner:document.getElementById('banner')?.textContent})");
        require(false, message + ": " + state);
    }
    private void screenshot(String name) {
        t.settle(500); Bitmap image = t.getUiAutomation().takeScreenshot(); require(image != null, "Screenshot");
        File folder = new File(t.getTargetContext().getExternalFilesDir(null), "local-studio-onboarding"); require(folder.isDirectory() || folder.mkdirs(), "Screenshot directory");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(folder, name + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
        catch (java.io.IOException error) { throw new AssertionError(error); } finally { image.recycle(); }
    }
    private void deleteOwned(File file) {
        if (file == null) return; File[] children = file.listFiles(); if (children != null) for (File child : children) deleteOwned(child);
        require(!file.exists() || file.delete(), "Remove owned fixture " + file);
    }
    private void require(boolean condition, String text) { t.require(condition, text); }
}
