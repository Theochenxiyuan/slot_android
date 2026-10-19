package fyi.slot.android;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.util.List;
import java.util.Set;

/** Stage 6 lifecycle, seated identity view and input checks, with external activity dispatch intercepted. */
final class RetroArchChecks {
    static final int COUNT = 23;
    private final InteractionChecks t;

    RetroArchChecks(InteractionChecks checks) { t = checks; }

    void run() {
        try (RetroArchFixture f = new RetroArchFixture(t)) {
            t.note("Stage 6 uses owned 512-byte ROM fixtures and intercepts RetroArch activities; no gameplay is claimed.");
            t.check(208, "romSeatingLaunchesOnceAfterAnimationAndCue", () -> {
                f.select("Garden.gba", false);
                f.sawLaunchingFeedback = false;
                int calls = f.launches.size();
                t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(1900);
                t.require(f.launches.size() == calls + 1, "Exactly one seating launch");
                t.require(f.sawLaunchingFeedback, "Initial launch shows transient launching feedback at dispatch");
                t.require(t.view().currentMode() == SlotView.Mode.PREVIEW && t.view().insertionProgress() == 1, "Seated before launch");
                t.require(t.view().revealProgress() == 0 && !t.view().canBrowse(), "Real ROM has no demo scene or shelf navigation");
                t.require(f.informationOpacityAtDispatch == 0f, "Automatic launch must dispatch without showing identity or controls");
                t.require(f.launches.get(calls).getStringExtra("ROM").equals(f.cart("Garden.gba").rom.file().getAbsolutePath()), "Selected ROM intent");
                t.require(f.launches.get(calls).getAction() == null && f.launches.get(calls).getCategories() == null,
                        "Explicit component launch must not request launcher intent filters");
                t.settle(400); t.require(f.launches.size() == calls + 1, "Idle redraw must not reenter");
            });
            t.check(209, "cancelledRomDragDoesNotLaunch", () -> {
                f.select("Garden.gba", false);
                t.runOnMainSync(() -> t.view().interactionPreferences().swipeToInsert = false);
                int calls = f.launches.size(); float cx = t.view().homeLayout().centerX();
                float start = t.view().selectedCardTop() + 30;
                t.dragStart(cx, start); t.dragMove(cx, start + t.view().homeLayout().insertionDistance(Cartridge.Platform.GBA) * .8f);
                t.require(t.view().insertionProgress() > SlotView.COMMIT && f.launches.size() == calls, "Holding never launches");
                t.touch(MotionEvent.ACTION_CANCEL, cx, start + 250); t.settle(900);
                t.require(t.view().canBrowse() && f.launches.size() == calls, "Cancel restores shelf without launch");
            });
            t.check(210, "returnAndHomeKeepRomSeatedWithoutRelaunch", () -> {
                f.select("Garden.gba", false); t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(1900);
                int calls = f.launches.size(); f.returnToSlot();
                t.runOnMainSync(() -> t.home().resume()); t.settle(400);
                t.require(t.view().currentMode() == SlotView.Mode.PREVIEW && t.view().insertionProgress() == 1, "Return leaves cartridge in slot");
                t.require(!t.view().isLaunchingFeedbackShown(), "Return clears launching feedback to neutral seated view");
                t.require(t.view().seatedInformationOpacity() == 1f, "Returned seated information finishes fading in");
                t.require(f.launches.size() == calls, "Resume and library refresh must not launch");
                f.screenshot("rom-seated");
            });
            t.check(211, "romRecreationRestoresSeatWithoutAutomaticLaunch", () -> {
                f.select("Garden.gba", false); t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(1900);
                int calls = f.launches.size(); t.recreateActivity(); f.interceptSuccess(); t.settle(400);
                t.require(t.view().currentMode() == SlotView.Mode.PREVIEW && t.view().currentCartridge().id.equals(f.cart("Garden.gba").id), "Deferred library restoration keeps same ROM");
                t.require(!t.view().isLaunchingFeedbackShown(), "Recreation must not leave stale launching feedback");
                t.require(f.launches.size() == calls, "Recreation must not automatically launch");
            });
            t.check(212, "seatedConfirmRelaunchesAndBlocksRepeatedRequests", () -> {
                f.select("Garden.gba", true); int calls = f.launches.size();
                t.runOnMainSync(() -> {
                    t.activity().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A));
                    t.activity().dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 2));
                    t.activity().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                });
                t.settle(200); t.require(f.launches.size() == calls + 1, "At most one pending launch");
                f.returnToSlot(); t.key(KeyEvent.KEYCODE_ENTER); t.settle(200);
                t.require(f.launches.size() == calls + 2, "Enter relaunches on return");
            });
            t.check(213, "onlyCentralSlotTapRelaunchesAndCancelDoesNot", () -> {
                f.select("Garden.gba", false);
                t.runOnMainSync(() -> t.view().interactionPreferences().autoLaunchOnInsert = false);
                try {
                    int calls = f.launches.size();
                    t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(1900);
                    t.require(t.view().currentMode() == SlotView.Mode.PREVIEW && f.launches.size() == calls,
                            "Disabling auto-launch on insert keeps cartridge seated without launching");
                    t.require(t.view().seatedInformationOpacity() == 1f, "Manual seating must finish revealing identity and controls");
                    float startX = t.view().homeLayout().width - 54f;
                    t.dragStart(startX, 39f); t.dragEnd(startX, 39f); t.settle(200);
                    t.require(f.launches.size() == calls + 1, "Top-right start button launches seated game");
                    f.returnToSlot();
                } finally {
                    t.runOnMainSync(() -> t.view().interactionPreferences().autoLaunchOnInsert = true);
                }
                f.select("Garden.gba", true); int calls = f.launches.size();
                float cx = t.view().homeLayout().centerX(), y = t.view().homeLayout().machineClosedTop() + 25;
                t.dragStart(cx, y); t.touch(MotionEvent.ACTION_CANCEL, cx, y); t.settle(100);
                t.dragStart(cx, 160); t.dragEnd(cx, 160); t.settle(100);
                t.require(f.launches.size() == calls, "Cancel and background tap do not launch");
                t.dragStart(cx, y); t.dragEnd(cx, y); t.settle(200);
                t.require(f.launches.size() == calls + 1, "Central slot tap launches");
            });
            t.check(214, "seatedBackButtonAndEjectButtonRestoreOriginalShelfSelection", () -> {
                for (int key : new int[]{KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, 0}) {
                    f.select("Moon.gb", true); int calls = f.launches.size();
                    if (key == 0) { t.dragStart(67, 40); t.dragEnd(67, 40); } else t.key(key);
                    t.settle(1700);
                    t.require(t.view().canBrowse() && t.view().currentCartridge().id.equals(f.cart("Moon.gb").id), "Eject restores selected shelf item");
                    t.require(f.launches.size() == calls, "Ejection never launches");
                }
            });
            t.check(215, "realRomEjectDragHonorsThresholdAndCancellation", () -> {
                f.select("Lake.gbc", true);
                t.runOnMainSync(() -> t.view().interactionPreferences().swipeToInsert = false);
                float cx = t.view().homeLayout().centerX();
                t.dragStart(cx, 340); t.dragMove(cx, 310); t.dragEnd(cx, 310); t.settle(200);
                t.require(t.view().currentMode() == SlotView.Mode.PREVIEW, "Short drag must stay seated");
                t.dragStart(cx, 340); t.dragMove(cx, 230); t.touch(MotionEvent.ACTION_CANCEL, cx, 230); t.settle(200);
                t.require(t.view().currentMode() == SlotView.Mode.PREVIEW, "Cancelled drag must stay seated");
                t.dragStart(cx, 340); t.dragMove(cx, 230); t.dragEnd(cx, 230); t.settle(1700);
                t.require(t.view().canBrowse(), "Committed drag must eject");
                f.select("Lake.gbc", true);
                t.runOnMainSync(() -> t.view().interactionPreferences().swipeToInsert = true);
                t.dragStart(cx, 340); t.dragMove(cx, 280); t.dragEnd(cx, 280); t.settle(1700);
                t.require(t.view().canBrowse(), "Short upward swipe ejects in swipe mode");
            });
            t.check(216, "retroArchUnavailableAndLaunchFailureAutomaticallyEject", () -> {
                for (RetroArchLauncher.Result result : new RetroArchLauncher.Result[]{RetroArchLauncher.Result.NOT_INSTALLED, RetroArchLauncher.Result.FAILED}) {
                    f.select("Garden.gba", false); int[] attempts = {0};
                    t.runOnMainSync(() -> t.home().gameLauncher = rom -> { attempts[0]++; return result; });
                    t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(3200);
                    t.require(attempts[0] == 1 && t.view().canBrowse(), "One failed attempt then automatic shelf recovery");
                    String previous = t.view().selectedCartridgeId(); t.key(KeyEvent.KEYCODE_DPAD_RIGHT); t.settle(500);
                    t.require(!previous.equals(t.view().selectedCartridgeId()), "Recovered shelf remains navigable");
                }
                f.interceptSuccess();
            });
            t.check(217, "missingRomRefreshesLibraryAndAutomaticallyEjects", () -> {
                f.select("Garden.gba", false); RomRecord rom = f.cart("Garden.gba").rom;
                t.require(rom.file().delete(), "Remove owned ROM only");
                RetroArchLauncher launcher = new RetroArchLauncher(t.activity(), new RetroArchPreferences(t.activity()));
                t.runOnMainSync(() -> t.home().gameLauncher = launcher::launch);
                t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(3200);
                t.require(t.view().canBrowse() && t.view().libraryCount() == 2, "Missing file refreshes shelf after ejection");
                t.require(t.home().library().snapshot().stream().noneMatch(cart -> cart.id.equals(rom.id)), "Missing record removed");
                f.write("GBA/Garden.gba"); t.runOnMainSync(() -> t.home().library().refresh()); f.ready(); f.interceptSuccess();
            });
            t.check(218, "missingRestoredRomCannotSeatAnotherGame", () -> {
                int calls = f.launches.size();
                t.runOnMainSync(() -> {
                    Bundle state = new Bundle(); state.putString("shelf_category", "ALL");
                    state.putString("shelf_selected_ALL", "removed-rom"); state.putBoolean("inserted", true);
                    t.view().restore(state);
                });
                t.settle(200); t.require(t.view().canBrowse() && f.launches.size() == calls, "Unavailable saved ROM stays on shelf");
            });
            t.check(219, "demoRetainsStaticPreviewAndInsertEjectAudio", () -> {
                MainActivity.testShelf = ShelfModel.demo(); t.recreateActivity(); f.interceptSuccess(); t.reset(1, false);
                t.runOnMainSync(() -> t.view().interactionPreferences().sound = true);
                int calls = f.launches.size(), cues = t.view().cartridgeAudio().playCount();
                t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(1900);
                t.require(t.view().currentMode() == SlotView.Mode.PREVIEW && t.view().revealProgress() > .99f, "Demo shows static scene");
                t.require(t.view().cartridgeAudio().playCount() == cues + 1, "One insert cue");
                t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(100); t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(1700);
                t.require(t.view().canBrowse() && t.view().cartridgeAudio().playCount() == cues + 2, "Demo ejects with one eject cue");
                t.require(f.launches.size() == calls, "Demo never launches emulator");
                MainActivity.testShelf = null; t.recreateActivity(); f.ready(); f.interceptSuccess();
            });
            new GameCoreChecks(t, f).run();
            t.check(235, "seatedIdentityViewBilingualLongTitleAndVisualThemes", () -> {
                java.io.File savedWallpaper = BackdropRenderer.wallpaperFile(t.getTargetContext());
                java.io.File backupWallpaper = new java.io.File(t.getTargetContext().getFilesDir(), "wallpaper.backup.png");
                boolean hadWallpaper = savedWallpaper.exists();
                if (hadWallpaper) savedWallpaper.renameTo(backupWallpaper);
                java.io.File sampleImage = new java.io.File(t.getTargetContext().getCacheDir(), "sample-photo.png");
                try {
                    android.graphics.Bitmap sourcePhoto = android.graphics.Bitmap.createBitmap(640, 480, android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas photoCanvas = new android.graphics.Canvas(sourcePhoto);
                    photoCanvas.drawColor(0xff1e3a5f);
                    android.graphics.Paint stripe = new android.graphics.Paint();
                    stripe.setColor(0xff4a90d9);
                    photoCanvas.drawRect(80, 60, 560, 420, stripe);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(sampleImage)) {
                        sourcePhoto.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    }
                    sourcePhoto.recycle();

                    t.runOnMainSync(() -> t.home().wallpaperCropDialog().open(android.net.Uri.fromFile(sampleImage), 1.5f));
                    long deadline = android.os.SystemClock.uptimeMillis() + 3000;
                    while (android.os.SystemClock.uptimeMillis() < deadline && !t.home().wallpaperCropDialog().isReady()) t.settle(50);
                    t.require(t.home().wallpaperCropDialog().isReady(), "Wallpaper crop dialog must decode local photo");
                    t.runOnMainSync(() -> {
                        t.home().wallpaperCropDialog().zoomBy(1.4f);
                        t.home().wallpaperCropDialog().panBy(1f, -1f);
                        t.home().wallpaperCropDialog().confirmCrop();
                    });
                    deadline = android.os.SystemClock.uptimeMillis() + 3000;
                    while (android.os.SystemClock.uptimeMillis() < deadline && t.home().wallpaperCropDialog().isShowing()) t.settle(50);
                    t.require(BackdropRenderer.hasCustomWallpaper(t.getTargetContext())
                                    && t.view().interactionPreferences().wallpaper == InteractionPreferences.Wallpaper.CUSTOM,
                            "Confirming crop must save custom wallpaper and select it");

                    t.runOnMainSync(() -> {
                        for (int[] size : new int[][]{{960, 640}, {960, 960}, {1280, 720}}) {
                            for (AppLanguage.Choice choice : java.util.List.of(AppLanguage.Choice.CHINESE, AppLanguage.Choice.ENGLISH)) {
                                java.io.File root = t.getTargetContext().getFilesDir();
                                RomRecord longRom = new RomRecord(root.getPath(),
                                        "Supercalifragilisticexpialidocious Anniversary Edition (World).gba",
                                        Cartridge.Platform.GBA, 512, 0, new byte[0x150], null, false);
                                Cartridge cart = new Cartridge(longRom,
                                        new ShellConfig.Choice("auto", 0xff2f5cc0, CartridgeRenderer.Finish.TRANSLUCENT), null);
                                SlotView isolated = new SlotView(t.getTargetContext(), new ShelfModel(java.util.List.of(cart)));
                                isolated.layout(0, 0, size[0], size[1]);
                                isolated.language().select(choice);
                                isolated.onLanguageChanged();
                                isolated.interactionPreferences().lightTheme = choice == AppLanguage.Choice.ENGLISH;
                                isolated.interactionPreferences().wallpaper = choice == AppLanguage.Choice.CHINESE
                                        ? InteractionPreferences.Wallpaper.NONE : InteractionPreferences.Wallpaper.CUSTOM;
                                isolated.interactionPreferences().wallpaperTint = InteractionPreferences.WallpaperTint.LOW;
                                isolated.interactionPreferences().showTitles = true;
                                Bundle state = new Bundle();
                                state.putString("shelf_selected_ALL", cart.id);
                                state.putBoolean("inserted", false);
                                isolated.restore(state);
                                android.graphics.Bitmap shelfFrame = android.graphics.Bitmap.createBitmap(size[0], size[1], android.graphics.Bitmap.Config.ARGB_8888);
                                isolated.draw(new android.graphics.Canvas(shelfFrame));
                                if (choice == AppLanguage.Choice.CHINESE) {
                                    t.require(shelfFrame.getPixel(Math.round(isolated.screenX(12f)), Math.round(isolated.screenY(120f))) == 0xff000000,
                                            "Default black wallpaper must render pure black backdrop");
                                } else {
                                    t.require(shelfFrame.getPixel(Math.round(isolated.screenX(12f)), Math.round(isolated.screenY(120f))) != 0xff000000,
                                            "Custom cropped wallpaper must render on backdrop");
                                }
                                state.putBoolean("inserted", true);
                                isolated.restore(state);
                                android.graphics.Bitmap seatedFrame = android.graphics.Bitmap.createBitmap(size[0], size[1], android.graphics.Bitmap.Config.ARGB_8888);
                                isolated.draw(new android.graphics.Canvas(seatedFrame));
                                int cx = Math.round(isolated.screenX(isolated.homeLayout().centerX()));
                                int cy = Math.round(isolated.screenY(isolated.homeLayout().machineClosedTop() / 2f));
                                t.require(seatedFrame.getPixel(cx, cy) != 0xff000000, "Seated identity card must render above pure black at " + size[0] + "x" + size[1]);
                                int ejectX = Math.round(isolated.screenX(44f)), ejectY = Math.round(isolated.screenY(39f));
                                t.require(seatedFrame.getPixel(ejectX, ejectY) != seatedFrame.getPixel(Math.round(isolated.screenX(10f)), ejectY),
                                        "Seated B eject badge must render at top-left");
                                int startX = Math.round(isolated.screenX(isolated.homeLayout().width - 44f));
                                t.require(seatedFrame.getPixel(startX, ejectY) != seatedFrame.getPixel(Math.round(isolated.screenX(isolated.homeLayout().width - 10f)), ejectY),
                                        "Seated A start badge must render at top-right");
                                shelfFrame.recycle();
                                seatedFrame.recycle();
                                isolated.onDetachedFromWindow();
                            }
                        }
                    });
                    SeatedBoxArtChecks.run(t);
                } catch (java.io.IOException error) {
                    throw new AssertionError(error);
                } finally {
                    sampleImage.delete();
                    savedWallpaper.delete();
                    if (hadWallpaper) backupWallpaper.renameTo(savedWallpaper);
                    t.runOnMainSync(() -> {
                        t.view().backdrop().reloadCustomWallpaper();
                        t.view().interactionPreferences().wallpaper = InteractionPreferences.Wallpaper.NONE;
                        t.view().interactionPreferences().save();
                    });
                }
            });
            t.check(238, "selectOpensCartridgeMenuAndRespectsProtectedStates", () -> {
                f.select("Garden.gba", false);
                int calls = f.launches.size();
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_SELECT);
                CartridgeDetails details = t.home().cartridgeDetails();
                t.require(details.isShowing() && !details.isDetailsPage(), "Select opens cartridge menu");
                t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(150);
                t.require(!details.isShowing(), "B closes the Select menu");
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_START); t.settle(150);
                t.require(details.isShowing() && !details.isDetailsPage(), "Start opens cartridge menu");
                PanelDialog actions = details.panel();
                int[] labels = {R.string.game_details, R.string.edit_appearance, R.string.game_core, R.string.favorite_game, R.string.hide_game};
                t.require(actions.rowCount() == labels.length, "Select exposes all five cartridge actions");
                for (int i = 0; i < labels.length; i++) {
                    t.require(actions.rowLabel(i).equals(t.view().language().text(labels[i])), "Existing menu action stays available");
                }
                f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                t.require(actions.selectedIndex() == 2 && !actions.rowView(0).isFocused()
                        && !actions.rowView(0).isActivated() && actions.rowView(2).isActivated(),
                        "Moving selection must not leave the first row focused");
                f.nativeKey(KeyEvent.KEYCODE_DPAD_UP);
                f.nativeKey(KeyEvent.KEYCODE_DPAD_UP);
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_A);
                t.require(details.isDetailsPage(), "A opens details from Select menu");
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_B);
                t.require(!details.isShowing(), "B closes details");
                t.runOnMainSync(() -> t.activity().dispatchKeyEvent(new KeyEvent(0, 0,
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT, 1)));
                t.require(!details.isShowing(), "Select auto-repeat cannot reopen menu");

                t.key(KeyEvent.KEYCODE_BUTTON_A);
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_SELECT);
                t.require(!details.isShowing(), "Select is blocked during insertion");
                t.settle(1900);
                f.returnToSlot();
                int afterInsert = f.launches.size();
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_SELECT);
                t.require(!details.isShowing() && t.view().currentMode() == SlotView.Mode.PREVIEW
                        && f.launches.size() == afterInsert, "Select cannot launch or eject seated cartridge");
                t.key(KeyEvent.KEYCODE_BUTTON_B);
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_SELECT);
                t.require(!details.isShowing(), "Select is blocked during ejection");
                t.settle(1700);

                t.key(KeyEvent.KEYCODE_BUTTON_Y); t.settle(450);
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_SELECT);
                t.require(t.view().isSettingsOpen() && !details.isShowing(), "Select leaves settings unchanged");
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_START);
                t.require(t.view().isSettingsOpen() && !details.isShowing(), "Start leaves settings unchanged");
                t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(450);
                t.openCategories();
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_SELECT);
                t.require(t.view().isCategoryMenuOpen() && !details.isShowing(), "Select leaves category menu unchanged");
                t.key(KeyEvent.KEYCODE_BUTTON_B);

                t.runOnMainSync(() -> {
                    for (ShelfModel model : List.of(new ShelfModel(List.of()), ShelfModel.demo())) {
                        SlotView isolated = new SlotView(t.activity(), model);
                        try {
                            t.require(isolated.handleKeyDown(KeyEvent.KEYCODE_BUTTON_SELECT,
                                    new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT)), "Select down is consumed");
                            t.require(isolated.handleKeyUp(KeyEvent.KEYCODE_BUTTON_SELECT,
                                    new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_SELECT)), "Select up is consumed");
                            t.require(isolated.currentMode() == SlotView.Mode.SHELF && isolated.insertionProgress() == 0
                                    && isolated.canOpenPanel(), "Empty and demo shelves must not insert or open panels");
                        } finally { isolated.onDetachedFromWindow(); }
                    }
                });
                t.require(afterInsert == calls + 1 && f.launches.size() == afterInsert, "Only A may launch during Select checks");
            });
            t.check(237, "favoritesAndRecentsShelvesFollowCartridgeMenuAndLaunches", () -> {
                t.runOnMainSync(() -> {
                    t.getTargetContext().getSharedPreferences(ShelfCollections.PREFS, 0).edit().clear().commit();
                });
                // Reconstruct the in-memory collections too; earlier cases have recorded launches.
                t.recreateActivity(); f.ready(); f.interceptSuccess();
                f.select("Lake.gbc", false);
                t.key(KeyEvent.KEYCODE_MENU); t.settle(150);
                PanelDialog menu = t.home().cartridgeDetails().panel();
                t.require(menu.rowCount() == 5
                                && menu.rowLabel(3).equals(t.view().language().text(R.string.favorite_game)),
                        "ROM cartridge menu must include edit appearance and favorite actions");
                f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_A);
                t.require(t.home().collections().isFavorite(f.cart("Lake.gbc").rom), "Cartridge menu must favorite Lake.gbc");

                f.select("Garden.gba", false);
                t.key(KeyEvent.KEYCODE_MENU); t.settle(150);
                f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_A);
                t.require(t.home().collections().isFavorite(f.cart("Garden.gba").rom), "Cartridge menu must favorite Garden.gba");

                // L1 from ALL wraps to FAVORITES while RECENTS is still empty.
                t.categoryKey(KeyEvent.KEYCODE_BUTTON_L1);
                t.require(t.view().currentCategory() == ShelfModel.Category.FAVORITES
                        && t.view().shelfCounter().equals("2 / 2")
                        && t.view().selectedCartridgeId().equals(f.cart("Lake.gbc").id),
                        "FAVORITES must preserve Lake selection at index 1 after Garden is inserted at index 0");
                t.key(KeyEvent.KEYCODE_DPAD_LEFT); t.settle(650);
                t.require(t.view().shelfCounter().equals("1 / 2")
                        && t.view().selectedCartridgeId().equals(f.cart("Garden.gba").id),
                        "FAVORITES must sort Garden before Lake at index 0");

                // Launch Moon.gb then Garden.gba, returning to the shelf after each launch.
                f.select("Moon.gb", false);
                t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(1900);
                f.returnToSlot();
                t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(1700);

                f.select("Garden.gba", false);
                t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(1900);
                f.returnToSlot();
                t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(1700);

                // L1 from ALL now reaches RECENTS, with the latest launched game (Garden) at index 0.
                t.categoryKey(KeyEvent.KEYCODE_BUTTON_L1);
                t.require(t.view().currentCategory() == ShelfModel.Category.RECENTS
                        && t.view().shelfCounter().equals("1 / 2")
                        && t.view().selectedCartridgeId().equals(f.cart("Garden.gba").id),
                        "RECENTS must place most recently launched Garden first");
                t.key(KeyEvent.KEYCODE_DPAD_RIGHT); t.settle(650);
                t.require(t.view().selectedCartridgeId().equals(f.cart("Moon.gb").id), "RECENTS must keep Moon second");

                // Relaunching Moon from RECENTS promotes Moon to index 0 after ejection and survives recreation.
                t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(1900);
                f.returnToSlot();
                t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(1700);
                t.require(t.view().currentCategory() == ShelfModel.Category.RECENTS
                        && t.view().selectedIndex() == 0
                        && t.view().selectedCartridgeId().equals(f.cart("Moon.gb").id),
                        "Relaunching Moon must move it to index 0 of RECENTS");
                t.recreateActivity(); f.ready(); f.interceptSuccess(); t.settle(300);
                t.require(t.view().currentCategory() == ShelfModel.Category.RECENTS
                        && t.view().selectedCartridgeId().equals(f.cart("Moon.gb").id),
                        "RECENTS category and selection must survive recreation");

                // Unfavoriting from FAVORITES keeps remaining items and falls back to ALL when empty.
                t.categoryKey(KeyEvent.KEYCODE_BUTTON_L1);
                t.require(t.view().currentCategory() == ShelfModel.Category.FAVORITES, "L1 from RECENTS must enter FAVORITES");
                t.key(KeyEvent.KEYCODE_MENU); t.settle(150);
                f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_A); t.settle(200);
                t.require(t.view().currentCategory() == ShelfModel.Category.FAVORITES
                        && t.view().shelfCounter().equals("1 / 1"),
                        "Removing one favorite must keep FAVORITES on the remaining game");
                t.key(KeyEvent.KEYCODE_MENU); t.settle(150);
                f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_A); t.settle(200);
                t.require(t.view().currentCategory() == ShelfModel.Category.ALL,
                        "Removing the last favorite must fall back to ALL");
            });
        }
    }
}
