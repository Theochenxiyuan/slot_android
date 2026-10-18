package fyi.slot.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = OnboardingTest.StorageAccess.class)
public final class OnboardingTest {
    @org.robolectric.annotation.Implements(android.os.Environment.class)
    public static final class StorageAccess {
        @org.robolectric.annotation.Implementation
        protected static boolean isExternalStorageManager() { return false; }
    }
    private Activity activity;
    private AppLanguage language;
    private SettingsPanel settings;
    private LibraryRepository library;
    private LibraryImportSession imports;
    private LibraryPanel libraryPanel;
    private RetroArchSettings retroarch;
    private OnboardingPanel guide;
    private final AtomicInteger shelfShown = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences("library", 0).edit().clear().commit();
        activity.getSharedPreferences("onboarding", 0).edit().clear().commit();
        language = new AppLanguage(activity); language.select(AppLanguage.Choice.ENGLISH);
        RetroArchPreferences preferences = new RetroArchPreferences(activity);
        retroarch = new RetroArchSettings(language, preferences, new RetroArchLauncher(activity, preferences), List::of);
        settings = new SettingsPanel(activity, language, List.of(
                new SettingsPanel.Group(LibraryPanel.GROUP, R.string.settings_library, List.of()),
                new SettingsPanel.Group(ConnectionPanel.GROUP, R.string.settings_connection, List.of()),
                retroarch.group()));
        library = new LibraryRepository(activity); imports = new LibraryImportSession(activity);
        libraryPanel = new LibraryPanel(activity, language, settings, library, imports);
        guide = new OnboardingPanel(activity, language, settings, library, libraryPanel, retroarch,
                settings::showGroup, shelfShown::incrementAndGet,
                () -> { guide.dismissed(); guide.hide(); closed.incrementAndGet(); }, () -> {}, () -> {});
        libraryPanel.setOnboarding(guide);
        libraryPanel.changed();
        guide.open();
    }
    @After public void tearDown() { libraryPanel.release(); imports.release(); retroarch.release(); library.release(); activity.finish(); }
    private boolean has(String id) { return guide.hasAction(id); }
    private void next() { guide.invokeAction("guide_next"); }

    @Test public void automaticOpeningRequiresUnconfiguredNonFixtureAndUnreadGuide() {
        OnboardingPreferences preferences = new OnboardingPreferences(activity);
        assertTrue(preferences.shouldOpen(false, null));
        assertFalse(preferences.shouldOpen(true, null));
        assertFalse(preferences.shouldOpen(false, new File("Slot")));
        preferences.dismiss();
        assertFalse(new OnboardingPreferences(activity).shouldOpen(false, null));
    }
    @Test public void explicitReentryStillWorksAfterDismissalAndKeepsSettingsRowsClean() {
        guide.dismissed(); assertFalse(guide.shouldOpen(false));
        next(); next(); guide.open();
        assertTrue(guide.isShowing()); assertEquals(0, guide.step());
        assertTrue(has("guide_choose"));
        assertEquals(LibraryPanel.GROUP, settings.groupId());
        assertNotNull(settings.rowView("status"));
        assertNull(settings.rowView("guide_next"));
    }
    @Test public void folderRulesAndUnconfiguredGamesOfferSetupWithoutImport() {
        assertTrue(guide.bodyText().contains("64 MiB"));
        next(); assertTrue(has("guide_setup")); assertFalse(has("guide_import")); assertFalse(has("guide_studio"));
        guide.invokeAction("guide_setup");
        assertEquals(0, guide.step()); assertTrue(has("guide_choose"));
    }
    @Test public void fourStepsDriveUnderlyingGroupsAndShelfAndCanFinishOrSkip() {
        assertEquals(LibraryPanel.GROUP, settings.groupId());
        next(); assertEquals(LibraryPanel.GROUP, settings.groupId());
        next(); assertEquals(RetroArchSettings.GROUP, settings.groupId());
        assertTrue(guide.statusText().startsWith("RetroArch · "));
        next(); assertEquals(1, shelfShown.get());
        assertFalse(has("guide_next")); assertTrue(has("guide_done"));
        guide.invokeAction("guide_done"); assertEquals(1, closed.get()); assertFalse(guide.isShowing());
        guide.open(); guide.invokeAction("guide_skip"); assertEquals(2, closed.get()); assertFalse(guide.isShowing());
    }
    @Test public void currentStepAndVisibilitySurviveReconstruction() {
        next(); next(); Bundle saved = new Bundle(); guide.save(saved);
        guide.hide(); guide.open(); guide.restore(saved);
        assertTrue(guide.isShowing()); assertEquals(2, guide.step());
        assertEquals(RetroArchSettings.GROUP, settings.groupId());
        guide.hide(); guide.save(saved);
        guide.open(); guide.restore(saved); assertFalse(guide.isShowing());
    }
    @Test public void emulatorLoadingEmptyAndDownloadUseTheOfficialPage() throws Exception {
        next(); next(); assertFalse(has("guide_download"));
        retroarch.reload();
        long deadline = System.currentTimeMillis() + 5000;
        while (retroarch.isChecking() && System.currentTimeMillis() < deadline) { Thread.sleep(5); shadowOf(Looper.getMainLooper()).idle(); }
        guide.refresh();
        assertTrue(has("guide_download")); guide.invokeAction("guide_download");
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals("https://www.retroarch.com/?page=platforms", intent.getDataString());
    }
    @Test public void guideCopyIsAvailableInBothLanguages() {
        for (AppLanguage.Choice choice : List.of(AppLanguage.Choice.CHINESE, AppLanguage.Choice.ENGLISH)) {
            language.select(choice);
            for (int id : new int[]{R.string.guide_folder, R.string.guide_rules_text, R.string.guide_games_text,
                    R.string.guide_core_text, R.string.guide_done,
                    R.string.edit_cartridges, R.string.studio_failed}) assertFalse(language.text(id).isBlank());
            assertTrue(language.text(id(R.string.guide_rules_text)).contains("64 MiB"));
            InteractionPreferences interactions = new InteractionPreferences(activity);
            String controls = language.text(R.string.guide_controls_text,
                    interactions.leftShoulderBadge(), interactions.rightShoulderBadge(),
                    interactions.confirmBadge(), interactions.backBadge());
            assertTrue(controls.contains(interactions.confirmBadge()));
            assertTrue(controls.contains(interactions.backBadge()));
            assertFalse(controls.contains("%"));
        }
    }
    private static int id(int value) { return value; }
    @Test public void nativePageNavigationRejectsExternalOriginsAndExecutableSchemes() {
        String origin = "http://127.0.0.1:12345";
        assertTrue(LocalStudioDialog.localPage(android.net.Uri.parse(origin + "/?local=1"), origin));
        for (String url : List.of("https://example.com/", "javascript:alert(1)", "file:///sdcard/game", "http://127.0.0.1:12346/", origin + "/other.html"))
            assertFalse(LocalStudioDialog.localPage(android.net.Uri.parse(url), origin));
    }
}
