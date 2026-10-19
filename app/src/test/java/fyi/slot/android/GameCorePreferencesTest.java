package fyi.slot.android;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import fyi.slot.android.Cartridge.Platform;
import fyi.slot.android.RetroArchPreferences.Core;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class GameCorePreferencesTest {
    private Context context;
    private RetroArchPreferences global;
    private GameCorePreferences games;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("retroarch", 0).edit().clear().commit();
        context.getSharedPreferences("game_cores", 0).edit().clear().commit();
        global = new RetroArchPreferences(context);
        games = new GameCorePreferences(context, global);
    }

    private RomRecord rom(String root, Platform platform, String filename, boolean hidden) {
        return new RomRecord(root, platform + "/" + filename, platform, 512, 0, new byte[512], null, hidden);
    }
    private RomRecord rom(Platform platform) { return rom("/games", platform, "Sample." + platform, false); }

    @Test public void defaultsInheritAllCurrentPlatformCores() {
        for (Platform platform : Platform.values()) {
            RomRecord rom = rom(platform);
            assertNull(games.override(rom));
            assertEquals(Core.MGBA, games.effective(rom));
            Core changed = RetroArchPreferences.cores(platform).get(1);
            global.selectCore(platform, changed);
            assertEquals(changed, games.effective(rom));
            assertNull(games.override(rom));
        }
    }

    @Test public void explicitOverrideSurvivesGlobalChange() {
        RomRecord rom = rom(Platform.GBA);
        games.select(rom, Core.GPSP);
        global.selectCore(Platform.GBA, Core.VBAM);
        assertEquals(Core.GPSP, games.effective(rom));
        assertEquals(Core.VBAM, games.inherited(rom));
    }

    @Test public void restoringGlobalRemovesStoredOverrideAndDynamicallyInherits() {
        RomRecord rom = rom(Platform.GBC);
        games.select(rom, Core.SAMEBOY);
        games.select(rom, null);
        assertFalse(context.getSharedPreferences("game_cores", 0).contains(rom.id));
        global.selectCore(Platform.GBC, Core.GAMBATTE);
        assertEquals(Core.GAMBATTE, games.effective(rom));
    }

    @Test public void sameNameAcrossPlatformsRootsAndExtensionsRemainsIsolated() {
        RomRecord selected = rom("/one", Platform.GB, "Twin.gb", false);
        games.select(selected, Core.SAMEBOY);
        for (RomRecord other : java.util.List.of(rom("/two", Platform.GB, "Twin.gb", false),
                rom("/one", Platform.GBC, "Twin.gb", false), rom("/one", Platform.GB, "Twin.gbc", false))) {
            assertNull(games.override(other));
            assertEquals(Core.MGBA, games.effective(other));
        }
        assertEquals(Core.SAMEBOY, games.override(selected));
    }

    @Test public void reconstructionAndHideRestoreKeepIdentityChoice() {
        RomRecord visible = rom(Platform.GB);
        games.select(visible, Core.GAMBATTE);
        GameCorePreferences restored = new GameCorePreferences(context, new RetroArchPreferences(context));
        assertEquals(Core.GAMBATTE, restored.effective(rom(visible.root, visible.platform, visible.filename, true)));
        assertEquals(Core.GAMBATTE, restored.effective(visible));
    }

    @Test public void rejectsOtherPlatformsCoreWithoutChangingChoice() {
        RomRecord gba = rom(Platform.GBA);
        games.select(gba, Core.VBAM);
        assertThrows(IllegalArgumentException.class, () -> games.select(gba, Core.SAMEBOY));
        assertEquals(Core.VBAM, games.effective(gba));
    }
}
