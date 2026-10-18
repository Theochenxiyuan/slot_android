package fyi.slot.android;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import fyi.slot.android.Cartridge.Platform;
import fyi.slot.android.ShelfModel.Category;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ShelfCollectionsTest {
    private Context context;
    private ShelfCollections collections;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(ShelfCollections.PREFS, 0).edit().clear().commit();
        collections = new ShelfCollections(context);
    }

    private RomRecord rom(String root, Platform platform, String filename) {
        return new RomRecord(root, "Games/" + platform + "/" + filename, platform, 512, 0, new byte[512], null, false);
    }

    private Cartridge cart(RomRecord rom) {
        return new Cartridge(rom, new ShellConfig.Choice("auto", 0xff35353a, CartridgeRenderer.Finish.SOLID), (File) null);
    }

    @Test public void favoritesTogglePersistAndIsolateIdentities() {
        RomRecord gba = rom("/one", Platform.GBA, "Twin.gba");
        RomRecord gb = rom("/one", Platform.GB, "Twin.gb");
        RomRecord gbcInGb = rom("/one", Platform.GB, "Twin.gbc");
        RomRecord otherRoot = rom("/two", Platform.GB, "Twin.gb");

        assertTrue(collections.toggleFavorite(gb));
        assertTrue(collections.isFavorite(gb));
        assertFalse(collections.isFavorite(gba));
        assertFalse(collections.isFavorite(gbcInGb));
        assertFalse(collections.isFavorite(otherRoot));

        ShelfCollections restored = new ShelfCollections(context);
        assertEquals(Set.of(gb.id), restored.favorites());
        assertTrue(restored.isFavorite(gb));

        assertFalse(restored.toggleFavorite(gb));
        assertFalse(restored.isFavorite(gb));
        assertTrue(new ShelfCollections(context).favorites().isEmpty());
    }

    @Test public void recentsKeepMostRecentFirstDeduplicateAndCapAtTen() {
        List<RomRecord> launched = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            RomRecord record = rom("/games", Platform.GBA, String.format("Game %02d.gba", i));
            launched.add(record);
            collections.recordLaunch(record);
        }
        assertEquals(ShelfCollections.MAX_RECENTS, collections.recents().size());
        assertEquals(launched.get(13).id, collections.recents().get(0));
        assertEquals(launched.get(4).id, collections.recents().get(9));

        collections.recordLaunch(launched.get(7));
        assertEquals(ShelfCollections.MAX_RECENTS, collections.recents().size());
        assertEquals(launched.get(7).id, collections.recents().get(0));
        assertEquals(launched.get(13).id, collections.recents().get(1));
        assertEquals(1, collections.recents().stream().filter(id -> id.equals(launched.get(7).id)).count());

        ShelfCollections restored = new ShelfCollections(context);
        assertEquals(collections.recents(), restored.recents());
    }

    @Test public void shelfModelBuildsFavoritesInTitleOrderAndRecentsInLaunchOrder() {
        Cartridge alpha = cart(rom("/games", Platform.GBA, "Alpha.gba"));
        Cartridge beta = cart(rom("/games", Platform.GB, "Beta.gb"));
        Cartridge delta = cart(rom("/games", Platform.GBC, "Delta.gbc"));
        List<Cartridge> all = List.of(alpha, beta, delta);

        ShelfModel model = new ShelfModel(all, Set.of(delta.id, alpha.id), List.of(delta.id, "missing-id", beta.id));
        assertEquals(List.of(alpha, delta), model.shelf(Category.FAVORITES).cartridges);
        assertSame(alpha, model.shelf(Category.FAVORITES).cartridges.get(0));
        assertSame(delta, model.shelf(Category.FAVORITES).cartridges.get(1));
        assertEquals(1, model.shelf(Category.FAVORITES).letterJumpDelta(1));

        assertEquals(List.of(delta, beta), model.shelf(Category.RECENTS).cartridges);
        assertSame(delta, model.shelf(Category.RECENTS).selectedCartridge());

        model.selectCategory(Category.RECENTS);
        model.current().select(1);
        assertEquals(beta.id, model.current().selectedCartridge().id);

        // Toggling a favorite must preserve the user's current position in Recents.
        model.replace(all, Set.of(alpha.id, beta.id, delta.id), List.of(delta.id, beta.id), null);
        assertEquals(Category.RECENTS, model.category());
        assertEquals(beta.id, model.current().selectedCartridge().id);

        // Recording a new launch selects the newly launched game at index 0 in Recents.
        model.replace(all, Set.of(alpha.id, beta.id, delta.id), List.of(alpha.id, delta.id, beta.id), alpha.id);
        assertEquals(0, model.shelf(Category.RECENTS).selectedIndex());
        assertEquals(alpha.id, model.shelf(Category.RECENTS).selectedCartridge().id);
    }

    @Test public void unfavoritingFromFavoritesSelectsAdjacentAndFallsBackToAllWhenEmpty() {
        Cartridge alpha = cart(rom("/games", Platform.GBA, "Alpha.gba"));
        Cartridge beta = cart(rom("/games", Platform.GB, "Beta.gb"));
        List<Cartridge> all = List.of(alpha, beta);

        ShelfModel model = new ShelfModel(all, Set.of(alpha.id, beta.id), List.of());
        assertTrue(model.selectCategory(Category.FAVORITES));
        model.current().select(1);
        assertEquals(beta.id, model.current().selectedCartridge().id);

        model.replace(all, Set.of(alpha.id), List.of(), null);
        assertEquals(Category.FAVORITES, model.category());
        assertEquals(0, model.current().selectedIndex());
        assertEquals(alpha.id, model.current().selectedCartridge().id);

        model.replace(all, Set.of(), List.of(), null);
        assertEquals(Category.ALL, model.category());
        assertEquals(0, model.shelf(Category.FAVORITES).size());
    }
}
