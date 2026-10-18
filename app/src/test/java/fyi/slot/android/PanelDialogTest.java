package fyi.slot.android;

import android.app.Activity;
import android.view.KeyEvent;
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
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class PanelDialogTest {
    private Activity activity;
    private AppLanguage language;
    private PanelDialog dialog;
    private final AtomicInteger picked = new AtomicInteger(-1);
    private final AtomicInteger cancelled = new AtomicInteger();

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        language = new AppLanguage(activity);
        language.select(AppLanguage.Choice.ENGLISH);
        dialog = new PanelDialog(activity, language, 460f)
                .setTitle("Game")
                .setRows(List.of("Details", "Edit appearance", "Core"), -1)
                .setPick(picked::set)
                .setCancelled(cancelled::incrementAndGet);
        dialog.show();
    }

    @After public void tearDown() { dialog.dismiss(); activity.finish(); }

    private static KeyEvent down(int code) { return new KeyEvent(KeyEvent.ACTION_DOWN, code); }

    @Test public void rowsKeepTheirLabelsAndStartOnTheFirstOne() {
        assertEquals(3, dialog.rowCount());
        assertEquals("Edit appearance", dialog.rowLabel(1));
        assertEquals(0, dialog.selectedIndex());
        assertEquals(-1, dialog.checkedIndex());
        assertTrue(dialog.rowView(0).isActivated());
        assertFalse(dialog.rowView(1).isActivated());
    }

    @Test public void rowsRejectFrameworkFocusSoMovingNeverLeavesFirstRowHighlighted() {
        for (int i = 0; i < dialog.rowCount(); i++) {
            assertFalse(dialog.rowView(i).isFocusable());
            assertFalse(dialog.rowView(i).getDefaultFocusHighlightEnabled());
        }
        assertTrue(dialog.handleKey(down(KeyEvent.KEYCODE_DPAD_DOWN)));
        assertTrue(dialog.handleKey(down(KeyEvent.KEYCODE_DPAD_DOWN)));
        assertFalse(dialog.rowView(0).isActivated());
        assertFalse(dialog.rowView(0).isFocused());
        assertTrue(dialog.rowView(2).isActivated());
    }

    @Test public void actionIconsKeepLabelsAndSingleRowSelection() {
        dialog.setRows(List.of("Details", "Edit appearance", "Core"), -1,
                R.drawable.menu_info, R.drawable.menu_edit, R.drawable.menu_core);
        for (int i = 0; i < dialog.rowCount(); i++) {
            LinearLayout row = (LinearLayout) dialog.rowView(i);
            TextView label = (TextView) row.getChildAt(0);
            assertNotNull(label.getCompoundDrawablesRelative()[0]);
            assertTrue(label.getCompoundDrawablesRelative()[0].getBounds().width() > 0);
            assertTrue(label.getCompoundDrawablePadding() > 0);
            assertFalse(row.isFocusable());
        }
        assertEquals("Edit appearance", dialog.rowLabel(1));
        dialog.handleKey(down(KeyEvent.KEYCODE_DPAD_DOWN));
        assertFalse(dialog.rowView(0).isActivated());
        assertTrue(dialog.rowView(1).isActivated());
        dialog.rowView(2).performClick();
        assertEquals(2, picked.get());
        assertFalse(dialog.rowView(1).isActivated());
        assertTrue(dialog.rowView(2).isActivated());
    }

    @Test public void coreRowsStayIconFreeAfterReplacingAnActionList() {
        dialog.setRows(List.of("Details"), -1, R.drawable.menu_info);
        dialog.setRows(List.of("Use global", "mGBA"), 1);
        for (int i = 0; i < dialog.rowCount(); i++) {
            TextView label = (TextView) ((LinearLayout) dialog.rowView(i)).getChildAt(0);
            assertNull(label.getCompoundDrawablesRelative()[0]);
            assertEquals(0, label.getCompoundDrawablePadding());
        }
        assertEquals(1, dialog.checkedIndex());
    }

    @Test public void padMovesWithoutChoosingAndConfirmPicksTheHighlightedRow() {
        assertTrue(dialog.handleKey(down(KeyEvent.KEYCODE_DPAD_DOWN)));
        assertTrue(dialog.handleKey(down(KeyEvent.KEYCODE_DPAD_DOWN)));
        assertEquals(2, dialog.selectedIndex());
        assertEquals(-1, picked.get());
        assertTrue(dialog.handleKey(down(KeyEvent.KEYCODE_BUTTON_A)));
        assertEquals(2, picked.get());
    }

    @Test public void selectionStopsAtBothEnds() {
        assertTrue(dialog.handleKey(down(KeyEvent.KEYCODE_DPAD_UP)));
        assertEquals(0, dialog.selectedIndex());
        for (int i = 0; i < 10; i++) dialog.handleKey(down(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(2, dialog.selectedIndex());
        assertEquals(-1, picked.get());
    }

    @Test public void confirmActsOnTheHighlightedRowOnly() {
        dialog.select(2);
        assertTrue(dialog.handleKey(down(KeyEvent.KEYCODE_BUTTON_A)));
        assertEquals(2, picked.get());
    }

    @Test public void backCancelsWithoutChoosing() {
        assertTrue(dialog.handleKey(down(KeyEvent.KEYCODE_BUTTON_B)));
        assertEquals(0, cancelled.get());
        assertEquals(-1, picked.get());
    }

    @Test public void aCheckedRowMarksItsIndexAndReplacesTheFreeBody() {
        dialog.setRows(List.of("Use global (VBA-M)", "mGBA", "VBA-M"), 2);
        assertEquals(2, dialog.checkedIndex());
        assertEquals(2, dialog.selectedIndex());
        assertEquals("VBA-M", dialog.rowLabel(2));
        dialog.setBody(new android.widget.TextView(activity));
        assertEquals(0, dialog.rowCount());
    }

    @Test public void bayAndShoulderInputIsSwallowedWhileTheCardIsOpen() {
        assertTrue(dialog.handleKey(down(KeyEvent.KEYCODE_BUTTON_Y)));
        assertTrue(dialog.handleKey(down(KeyEvent.KEYCODE_BUTTON_L1)));
        assertTrue(dialog.handleKey(down(KeyEvent.KEYCODE_BUTTON_SELECT)));
        assertTrue(dialog.handleKey(down(KeyEvent.KEYCODE_BUTTON_START)));
        assertEquals(-1, picked.get());
        assertEquals(0, cancelled.get());
    }
}
