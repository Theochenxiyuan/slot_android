package fyi.slot.android;

import android.app.Activity;
import android.content.ComponentName;
import android.os.Process;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class AppsDrawerThemeTest {
    private Activity activity;
    private AppIconCache icons;
    private boolean originalLight;

    @Before public void setUp() {
        originalLight = PanelStyle.isLight();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        icons = new AppIconCache(160);
    }

    @After public void tearDown() {
        icons.release();
        activity.finish();
        PanelStyle.setLight(originalLight);
    }

    @Test public void existingNamesRefreshWhenSwitchingThemesInBothDirections() {
        PanelStyle.setLight(false);
        AppsDrawer drawer = createDrawer();
        TextView name = name(drawer, 0);
        assertNames(drawer, 0xffe0e0e1);
        PanelStyle.setLight(true);
        drawer.applyLayout(HomeLayout.reference(), 600);
        assertSame(name, name(drawer, 0));
        assertNames(drawer, 0xff1c1c22);
        PanelStyle.setLight(false);
        drawer.applyLayout(HomeLayout.reference(), 600);
        assertSame(name, name(drawer, 0));
        assertNames(drawer, 0xffe0e0e1);
    }

    @Test public void namesCreatedInLightThemeUseDarkText() {
        PanelStyle.setLight(true);
        assertNames(createDrawer(), 0xff1c1c22);
    }

    private AppsDrawer createDrawer() {
        AppsDrawer drawer = new AppsDrawer(activity, icons);
        drawer.applyLayout(HomeLayout.reference(), 600);
        drawer.setApps(AppRepository.Status.READY, List.of(
                new AppEntry(new ComponentName("test.one", "test.one.Main"), Process.myUserHandle(), "One", null),
                new AppEntry(new ComponentName("test.two", "test.two.Main"), Process.myUserHandle(), "Two", null)));
        return drawer;
    }

    private void assertNames(AppsDrawer drawer, int color) {
        for (int i = 0; i < drawer.apps().size(); i++) assertEquals(color, name(drawer, i).getCurrentTextColor());
    }

    private TextView name(AppsDrawer drawer, int index) {
        return (TextView) ((LinearLayout) drawer.cell(index)).getChildAt(1);
    }
}
