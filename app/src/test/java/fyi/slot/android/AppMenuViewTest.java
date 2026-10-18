package fyi.slot.android;

import android.app.Activity;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Process;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class AppMenuViewTest {
    private Activity activity;
    private AppIconCache icons;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        icons = new AppIconCache(160);
    }

    @After public void tearDown() { icons.release(); activity.finish(); }

    @Test public void menuCentersBelowTheIcon() {
        assertEquals(new Rect(205, 86, 395, 206),
                place(new Rect(276, 32, 324, 80), 600, 400, 190, 120, 6));
    }

    @Test public void bottomRowMenuFlipsAbove() {
        assertEquals(new Rect(205, 206, 395, 326),
                place(new Rect(276, 332, 324, 380), 600, 400, 190, 120, 6));
    }

    @Test public void leftAndRightEdgesStayInsideTheBay() {
        assertEquals(6, place(new Rect(12, 32, 60, 80), 600, 400, 190, 120, 6).left);
        assertEquals(594, place(new Rect(540, 32, 588, 80), 600, 400, 190, 120, 6).right);
    }

    @Test public void shortBayClampsTheMenuToItsInset() {
        assertEquals(new Rect(6, 6, 196, 126),
                place(new Rect(12, 20, 60, 68), 210, 132, 190, 120, 6));
    }

    private static Rect place(Rect anchor, int width, int height, int cardWidth, int cardHeight, int gap) {
        Rect bounds = new Rect();
        AppMenuView.place(anchor, width, height, cardWidth, cardHeight, gap, bounds);
        return bounds;
    }

    @Test public void selectOpensTheSelectedAppWithoutLaunchingAndIgnoresRepeats() {
        AppsDrawer drawer = new AppsDrawer(activity, icons);
        AppEntry entry = new AppEntry(new ComponentName("test.app", "test.app.Main"), Process.myUserHandle(), "Test", null);
        int[] menus = {0}, launches = {0};
        drawer.setActions(new AppsDrawer.Actions() {
            @Override public void launch(AppEntry app, Rect bounds) { launches[0]++; }
            @Override public void showMenu(AppEntry app) { assertSame(entry, app); menus[0]++; }
            @Override public void retry() { }
        });
        drawer.setApps(AppRepository.Status.READY, List.of(entry));
        for (int key : new int[]{KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START}) {
            assertTrue(drawer.handleKey(key, new KeyEvent(KeyEvent.ACTION_DOWN, key)));
        }
        assertEquals(2, menus[0]);
        assertTrue(drawer.isKeyMode());
        assertNotNull(drawer.selectedIcon());
        assertTrue(drawer.handleKey(KeyEvent.KEYCODE_BUTTON_START, new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_START, 1)));
        assertEquals(2, menus[0]);
        assertEquals(0, launches[0]);
    }

    @Test public void emptyDrawerConsumesMenuKeysWithoutOpeningAnything() {
        AppsDrawer drawer = new AppsDrawer(activity, icons);
        for (AppRepository.Status state : AppRepository.Status.values()) {
            drawer.setApps(state, List.of());
            assertTrue(drawer.handleKey(KeyEvent.KEYCODE_BUTTON_SELECT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT)));
            assertTrue(drawer.handleKey(KeyEvent.KEYCODE_MENU, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU)));
        }
    }

    @Test public void menuKeysDismissTheMenuButTheirRepeatsDoNot() {
        AppMenuView menu = new AppMenuView(activity, new AppLanguage(activity));
        int[] dismissed = {0};
        menu.setActions(new AppMenuView.Actions() {
            @Override public void showDetails(AppEntry entry) { fail("Menu keys must not launch details"); }
            @Override public void dismissMenu() { dismissed[0]++; }
        });
        for (int key : new int[]{KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU}) {
            menu.handleKey(key, new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key, 1));
        }
        assertEquals(0, dismissed[0]);
        for (int key : new int[]{KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU}) {
            menu.handleKey(key, new KeyEvent(KeyEvent.ACTION_DOWN, key));
        }
        assertEquals(3, dismissed[0]);
    }

    @Test public void infoIconKeepsTheWholeRowClickableAcrossLanguageChanges() {
        AppLanguage language = new AppLanguage(activity);
        AppMenuView menu = new AppMenuView(activity, language);
        AppEntry app = new AppEntry(new ComponentName("test.app", "test.app.Main"), Process.myUserHandle(), "Test", null);
        int[] opened = {0};
        menu.setActions(new AppMenuView.Actions() {
            @Override public void showDetails(AppEntry entry) { assertSame(app, entry); opened[0]++; }
            @Override public void dismissMenu() { }
        });
        menu.applyLayout(HomeLayout.reference());
        menu.show(app, new View(activity));
        TextView row = (TextView) ((LinearLayout) menu.getChildAt(0)).getChildAt(1);
        assertNotNull(row.getCompoundDrawablesRelative()[0]);
        assertFalse(row.isFocusable());
        assertFalse(row.getDefaultFocusHighlightEnabled());
        assertTrue(row.performClick());
        language.select(AppLanguage.Choice.ENGLISH);
        menu.onLanguageChanged();
        assertEquals(language.text(R.string.app_info), row.getText().toString());
        assertNotNull(row.getCompoundDrawablesRelative()[0]);
        menu.handleKey(KeyEvent.KEYCODE_BUTTON_A, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(2, opened[0]);
        menu.hide();
    }
}
