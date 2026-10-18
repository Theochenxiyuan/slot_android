package fyi.slot.android;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import fyi.slot.android.MachinePanelController.Content;

/** Device checks for the default-home desktop: machine panel, app drawer, settings and adaptive layout. */
final class DesktopChecks {
    private static final long LIFT = 520;
    private final InteractionChecks t;

    DesktopChecks(InteractionChecks checks) { t = checks; }

    /** True when a page faded alone: pages dissolve one after the other, never into a ghost. */
    private boolean dissolvedAlone(String label) {
        boolean sawFade = false, savedOut = false, savedIn = false;
        long until = SystemClock.uptimeMillis() + 900;
        while (SystemClock.uptimeMillis() < until) {
            float[] snapshot = new float[2];
            t.runOnMainSync(() -> {
                View apps = t.home().appsDrawer(), settings = t.home().settingsPanel();
                snapshot[0] = apps.getVisibility() == View.VISIBLE ? apps.getAlpha() : -1f;
                snapshot[1] = settings.getVisibility() == View.VISIBLE ? settings.getAlpha() : -1f;
            });
            boolean appsMid = snapshot[0] > .02f && snapshot[0] < .98f;
            boolean settingsMid = snapshot[1] > .02f && snapshot[1] < .98f;
            if (appsMid && settingsMid) return false;
            boolean toApps = t.view().isAppsOpen();
            boolean incomingMid = toApps ? appsMid : settingsMid;
            boolean leavingMid = toApps ? settingsMid : appsMid;
            if (incomingMid || leavingMid) {
                sawFade = true;
                if (leavingMid && !savedOut) { save(label + "-out"); savedOut = true; }
                else if (incomingMid && !savedIn) { save(label + "-in"); savedIn = true; }
            }
            if (!t.view().panel().isSwapping()) break;
        }
        return sawFade;
    }

    /** Keeps one frame of a real transition for the validation record. */
    private void save(String name) {
        Bitmap image = t.getUiAutomation().takeScreenshot();
        if (image == null) return;
        File folder = new File(t.getTargetContext().getExternalFilesDir(null), "desktop-swap");
        if (folder.isDirectory() || folder.mkdirs()) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(folder, name + ".png"))) {
                image.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (java.io.IOException ignored) { }
        }
        image.recycle();
    }

    void run() {
        t.check(81, "machineLiftOpensAppsAndSwitchesContentInPlace", () -> {
            t.reset(1, false);
            t.key(KeyEvent.KEYCODE_BUTTON_X);
            t.require(t.view().isAppsOpen(), "X must open apps");
            t.settle(LIFT);
            t.require(panel().isFullyOpen() && panel().state() == MachinePanelController.State.OPEN, "Apps must finish lifting");
            t.key(KeyEvent.KEYCODE_BUTTON_Y);
            t.require(t.view().isSettingsOpen() && panel().progress() == 1f && !panel().isAnimating(),
                    "Switching to settings must keep the machine lifted");
            t.require(dissolvedAlone("to-settings"), "Switching apps to settings must dissolve one page at a time");
            t.settle(260);
            t.require(t.home().appsDrawer().getVisibility() == View.INVISIBLE
                    && t.home().settingsPanel().getAlpha() == 1f && !panel().isSwapping(), "Cross-fade must settle");
            t.require(panel().previousTitle() == null, "Settled content keeps one title");
            t.key(KeyEvent.KEYCODE_BUTTON_X);
            t.require(t.view().isAppsOpen() && panel().progress() == 1f, "Switching back must keep the machine lifted");
            t.require(dissolvedAlone("to-apps"), "Switching settings to apps must dissolve one page at a time");
            t.settle(260);
            t.require(t.home().settingsPanel().getVisibility() == View.INVISIBLE
                    && t.home().appsDrawer().getAlpha() == 1f, "Reverse cross-fade must settle");
            t.key(KeyEvent.KEYCODE_BUTTON_X);
            t.require(!panel().isTargetOpen(), "The current entry must act as back");
            t.settle(LIFT);
            t.require(panel().isClosed() && t.view().canBrowse(), "Closing must return a usable shelf");
        });
        t.check(82, "rapidPanelRequestsContinueFromTheCurrentProgress", () -> {
            t.reset(1, false);
            t.key(KeyEvent.KEYCODE_BUTTON_X); SystemClock.sleep(110);
            float[] before = new float[1], after = new float[1];
            t.runOnMainSync(() -> {
                before[0] = panel().progress();
                t.home().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B));
                after[0] = panel().progress();
            });
            t.release(KeyEvent.KEYCODE_BUTTON_B);
            t.require(before[0] > 0 && before[0] < 1, "Check must interrupt a moving machine: " + before[0]);
            t.require(after[0] == before[0] && !panel().isTargetOpen(), "Reversing must start from the current height");
            SystemClock.sleep(60);
            t.key(KeyEvent.KEYCODE_BUTTON_X);
            t.require(panel().progress() > 0 && panel().isTargetOpen(), "Reopening must continue without dropping");
            t.settle(LIFT);
            t.require(t.view().isAppsOpen() && panel().isFullyOpen(), "Last request must win");
            for (int code : new int[]{KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_B,
                    KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_B}) t.key(code);
            t.settle(LIFT);
            t.require(panel().isClosed() && t.view().currentMode() == SlotView.Mode.SHELF,
                    "Quick toggles must settle on the final closed request");
        });
        t.check(83, "liftedMachineIsolatesShelfInputAndKeepsTheBayOpen", () -> {
            t.reset(1, false); openApps();
            for (int code : new int[]{KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_Q,
                    KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_DPAD_RIGHT}) t.key(code);
            t.settle(300);
            t.require(t.view().currentCategory() == ShelfModel.Category.ALL && t.view().selectedIndex() == 1
                    && !t.view().isNavigationKeyHeld(), "Shelf must ignore category and browse keys while lifted");
            t.dragStart(360, 30);
            t.require(panel().isTargetOpen() && t.view().currentCategory() == ShelfModel.Category.ALL,
                    "Machine body touch must keep the bay open");
            t.dragMove(360, 200); t.dragEnd(360, 200); t.settle(300);
            t.require(panel().isTargetOpen() && t.view().insertionProgress() == 0 && t.view().selectedIndex() == 1
                    && !t.view().isCategoryMenuOpen(), "Machine body gesture must not reach the shelf");
            t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(LIFT);
            t.require(panel().isClosed() && t.view().canBrowse(), "Back must lower the machine");
        });
        t.check(84, "machineBodyTapsKeepTheBayOpen", () -> {
            t.reset(1, false); openApps();
            HomeLayout layout = t.view().homeLayout();
            float top = layout.machineTop(1);
            t.windowTap(layout.toScreenX(10), layout.toScreenY(300));
            t.windowTap(layout.toScreenX(layout.centerX()), layout.toScreenY(top + HomeLayout.TITLE_CENTER));
            t.windowTap(layout.toScreenX(layout.centerX()), layout.toScreenY(top + 30));
            t.require(t.view().isAppsOpen() && panel().isFullyOpen(), "Machine body and title taps must not close the bay");
            t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(LIFT);
        });
        t.check(85, "backClosesMenuThenBayWithBothBackPaths", () -> {
            t.reset(1, false); openApps(); waitForApps();
            t.key(KeyEvent.KEYCODE_MENU);
            t.require(t.home().appMenu().isShowing() && t.view().isAppsOpen(), "MENU must open the selected app's menu");
            t.key(KeyEvent.KEYCODE_BUTTON_B);
            t.require(!t.home().appMenu().isShowing() && t.view().isAppsOpen(), "B must close only the menu");
            t.key(KeyEvent.KEYCODE_MENU);
            t.runOnMainSync(() -> t.activity().onBackPressed());
            t.require(!t.home().appMenu().isShowing() && t.view().isAppsOpen(), "System back must close only the menu");
            t.key(KeyEvent.KEYCODE_BUTTON_SELECT); t.settle(100);
            t.require(t.home().appMenu().isShowing(), "Select must open the app menu");
            assertMenuAnchor();
            t.runOnMainSync(() -> t.activity().dispatchKeyEvent(new KeyEvent(0, 0,
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT, 1)));
            t.require(t.home().appMenu().isShowing(), "Held Select must not toggle the menu");
            t.key(KeyEvent.KEYCODE_BUTTON_SELECT);
            t.require(!t.home().appMenu().isShowing() && t.view().isAppsOpen(), "Select must close only the menu");
            t.key(KeyEvent.KEYCODE_BUTTON_START);
            t.require(t.home().appMenu().isShowing(), "Start must open the app menu");
            assertMenuAnchor();
            t.key(KeyEvent.KEYCODE_BUTTON_START);
            t.require(!t.home().appMenu().isShowing() && t.view().isAppsOpen(), "Start must close only the menu");
            t.key(KeyEvent.KEYCODE_BUTTON_Y); t.settle(300);
            t.key(KeyEvent.KEYCODE_BUTTON_START); t.settle(150);
            t.require(t.view().isSettingsOpen() && !t.home().appMenu().isShowing(),
                    "Start must leave settings open without an app menu");
            t.key(KeyEvent.KEYCODE_BUTTON_X); t.settle(300);
            AppsDrawer apps = t.home().appsDrawer();
            for (int i = 0; i <= apps.apps().size() / apps.columns(); i++) t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.settle(900);
            int selected = apps.selectedIndex();
            t.key(KeyEvent.KEYCODE_BUTTON_SELECT); t.settle(100);
            assertMenuAnchor();
            save("app-menu-bottom");
            t.key(KeyEvent.KEYCODE_BUTTON_B);
            t.require(apps.selectedIndex() == selected, "Closing the menu must preserve selection");
            t.runOnMainSync(() -> t.activity().onBackPressed());
            t.require(!panel().isTargetOpen(), "System back at the top level must lower the machine");
            t.settle(LIFT);
            t.runOnMainSync(() -> t.activity().onBackPressed());
            t.require(!t.activity().isFinishing() && t.view().currentMode() == SlotView.Mode.SHELF,
                    "Back on the resting shelf must stay on the desktop");
        });
        t.check(86, "appGridMovesByKeysAndStopsAtEdges", () -> {
            t.reset(1, false); openApps(); waitForApps();
            AppsDrawer apps = t.home().appsDrawer();
            int columns = apps.columns(), count = apps.apps().size();
            t.require(apps.isKeyMode() && apps.selectedIndex() == 0, "Key opening must focus the first app");
            t.key(KeyEvent.KEYCODE_DPAD_LEFT); t.key(KeyEvent.KEYCODE_DPAD_UP);
            t.require(apps.selectedIndex() == 0, "Top-left edges must stop");
            for (int i = 0; i < columns + 2; i++) t.key(KeyEvent.KEYCODE_DPAD_RIGHT);
            t.require(apps.selectedIndex() == Math.min(columns, count) - 1, "Right edge must stop on the row");
            t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            int expected = count > columns ? Math.min(2 * columns - 1, count - 1) : Math.min(columns, count) - 1;
            t.require(apps.selectedIndex() == expected, "Down must move one row or stay on the last row");
            for (int i = 0; i < count / Math.max(1, columns) + 2; i++) t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.settle(900);
            t.require(apps.selectedIndex() >= (count - 1) / columns * columns, "Down must reach the last row and stop");
            View cell = selectedCell(apps);
            int[] scroll = new int[2];
            t.runOnMainSync(() -> { scroll[0] = apps.scroller().getScrollY(); scroll[1] = apps.scroller().getHeight(); });
            t.require(cell != null && cell.getTop() >= scroll[0] - 1 && cell.getBottom() <= scroll[0] + scroll[1] + 1,
                    "Selected app must scroll fully into view");
            t.require(apps.move(apps.selectedIndex(), KeyEvent.KEYCODE_DPAD_DOWN) == apps.selectedIndex(),
                    "Bottom edge must stop");
            t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(LIFT);
        });
        t.check(87, "drawerListsLaunchableAppsExceptThisDesktop", () -> {
            t.reset(1, false); openApps(); waitForApps();
            List<AppEntry> apps = t.home().appsDrawer().apps();
            t.require(!apps.isEmpty(), "Launchable apps must be listed");
            boolean settings = false;
            for (AppEntry entry : apps) {
                t.require(!entry.packageName().equals(t.getTargetContext().getPackageName()), "Desktop must not list itself");
                settings |= entry.packageName().equals("com.android.settings");
            }
            t.require(settings, "System settings app must be reachable from the drawer");
            t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(LIFT);
        });
        t.check(88, "launchLowersMachineAndDetailsKeepTheBay", () -> {
            Recorder recorder = new Recorder();
            AppLauncher original = t.home().launcher;
            t.runOnMainSync(() -> t.home().launcher = recorder.create(t.activity()));
            try {
                t.reset(1, false); openApps(); waitForApps();
                recorder.result = false;
                t.key(KeyEvent.KEYCODE_BUTTON_A);
                t.require(recorder.launched.size() == 1 && t.view().isAppsOpen(), "Failed launch must keep the bay open");
                recorder.result = true;
                t.key(KeyEvent.KEYCODE_DPAD_RIGHT); t.key(KeyEvent.KEYCODE_BUTTON_A);
                t.require(recorder.launched.size() == 2
                                && recorder.launched.get(1).key.equals(t.home().appsDrawer().apps().get(1).key),
                        "A must launch the selected app");
                t.require(panel().isClosed() && t.view().canBrowse(), "Launching must lower the machine first");
                openApps();
                t.key(KeyEvent.KEYCODE_BUTTON_SELECT); t.key(KeyEvent.KEYCODE_BUTTON_A);
                t.require(recorder.details.size() == 1 && t.view().isAppsOpen() && !t.home().appMenu().isShowing(),
                        "App information must keep the bay for returning");
                t.key(KeyEvent.KEYCODE_BUTTON_Y);
                t.settle(LIFT);
                t.runOnMainSync(() -> t.home().settingsPanel().showGroup(HomeCoordinator.SYSTEM_GROUP));
                t.key(KeyEvent.KEYCODE_BUTTON_A);
                t.key(KeyEvent.KEYCODE_DPAD_DOWN); t.key(KeyEvent.KEYCODE_BUTTON_A);
                t.key(KeyEvent.KEYCODE_DPAD_DOWN); t.key(KeyEvent.KEYCODE_BUTTON_A);
                t.require(recorder.system == 1 && recorder.wifi == 1 && t.view().isSettingsOpen(),
                        "System and Wi-Fi rows must open their screens and keep settings");
            } finally {
                t.runOnMainSync(() -> t.home().launcher = original);
            }
            t.key(KeyEvent.KEYCODE_BUTTON_B); t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(LIFT);
        });
        t.check(89, "longPressOpensAppMenuAndOutsideTouchDismisses", () -> {
            t.reset(1, false);
            HomeLayout layout = t.view().homeLayout();
            float entryY = layout.machineClosedTop() + HomeLayout.ENTRY_CENTER;
            t.dragStart(650, entryY); t.dragEnd(650, entryY);
            t.require(t.view().isAppsOpen(), "Touch on the right entry must open apps");
            t.settle(LIFT); waitForApps();
            t.require(!t.home().appsDrawer().isKeyMode(), "Touch opening must not show key focus");
            View cell = cellAt(t.home().appsDrawer(), 0);
            float[] center = centerOf(cell);
            t.windowTouch(MotionEvent.ACTION_DOWN, center[0], center[1]);
            t.settle(ViewConfiguration.getLongPressTimeout() + 250);
            t.windowTouch(MotionEvent.ACTION_UP, center[0], center[1]);
            t.settle(200);
            t.require(t.home().appMenu().isShowing(), "Long press must open the app menu");
            assertMenuAnchor();
            t.key(KeyEvent.KEYCODE_BUTTON_START); t.settle(100);
            t.require(!t.home().appMenu().isShowing(), "Start must close the touch-opened menu");
            t.key(KeyEvent.KEYCODE_BUTTON_START); t.settle(100);
            t.require(t.home().appMenu().isShowing() && t.home().appsDrawer().isKeyMode(),
                    "Start must reopen the touched app's menu");
            assertMenuAnchor();
            save("app-menu-long-press");
            t.windowTap(layout.toScreenX(layout.bodyRight() - 10), layout.toScreenY(layout.machineTop(1) + layout.bodyTopOffset() + 10));
            t.require(!t.home().appMenu().isShowing() && t.view().isAppsOpen(), "Outside touch must only close the menu");
            t.dragStart(650, layout.machineTop(1) + 30); t.dragEnd(650, layout.machineTop(1) + 30);
            t.require(!panel().isTargetOpen(), "Right entry shows back while apps are open");
            t.settle(LIFT);
        });
        t.check(90, "panelPagesAndSelectionsSurviveRecreation", () -> {
            t.reset(1, false); openApps(); waitForApps();
            t.key(KeyEvent.KEYCODE_DPAD_RIGHT); t.key(KeyEvent.KEYCODE_DPAD_RIGHT);
            String selected = t.home().appsDrawer().selectedKey();
            t.recreateActivity(); waitForApps();
            t.require(t.view().isAppsOpen() && panel().isFullyOpen(), "Apps must reopen at rest after recreation");
            t.require(selected.equals(t.home().appsDrawer().selectedKey()), "App selection must survive recreation");
            t.key(KeyEvent.KEYCODE_BUTTON_Y); t.key(KeyEvent.KEYCODE_BUTTON_A); t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.recreateActivity();
            t.require(t.view().isSettingsOpen() && t.home().settingsPanel().isInRows() && t.home().settingsPanel().selectedRow() == 1,
                    "Settings row must survive recreation");
            t.key(KeyEvent.KEYCODE_BUTTON_X);
            t.require(t.view().isAppsOpen() && selected.equals(t.home().appsDrawer().selectedKey()),
                    "Each content must keep its own selection");
            t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(LIFT);
            openApps();
            t.require(t.home().appsDrawer().selectedIndex() == 0, "A fresh opening must start at the top");
            t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(LIFT);
        });
        t.check(91, "homeIntentLowersMachineAndClosesTemporaryState", () -> {
            t.reset(1, false); openApps(); waitForApps(); t.key(KeyEvent.KEYCODE_MENU);
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            t.runOnMainSync(() -> t.activity().onNewIntent(home));
            t.require(!panel().isTargetOpen() && !t.home().appMenu().isShowing(), "Home must close the menu and bay");
            t.settle(LIFT);
            t.require(panel().isClosed() && t.view().canBrowse(), "Home must end on a usable shelf");
            openApps();
            t.runOnMainSync(() -> t.home().onHome(false));
            t.require(panel().isClosed() && t.view().canBrowse(), "Home from the background must settle immediately");
            t.openCategories();
            t.runOnMainSync(() -> t.activity().onNewIntent(home));
            t.require(!t.view().isCategoryMenuOpen(), "Home must close the category menu");
        });
        t.check(92, "layoutKeepsShapesInsideEverySupportedRatio", () -> t.runOnMainSync(() -> {
            int[][] sizes = {{960, 640}, {1024, 768}, {960, 960}, {1280, 720}, {1152, 720}, {1200, 900},
                    {1600, 720}, {640, 960}};
            for (int[] size : sizes) {
                SlotView isolated = new SlotView(t.getTargetContext(), ShelfModel.demo());
                try {
                    isolated.layout(0, 0, size[0], size[1]);
                    HomeLayout layout = isolated.homeLayout();
                    String name = size[0] + "x" + size[1];
                    float frameWidth = layout.frameRight - layout.frameLeft, frameHeight = layout.frameBottom - layout.frameTop;
                    float aspect = frameWidth / frameHeight;
                    t.require(aspect >= .999f && aspect <= 16f / 9f + .001f, "Frame ratio must stay within 1:1–16:9 at " + name);
                    t.require(Math.abs(layout.frameLeft - (size[0] - layout.frameRight)) < 1
                            && Math.abs(layout.frameTop - (size[1] - layout.frameBottom)) < 1, "Frame must be centred at " + name);
                    t.require(Math.abs(layout.width / layout.height - aspect) < .001f, "Logical space must not stretch at " + name);
                    for (Cartridge.Platform platform : Cartridge.Platform.values()) {
                        float ratio = layout.cardWidth(platform) / layout.cardHeight(platform);
                        t.require(Math.abs(ratio - platform.width / platform.height) < .0001f, "Card must keep its shape at " + name);
                        t.require(layout.restY(platform) >= HomeLayout.HEADER_BOTTOM
                                && layout.restY(platform) + layout.cardHeight(platform) < layout.dotsY() - 6,
                                "Card must clear header and dots at " + name);
                    }
                    t.require(layout.machineClosedTop() + HomeLayout.MACHINE_LIP == layout.height
                            && layout.bodyTopOffset() + HomeLayout.MACHINE_OPEN_TOP < layout.bodyBottomOffset() + HomeLayout.MACHINE_OPEN_TOP - 150,
                            "Machine and bay must fit at " + name);
                    if (size[0] == 960 && size[1] == 640) {
                        t.require(layout.width == 720 && layout.height == 480 && layout.scale == 4f / 3f,
                                "960×640 must keep the 720×480 reference");
                    }
                    if (size[0] == 960 && size[1] == 960) {
                        t.require(layout.height == 720 && layout.cardScale() > 1, "Square frames must use their extra height");
                    }
                    float entryY = layout.machineClosedTop() + HomeLayout.ENTRY_CENTER;
                    t.dispatchTap(isolated, layout.width - 60, entryY);
                    t.require(isolated.isAppsOpen(), "Right entry must open apps at " + name);
                    isolated.panel().snap(false, Content.APPS);
                    if (layout.frameLeft > 2) {
                        long now = SystemClock.uptimeMillis();
                        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                            MotionEvent event = MotionEvent.obtain(now, now, action, 1, layout.toScreenY(entryY), 0);
                            isolated.dispatchTouchEvent(event); event.recycle();
                        }
                        t.require(!isolated.panel().isTargetOpen(), "Margins outside the frame must stay inert at " + name);
                    }
                    Bitmap frame = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
                    isolated.draw(new Canvas(frame));
                    int bottom = frame.getPixel(Math.round(layout.toScreenX(layout.centerX())), Math.round(layout.toScreenY(layout.height - 3)));
                    t.require(Math.abs(Color.red(bottom) - Color.red(MachineRenderer.HOUSING)) <= 40
                                    && Math.abs(Color.green(bottom) - Color.green(MachineRenderer.HOUSING)) <= 40
                                    && Math.abs(Color.blue(bottom) - Color.blue(MachineRenderer.HOUSING)) <= 40
                                    && bottom != 0xff000000, "Housing must reach the frame bottom at " + name);
                    if (layout.frameLeft > 2) t.require(frame.getPixel(1, size[1] / 2) == 0xff000000, "Margins must stay blank at " + name);
                    frame.recycle();
                } finally { isolated.onDetachedFromWindow(); }
            }
        }));
        t.check(93, "resizingKeepsBayAlignedAndPageState", () -> {
            t.reset(1, false); openApps(); waitForApps();
            t.key(KeyEvent.KEYCODE_DPAD_RIGHT); t.key(KeyEvent.KEYCODE_DPAD_RIGHT);
            String selected = t.home().appsDrawer().selectedKey();
            String original = displaySize();
            float originalWidth = t.view().homeLayout().width, originalHeight = t.view().homeLayout().height;
            try {
                for (String size : new String[]{"1280x720", "960x960", "1024x768"}) {
                    shell("wm size " + size); t.settle(1800);
                    t.require(t.view().isAppsOpen() && panel().isFullyOpen(), "Resize must keep apps open at " + size);
                    t.require(selected.equals(t.home().appsDrawer().selectedKey()), "Resize must keep the selection at " + size);
                    t.runOnMainSync(() -> {
                        HomeLayout layout = t.view().homeLayout();
                        Rect bay = new Rect();
                        View bayView = t.home().panelView().bay();
                        int[] location = new int[2];
                        bayView.getLocationInWindow(location);
                        bay.set(location[0], location[1], location[0] + bayView.getWidth(), location[1] + bayView.getHeight());
                        float top = layout.machineTop(1);
                        t.require(Math.abs(bay.left - layout.toScreenX(layout.bodyLeft())) <= 1
                                && Math.abs(bay.top - layout.toScreenY(top + layout.bodyTopOffset())) <= 1
                                && Math.abs(bay.right - layout.toScreenX(layout.bodyRight())) <= 1
                                && Math.abs(bay.bottom - layout.toScreenY(top + layout.bodyBottomOffset())) <= 1,
                                "Native content must sit inside the drawn bay at " + size + ": " + bay);
                        t.require(t.view().getWidth() == t.home().panelView().getWidth(), "Layers must share one frame");
                    });
                }
            } finally {
                shell(original == null ? "wm size reset" : "wm size " + original); t.settle(1800);
            }
            t.require(Math.abs(t.view().homeLayout().width - originalWidth) < 1 && Math.abs(t.view().homeLayout().height - originalHeight) < 1
                    && t.view().isAppsOpen(), "Original size must restore the device layout");
            t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(LIFT);
        });
        t.check(94, "controllerChangesClearHeldDirections", () -> {
            t.reset(1, false);
            t.press(KeyEvent.KEYCODE_DPAD_RIGHT);
            t.require(t.view().isNavigationKeyHeld(), "Direction must be held");
            t.runOnMainSync(() -> t.home().onInputDeviceRemoved(7));
            t.require(!t.view().isNavigationKeyHeld(), "Controller removal must clear held input");
            t.release(KeyEvent.KEYCODE_DPAD_RIGHT);
            t.press(KeyEvent.KEYCODE_DPAD_LEFT);
            t.runOnMainSync(() -> t.home().onInputDeviceAdded(8));
            t.require(!t.view().isNavigationKeyHeld(), "Controller connection must clear held input");
            t.release(KeyEvent.KEYCODE_DPAD_LEFT);
            int selected = t.view().selectedIndex(); t.settle(650);
            t.require(t.view().selectedIndex() == selected && t.view().canBrowse(), "Shelf must stay usable after reconnection");
        });
        t.check(95, "drawerShowsLoadingEmptyAndErrorStates", () -> {
            waitForApps();
            List<AppEntry> real = t.home().appsDrawer().apps();
            t.runOnMainSync(() -> {
                AppIconCache icons = new AppIconCache(160);
                AppsDrawer drawer = new AppsDrawer(t.getTargetContext(), icons);
                int[] retries = new int[1];
                drawer.setActions(new AppsDrawer.Actions() {
                    @Override public void launch(AppEntry entry, Rect sourceBounds) { }
                    @Override public void showMenu(AppEntry entry) { }
                    @Override public void retry() { retries[0]++; }
                });
                drawer.setApps(AppRepository.Status.LOADING, List.of());
                t.require(ui(R.string.apps_loading).equals(drawer.statusText()), "Loading state must be visible");
                drawer.setApps(AppRepository.Status.FAILED, List.of());
                t.require(ui(R.string.apps_failed).equals(drawer.statusText()), "Error state must offer retry");
                drawer.handleKey(KeyEvent.KEYCODE_BUTTON_A, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A));
                t.require(retries[0] == 1, "A must retry after an error");
                drawer.setApps(AppRepository.Status.READY, List.of());
                t.require(ui(R.string.apps_empty).equals(drawer.statusText()), "Empty state must be visible");
                drawer.setApps(AppRepository.Status.READY, real);
                t.require(drawer.statusText() == null && drawer.apps().size() == real.size(), "Ready state must show the grid");
                drawer.restoreSelection("missing", real.size() + 5, true);
                t.require(drawer.selectedIndex() == real.size() - 1, "Missing selection must use the nearest app");
                icons.release();
            });
        });
        t.check(96, "entriesWaitForInsertionPreviewAndCategoryMenu", () -> {
            t.reset(1, false);
            t.key(KeyEvent.KEYCODE_BUTTON_A); t.key(KeyEvent.KEYCODE_BUTTON_X);
            t.dragStart(650, 450); t.dragEnd(650, 450);
            t.require(!panel().isTargetOpen(), "Entries must wait while a cartridge is inserting");
            t.settle(1800);
            t.require(t.view().currentMode() == SlotView.Mode.PREVIEW, "Cartridge must finish inserting");
            t.key(KeyEvent.KEYCODE_BUTTON_X); t.key(KeyEvent.KEYCODE_BUTTON_Y);
            t.require(!panel().isTargetOpen(), "Entries must stay hidden over an inserted cartridge");
            t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(1900);
            t.openCategories(); t.key(KeyEvent.KEYCODE_BUTTON_X);
            t.require(!panel().isTargetOpen() && t.view().isCategoryMenuOpen(), "Category menu must consume entry keys");
            t.key(KeyEvent.KEYCODE_BUTTON_B);
            t.runOnMainSync(() -> {
                SlotView empty = new SlotView(t.getTargetContext(), new ShelfModel(List.of()));
                empty.layout(0, 0, 960, 640);
                empty.handleKeyDown(KeyEvent.KEYCODE_BUTTON_X, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_X));
                t.require(empty.isAppsOpen(), "Apps must open from an empty shelf");
                empty.onDetachedFromWindow();
            });
        });
        t.check(97, "entryLabelsFollowTheOpenContent", () -> {
            t.reset(1, false);
            t.require(labels().equals(ui(R.string.settings) + "|" + ui(R.string.apps)), "Closed labels must name both entries: " + labels());
            t.key(KeyEvent.KEYCODE_BUTTON_X);
            t.require(labels().equals(ui(R.string.settings) + "|" + ui(R.string.back)) && panel().title().equals(ui(R.string.apps)), "Apps must turn the right entry into back");
            t.key(KeyEvent.KEYCODE_BUTTON_Y);
            t.require(labels().equals(ui(R.string.back) + "|" + ui(R.string.apps)) && panel().title().equals(ui(R.string.settings)), "Settings must turn the left entry into back");
            t.key(KeyEvent.KEYCODE_BUTTON_B);
            t.require(labels().equals(ui(R.string.settings) + "|" + ui(R.string.apps)), "Closing must restore both entries immediately");
            t.settle(LIFT);
        });
        t.check(98, "settingsTabsAndRowsFollowControllerLevels", () -> {
            t.reset(1, false);
            boolean swipe = t.view().interactionPreferences().swipeToInsert;
            t.key(KeyEvent.KEYCODE_BUTTON_Y);
            SettingsPanel settings = t.home().settingsPanel();
            t.require(!settings.isInRows() && "controls".equals(settings.groupId()), "Opening must focus the first tab");
            t.key(KeyEvent.KEYCODE_DPAD_UP);
            t.require("controls".equals(settings.groupId()), "Top tab must stop");
            for (int i = 0; i < settings.groupCount() + 1; i++) t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.require("about".equals(settings.groupId()) && !settings.isInRows(), "Bottom tab must stop");
            for (int i = 1; i < settings.groupCount(); i++) t.key(KeyEvent.KEYCODE_DPAD_UP);
            t.require("controls".equals(settings.groupId()), "Up must return to the first tab");
            t.key(KeyEvent.KEYCODE_DPAD_LEFT);
            t.require(!settings.isInRows() && t.view().isSettingsOpen(), "Left on tabs must stay on tabs");
            t.key(KeyEvent.KEYCODE_DPAD_RIGHT);
            t.require(!settings.isInRows() && t.view().isSettingsOpen(), "Right on tabs must stay on tabs");
            t.key(KeyEvent.KEYCODE_BUTTON_A);
            t.require(settings.isInRows() && settings.selectedRow() == 0, "A on a tab must enter the rows");
            t.key(KeyEvent.KEYCODE_BUTTON_A);
            t.require(t.view().interactionPreferences().swipeToInsert != swipe, "A must change a switch row");
            t.key(KeyEvent.KEYCODE_BUTTON_A);
            t.require(t.view().interactionPreferences().swipeToInsert == swipe, "A must change it back");
            t.key(KeyEvent.KEYCODE_DPAD_RIGHT);
            t.require(t.view().interactionPreferences().swipeToInsert != swipe, "Right must toggle a switch row");
            t.key(KeyEvent.KEYCODE_DPAD_LEFT);
            t.require(t.view().interactionPreferences().swipeToInsert == swipe, "Left must toggle it back");
            t.key(KeyEvent.KEYCODE_DPAD_UP);
            t.require(settings.selectedRow() == 0, "Top row must stop");
            for (int i = 0; i < settings.rowCount() + 2; i++) t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.require(settings.selectedRow() == settings.rowCount() - 1, "Bottom row must stop");
            Bundle state = new Bundle();
            t.runOnMainSync(() -> t.home().save(state));
            t.require(state.getInt("settings_row") == settings.rowCount() - 1 && state.getBoolean("settings_in_rows"),
                    "Row must be saved for restoration");
            t.key(KeyEvent.KEYCODE_BUTTON_B);
            t.require(!settings.isInRows() && t.view().isSettingsOpen(), "B must return from rows to tabs");
            t.key(KeyEvent.KEYCODE_BUTTON_B);
            t.require(!t.view().isSettingsOpen(), "B on tabs must close settings");
            t.settle(LIFT);
            t.key(KeyEvent.KEYCODE_BUTTON_Y);
            t.key(KeyEvent.KEYCODE_BUTTON_A);
            for (int i = 0; i < 4; i++) t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.require(settings.isInRows() && settings.selectedRow() == 4, "Button prompts row must be reachable");
            InteractionPreferences.ButtonPrompts before = t.view().interactionPreferences().buttonPrompts;
            t.key(KeyEvent.KEYCODE_DPAD_RIGHT);
            InteractionPreferences.ButtonPrompts[] promptValues = InteractionPreferences.ButtonPrompts.values();
            t.require(t.view().interactionPreferences().buttonPrompts
                            == promptValues[Math.floorMod(before.ordinal() + 1, promptValues.length)],
                    "Right must cycle a choice row forward");
            t.key(KeyEvent.KEYCODE_DPAD_LEFT);
            t.require(t.view().interactionPreferences().buttonPrompts == before,
                    "Left must cycle a choice row back");
            t.key(KeyEvent.KEYCODE_BUTTON_B);
            t.require(!settings.isInRows() && t.view().isSettingsOpen(), "B must return from rows to tabs");
            t.key(KeyEvent.KEYCODE_BUTTON_B);
            t.require(!t.view().isSettingsOpen(), "B on tabs must close settings");
            t.settle(LIFT);
            InteractionPreferences prefs = t.view().interactionPreferences();
            InteractionPreferences.ButtonPrompts originalPrompts = prefs.buttonPrompts;
            boolean originalSwap = prefs.swapConfirmBack;
            try {
                t.runOnMainSync(() -> {
                    prefs.buttonPrompts = InteractionPreferences.ButtonPrompts.XBOX;
                    prefs.swapConfirmBack = false;
                    prefs.save();
                });
                t.require("Y".equals(t.view().entryKey(Content.SETTINGS)) && "X".equals(t.view().entryKey(Content.APPS))
                        && "A".equals(t.view().startKey()) && "B".equals(t.view().ejectKey())
                        && "LB".equals(t.view().leftShoulderKey()) && "RB".equals(t.view().rightShoulderKey()),
                        "Xbox default badges must match physical positions");
                t.runOnMainSync(() -> {
                    prefs.cycleButtonPrompts(1);
                    t.home().settingsPanel().refresh();
                });
                t.require(prefs.buttonPrompts == InteractionPreferences.ButtonPrompts.NINTENDO
                        && "X".equals(t.view().entryKey(Content.SETTINGS)) && "Y".equals(t.view().entryKey(Content.APPS))
                        && "B".equals(t.view().startKey()) && "A".equals(t.view().ejectKey())
                        && "L".equals(t.view().leftShoulderKey()) && "R".equals(t.view().rightShoulderKey()),
                        "Nintendo badges must map physical positions without swapping confirm/back keys yet");
                t.runOnMainSync(() -> {
                    prefs.toggleSwapConfirmBack();
                    t.home().settingsPanel().refresh();
                });
                t.require(prefs.swapConfirmBack && "A".equals(t.view().startKey()) && "B".equals(t.view().ejectKey()),
                        "Swapping confirm and back on Nintendo must show A for confirm and B for back");
                t.key(KeyEvent.KEYCODE_BUTTON_Y);
                t.require(t.view().isSettingsOpen() && "B".equals(t.view().entryKey(Content.SETTINGS)),
                        "Open settings must show swapped back badge");
                t.key(KeyEvent.KEYCODE_BUTTON_B);
                t.require(settings.isInRows() && t.view().isSettingsOpen(),
                        "Swapped confirm key (BUTTON_B) must enter settings rows instead of closing");
                t.key(KeyEvent.KEYCODE_BUTTON_A);
                t.require(!settings.isInRows() && t.view().isSettingsOpen(),
                        "Swapped back key (BUTTON_A) must return from rows to tabs");
                t.key(KeyEvent.KEYCODE_BUTTON_A);
                t.require(!t.view().isSettingsOpen(),
                        "Swapped back key (BUTTON_A) on tabs must close settings");
                t.settle(LIFT);
                t.runOnMainSync(() -> {
                    prefs.cycleButtonPrompts(1);
                    prefs.toggleSwapConfirmBack();
                    t.home().settingsPanel().refresh();
                });
                t.require(prefs.buttonPrompts == InteractionPreferences.ButtonPrompts.PLAYSTATION && !prefs.swapConfirmBack
                        && "△".equals(t.view().entryKey(Content.SETTINGS)) && "□".equals(t.view().entryKey(Content.APPS))
                        && "✕".equals(t.view().startKey()) && "○".equals(t.view().ejectKey())
                        && "L1".equals(t.view().leftShoulderKey()) && "R1".equals(t.view().rightShoulderKey()),
                        "PlayStation badges must show geometric symbols");
            } finally {
                t.runOnMainSync(() -> {
                    prefs.buttonPrompts = originalPrompts;
                    prefs.swapConfirmBack = originalSwap;
                    prefs.save();
                    t.home().settingsPanel().refresh();
                });
            }
        });
    }

    private MachinePanelController panel() { return t.view().panel(); }
    private String ui(int id) { return t.view().language().text(id); }

    private void assertMenuAnchor() {
        t.runOnMainSync(() -> {
            View menu = t.home().appMenu(), card = t.home().appMenu().getChildAt(0);
            View icon = t.home().appsDrawer().selectedIcon();
            Rect bay = new Rect(), popup = new Rect(), source = new Rect();
            t.require(menu.getGlobalVisibleRect(bay) && card.getGlobalVisibleRect(popup)
                    && icon != null && icon.getGlobalVisibleRect(source), "Menu and selected icon must be visible");
            t.require(bay.contains(popup) && popup.width() == card.getWidth() && popup.height() == card.getHeight(),
                    "Menu must remain fully inside the bay: " + popup + " / " + bay);
            int gap = Math.max(1, Math.round(t.view().homeLayout().px(6)));
            boolean below = source.bottom + gap + card.getHeight() <= bay.bottom - gap;
            t.require(below ? Math.abs(popup.top - source.bottom - gap) <= 1
                    : Math.abs(popup.bottom - source.top + gap) <= 1,
                    "Menu must follow its icon, flipping above when needed: " + source + " / " + popup);
            t.require(popup.left < source.right && popup.right > source.left, "Menu must align horizontally with its icon");
        });
    }

    private String labels() { return t.view().entryText(Content.SETTINGS) + "|" + t.view().entryText(Content.APPS); }

    private void openApps() {
        t.key(KeyEvent.KEYCODE_BUTTON_X);
        t.settle(LIFT);
        t.require(t.view().isAppsOpen() && panel().isFullyOpen(), "Apps must be open");
    }

    private void waitForApps() {
        long limit = SystemClock.uptimeMillis() + 5000;
        while (t.home().repository().status() != AppRepository.Status.READY && SystemClock.uptimeMillis() < limit) {
            SystemClock.sleep(20);
        }
        t.settle(150);
        t.require(t.home().repository().status() == AppRepository.Status.READY, "App list must load");
    }

    private View selectedCell(AppsDrawer apps) {
        return cellAt(apps, apps.selectedIndex());
    }

    private View cellAt(AppsDrawer apps, int index) {
        View[] cell = new View[1];
        t.runOnMainSync(() -> cell[0] = apps.cell(index));
        return cell[0];
    }

    private float[] centerOf(View view) {
        float[] center = new float[2];
        t.runOnMainSync(() -> {
            int[] location = new int[2];
            view.getLocationInWindow(location);
            center[0] = location[0] + view.getWidth() / 2f;
            center[1] = location[1] + view.getHeight() / 2f;
        });
        return center;
    }

    private String displaySize() {
        String output = shell("wm size");
        Matcher override = Pattern.compile("Override size: (\\d+x\\d+)").matcher(output);
        return override.find() ? override.group(1) : null;
    }

    private String shell(String command) {
        ParcelFileDescriptor descriptor = t.getUiAutomation().executeShellCommand(command);
        StringBuilder output = new StringBuilder();
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024];
            for (int read; (read = in.read(buffer)) > 0; ) output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new AssertionError("Shell command failed: " + command, error);
        }
        return output.toString();
    }

    /** Records launches instead of leaving the desktop during checks. */
    private static final class Recorder {
        final List<AppEntry> launched = new ArrayList<>(), details = new ArrayList<>();
        int system, wifi;
        boolean result = true;

        AppLauncher create(MainActivity activity) {
            return new AppLauncher(activity) {
                @Override boolean launch(AppEntry entry, Rect sourceBounds) { launched.add(entry); return result; }
                @Override boolean showDetails(AppEntry entry) { details.add(entry); return true; }
                @Override boolean openSystemSettings() { system++; return true; }
                @Override boolean openWifiSettings() { wifi++; return true; }
            };
        }
    }
}
