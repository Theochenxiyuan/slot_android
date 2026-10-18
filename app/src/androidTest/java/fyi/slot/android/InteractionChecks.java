package fyi.slot.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Dependency-free instrumented checks using actual Android input and rendered frames. */
public final class InteractionChecks extends Instrumentation {
    private volatile MainActivity activity;
    private int failures;
    private final StringBuilder results = new StringBuilder();
    private static final int COUNT = 254;
    private boolean artworkOnly, studioOnly;
    private boolean onboardingOnly;
    private boolean retroArchOnly;
    private boolean launchProbeOnly;
    private boolean retroArchSettingsOnly;
    private boolean retroArchPresetsOnly;
    private boolean lettersOnly;
    private boolean languageOnly;
    private boolean libraryOnly;
    private boolean managementOnly;
    private boolean libraryManagementOnly;
    private boolean connectionOnly;
    private boolean drawingOnly;
    private boolean animationOnly;
    private boolean animationTailOnly;
    private boolean swipeOnly;
    private int checkOnly;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        artworkOnly = arguments != null && "artwork".equals(arguments.getString("suite"));
        studioOnly = arguments != null && "studio".equals(arguments.getString("suite"));
        onboardingOnly = arguments != null && "onboarding".equals(arguments.getString("suite"));
        launchProbeOnly = arguments != null && "retroarch-probe".equals(arguments.getString("suite"));
        retroArchOnly = arguments != null && "retroarch".equals(arguments.getString("suite"));
        retroArchSettingsOnly = arguments != null && "retroarch-settings".equals(arguments.getString("suite"));
        retroArchPresetsOnly = arguments != null && "retroarch-presets".equals(arguments.getString("suite"));
        lettersOnly = arguments != null && "letters".equals(arguments.getString("suite"));
        languageOnly = arguments != null && "language".equals(arguments.getString("suite"));
        libraryManagementOnly = arguments != null && "library-management".equals(arguments.getString("suite"));
        libraryOnly = libraryManagementOnly || arguments != null && "library".equals(arguments.getString("suite"));
        managementOnly = arguments != null && "management".equals(arguments.getString("suite"));
        connectionOnly = arguments != null && "connection".equals(arguments.getString("suite"));
        drawingOnly = arguments != null && "drawing".equals(arguments.getString("suite"));
        animationOnly = arguments != null && "animation".equals(arguments.getString("suite"));
        animationTailOnly = arguments != null && "animation-tail".equals(arguments.getString("suite"));
        swipeOnly = arguments != null && "swipe".equals(arguments.getString("suite"));
        checkOnly = arguments == null ? 0 : Integer.parseInt(arguments.getString("check", "0"));
        MainActivity.testShelf = studioOnly || launchProbeOnly || libraryOnly || managementOnly || connectionOnly ? null : ShelfModel.demo();
        start();
    }

    @Override public void callActivityOnCreate(Activity created, Bundle icicle) {
        if (created instanceof MainActivity main) activity = main;
        super.callActivityOnCreate(created, icicle);
    }

    @Override public void onStart() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long deadline = SystemClock.uptimeMillis() + 5000;
            while ((activity == null || activity.isFinishing() || activity.isDestroyed())
                    && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(15);
            }
        }
        waitForIdleSync(); SystemClock.sleep(400);
        if (artworkOnly) { new ArtworkChecks(this).run(); finishChecks(); return; }
        if (onboardingOnly || studioOnly) { new OnboardingChecks(this).run(); finishChecks(); return; }
        if (launchProbeOnly) { check(1, "probeExternalIntentResolution", () -> new RetroArchLaunchProbe(this).run()); finishChecks(); return; }
        if (retroArchOnly) { new RetroArchChecks(this).run(); finishChecks(); return; }
        if (retroArchSettingsOnly) { new RetroArchSettingsChecks(this).run(); finishChecks(); return; }
        if (retroArchPresetsOnly) { new RetroArchPresetChecks(this).run(); finishChecks(); return; }
        if (animationTailOnly) { new AnimationChecks(this).runTailCheck(); finishChecks(); return; }
        if (swipeOnly) { new SwipeChecks(this).run(); finishChecks(); return; }
        if (drawingOnly) { new DrawingChecks(this).run(); finishChecks(); return; }
        if (lettersOnly) { new LetterNavigationChecks(this).run(); finishChecks(); return; }
        if (libraryOnly) {
            new LibraryChecks(this).run();
            if (libraryManagementOnly) new LibraryManagementChecks(this).run();
            finishChecks(); return;
        }
        if (managementOnly) { new LibraryManagementChecks(this).run(); finishChecks(); return; }
        if (connectionOnly) { new ConnectionChecks(this).run(); finishChecks(); return; }
        if (languageOnly) {
            results.append(LanguageResourceProbe.report(getTargetContext()));
            new LanguageChecks(this).run();
            finishChecks();
            return;
        }
        boolean originalSwipe = view().interactionPreferences().swipeToInsert;
        boolean originalHaptics = view().interactionPreferences().haptics;
        boolean originalSound = view().interactionPreferences().sound;
        boolean originalAutoLaunch = view().interactionPreferences().autoLaunchOnInsert;
        boolean originalLight = view().interactionPreferences().lightTheme;
        InteractionPreferences.Wallpaper originalWallpaper = view().interactionPreferences().wallpaper;
        InteractionPreferences.WallpaperTint originalTint = view().interactionPreferences().wallpaperTint;
        boolean originalShowTitles = view().interactionPreferences().showTitles;
        InteractionPreferences.ButtonPrompts originalPrompts = view().interactionPreferences().buttonPrompts;
        boolean originalSwapConfirmBack = view().interactionPreferences().swapConfirmBack;
        runOnMainSync(() -> {
            view().interactionPreferences().swipeToInsert = false;
            view().interactionPreferences().autoLaunchOnInsert = false;
            view().interactionPreferences().lightTheme = false;
            view().interactionPreferences().wallpaper = InteractionPreferences.Wallpaper.NONE;
            view().interactionPreferences().wallpaperTint = InteractionPreferences.WallpaperTint.OFF;
            view().interactionPreferences().showTitles = false;
            view().interactionPreferences().buttonPrompts = InteractionPreferences.ButtonPrompts.XBOX;
            view().interactionPreferences().swapConfirmBack = false;
            view().interactionPreferences().save();
        });
        check(1, "startsOnShelf", () -> {
            require(view().currentMode() == SlotView.Mode.SHELF, "Expected shelf");
            require(view().selectedIndex() == 1, "Expected center sample");
        });
        check(2, "dragFollowsFingerAndShortReleaseReturns", () -> {
            reset(1, false);
            dragStart(360, 245); dragMove(360, 330);
            require(view().insertionProgress() > .25f && view().insertionProgress() < .35f,
                    "Cartridge must follow 85 px drag immediately");
            require(view().currentMode() == SlotView.Mode.SHELF, "Must not insert while held");
            dragEnd(360, 330); settle(900);
            require(view().insertionProgress() < .01f, "Short drag must spring back");
            require(view().currentMode() == SlotView.Mode.SHELF, "Short drag must not launch");
        });
        check(3, "cancelBeyondThresholdDoesNotInsert", () -> {
            reset(1, false); dragStart(360, 210); dragMove(360, 445);
            require(view().insertionProgress() > SlotView.COMMIT,
                    "Must cross insertion threshold: progress=" + view().insertionProgress()
                            + ", swipe=" + view().interactionPreferences().swipeToInsert);
            touch(MotionEvent.ACTION_CANCEL, 360, 445); settle(900);
            require(view().currentMode() == SlotView.Mode.SHELF, "Cancelled touch must not insert");
            require(view().insertionProgress() < .01f, "Cancelled drag must return");
        });
        check(4, "canReverseBeforeRelease", () -> {
            reset(1, false); dragStart(360, 210); dragMove(360, 446); dragMove(360, 270);
            dragEnd(360, 270); settle(900);
            require(view().currentMode() == SlotView.Mode.SHELF, "Reversed drag must not insert");
        });
        check(5, "fullDragInsertsOnRelease", () -> {
            reset(1, false); dragStart(360, 210); dragMove(360, 446);
            require(view().currentMode() == SlotView.Mode.SHELF, "Holding must remain interactive");
            dragEnd(360, 446); settle(1700);
            require(view().currentMode() == SlotView.Mode.PREVIEW, "Full drag must insert");
            require(view().revealProgress() > .99f, "Preview must finish revealing");
        });
        check(6, "upwardSwipeEjects", () -> {
            reset(1, true); dragStart(360, 340); dragMove(360, 235); dragEnd(360, 235); settle(1800);
            require(view().currentMode() == SlotView.Mode.SHELF, "Upward swipe must eject");
            require(view().insertionProgress() < .01f, "Ejected cartridge must return to shelf");
        });
        check(7, "horizontalSwipeSelectsWithoutInserting", () -> {
            reset(1, false); dragStart(530, 250); dragMove(190, 254); dragEnd(190, 254); settle(900);
            require(view().selectedIndex() == 2, "Left swipe must select next cartridge");
            require(Math.abs(view().shelfPosition() - 2) < .01f, "Shelf must snap to selected cartridge");
            require(view().insertionProgress() < .01f, "Horizontal swipe must not insert");
        });
        check(8, "keyNavigationAndInsertion", () -> {
            reset(0, false); key(KeyEvent.KEYCODE_DPAD_LEFT); settle(600);
            require(view().selectedIndex() == 8, "First cartridge must wrap left navigation");
            key(KeyEvent.KEYCODE_DPAD_RIGHT); settle(600);
            key(KeyEvent.KEYCODE_DPAD_RIGHT); settle(500);
            require(view().selectedIndex() == 1, "D-pad must select cartridge");
            key(KeyEvent.KEYCODE_BUTTON_A); settle(1700);
            require(view().currentMode() == SlotView.Mode.PREVIEW, "A must insert");
            key(KeyEvent.KEYCODE_BUTTON_B); settle(1800);
            require(view().currentMode() == SlotView.Mode.SHELF, "B must eject");
        });
        check(9, "focusLossCancelsUncommittedDrag", () -> {
            reset(1, false); dragStart(360, 210); dragMove(360, 445);
            runOnMainSync(() -> activity.onWindowFocusChanged(false)); settle(900);
            require(view().currentMode() == SlotView.Mode.SHELF, "Focus loss must not commit drag");
            require(view().insertionProgress() < .01f, "Focus loss must return cartridge");
            runOnMainSync(() -> activity.onWindowFocusChanged(true));
        });
        check(10, "selectionAndInsertedStateSurviveRecreation", () -> {
            reset(3, true);
            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            runOnMainSync(() -> activity.recreate());
            Activity restored = monitor.waitForActivityWithTimeout(5000); removeMonitor(monitor);
            require(restored instanceof MainActivity, "Activity must recreate");
            activity = (MainActivity) restored; waitForIdleSync(); settle(500);
            require(view().selectedIndex() == 3, "Selected cartridge must survive recreation");
            require(view().currentMode() == SlotView.Mode.PREVIEW, "Inserted state must survive recreation");
        });
        check(11, "settingsSwitchesInsertionModeByTouch", () -> {
            reset(1, false);
            runOnMainSync(() -> view().interactionPreferences().swipeToInsert = false);
            float entryY = view().homeLayout().machineClosedTop() + HomeLayout.ENTRY_CENTER;
            dragStart(76, entryY); dragEnd(76, entryY);
            require(view().isSettingsOpen(), "Bottom-left settings button must open settings");
            settle(450);
            tapSettingsRow(0);
            require(view().interactionPreferences().swipeToInsert, "Mode row must switch to swipe");
            key(KeyEvent.KEYCODE_BUTTON_B);
            require(!view().isSettingsOpen(), "B must close settings");
            require(view().currentMode() == SlotView.Mode.SHELF, "Settings must not insert a cartridge");
        });
        check(12, "shortSwipeFromShelfBackgroundInserts", () -> {
            reset(1, false);
            runOnMainSync(() -> view().interactionPreferences().swipeToInsert = true);
            dragStart(360, 80); dragMove(360, 140);
            require(view().insertionProgress() <= .181f, "Swipe feedback must remain a short movement");
            require(view().currentMode() == SlotView.Mode.SHELF, "Swipe must wait for release");
            dragEnd(360, 140); settle(1900);
            require(view().currentMode() == SlotView.Mode.PREVIEW, "60 px swipe must insert without reaching slot");
        });
        check(13, "smallReversedAndCancelledSwipesDoNotInsert", () -> {
            reset(1, false);
            runOnMainSync(() -> view().interactionPreferences().swipeToInsert = true);
            dragStart(360, 210); dragMove(360, 234); dragEnd(360, 234); settle(700);
            require(view().currentMode() == SlotView.Mode.SHELF, "Small movement must not insert");
            dragStart(360, 210); dragMove(360, 280); dragMove(360, 230); dragEnd(360, 230); settle(700);
            require(view().currentMode() == SlotView.Mode.SHELF, "Reversed swipe must not insert");
            dragStart(360, 210); dragMove(360, 280); touch(MotionEvent.ACTION_CANCEL, 360, 280); settle(700);
            require(view().currentMode() == SlotView.Mode.SHELF, "Cancelled swipe must not insert");
            dragStart(530, 250); dragMove(190, 254); dragEnd(190, 254); settle(900);
            require(view().selectedIndex() == 2 && view().currentMode() == SlotView.Mode.SHELF,
                    "Horizontal swipe in swipe mode must only select");
        });
        check(14, "shortUpwardSwipeEjectsInSwipeMode", () -> {
            reset(1, true);
            runOnMainSync(() -> view().interactionPreferences().swipeToInsert = true);
            dragStart(360, 340); dragMove(360, 280); dragEnd(360, 280); settle(1800);
            require(view().currentMode() == SlotView.Mode.SHELF, "60 px upward swipe must eject");
        });
        check(15, "settingsAndPreferencesSurviveRecreation", () -> {
            reset(1, false);
            runOnMainSync(() -> {
                view().interactionPreferences().swipeToInsert = true;
                view().interactionPreferences().save();
            });
            key(KeyEvent.KEYCODE_MENU);
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_BUTTON_A);
            boolean haptics = view().interactionPreferences().haptics;
            boolean sound = view().interactionPreferences().sound;
            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            runOnMainSync(() -> activity.recreate());
            Activity restored = monitor.waitForActivityWithTimeout(5000); removeMonitor(monitor);
            require(restored instanceof MainActivity, "Activity must recreate");
            activity = (MainActivity) restored; waitForIdleSync(); settle(500);
            require(view().isSettingsOpen(), "Settings must remain open after recreation");
            require(view().interactionPreferences().swipeToInsert, "Insertion preference must persist");
            require(view().interactionPreferences().haptics == haptics, "Haptic preference must persist");
            require(view().interactionPreferences().sound == sound, "Sound preference must persist");
        });
        check(16, "gbShapeAndUpperGripUseTallCartridgeBounds", () -> {
            runOnMainSync(() -> {
                view().interactionPreferences().swipeToInsert = false;
                view().interactionPreferences().save();
            });
            reset(5, false);
            require(view().selectedPlatform() == Cartridge.Platform.GB, "Must select GB cartridge");
            require(view().selectedCardHeight() > view().selectedCardWidth(), "GB must retain portrait silhouette");
            require(view().selectedCardTop() >= 72, "GB must clear the centered category controls");
            require(view().selectedCardTop() + view().selectedCardHeight() < view().homeLayout().dotsY(),
                    "Tall cartridge must clear shelf indicators");
            float grip = view().selectedCardTop() + 24;
            dragStart(360, grip); dragMove(360, grip + 200);
            require(view().insertionProgress() > .5f, "GB upper grip must move the cartridge");
            touch(MotionEvent.ACTION_CANCEL, 360, grip + 200); settle(900);
            require(view().insertionProgress() < .01f, "Cancelled GB drag must return to rest");
        });
        check(17, "gbDragInsertsAndEjects", () -> {
            reset(5, false);
            dragStart(360, 85); dragMove(360, 440); dragEnd(360, 440); settle(1900);
            require(view().currentMode() == SlotView.Mode.PREVIEW, "GB drag must insert");
            require(view().selectedPlatform() == Cartridge.Platform.GB, "Inserted GB must preserve platform");
            key(KeyEvent.KEYCODE_BUTTON_B); settle(1800);
            require(view().currentMode() == SlotView.Mode.SHELF, "GB must eject");
        });
        check(18, "gbcDragInsertsAndEjects", () -> {
            reset(7, false);
            require(view().selectedPlatform() == Cartridge.Platform.GBC, "Must select GBC cartridge");
            require(view().selectedCardHeight() > view().selectedCardWidth(), "GBC must retain portrait silhouette");
            require(view().selectedCardTop() >= 72
                            && view().selectedCardTop() + view().selectedCardHeight() < view().homeLayout().dotsY(),
                    "GBC must clear category controls and shelf indicators");
            dragStart(360, 85); dragMove(360, 440); dragEnd(360, 440); settle(1900);
            require(view().currentMode() == SlotView.Mode.PREVIEW, "GBC drag must insert");
            key(KeyEvent.KEYCODE_BUTTON_B); settle(1800);
            require(view().currentMode() == SlotView.Mode.SHELF, "GBC must eject");
        });
        check(19, "shortSwipesWorkForBothTallCartridgeTypes", () -> {
            runOnMainSync(() -> {
                view().interactionPreferences().swipeToInsert = true;
                view().interactionPreferences().save();
            });
            for (int index : new int[]{6, 8}) {
                reset(index, false);
                dragStart(360, 170); dragMove(360, 230); dragEnd(360, 230); settle(1900);
                require(view().currentMode() == SlotView.Mode.PREVIEW, "Short swipe must insert tall cartridge " + index);
                dragStart(360, 340); dragMove(360, 280); dragEnd(360, 280); settle(1800);
                require(view().currentMode() == SlotView.Mode.SHELF, "Short swipe must eject tall cartridge " + index);
            }
        });
        check(20, "mixedShelfNavigationKeepsPlatformAndDoesNotInsert", () -> {
            reset(4, false);
            key(KeyEvent.KEYCODE_DPAD_RIGHT); settle(600);
            require(view().selectedPlatform() == Cartridge.Platform.GB, "Navigation must cross from GBA into GB");
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_RIGHT); settle(600);
            require(view().selectedPlatform() == Cartridge.Platform.GBC, "Navigation must cross into GBC");
            key(KeyEvent.KEYCODE_DPAD_LEFT); settle(600);
            require(view().selectedPlatform() == Cartridge.Platform.GB, "Navigation must return from GBC to GB");
            dragStart(530, 220); dragMove(190, 224); dragEnd(190, 224); settle(900);
            require(view().selectedPlatform() == Cartridge.Platform.GBC, "Swipe must cross between tall cartridge types");
            require(view().currentMode() == SlotView.Mode.SHELF && view().insertionProgress() < .01f,
                    "Mixed shelf navigation must not insert");
        });
        CartridgeRenderer renderer = new CartridgeRenderer(getTargetContext().getAssets());
        check(21, "gbaSolidMatchesOriginalRustPixels", () ->
                matchesOriginal(renderer, "gba-solid", Cartridge.Platform.GBA, 0xffc4ab59, CartridgeRenderer.Finish.SOLID));
        check(22, "gbaClearMatchesOriginalRustPixels", () -> {
            matchesOriginal(renderer, "gba-clear", Cartridge.Platform.GBA, 0xff575c87, CartridgeRenderer.Finish.TRANSLUCENT);
            matchesPixels("gba-interior", renderer.spec(Cartridge.Platform.GBA), renderer.renderInteriorPixels(Cartridge.Platform.GBA, 0xff575c87));
            matchesPixels("gba-backing", renderer.spec(Cartridge.Platform.GBA), renderer.renderBackingPixels(Cartridge.Platform.GBA));
        });
        check(23, "gbaGlitterMatchesOriginalRustPixels", () ->
                matchesOriginal(renderer, "gba-glitter", Cartridge.Platform.GBA, 0xff86b9bf, CartridgeRenderer.Finish.GLITTER));
        check(24, "gbSolidMatchesOriginalRustPixels", () ->
                matchesOriginal(renderer, "gb-solid", Cartridge.Platform.GB, 0xff9a978f, CartridgeRenderer.Finish.SOLID));
        check(25, "gbClearMatchesOriginalRustPixels", () -> {
            matchesOriginal(renderer, "gb-clear", Cartridge.Platform.GB, 0xff7c7a8a, CartridgeRenderer.Finish.TRANSLUCENT);
            matchesPixels("gb-interior", renderer.spec(Cartridge.Platform.GB), renderer.renderInteriorPixels(Cartridge.Platform.GB, 0xff7c7a8a));
            matchesPixels("gb-backing", renderer.spec(Cartridge.Platform.GB), renderer.renderBackingPixels(Cartridge.Platform.GB));
        });
        check(26, "gbcClearMatchesOriginalRustPixels", () -> {
            matchesOriginal(renderer, "gbc-clear", Cartridge.Platform.GBC, 0xff7c7a8a, CartridgeRenderer.Finish.TRANSLUCENT);
            matchesPixels("gbc-interior", renderer.spec(Cartridge.Platform.GBC), renderer.renderInteriorPixels(Cartridge.Platform.GBC, 0xff7c7a8a));
            matchesPixels("gbc-backing", renderer.spec(Cartridge.Platform.GBC), renderer.renderBackingPixels(Cartridge.Platform.GBC));
        });
        check(27, "gbcGlitterMatchesOriginalRustPixels", () ->
                matchesOriginal(renderer, "gbc-glitter", Cartridge.Platform.GBC, 0xff86b9bf, CartridgeRenderer.Finish.GLITTER));
        check(28, "originalAudioLoadsAndInsertionCuePrecedesPreview", () -> {
            reset(1, false);
            runOnMainSync(() -> view().interactionPreferences().sound = true);
            waitForAudioReady();
            int before = view().cartridgeAudio().playCount();
            key(KeyEvent.KEYCODE_BUTTON_A); waitForPlayback(before);
            require(view().cartridgeAudio().lastEffect() == CartridgeAudio.Effect.INSERT, "Must play original insert clip");
            require(view().cartridgeAudio().lastStream() > 0, "Android must accept insert playback");
            require(view().currentMode() == SlotView.Mode.SHELF && view().insertionPhase() > .75f,
                    "Insert lead must start near seating while the cartridge is still visible");
            settle(1600);
            require(view().currentMode() == SlotView.Mode.PREVIEW, "Insertion must finish with audio enabled");
            require(view().cartridgeAudio().playCount() == before + 1, "Insert must play exactly once");
        });
        check(29, "ejectAudioWaitsForSceneToDisappearAndPlaysOnce", () -> {
            reset(1, true);
            int before = view().cartridgeAudio().playCount();
            key(KeyEvent.KEYCODE_BUTTON_B);
            require(view().cartridgeAudio().playCount() == before, "Eject must not sound over the live scene");
            waitForPlayback(before);
            require(view().revealProgress() < .01f, "Scene must disappear before eject audio");
            require(view().cartridgeAudio().lastEffect() == CartridgeAudio.Effect.EJECT, "Must play original eject clip");
            require(view().cartridgeAudio().lastStream() > 0, "Android must accept eject playback");
            settle(1400);
            require(view().currentMode() == SlotView.Mode.SHELF, "Ejection must finish with audio enabled");
            require(view().cartridgeAudio().playCount() == before + 1, "Eject must play exactly once");
        });
        check(30, "soundSettingMutesBothClipsAcrossAllPlatforms", () -> {
            reset(1, false);
            key(KeyEvent.KEYCODE_MENU); key(KeyEvent.KEYCODE_BUTTON_A);
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN);
            key(KeyEvent.KEYCODE_BUTTON_A); key(KeyEvent.KEYCODE_BUTTON_B); key(KeyEvent.KEYCODE_BUTTON_B);
            require(!view().interactionPreferences().sound, "Sound setting must turn off");
            int before = view().cartridgeAudio().playCount();
            for (int index : new int[]{1, 5, 7}) {
                reset(index, false); key(KeyEvent.KEYCODE_BUTTON_A); settle(1700);
                require(view().currentMode() == SlotView.Mode.PREVIEW, "Muted insertion must work");
                key(KeyEvent.KEYCODE_BUTTON_B); settle(1900);
                require(view().currentMode() == SlotView.Mode.SHELF, "Muted ejection must work");
            }
            require(view().cartridgeAudio().playCount() == before, "Muted insertion and ejection must not submit any audio");
            key(KeyEvent.KEYCODE_MENU); key(KeyEvent.KEYCODE_BUTTON_A);
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN);
            key(KeyEvent.KEYCODE_BUTTON_A); key(KeyEvent.KEYCODE_BUTTON_B); key(KeyEvent.KEYCODE_BUTTON_B);
            require(view().interactionPreferences().sound, "Sound setting must turn back on");
        });
        check(31, "uncommittedAndCancelledDragsRemainSilent", () -> {
            reset(1, false);
            runOnMainSync(() -> view().interactionPreferences().swipeToInsert = false);
            int before = view().cartridgeAudio().playCount();
            dragStart(360, 210); dragMove(360, 445); settle(250);
            require(view().cartridgeAudio().playCount() == before, "Holding beyond commit threshold must remain silent");
            touch(MotionEvent.ACTION_CANCEL, 360, 445); settle(900);
            dragStart(360, 245); dragMove(360, 330); dragEnd(360, 330); settle(900);
            require(view().currentMode() == SlotView.Mode.SHELF, "Cancelled and short drags must return");
            require(view().cartridgeAudio().playCount() == before, "Cancelled and short drags must not play a click");
        });
        check(32, "backgroundAndRestorationDoNotReplayOldAudio", () -> {
            reset(1, false);
            int before = view().cartridgeAudio().playCount();
            key(KeyEvent.KEYCODE_BUTTON_A); waitForPlayback(before);
            runOnMainSync(() -> view().pause()); settle(300);
            runOnMainSync(() -> view().resume()); settle(1700);
            require(view().cartridgeAudio().playCount() == before + 1, "Resuming must not replay an old clip");
            reset(1, true); settle(350);
            require(view().cartridgeAudio().playCount() == before + 1, "Restoring an inserted cartridge must remain silent");
        });
        check(33, "fullyDraggedCardKeepsAudioLeadAndNextCycleSounds", () -> {
            reset(3, false);
            int before = view().cartridgeAudio().playCount();
            dragStart(360, 210); dragMove(360, 510);
            require(view().cartridgeAudio().playCount() == before, "Full held drag must remain silent");
            dragEnd(360, 510); waitForPlayback(before);
            require(view().currentMode() == SlotView.Mode.SHELF, "Full drag must leave time for the original audio lead");
            settle(1700);
            require(view().currentMode() == SlotView.Mode.PREVIEW, "Full drag must not stall at the cue hold");
            key(KeyEvent.KEYCODE_BUTTON_B); settle(1900);
            key(KeyEvent.KEYCODE_BUTTON_A); settle(1700);
            require(view().currentMode() == SlotView.Mode.PREVIEW, "Next insertion must finish");
            require(view().cartridgeAudio().playCount() == before + 3, "Each insert/eject cycle must play one clip per action");
        });
        check(34, "shelvesFilterInOrderAndShareCartridgeIdentity", () -> {
            ShelfModel model = ShelfModel.demo();
            ShelfModel.Category[] categories = ShelfModel.Category.values();
            require(List.of(categories).equals(List.of(ShelfModel.Category.ALL, ShelfModel.Category.GBA,
                    ShelfModel.Category.GBC, ShelfModel.Category.GB,
                    ShelfModel.Category.FAVORITES, ShelfModel.Category.RECENTS)), "Category order must match navigation order");
            int[] counts = {9, 5, 2, 2, 0, 0};
            for (int i = 0; i < categories.length; i++) {
                ShelfModel.Shelf shelf = model.shelf(categories[i]);
                require(shelf.size() == counts[i], "Wrong count in " + categories[i]);
                if (shelf.size() == 0) continue;
                int position = 0;
                for (Cartridge cartridge : model.current().cartridges) {
                    if (categories[i].platform == null || cartridge.platform == categories[i].platform) {
                        require(shelf.cartridges.get(position++) == cartridge,
                                "Filtered shelves must preserve order and reuse the same cartridge object");
                    }
                }
            }
            require(model.current().selectedCartridge().id.equals("moss"), "ALL must start on MOSS");
            require(model.shelf(ShelfModel.Category.GBA).selectedCartridge().id.equals("moss"), "GBA must start on MOSS");
            require(model.shelf(ShelfModel.Category.GBC).selectedCartridge().id.equals("lagoon"), "GBC must start on LAGOON");
            require(model.shelf(ShelfModel.Category.GB).selectedCartridge().id.equals("grove"), "GB must start on GROVE");
        });
        check(35, "eachShelfRetainsItsOwnSelection", () -> {
            ShelfModel model = ShelfModel.demo();
            model.current().select(7);
            require(model.selectCategory(ShelfModel.Category.GBA), "Must enter GBA");
            model.current().select(3);
            model.selectCategory(ShelfModel.Category.GBC); model.current().select(1);
            model.selectCategory(ShelfModel.Category.GB); model.current().select(1);
            model.selectCategory(ShelfModel.Category.ALL);
            require(model.current().selectedCartridge().id.equals("lagoon"), "ALL must retain LAGOON");
            require(!model.selectCategory(ShelfModel.Category.ALL), "Current category must remain selected");
            require(model.current().selectedIndex() == 7, "Reselecting a category must retain its position");
            model.selectCategory(ShelfModel.Category.GBA);
            require(model.current().selectedCartridge().id.equals("tide"), "GBA must retain TIDE");
            model.selectCategory(ShelfModel.Category.GBC);
            require(model.current().selectedCartridge().id.equals("dusk"), "GBC must retain DUSK");
            model.selectCategory(ShelfModel.Category.GB);
            require(model.current().selectedCartridge().id.equals("moon"), "GB must retain MOON");
        });
        check(36, "emptyAndSingleCartridgeShelvesHaveValidSelection", () -> {
            ShelfModel empty = new ShelfModel(List.of());
            empty.current().select(10);
            empty.current().restoreSelection("missing");
            require(empty.current().size() == 0 && empty.current().selectedIndex() == -1
                    && empty.current().selectedCartridge() == null, "Empty shelf must have no selection");
            require(!empty.selectCategory(ShelfModel.Category.GBC), "Empty categories must be skipped");
            Cartridge moss = ShelfModel.demo().current().selectedCartridge();
            ShelfModel single = new ShelfModel(List.of(moss));
            single.current().select(-10); single.current().select(10);
            require(single.current().selectedIndex() == 0 && single.current().selectedCartridge() == moss,
                    "A single cartridge must remain selected at either boundary");
            require(single.selectCategory(ShelfModel.Category.GBA), "Nonempty GBA shelf must be available");
            require(!single.selectCategory(ShelfModel.Category.GB), "Empty GB shelf must be skipped");
            require(single.category() == ShelfModel.Category.GBA, "Skipping an empty category must preserve selection");
            single.current().restoreSelection("missing");
            require(single.current().selectedCartridge() == moss, "Invalid saved identity must return to default selection");
            boolean duplicateRejected = false;
            try { new ShelfModel(List.of(moss, moss)); }
            catch (IllegalArgumentException expected) { duplicateRejected = true; }
            require(duplicateRejected, "Duplicate cartridge IDs must be rejected");
        });
        check(37, "filteredShelfUsesStableIdentityForCardAndPreview", () -> {
            reset(8, true);
            Bitmap originalPreview = frame();
            restoreShelf(ShelfModel.Category.GBC, "dusk", true);
            require(view().selectedIndex() == 1 && view().selectedCartridgeId().equals("dusk"),
                    "GBC index 1 must map to DUSK");
            Bitmap filteredPreview = frame();
            require(originalPreview.sameAs(filteredPreview), "Filtered preview must match the same cartridge in ALL");
            originalPreview.recycle(); filteredPreview.recycle();
            restoreShelf(ShelfModel.Category.GBC, "dusk", false);
            key(KeyEvent.KEYCODE_DPAD_LEFT); settle(900);
            require(view().selectedIndex() == 0 && view().selectedCartridgeId().equals("lagoon"),
                    "Filtered navigation must select LAGOON");
            Bitmap filteredShelf = frame();
            reset(7, false);
            Bitmap originalShelf = frame();
            int cardX = (int) view().screenX((view().homeLayout().width - view().selectedCardWidth()) / 2);
            int cardY = (int) view().screenY(view().selectedCardTop());
            Bitmap originalCard = Bitmap.createBitmap(originalShelf, cardX, cardY,
                    (int) view().homeLayout().px(view().selectedCardWidth()),
                    (int) view().homeLayout().px(view().selectedCardHeight()));
            Bitmap filteredCard = Bitmap.createBitmap(filteredShelf, cardX, cardY,
                    originalCard.getWidth(), originalCard.getHeight());
            require(originalCard.sameAs(filteredCard), "Filtered shelf must draw LAGOON's original bitmap");
            originalCard.recycle(); filteredCard.recycle(); originalShelf.recycle(); filteredShelf.recycle();
        });
        check(38, "allShelfSelectionsSurviveActivityRecreation", () -> {
            runOnMainSync(() -> {
                Bundle state = new Bundle();
                state.putString("shelf_category", "GBC");
                state.putString("shelf_selected_ALL", "lagoon");
                state.putString("shelf_selected_GBA", "tide");
                state.putString("shelf_selected_GBC", "dusk");
                state.putString("shelf_selected_GB", "moon");
                state.putBoolean("inserted", true);
                view().restore(state);
            });
            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            runOnMainSync(() -> activity.recreate());
            Activity restored = monitor.waitForActivityWithTimeout(5000); removeMonitor(monitor);
            require(restored instanceof MainActivity, "Activity must recreate");
            activity = (MainActivity) restored; waitForIdleSync(); settle(500);
            require(view().currentCategory() == ShelfModel.Category.GBC && view().selectedIndex() == 1
                    && view().selectedCartridgeId().equals("dusk"), "Current shelf must survive recreation");
            require(view().currentMode() == SlotView.Mode.PREVIEW && !view().isNavigationKeyHeld(),
                    "Restoration must be inserted and have no held keys");
            require(view().cartridgeAudio().playCount() == 0, "Recreated view must not replay audio");
            runOnMainSync(() -> {
                Bundle state = new Bundle(); view().save(state);
                require(state.getString("shelf_selected_ALL").equals("lagoon"), "ALL position must survive");
                require(state.getString("shelf_selected_GBA").equals("tide"), "GBA position must survive");
                require(state.getString("shelf_selected_GB").equals("moon"), "GB position must survive");
            });
        });
        check(39, "keyReleaseAndInputInterruptionsClearHeldNavigation", () -> {
            reset(1, false);
            press(KeyEvent.KEYCODE_DPAD_LEFT);
            require(view().isNavigationKeyHeld(), "Direction down must be tracked");
            int selected = view().selectedIndex();
            runOnMainSync(() -> activity.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,
                    new KeyEvent(0, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 1)));
            require(view().selectedIndex() == selected, "System repeats must remain consumed");
            release(KeyEvent.KEYCODE_DPAD_LEFT);
            require(!view().isNavigationKeyHeld(), "Direction up must clear the held state");
            press(KeyEvent.KEYCODE_DPAD_RIGHT);
            runOnMainSync(() -> activity.onWindowFocusChanged(false));
            require(!view().isNavigationKeyHeld(), "Focus loss must clear held keys");
            runOnMainSync(() -> activity.onWindowFocusChanged(true));
            press(KeyEvent.KEYCODE_DPAD_LEFT);
            runOnMainSync(() -> view().pause());
            require(!view().isNavigationKeyHeld(), "Pause must clear held keys");
            press(KeyEvent.KEYCODE_DPAD_RIGHT);
            require(!view().isNavigationKeyHeld(), "Paused view must not accept a new held direction");
            runOnMainSync(() -> view().resume());
            press(KeyEvent.KEYCODE_DPAD_RIGHT);
            dragStart(360, 210);
            require(!view().isNavigationKeyHeld(), "Touch takeover must clear held keys");
            touch(MotionEvent.ACTION_CANCEL, 360, 210);
            press(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_MENU);
            require(view().isSettingsOpen() && !view().isNavigationKeyHeld(), "Settings must clear held keys");
            key(KeyEvent.KEYCODE_BUTTON_B);
            press(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_BUTTON_A);
            require(!view().isNavigationKeyHeld(), "Insertion must clear held keys");
            reset(1, false);
            require(!view().isNavigationKeyHeld(), "Restoration must clear held keys");
            runOnMainSync(() -> {
                SlotView detached = new SlotView(getTargetContext(), ShelfModel.demo());
                detached.handleKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                require(detached.isNavigationKeyHeld(), "Detached check must start with held input");
                detached.onDetachedFromWindow();
                require(!detached.isNavigationKeyHeld(), "View removal must clear held keys");
            });
        });
        check(40, "browseAndCategoryGuardsProtectInsertionAndEjection", () -> {
            reset(1, false);
            require(view().canBrowse() && view().canSwitchCategory(), "Resting shelf must allow navigation");
            key(KeyEvent.KEYCODE_MENU);
            require(!view().canBrowse() && !view().canSwitchCategory(), "Settings must lock shelf navigation");
            key(KeyEvent.KEYCODE_BUTTON_B); settle(450);
            runOnMainSync(() -> view().interactionPreferences().swipeToInsert = false);
            dragStart(360, 210); dragMove(360, 300);
            require(!view().canBrowse() && !view().canSwitchCategory(), "Insertion drag must lock navigation");
            touch(MotionEvent.ACTION_CANCEL, 360, 300);
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            require(view().selectedIndex() == 1, "Returning cartridge must not change during a cancelled drag");
            settle(900);
            key(KeyEvent.KEYCODE_BUTTON_A); key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_MENU);
            require(view().selectedIndex() == 1 && !view().isSettingsOpen(), "Insertion animation must retain its cartridge");
            require(!view().canBrowse() && !view().canSwitchCategory(), "Committed insertion must lock navigation");
            settle(1700);
            require(!view().canBrowse() && !view().canSwitchCategory(), "Preview must lock navigation");
            key(KeyEvent.KEYCODE_BUTTON_B); key(KeyEvent.KEYCODE_DPAD_RIGHT);
            require(view().selectedIndex() == 1, "Ejection must retain its cartridge");
            require(!view().canBrowse() && !view().canSwitchCategory(), "Ejection must lock navigation");
            settle(1800);
            require(view().canBrowse() && view().canSwitchCategory(), "Shelf navigation must return after ejection");
        });
        check(41, "missingSavedIdentityFallsBackToShelfDefaults", () -> {
            restoreShelf(ShelfModel.Category.GBC, "missing", false);
            require(view().selectedCartridgeId().equals("lagoon"), "GBC must fall back to its first cartridge");
            runOnMainSync(() -> {
                Bundle state = new Bundle();
                state.putString("shelf_category", "missing");
                state.putString("shelf_selected_ALL", "missing");
                view().restore(state);
            });
            require(view().currentCategory() == ShelfModel.Category.ALL && view().selectedCartridgeId().equals("moss"),
                    "Invalid category must fall back to ALL and MOSS");
        });
        check(42, "keysWrapEveryShelfWithOneStepInTheInputDirection", () -> {
            ShelfModel model = ShelfModel.demo();
            for (ShelfModel.Category category : ShelfModel.Category.values()) {
                ShelfModel.Shelf shelf = model.shelf(category);
                if (shelf.size() == 0) continue;
                restoreShelf(category, shelf.cartridges.get(0).id, false);
                key(KeyEvent.KEYCODE_DPAD_LEFT);
                require(view().selectedIndex() == shelf.size() - 1, "Left must wrap in " + category);
                require(view().shelfTargetPosition() == -1, "Left seam must travel one position");
                settle(800);
                require(Math.abs(view().shelfPosition() - (shelf.size() - 1)) < .01f, "Left wrap must normalize");
                key(KeyEvent.KEYCODE_DPAD_RIGHT);
                require(view().selectedIndex() == 0 && view().shelfTargetPosition() == shelf.size(),
                        "Right seam must travel one position in " + category);
                settle(800);
                require(Math.abs(view().shelfPosition()) < .01f, "Right wrap must normalize to zero");
            }
        });
        check(43, "swipesAndCancellationFollowTheFingerAcrossBothSeams", () -> {
            ShelfModel model = ShelfModel.demo();
            for (ShelfModel.Category category : ShelfModel.Category.values()) {
                ShelfModel.Shelf shelf = model.shelf(category);
                if (shelf.size() == 0) continue;
                restoreShelf(category, shelf.cartridges.get(shelf.size() - 1).id, false);
                dragStart(530, 250); dragMove(190, 254);
                float travel = 340f / view().homeLayout().pitch();
                require(Math.abs(view().shelfPosition() - (shelf.size() - 1 + travel)) < .01f,
                        "Drag must follow the finger beyond the right seam in " + category);
                require(view().selectedIndex() == 0, "Nearest cartridge must wrap to first");
                dragEnd(190, 254); settle(800);
                require(view().selectedIndex() == 0 && Math.abs(view().shelfPosition()) < .01f, "Swipe must snap to first");
                dragStart(190, 250); dragMove(530, 254);
                require(Math.abs(view().shelfPosition() + travel) < .01f, "Left seam drag must remain continuous");
                touch(MotionEvent.ACTION_CANCEL, 530, 254); settle(800);
                require(view().selectedIndex() == shelf.size() - 1
                        && Math.abs(view().shelfPosition() - (shelf.size() - 1)) < .01f,
                        "Cancelled seam drag must snap to its nearest selected cartridge");
                require(view().insertionProgress() < .01f, "Horizontal seam input must never insert");
            }
        });
        check(44, "sideTapsWrapUsingTheActualDrawnCardBounds", () -> {
            ShelfModel model = ShelfModel.demo();
            for (ShelfModel.Category category : ShelfModel.Category.values()) {
                ShelfModel.Shelf shelf = model.shelf(category);
                if (shelf.size() == 0) continue;
                restoreShelf(category, shelf.cartridges.get(0).id, false);
                dragStart(130, 240); dragEnd(130, 240);
                require(view().selectedIndex() == 0, "Tapping a gap must not select an invisible cartridge");
                dragStart(40, 240); dragEnd(40, 240);
                require(view().selectedIndex() == shelf.size() - 1 && view().shelfTargetPosition() == -1,
                        "Visible left cartridge must wrap backwards in " + category);
                settle(800);
                float right = view().homeLayout().width - 40;
                dragStart(right, 240); dragEnd(right, 240);
                require(view().selectedIndex() == 0 && view().shelfTargetPosition() == shelf.size(),
                        "Visible right cartridge must wrap forwards in " + category);
                settle(800);
            }
        });
        check(45, "twoCardShelvesDrawAndHitBothDirectionalInstances", () -> {
            for (ShelfModel.Category category : List.of(ShelfModel.Category.GB, ShelfModel.Category.GBC)) {
                String first = ShelfModel.demo().shelf(category).cartridges.get(0).id;
                restoreShelf(category, first, false);
                Bitmap shelf = frame();
                require(shelf.getPixel((int) view().screenX(40), (int) view().screenY(240)) != 0xff000000,
                        "Two-card shelf must draw its left neighbour");
                float right = view().homeLayout().width - 40;
                require(shelf.getPixel((int) view().screenX(right), (int) view().screenY(240)) != 0xff000000,
                        "Two-card shelf must draw its right neighbour");
                shelf.recycle();
                dragStart(40, 80); dragEnd(40, 80);
                require(view().selectedIndex() == 0, "Unscaled upper bounds must not hit a side cartridge");
                dragStart(40, 240); dragEnd(40, 240);
                require(view().selectedIndex() == 1 && view().shelfTargetPosition() == -1, "Left duplicate must mean left");
                restoreShelf(category, first, false);
                dragStart(right, 240); dragEnd(right, 240);
                require(view().selectedIndex() == 1 && view().shelfTargetPosition() == 1, "Right duplicate must mean right");
                settle(800);
            }
        });
        check(46, "wrappedSelectionSeatsAtTheCenterAndKeepsItsIdentity", () -> {
            reset(8, false);
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_BUTTON_A);
            require(view().selectedCartridgeId().equals("night-drive"), "Wrapped insertion must select NIGHT DRIVE");
            require(!view().isNavigationKeyHeld(), "Insertion must stop navigation");
            settle(2100);
            require(view().currentMode() == SlotView.Mode.PREVIEW
                    && view().selectedCartridgeId().equals("night-drive"), "Wrapped key insertion must finish on the selected card");
            require(Math.abs(view().shelfPosition()) < .01f, "Inserted card must have centered horizontally");
            restoreShelf(ShelfModel.Category.GBC, "lagoon", false);
            key(KeyEvent.KEYCODE_DPAD_LEFT); settle(800);
            dragStart(360, 170); dragMove(360, 440); dragEnd(360, 440); settle(2100);
            require(view().currentMode() == SlotView.Mode.PREVIEW && view().selectedCartridgeId().equals("dusk"),
                    "Touch insertion after a two-card wrap must use the central card");
        });
        check(47, "emptyAndSingleCardViewsIgnoreHorizontalInputAndFeedback", () -> {
            runOnMainSync(() -> {
                Cartridge moss = ShelfModel.demo().current().selectedCartridge();
                int[] emptySides = new int[2];
                boolean hasEmptySides = false;
                for (ShelfModel model : List.of(new ShelfModel(List.of()), new ShelfModel(List.of(moss)))) {
                    SlotView isolated = new SlotView(getTargetContext(), model);
                    isolated.layout(0, 0, 960, 640);
                    isolated.interactionPreferences().haptics = true;
                    int selection = isolated.selectedIndex();
                    for (int key : new int[]{KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT}) {
                        isolated.handleKeyDown(key, new KeyEvent(KeyEvent.ACTION_DOWN, key));
                        isolated.handleKeyUp(key, new KeyEvent(KeyEvent.ACTION_UP, key));
                    }
                    long now = SystemClock.uptimeMillis();
                    for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
                        MotionEvent event = MotionEvent.obtain(now, now + 100, action,
                                isolated.screenX(action == MotionEvent.ACTION_DOWN ? 530 : 190), isolated.screenY(250), 0);
                        isolated.dispatchTouchEvent(event); event.recycle();
                    }
                    Bitmap frame = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
                    isolated.draw(new Canvas(frame));
                    if (!hasEmptySides) {
                        emptySides[0] = frame.getPixel(30, 300);
                        emptySides[1] = frame.getPixel(930, 300);
                        hasEmptySides = true;
                    }
                    require(isolated.selectedIndex() == selection && isolated.shelfPosition() == 0
                            && isolated.shelfTargetPosition() == 0, "Empty/single shelf must stay at rest");
                    require(isolated.browseHapticCount() == 0 && !isolated.isNavigationKeyHeld(),
                            "Empty/single shelf must not create navigation feedback or repeats");
                    require(frame.getPixel(30, 300) == emptySides[0] && frame.getPixel(930, 300) == emptySides[1],
                            "Empty/single shelf must not duplicate a card at the sides");
                    frame.recycle(); isolated.onDetachedFromWindow();
                }
            });
        });
        check(48, "manyCircularStepsNormalizeAndSaveOnlyCartridgeIdentity", () -> {
            reset(0, false);
            runOnMainSync(() -> {
                for (int i = 0; i < 274; i++) {
                    activity.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                    activity.onKeyUp(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
                }
                require(view().selectedIndex() == 4 && view().shelfTargetPosition() == 274,
                        "Selection must wrap while animation coordinates remain continuous");
            });
            settle(1800);
            require(Math.abs(view().shelfPosition() - 4) < .01f && view().shelfTargetPosition() == 4,
                    "Settled coordinates must normalize after many laps");
            runOnMainSync(() -> {
                Bundle state = new Bundle(); view().save(state); view().restore(state);
                require(view().selectedCartridgeId().equals("ember") && view().shelfPosition() == 4,
                        "Restoring a circulated shelf must use stable identity and resting coordinates");
            });
        });
        check(49, "repeatClockUsesTheFullDelayAndEveryAccelerationLevel", () -> {
            ShelfKeyRepeat repeat = new ShelfKeyRepeat();
            require(repeat.press(1, 1000) == 1, "Down must immediately produce one step");
            require(repeat.press(1, 1200) == 0, "Duplicate down must not reset or add a step");
            int[] deadlines = {1400, 1510, 1620, 1730, 1840, 1925, 2010, 2095,
                    2180, 2245, 2310, 2375, 2440, 2490, 2540, 2590};
            for (int deadline : deadlines) {
                require(repeat.poll(deadline - 1) == 0, "Repeat must wait until " + deadline);
                require(repeat.poll(deadline) == 1, "Repeat must fire at " + deadline);
                require(repeat.poll(deadline) == 0, "One timestamp must never produce duplicate steps");
            }
            repeat.release(1, 2600);
            require(!repeat.isHeld() && repeat.poll(100000) == 0, "Release must stop all future steps");
        });
        check(50, "bothDirectionsPauseAndLateFramesNeverCatchUpInABurst", () -> {
            ShelfKeyRepeat repeat = new ShelfKeyRepeat();
            require(repeat.press(-1, 0) == -1 && repeat.poll(400) == -1, "Left must use the same delay");
            require(repeat.press(1, 401) == 0 && repeat.direction() == 0, "Both directions must pause immediately");
            require(repeat.poll(9000) == 0, "Both held must not repeat");
            repeat.release(-1, 10000);
            require(repeat.poll(10399) == 0 && repeat.poll(10400) == 1, "Returning to one direction must restart the delay");
            require(repeat.poll(20000) == 1 && repeat.poll(20000) == 0, "Late frame must produce only one step");
            require(repeat.poll(20109) == 0 && repeat.poll(20110) == 1, "Late frame must schedule from the current time");
            repeat.clear();
            require(!repeat.isHeld() && repeat.poll(90000) == 0, "Cleared state must never repeat");
        });
        check(51, "heldKeyRepeatsWithoutSystemEventsAndStopsOnRelease", () -> {
            reset(0, false); press(KeyEvent.KEYCODE_DPAD_LEFT);
            require(view().selectedIndex() == 8, "Down must wrap once immediately");
            settle(220);
            require(view().selectedIndex() == 8, "Short hold must not repeat before the delay");
            runOnMainSync(() -> {
                for (int repeat = 1; repeat <= 3; repeat++) {
                    activity.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,
                            new KeyEvent(0, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, repeat));
                }
                require(view().selectedIndex() == 8, "Android repeats must not add selections");
            });
            settle(500);
            require(view().selectedIndex() != 8, "Held direction must repeat using the application clock");
            release(KeyEvent.KEYCODE_DPAD_LEFT);
            int selection = view().selectedIndex(); settle(900);
            require(view().selectedIndex() == selection && Math.abs(view().shelfPosition() - selection) < .01f,
                    "Release must keep the last selection and settle the shelf");
        });
        check(52, "bothHeldDirectionsPauseTheViewAndRestartAfterRelease", () -> {
            reset(1, false); press(KeyEvent.KEYCODE_DPAD_RIGHT); press(KeyEvent.KEYCODE_DPAD_LEFT);
            require(view().selectedIndex() == 2, "Opposite direction must pause without another step");
            settle(600);
            require(view().selectedIndex() == 2, "Both held must pause the actual shelf");
            release(KeyEvent.KEYCODE_DPAD_LEFT); settle(220);
            require(view().selectedIndex() == 2, "Releasing one direction must start a fresh delay");
            settle(350);
            require(view().selectedIndex() != 2, "Remaining direction must eventually resume");
            release(KeyEvent.KEYCODE_DPAD_RIGHT);
            int selection = view().selectedIndex(); settle(700);
            require(view().selectedIndex() == selection, "Releasing both must stop resumed repeat");
        });
        check(53, "inputInterruptionsStopPendingRepeatCallbacks", () -> {
            for (int interruption = 0; interruption < 6; interruption++) {
                reset(1, false); press(KeyEvent.KEYCODE_DPAD_RIGHT);
                int selection = view().selectedIndex();
                switch (interruption) {
                    case 0 -> { dragStart(360, 240); touch(MotionEvent.ACTION_CANCEL, 360, 240); }
                    case 1 -> key(KeyEvent.KEYCODE_MENU);
                    case 2 -> runOnMainSync(() -> view().pause());
                    case 3 -> runOnMainSync(() -> activity.onWindowFocusChanged(false));
                    case 4 -> runOnMainSync(() -> { Bundle state = new Bundle(); view().save(state); view().restore(state); });
                    default -> runOnMainSync(() -> require(view().switchCategory(ShelfModel.Category.GBC),
                            "Changing a shelf through the shared state entry must succeed"));
                }
                if (interruption == 5) selection = 0;
                require(!view().isNavigationKeyHeld(), "Interruption must clear held keys");
                settle(650);
                require(view().selectedIndex() == selection, "Interruption must stop delayed repeats");
                if (interruption == 1) key(KeyEvent.KEYCODE_BUTTON_B);
                if (interruption == 2) runOnMainSync(() -> view().resume());
                if (interruption == 3) runOnMainSync(() -> activity.onWindowFocusChanged(true));
                settle(450);
                require(view().selectedIndex() == selection, "Returning from an interruption must not restart repeats");
            }
        });
        check(54, "repeatThenInsertionAndRecreationKeepTheLastSelection", () -> {
            reset(1, false); press(KeyEvent.KEYCODE_DPAD_RIGHT); settle(1100); release(KeyEvent.KEYCODE_DPAD_RIGHT);
            String id = view().selectedCartridgeId(); key(KeyEvent.KEYCODE_BUTTON_A); settle(2200);
            require(view().currentMode() == SlotView.Mode.PREVIEW && view().selectedCartridgeId().equals(id)
                    && !view().isNavigationKeyHeld(), "Insertion after repeating must seat the last selected card");
            key(KeyEvent.KEYCODE_BUTTON_B); settle(1900); press(KeyEvent.KEYCODE_DPAD_RIGHT);
            String restoredId = view().selectedCartridgeId();
            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            runOnMainSync(() -> activity.recreate());
            Activity restored = monitor.waitForActivityWithTimeout(5000); removeMonitor(monitor);
            require(restored instanceof MainActivity, "Activity must recreate");
            activity = (MainActivity) restored; waitForIdleSync(); settle(700);
            require(view().selectedCartridgeId().equals(restoredId) && !view().isNavigationKeyHeld(),
                    "Recreation while holding must restore identity and stop repeats");
            require(view().cartridgeAudio().playCount() == 0, "Restoring the resting shelf must remain silent");
        });
        check(55, "fastRepeatsThrottleHapticsAndNeverPlayBrowseAudio", () -> {
            reset(1, false);
            runOnMainSync(() -> { view().interactionPreferences().haptics = true; view().interactionPreferences().sound = true; });
            int haptics = view().browseHapticCount(), audio = view().cartridgeAudio().playCount();
            long started = SystemClock.uptimeMillis();
            press(KeyEvent.KEYCODE_DPAD_RIGHT); settle(2200); release(KeyEvent.KEYCODE_DPAD_RIGHT);
            long elapsed = SystemClock.uptimeMillis() - started;
            int feedback = view().browseHapticCount() - haptics;
            require(feedback > 0 && feedback <= elapsed / 100 + 1, "Browse feedback must be limited to ten times per second");
            require(view().cartridgeAudio().playCount() == audio, "Browsing must remain silent with sound enabled");
            runOnMainSync(() -> view().interactionPreferences().haptics = false);
            haptics = view().browseHapticCount();
            press(KeyEvent.KEYCODE_DPAD_LEFT); settle(800); release(KeyEvent.KEYCODE_DPAD_LEFT);
            require(view().browseHapticCount() == haptics, "Haptic setting must mute repeated browse feedback");
        });
        check(56, "settingsIgnoreHeldDirectionAndAndroidAutoRepeats", () -> {
            reset(1, false); key(KeyEvent.KEYCODE_MENU); press(KeyEvent.KEYCODE_DPAD_RIGHT);
            boolean swipe = view().interactionPreferences().swipeToInsert;
            runOnMainSync(() -> activity.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                    new KeyEvent(0, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 1)));
            settle(650);
            require(view().interactionPreferences().swipeToInsert == swipe && !view().isNavigationKeyHeld(),
                    "Settings must toggle once per press and never use shelf repetition");
            release(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_BUTTON_B);
        });
        check(57, "categoryHeaderShowsCurrentShelfPositionAndCount", () -> {
            reset(1, false);
            require(view().currentCategory() == ShelfModel.Category.ALL && view().shelfCounter().equals("2 / 9"), "ALL counter must start at 2 / 9");
            categoryKey(KeyEvent.KEYCODE_BUTTON_R1);
            require(view().currentCategory() == ShelfModel.Category.GBA && view().shelfCounter().equals("2 / 5"), "GBA must start on MOSS");
            categoryKey(KeyEvent.KEYCODE_E);
            require(view().currentCategory() == ShelfModel.Category.GBC && view().shelfCounter().equals("1 / 2"), "GBC must start on LAGOON");
            key(KeyEvent.KEYCODE_DPAD_RIGHT); settle(800);
            require(view().shelfCounter().equals("2 / 2"), "Filtered count must use shelf position");
            categoryKey(KeyEvent.KEYCODE_E);
            require(view().currentCategory() == ShelfModel.Category.GB && view().shelfCounter().equals("1 / 2"), "GB must start on GROVE");
        });
        check(58, "categoryLabelHasIndependentTouchBounds", () -> {
            reset(1, false);
            dragStart(360, 35); dragMove(490, 38); dragEnd(490, 38); settle(600);
            require(!view().isCategoryMenuOpen() && view().selectedIndex() == 1 && view().insertionProgress() == 0,
                    "Dragging from category label must not browse or insert");
            openCategories();
            require(view().isCategoryMenuOpen() && view().categoryMenuRow() == 0, "Label must open the current category");
            require(!view().canBrowse() && !view().canSwitchCategory(), "Category menu must lock shelf navigation");
            key(KeyEvent.KEYCODE_BUTTON_B);
        });
        check(59, "touchCategoryMenuSelectsEveryShelfAndCloses", () -> {
            reset(1, false);
            String[] defaults = {"moss", "moss", "lagoon", "grove"};
            for (int i = 0; i < defaults.length; i++) {
                ShelfModel.Category category = ShelfModel.Category.values()[i];
                touchCategory(category);
                require(view().currentCategory() == category && view().selectedCartridgeId().equals(defaults[i]),
                        "Touch must enter " + category + " at its default selection");
                require(!view().isCategoryMenuOpen() && view().currentMode() == SlotView.Mode.SHELF,
                        "Selecting a category must close the menu and remain on the shelf");
            }
        });
        check(60, "keyboardCategoryMenuWrapsAndConfirmsWithoutInsertion", () -> {
            reset(1, false); openCategories();
            key(KeyEvent.KEYCODE_DPAD_UP);
            require(view().categoryMenuRow() == 3, "Up from ALL must wrap to GB");
            key(KeyEvent.KEYCODE_ENTER); settle(450);
            require(view().currentCategory() == ShelfModel.Category.GB && view().currentMode() == SlotView.Mode.SHELF,
                    "Enter must confirm a category without inserting");
            openCategories(); key(KeyEvent.KEYCODE_DPAD_DOWN);
            require(view().categoryMenuRow() == 0, "Down from GB must wrap to ALL");
            key(KeyEvent.KEYCODE_BUTTON_A); settle(450);
            require(view().currentCategory() == ShelfModel.Category.ALL && view().insertionProgress() == 0,
                    "A must confirm a category without inserting");
            for (int dismiss : new int[]{KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK}) {
                openCategories(); key(KeyEvent.KEYCODE_DPAD_DOWN); settle(450);
                ShelfModel.Category selected = view().currentCategory();
                key(dismiss); settle(450);
                require(!view().isCategoryMenuOpen() && view().currentCategory() == selected,
                        "Dismiss must retain the current shelf");
            }
            for (int confirm : new int[]{KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_CENTER}) {
                ShelfModel.Category previous = view().currentCategory();
                openCategories(); key(KeyEvent.KEYCODE_DPAD_DOWN); key(confirm); settle(450);
                require(!view().isCategoryMenuOpen() && view().currentCategory() != previous
                        && view().currentMode() == SlotView.Mode.SHELF, "Confirmation keys must reach the menu before View click handling");
            }
        });
        check(61, "outsideMenuTouchClosesAndConsumesTheWholeGesture", () -> {
            reset(1, false); openCategories();
            dragStart(530, 210);
            require(!view().isCategoryMenuOpen(), "Outside down must close the menu");
            dragMove(530, 445); dragEnd(530, 445); settle(700);
            require(view().currentMode() == SlotView.Mode.SHELF && view().insertionProgress() == 0, "Dismissal gesture must not insert");
            openCategories(); dragStart(40, 300); dragEnd(40, 300); settle(450);
            require(view().selectedIndex() == 1, "Outside card tap must not select a neighbour");
            openCategories(); dragStart(76, 453); dragEnd(76, 453);
            require(!view().isSettingsOpen() && !view().isCategoryMenuOpen(), "Settings dismissal must not open settings");
        });
        check(62, "categoryMenuConsumesBrowseAndShoulderKeys", () -> {
            reset(3, false); openCategories();
            for (int blocked : new int[]{KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_E,
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_Y,
                    KeyEvent.KEYCODE_S}) key(blocked);
            settle(650);
            require(view().isCategoryMenuOpen() && view().currentCategory() == ShelfModel.Category.ALL
                    && view().selectedIndex() == 3 && !view().isNavigationKeyHeld() && !view().isSettingsOpen(),
                    "Menu must consume browse/category/settings shortcuts");
            press(KeyEvent.KEYCODE_DPAD_DOWN); settle(500); release(KeyEvent.KEYCODE_DPAD_DOWN);
            require(view().categoryMenuRow() == 1, "Menu direction hold must only move once");
            runOnMainSync(() -> view().goBack());
            require(!view().isCategoryMenuOpen(), "Native back bridge must close the menu");
        });
        check(63, "selectingCurrentCategoryDoesNotResetShelfPosition", () -> {
            reset(7, false); touchCategory(ShelfModel.Category.ALL);
            require(view().selectedCartridgeId().equals("lagoon") && !view().isCategoryTransitioning(), "Current ALL selection must remain unchanged");
            categoryKey(KeyEvent.KEYCODE_E); key(KeyEvent.KEYCODE_DPAD_RIGHT); settle(800);
            String selected = view().selectedCartridgeId(); float position = view().shelfPosition();
            openCategories(); key(KeyEvent.KEYCODE_BUTTON_A);
            require(view().selectedCartridgeId().equals(selected) && view().shelfPosition() == position
                    && !view().isCategoryTransitioning(), "Confirming the current category must preserve its position");
        });
        check(64, "shoulderAndKeyboardCategoryKeysWrapAndIgnoreAutoRepeat", () -> {
            reset(1, false);
            categoryKey(KeyEvent.KEYCODE_BUTTON_L1);
            require(view().currentCategory() == ShelfModel.Category.GB, "L1 must wrap ALL to GB");
            categoryKey(KeyEvent.KEYCODE_BUTTON_R1);
            require(view().currentCategory() == ShelfModel.Category.ALL, "R1 must wrap GB to ALL");
            press(KeyEvent.KEYCODE_E);
            runOnMainSync(() -> activity.onKeyDown(KeyEvent.KEYCODE_E,
                    new KeyEvent(0, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E, 1)));
            settle(650); release(KeyEvent.KEYCODE_E);
            require(view().currentCategory() == ShelfModel.Category.GBA && !view().isNavigationKeyHeld(), "Held E must switch exactly once");
            categoryKey(KeyEvent.KEYCODE_Q);
            require(view().currentCategory() == ShelfModel.Category.ALL, "Q must match L1");
        });
        check(65, "liveCategoryInputsRetainIndependentCartridgeSelections", () -> {
            reset(7, false); categoryKey(KeyEvent.KEYCODE_E);
            key(KeyEvent.KEYCODE_DPAD_LEFT); settle(700);
            require(view().selectedCartridgeId().equals("night-drive"), "GBA must retain its own navigation");
            touchCategory(ShelfModel.Category.GBC); key(KeyEvent.KEYCODE_DPAD_RIGHT); settle(700);
            touchCategory(ShelfModel.Category.GB); key(KeyEvent.KEYCODE_DPAD_RIGHT); settle(700);
            touchCategory(ShelfModel.Category.ALL);
            require(view().selectedCartridgeId().equals("lagoon"), "ALL must retain LAGOON");
            categoryKey(KeyEvent.KEYCODE_BUTTON_R1);
            require(view().selectedCartridgeId().equals("night-drive"), "GBA must retain NIGHT DRIVE");
            categoryKey(KeyEvent.KEYCODE_E);
            require(view().selectedCartridgeId().equals("dusk"), "GBC must retain DUSK");
            categoryKey(KeyEvent.KEYCODE_E);
            require(view().selectedCartridgeId().equals("moon"), "GB must retain MOON");
        });
        check(66, "categoryFadeChangesShapeAtZeroOpacityAndKeepsSlotVisible", () -> {
            SlotView[] isolated = new SlotView[1];
            int[] slotPixel = new int[1];
            runOnMainSync(() -> {
                isolated[0] = new SlotView(getTargetContext(), ShelfModel.demo()); isolated[0].layout(0, 0, 960, 640);
                Bitmap before = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
                isolated[0].draw(new Canvas(before));
                slotPixel[0] = before.getPixel((int) isolated[0].screenX(10), (int) isolated[0].screenY(430));
                before.recycle();
                require(slotPixel[0] != 0xff000000, "Slot housing texture must be visible");
                require(isolated[0].switchCategory(ShelfModel.Category.GBC), "Must accept category transition");
                require(!isolated[0].canBrowse() && isolated[0].categoryOpacity() == 1, "Transition must start on the old shelf");
            });
            boolean sawNewShape = false;
            try {
                for (int i = 0; i < 25; i++) {
                    SystemClock.sleep(20);
                    boolean[] switched = new boolean[1];
                    runOnMainSync(() -> {
                        SlotView target = isolated[0]; boolean wasOld = target.currentCategory() == ShelfModel.Category.ALL;
                        Bitmap frame = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888); target.draw(new Canvas(frame));
                        switched[0] = wasOld && target.currentCategory() == ShelfModel.Category.GBC;
                        if (switched[0]) require(target.categoryOpacity() == 0 && target.shelfPosition() == 0,
                                "New dimensions must appear at zero opacity with resting horizontal coordinates");
                        require(frame.getPixel((int) target.screenX(10), (int) target.screenY(430)) == slotPixel[0],
                                "Category fade must retain the slot housing");
                        frame.recycle();
                    });
                    sawNewShape |= switched[0];
                }
                require(sawNewShape && !isolated[0].isCategoryTransitioning() && isolated[0].categoryOpacity() == 1,
                        "Fade must switch shape and finish fully visible");
            } finally { runOnMainSync(() -> isolated[0].onDetachedFromWindow()); }
        });
        check(67, "categoryInputsCannotChangeCardsDuringProtectedStates", () -> {
            reset(1, false); key(KeyEvent.KEYCODE_ENTER);
            blockedCategoryKeys();
            require(view().currentCategory() == ShelfModel.Category.ALL && view().selectedCartridgeId().equals("moss"), "Insertion must retain MOSS");
            settle(2000); blockedCategoryKeys();
            require(view().currentMode() == SlotView.Mode.PREVIEW && view().currentCategory() == ShelfModel.Category.ALL,
                    "Preview must block category switches");
            key(KeyEvent.KEYCODE_BUTTON_B); blockedCategoryKeys(); settle(1900);
            require(view().currentCategory() == ShelfModel.Category.ALL, "Ejection must retain the current category");
            key(KeyEvent.KEYCODE_MENU); blockedCategoryKeys();
            require(view().isSettingsOpen() && view().currentCategory() == ShelfModel.Category.ALL, "Settings must block category keys");
            key(KeyEvent.KEYCODE_BUTTON_B); settle(450); categoryKey(KeyEvent.KEYCODE_E);
            require(view().currentCategory() == ShelfModel.Category.GBA, "Category inputs must resume at rest");
        });
        check(68, "focusPauseAndRecreationCloseCategoryMenu", () -> {
            reset(7, false); openCategories(); key(KeyEvent.KEYCODE_DPAD_UP); settle(450);
            ShelfModel.Category wheeled = view().currentCategory();
            require(wheeled != ShelfModel.Category.ALL, "Wheel step must enter the previous shelf");
            runOnMainSync(() -> activity.onWindowFocusChanged(false));
            require(!view().isCategoryMenuOpen() && view().currentCategory() == wheeled,
                    "Focus loss must dismiss the wheel and keep its shelf and selection");
            runOnMainSync(() -> activity.onWindowFocusChanged(true));
            openCategories(); runOnMainSync(() -> view().pause());
            require(!view().isCategoryMenuOpen(), "Pause must close the menu");
            runOnMainSync(() -> view().resume());
            openCategories(); key(KeyEvent.KEYCODE_DPAD_DOWN); settle(450);
            ShelfModel.Category next = view().currentCategory();
            String kept = view().selectedCartridgeId();
            recreateActivity();
            require(!view().isCategoryMenuOpen() && view().selectedCartridgeId().equals(kept)
                    && view().currentCategory() == next && !view().isNavigationKeyHeld(),
                    "Recreation must keep the wheel's shelf and restore a closed menu");
            require(view().cartridgeAudio().playCount() == 0, "Menu restoration must remain silent");
        });
        check(69, "pendingCategoryRecreatesAtRestWithoutReplayingFade", () -> {
            reset(7, false);
            runOnMainSync(() -> {
                require(view().switchCategory(ShelfModel.Category.GB), "Must accept the pending GB switch");
                Bundle state = new Bundle(); view().save(state);
                require(state.getString("shelf_category").equals("GB"), "Pending destination must be saved");
            });
            recreateActivity();
            require(view().currentCategory() == ShelfModel.Category.GB && view().selectedCartridgeId().equals("grove")
                    && view().shelfPosition() == 0 && !view().isCategoryTransitioning() && view().categoryOpacity() == 1,
                    "Recreation must show the accepted destination without a resumed animation");
            require(view().cartridgeAudio().playCount() == 0, "Transition restoration must remain silent");
        });
        check(70, "rapidCategoryPressesKeepTheLatestDestination", () -> {
            reset(7, false);
            runOnMainSync(() -> {
                for (int i = 0; i < 4; i++) {
                    activity.onKeyDown(KeyEvent.KEYCODE_BUTTON_R1, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1));
                    activity.onKeyUp(KeyEvent.KEYCODE_BUTTON_R1, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1));
                }
            });
            settle(450);
            require(view().currentCategory() == ShelfModel.Category.ALL && view().selectedCartridgeId().equals("lagoon"),
                    "Four quick category presses must loop back without losing the ALL position");
            key(KeyEvent.KEYCODE_E); key(KeyEvent.KEYCODE_E); key(KeyEvent.KEYCODE_E); settle(450);
            require(view().currentCategory() == ShelfModel.Category.GB, "Quick presses must count from the pending destination");
        });
        check(71, "menuAndCategoryInputsStopHeldBrowseWithoutRestarting", () -> {
            reset(1, false); press(KeyEvent.KEYCODE_DPAD_RIGHT); openCategories();
            int selection = view().selectedIndex(); settle(650);
            require(view().selectedIndex() == selection && !view().isNavigationKeyHeld(), "Opening menu must stop delayed repeats");
            key(KeyEvent.KEYCODE_BUTTON_B); settle(450);
            require(view().selectedIndex() == selection, "Closing menu must not restart a held direction");
            press(KeyEvent.KEYCODE_DPAD_RIGHT); categoryKey(KeyEvent.KEYCODE_E); settle(650);
            require(view().currentCategory() == ShelfModel.Category.GBA && view().selectedCartridgeId().equals("moss")
                    && !view().isNavigationKeyHeld(), "Category shortcut must stop browsing before entering GBA");
            key(KeyEvent.KEYCODE_BUTTON_A); settle(1900);
            require(view().currentMode() == SlotView.Mode.PREVIEW && view().selectedCartridgeId().equals("moss"),
                    "Insertion after category switch must seat the new shelf's selection");
        });
        check(254, "categoryWheelSwitchesShelvesWhileOpenAndR3ClosesIt", () -> {
            reset(1, false);
            key(KeyEvent.KEYCODE_BUTTON_THUMBR);
            require(view().isCategoryMenuOpen() && view().categoryMenuRow() == ShelfModel.Category.ALL.ordinal(),
                    "R3 must raise the wheel on the current category");
            key(KeyEvent.KEYCODE_DPAD_DOWN); settle(450);
            require(view().isCategoryMenuOpen() && view().currentCategory() == ShelfModel.Category.GBA
                    && view().categoryMenuRow() == ShelfModel.Category.GBA.ordinal(),
                    "Moving the wheel must enter the highlighted shelf and keep it open");
            key(KeyEvent.KEYCODE_BUTTON_THUMBR); settle(450);
            require(!view().isCategoryMenuOpen() && view().currentCategory() == ShelfModel.Category.GBA,
                    "R3 must close the wheel without changing the shelf");
            key(KeyEvent.KEYCODE_BUTTON_THUMBR);
            key(KeyEvent.KEYCODE_DPAD_UP); settle(450);
            require(view().isCategoryMenuOpen() && view().currentCategory() == ShelfModel.Category.ALL,
                    "Upwards wheel movement must return to ALL");
            key(KeyEvent.KEYCODE_BUTTON_A);
            require(!view().isCategoryMenuOpen() && view().currentCategory() == ShelfModel.Category.ALL,
                    "Confirm must simply close the wheel");
        });
        check(72, "cancelledMenuTouchNeverConfirmsACategory", () -> {
            reset(1, false); openCategories();
            dragStart(360, 136); touch(MotionEvent.ACTION_CANCEL, 360, 136);
            require(view().isCategoryMenuOpen() && view().currentCategory() == ShelfModel.Category.ALL, "Cancelled option press must remain unconfirmed");
            dragStart(360, 136); dragMove(360, 180); dragEnd(360, 180);
            require(view().isCategoryMenuOpen() && view().currentCategory() == ShelfModel.Category.ALL, "Dragging across options must not select");
            dragStart(360, 136); touch(MotionEvent.ACTION_POINTER_UP, 360, 136);
            require(view().isCategoryMenuOpen() && view().currentCategory() == ShelfModel.Category.ALL, "Lost pointer must not confirm");
            key(KeyEvent.KEYCODE_ESCAPE);
        });
        check(73, "dragInsertionCancellationAndAudioWorkInEveryCategory", () -> {
            reset(1, false);
            runOnMainSync(() -> { view().interactionPreferences().swipeToInsert = false; view().interactionPreferences().sound = true; });
            for (ShelfModel.Category category : ShelfModel.Category.values()) {
                if (ShelfModel.demo().shelf(category).size() == 0) continue;
                touchCategory(category); waitForAudioReady();
                String id = view().selectedCartridgeId(); int audio = view().cartridgeAudio().playCount();
                float grip = view().selectedCardTop() + 30;
                dragStart(360, grip); dragMove(360, 445); touch(MotionEvent.ACTION_CANCEL, 360, 445); settle(750);
                require(view().insertionProgress() < .01f && view().cartridgeAudio().playCount() == audio, "Cancelled drag must stay silent in " + category);
                dragStart(360, grip); dragMove(360, 445); dragEnd(360, 445); waitForPlayback(audio); settle(1700);
                require(view().currentMode() == SlotView.Mode.PREVIEW && view().selectedCartridgeId().equals(id)
                        && view().cartridgeAudio().playCount() == audio + 1, "Drag must insert the classified cartridge once");
                key(KeyEvent.KEYCODE_BUTTON_B); settle(1900);
                require(view().currentMode() == SlotView.Mode.SHELF && view().currentCategory() == category
                        && view().cartridgeAudio().playCount() == audio + 2, "Ejection must return to the same classified shelf");
            }
        });
        check(74, "shortSwipeAndSoundMuteWorkInEveryCategory", () -> {
            reset(1, false);
            runOnMainSync(() -> { view().interactionPreferences().swipeToInsert = true; view().interactionPreferences().sound = false; });
            for (ShelfModel.Category category : ShelfModel.Category.values()) {
                if (ShelfModel.demo().shelf(category).size() == 0) continue;
                touchCategory(category); int audio = view().cartridgeAudio().playCount();
                dragStart(360, 170); dragMove(360, 230); dragEnd(360, 230); settle(1900);
                require(view().currentMode() == SlotView.Mode.PREVIEW, "Short swipe must insert in " + category);
                dragStart(360, 340); dragMove(360, 280); dragEnd(360, 280); settle(1900);
                require(view().currentMode() == SlotView.Mode.SHELF && view().currentCategory() == category
                        && view().cartridgeAudio().playCount() == audio, "Muted swipes must retain the category and stay silent");
            }
        });
        check(75, "emptyCategoriesAreSkippedByShouldersAndMenu", () -> {
            runOnMainSync(() -> {
                Cartridge moss = ShelfModel.demo().current().selectedCartridge();
                SlotView isolated = new SlotView(getTargetContext(), new ShelfModel(List.of(moss)));
                isolated.layout(0, 0, 960, 640);
                isolated.handleKeyDown(KeyEvent.KEYCODE_BUTTON_L1, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1));
                isolated.loseFocus();
                require(isolated.currentCategory() == ShelfModel.Category.GBA, "L1 must skip empty GB/GBC and reach GBA");
                isolated.handleKeyDown(KeyEvent.KEYCODE_BUTTON_R1, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1));
                isolated.loseFocus();
                require(isolated.currentCategory() == ShelfModel.Category.ALL, "R1 must skip empty GB/GBC and return to ALL");
                dispatchTap(isolated, 360, 35);
                isolated.handleKeyDown(KeyEvent.KEYCODE_DPAD_UP, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
                require(isolated.isCategoryMenuOpen() && isolated.categoryMenuRow() == 1, "Menu must skip empty options");
                dispatchTap(isolated, 360, 224);
                require(isolated.isCategoryMenuOpen() && isolated.currentCategory() == ShelfModel.Category.ALL, "Empty option must not confirm");
                isolated.onDetachedFromWindow();
                SlotView empty = new SlotView(getTargetContext(), new ShelfModel(List.of()));
                empty.handleKeyDown(KeyEvent.KEYCODE_E, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E));
                require(empty.shelfCounter().equals("0 / 0") && !empty.isCategoryTransitioning(), "Empty collection must not enter category switching");
                empty.onDetachedFromWindow();
            });
        });
        check(76, "classifiedSettingsSurviveRecreationWithClosedCategoryMenu", () -> {
            reset(7, false); touchCategory(ShelfModel.Category.GBC); key(KeyEvent.KEYCODE_DPAD_RIGHT); settle(700);
            key(KeyEvent.KEYCODE_MENU); recreateActivity();
            require(view().currentCategory() == ShelfModel.Category.GBC && view().selectedCartridgeId().equals("dusk")
                    && view().shelfCounter().equals("2 / 2") && view().isSettingsOpen() && !view().isCategoryMenuOpen(),
                    "Recreated settings must retain the classified selection and a closed category menu");
            blockedCategoryKeys();
            require(view().currentCategory() == ShelfModel.Category.GBC, "Restored settings must keep category controls locked");
            key(KeyEvent.KEYCODE_BUTTON_B); settle(450); categoryKey(KeyEvent.KEYCODE_Q); categoryKey(KeyEvent.KEYCODE_Q);
            require(view().currentCategory() == ShelfModel.Category.ALL && view().selectedCartridgeId().equals("lagoon"),
                    "ALL selection must survive classified settings recreation");
        });
        check(77, "yShortcutOpensSettingsStopsBrowseAndIgnoresRepeat", () -> {
            reset(1, false); press(KeyEvent.KEYCODE_DPAD_RIGHT);
            key(KeyEvent.KEYCODE_BUTTON_Y);
            require(view().isSettingsOpen() && !view().isNavigationKeyHeld(), "Y must open settings and stop held browsing");
            int selection = view().selectedIndex();
            runOnMainSync(() -> activity.dispatchKeyEvent(new KeyEvent(0, SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y, 1)));
            settle(600);
            require(view().isSettingsOpen() && view().selectedIndex() == selection, "Repeated Y must leave settings open");
            key(KeyEvent.KEYCODE_BUTTON_Y);
            require(!view().isSettingsOpen() && view().currentMode() == SlotView.Mode.SHELF
                    && view().insertionProgress() == 0, "Y must close settings without inserting");
        });
        check(78, "touchShoulderHintsSwitchCategoriesAndCancelSafely", () -> {
            reset(1, false);
            float left = view().homeLayout().centerX() - 94, right = view().homeLayout().centerX() + 94;
            dragStart(left, 36); dragEnd(left, 36); settle(450);
            require(view().currentCategory() == ShelfModel.Category.GB, "Left hint must wrap to GB");
            dragStart(right, 36); dragEnd(right, 36); settle(450);
            require(view().currentCategory() == ShelfModel.Category.ALL, "Right hint must wrap to ALL");
            dragStart(right, 36); touch(MotionEvent.ACTION_CANCEL, right, 36);
            require(!view().isCategoryTransitioning(), "Cancelled hint must not switch categories");
            dragStart(left, 36); dragMove(360, 200); dragEnd(360, 200); settle(600);
            require(view().currentCategory() == ShelfModel.Category.ALL && view().insertionProgress() == 0,
                    "Dragging from a hint must not browse or insert");
            openCategories(); dragStart(right, 36); dragEnd(right, 36); settle(450);
            require(!view().isCategoryMenuOpen() && view().currentCategory() == ShelfModel.Category.ALL,
                    "Hint outside menu must only dismiss the menu");
        });
        check(79, "statusCornersDoNotActivateCategoryOrSettings", () -> {
            reset(1, false);
            for (float x : new float[]{50, 591, 650, 683}) {
                dragStart(x, 35); dragEnd(x, 35);
                require(!view().isCategoryMenuOpen() && !view().isSettingsOpen()
                        && view().currentCategory() == ShelfModel.Category.ALL && view().insertionProgress() == 0,
                        "Status corner taps must leave shelf controls idle");
            }
            float entryY = view().homeLayout().machineClosedTop() + HomeLayout.ENTRY_CENTER;
            dragStart(76, entryY); dragMove(210, entryY); dragEnd(210, entryY);
            require(!view().isSettingsOpen(), "Dragging off settings must cancel the press");
        });
        check(80, "positionIndicatorAdaptsAtNineCardsAndStaysCenteredAfterWrap", () -> {
            runOnMainSync(() -> {
                List<Cartridge> samples = new ArrayList<>(ShelfModel.demo().current().cartridges);
                Cartridge moss = samples.get(1);
                samples.add(new Cartridge("position-extra", moss.title, "10", moss.plastic,
                        moss.accent, moss.scene, moss.platform, moss.finish));
                Bitmap emptyReference = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
                SlotView emptyView = new SlotView(getTargetContext(), new ShelfModel(List.of()));
                emptyView.layout(0, 0, 960, 640);
                emptyView.draw(new Canvas(emptyReference));
                emptyView.onDetachedFromWindow();
                try {
                    for (int count : new int[]{0, 1, 9, 10}) {
                        ShelfModel model = new ShelfModel(samples.subList(0, count));
                        if (count > 0) model.current().select(0);
                        SlotView isolated = new SlotView(getTargetContext(), model);
                        isolated.layout(0, 0, 960, 640);
                        Bitmap frame = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
                        try {
                            if (count > 1) {
                                isolated.handleKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,
                                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
                                isolated.handleKeyUp(KeyEvent.KEYCODE_DPAD_LEFT,
                                        new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));
                                require(isolated.shelfCounter().equals(count + " / " + count),
                                        "Position must update when wrapping from first to last card");
                                Bundle state = new Bundle();
                                isolated.save(state); isolated.restore(state);
                            }
                            isolated.draw(new Canvas(frame));
                            int ink = 0, outsideCenter = 0;
                            for (int y = (int) isolated.screenY(389); y < isolated.screenY(411); y++) {
                                for (int x = (int) isolated.screenX(80); x < isolated.screenX(640); x++) {
                                    if (frame.getPixel(x, y) != emptyReference.getPixel(x, y)) {
                                        ink++;
                                        if (x < isolated.screenX(320) || x >= isolated.screenX(400)) outsideCenter++;
                                    }
                                }
                            }
                            require(count <= 1 ? ink == 0 : ink > 0, "Empty/single shelves must hide position; multiple cards must show it");
                            if (count == 9) require(outsideCenter > 0, "Nine cards must retain a row of dots");
                            if (count == 10) require(outsideCenter == 0, "Ten cards must replace dots with a centered counter");
                            for (int y = (int) isolated.screenY(444); y < isolated.screenY(465); y++) {
                                for (int x = (int) isolated.screenX(500); x < isolated.screenX(570); x++) {
                                    require(frame.getPixel(x, y) == emptyReference.getPixel(x, y), "Bottom-right counter must be removed");
                                }
                            }
                            File file = new File(getTargetContext().getExternalFilesDir(null), "slot-position-" + count + ".png");
                            try (FileOutputStream output = new FileOutputStream(file)) {
                                require(frame.compress(Bitmap.CompressFormat.PNG, 100, output), "Position screenshot must export");
                            } catch (IOException error) {
                                throw new AssertionError("Cannot export position screenshot", error);
                            }
                        } finally {
                            frame.recycle(); isolated.onDetachedFromWindow();
                        }
                    }
                } finally {
                    emptyReference.recycle();
                }
            });
        });
        new AnimationChecks(this).run();
        new SwipeChecks(this).run();
        if (animationOnly) {
            runOnMainSync(() -> {
                view().interactionPreferences().swipeToInsert = originalSwipe;
                view().interactionPreferences().haptics = originalHaptics;
                view().interactionPreferences().sound = originalSound;
                view().interactionPreferences().autoLaunchOnInsert = originalAutoLaunch;
                view().interactionPreferences().lightTheme = originalLight;
                view().interactionPreferences().wallpaper = originalWallpaper;
                view().interactionPreferences().wallpaperTint = originalTint;
                view().interactionPreferences().showTitles = originalShowTitles;
                view().interactionPreferences().buttonPrompts = originalPrompts;
                view().interactionPreferences().swapConfirmBack = originalSwapConfirmBack;
                view().interactionPreferences().save();
            });
            reset(1, false); finishChecks(); return;
        }
        new LetterNavigationChecks(this).run();
        new DesktopChecks(this).run();
        new LanguageChecks(this).run();
        new DrawingChecks(this).run();
        runOnMainSync(() -> {
            view().interactionPreferences().swipeToInsert = originalSwipe;
            view().interactionPreferences().haptics = originalHaptics;
            view().interactionPreferences().sound = originalSound;
            view().interactionPreferences().autoLaunchOnInsert = originalAutoLaunch;
            view().interactionPreferences().lightTheme = originalLight;
            view().interactionPreferences().wallpaper = originalWallpaper;
            view().interactionPreferences().wallpaperTint = originalTint;
            view().interactionPreferences().showTitles = originalShowTitles;
            view().interactionPreferences().buttonPrompts = originalPrompts;
            view().interactionPreferences().swapConfirmBack = originalSwapConfirmBack;
            view().interactionPreferences().save();
        });
        reset(1, false);
        if (checkOnly == 0 || checkOnly >= 108 && checkOnly <= 132) new LibraryChecks(this).run();
        if (checkOnly == 0 || checkOnly >= 133 && checkOnly <= 152) new LibraryManagementChecks(this).run();
        if (checkOnly == 0 || checkOnly >= 166 && checkOnly <= 175 || checkOnly == 236) new ConnectionChecks(this).run();
        if (checkOnly == 0 || checkOnly >= 203 && checkOnly <= 207) new RetroArchSettingsChecks(this).run();
        if (checkOnly == 0 || checkOnly >= 208 && checkOnly <= 227 || checkOnly == 235 || checkOnly == 237 || checkOnly == 238) new RetroArchChecks(this).run();
        if (checkOnly == 0 || checkOnly >= 228 && checkOnly <= 234) new RetroArchPresetChecks(this).run();
        if (checkOnly == 0 || checkOnly >= 239 && checkOnly <= 250) new OnboardingChecks(this).run();
        if (checkOnly == 0 || checkOnly >= 251 && checkOnly <= 253) new ArtworkChecks(this).run();
        finishChecks();
    }

    private void finishChecks() {
        Bundle finalResult = new Bundle();
        finalResult.putString("stream", "\n" + results + (failures == 0 ? "OK (" + suiteCount() + " tests)\n" : "FAILURES: " + failures + "\n"));
        finalResult.putInt("failures", failures);
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, finalResult);
    }

    private int suiteCount() { return checkOnly > 0 || launchProbeOnly ? 1 : artworkOnly ? ArtworkChecks.COUNT : studioOnly ? 6 : onboardingOnly ? OnboardingChecks.COUNT : retroArchOnly ? RetroArchChecks.COUNT : retroArchSettingsOnly ? RetroArchSettingsChecks.COUNT : retroArchPresetsOnly ? RetroArchPresetChecks.COUNT : swipeOnly ? SwipeChecks.COUNT : animationTailOnly ? 1 : animationOnly ? 43 : drawingOnly ? DrawingChecks.COUNT : lettersOnly ? 13 : languageOnly ? 9 : libraryManagementOnly ? 45 : libraryOnly ? 25 : managementOnly ? 20 : connectionOnly ? 11 : COUNT; }

    void note(String text) { results.append(text).append('\n'); }

    void check(int number, String name, Runnable action) {
        if (studioOnly && (number < 243 || number > 248)) return;
        if (checkOnly > 0 && number != checkOnly) return;
        if (animationOnly && !(number <= 20 || number >= 28 && number <= 33 || number >= 186 && number <= 195)) return;
        Bundle status = new Bundle();
        status.putString("id", "InstrumentationTestRunner");
        status.putString("class", getClass().getName()); status.putString("test", name);
        status.putInt("numtests", suiteCount());
        status.putInt("current", checkOnly > 0 ? 1 : retroArchOnly ? (number == 235 ? 21 : number == 237 ? 22 : number == 238 ? 23 : number - 207) : retroArchSettingsOnly ? number - 202 : retroArchPresetsOnly ? number - 227 : swipeOnly ? number - 195 : animationTailOnly ? 1 : animationOnly ? number <= 20 ? number : number <= 33 ? number - 7 : number - 159
                : artworkOnly ? number - 250 : studioOnly ? number - 242 : onboardingOnly ? number - 238 : drawingOnly ? number - 175 : lettersOnly ? number - 152 : languageOnly ? number - 98 : libraryOnly ? number - 107 : managementOnly ? number - 132 : connectionOnly ? (number == 236 ? 7 : number >= 172 ? number - 164 : number - 165) : number);
        sendStatus(1, status);
        try {
            action.run(); results.append("PASS ").append(name).append('\n');
            status.putString("stream", "."); sendStatus(0, status);
        } catch (Throwable error) {
            failures++; results.append("FAIL ").append(name).append(": ").append(error).append('\n');
            status.putString("stack", error.toString()); status.putString("stream", "\n" + error + "\n");
            sendStatus(-2, status);
        }
    }

    /** Keep failed UI-thread assertions in the test report instead of terminating the target process. */
    @Override public void runOnMainSync(Runnable action) {
        Throwable[] failure = new Throwable[1];
        super.runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] instanceof RuntimeException error) throw error;
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private void waitForAudioReady() {
        long limit = SystemClock.uptimeMillis() + 5000;
        while (!view().cartridgeAudio().ready() && SystemClock.uptimeMillis() < limit) SystemClock.sleep(5);
        require(view().cartridgeAudio().ready(), "Original WAV files must decode in Android SoundPool");
    }

    private void waitForPlayback(int previous) {
        long limit = SystemClock.uptimeMillis() + 3000;
        while (view().cartridgeAudio().playCount() <= previous && SystemClock.uptimeMillis() < limit) SystemClock.sleep(5);
        require(view().cartridgeAudio().playCount() > previous, "Expected a native audio playback stream");
    }

    private void matchesOriginal(CartridgeRenderer renderer, String name, Cartridge.Platform platform,
                                 int plastic, CartridgeRenderer.Finish finish) {
        matchesPixels(name, renderer.spec(platform), renderer.renderPixels(platform, plastic, finish, null));
    }

    private void matchesPixels(String name, CartridgeRenderer.Spec spec, int[] pixels) {
        try (DataInputStream in = new DataInputStream(getContext().getAssets().open("cartridges/" + name + ".argb"))) {
            require(in.readInt() == spec.width && in.readInt() == spec.height, "Reference size must match");
            for (int i = 0; i < pixels.length; i++) {
                int expected = in.readInt();
                if (pixels[i] != expected) throw new AssertionError(name + " differs at "
                        + (i % spec.width) + "," + (i / spec.width) + ": expected "
                        + Integer.toHexString(expected) + ", actual " + Integer.toHexString(pixels[i]));
            }
            require(in.read() == -1, "Reference must contain exactly one frame");
        } catch (IOException error) { throw new AssertionError("Cannot read original reference " + name, error); }
    }

    SlotView view() { return activity.slotView; }
    MainActivity activity() { return activity; }
    HomeCoordinator home() { return activity.home; }
    void categoryKey(int code) { key(code); settle(450); }
    void openCategories() {
        dragStart(360, 35); dragEnd(360, 35);
        require(view().isCategoryMenuOpen(), "Category label must open its menu");
    }
    private void touchCategory(ShelfModel.Category category) {
        openCategories(); float y = 92 + category.ordinal() * 44;
        dragStart(360, y); dragEnd(360, y); settle(450);
    }
    private void blockedCategoryKeys() {
        for (int code : new int[]{KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_E}) key(code);
    }
    void recreateActivity() {
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        runOnMainSync(() -> activity.recreate());
        Activity restored = monitor.waitForActivityWithTimeout(5000); removeMonitor(monitor);
        require(restored instanceof MainActivity, "Activity must recreate");
        activity = (MainActivity) restored; waitForIdleSync(); settle(500);
    }
    void dispatchTap(SlotView target, float x, float y) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, now, action, target.screenX(x), target.screenY(y), 0);
            target.dispatchTouchEvent(event); event.recycle();
        }
    }
    void reset(int index, boolean inserted) {
        runOnMainSync(() -> {
            view().cancelInteraction();
            Bundle state = new Bundle();
            state.putString("shelf_selected_ALL", ShelfModel.demo().current().cartridges.get(index).id);
            state.putBoolean("inserted", inserted);
            view().restore(state);
        });
        settle(500);
    }
    private long downTime;
    private void restoreShelf(ShelfModel.Category category, String id, boolean inserted) {
        runOnMainSync(() -> {
            Bundle state = new Bundle();
            state.putString("shelf_category", category.name());
            state.putString("shelf_selected_" + category.name(), id);
            state.putBoolean("inserted", inserted);
            view().restore(state);
        });
        settle(500);
    }

    Bitmap frame() {
        Bitmap[] frame = new Bitmap[1];
        runOnMainSync(() -> {
            frame[0] = Bitmap.createBitmap(view().getWidth(), view().getHeight(), Bitmap.Config.ARGB_8888);
            view().draw(new Canvas(frame[0]));
        });
        return frame[0];
    }
    void dragStart(float x, float y) { downTime = SystemClock.uptimeMillis(); touch(MotionEvent.ACTION_DOWN, x, y); }
    void dragMove(float x, float y) { SystemClock.sleep(85); touch(MotionEvent.ACTION_MOVE, x, y); }
    void dragEnd(float x, float y) { SystemClock.sleep(100); touch(MotionEvent.ACTION_UP, x, y); }
    void touch(int action, float x, float y) {
        runOnMainSync(() -> {
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    view().screenX(x), view().screenY(y), 0);
            view().dispatchTouchEvent(event); event.recycle();
        });
    }
    /** Sends a touch through the whole window so native bay content and the drawn machine both see it. */
    void windowTouch(int action, float screenX, float screenY) {
        runOnMainSync(() -> {
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, screenX, screenY, 0);
            activity.getWindow().getDecorView().dispatchTouchEvent(event); event.recycle();
        });
    }
    void windowTap(float screenX, float screenY) {
        downTime = SystemClock.uptimeMillis();
        windowTouch(MotionEvent.ACTION_DOWN, screenX, screenY);
        SystemClock.sleep(60);
        windowTouch(MotionEvent.ACTION_UP, screenX, screenY);
        settle(250);
    }
    /** Taps the centre of a native view in window coordinates. */
    void tapView(android.view.View target) {
        float[] center = new float[2];
        runOnMainSync(() -> {
            int[] location = new int[2];
            target.getLocationInWindow(location);
            center[0] = location[0] + target.getWidth() / 2f;
            center[1] = location[1] + target.getHeight() / 2f;
        });
        windowTap(center[0], center[1]);
    }
    void tapSettingsRow(int row) {
        android.view.View[] target = new android.view.View[1];
        runOnMainSync(() -> target[0] = home().settingsPanel().rowView(row));
        tapView(target[0]);
    }
    void key(int code) {
        press(code); release(code);
    }
    void press(int code) {
        runOnMainSync(() -> activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code)));
    }
    void release(int code) {
        runOnMainSync(() -> activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code)));
    }
    void settle(long millis) { SystemClock.sleep(millis); waitForIdleSync(); }
    void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
