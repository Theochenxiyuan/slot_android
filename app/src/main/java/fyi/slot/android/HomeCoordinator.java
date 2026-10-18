package fyi.slot.android;

import android.app.Activity;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.util.List;
import fyi.slot.android.MachinePanelController.Content;

/** Connects the shelf, the lifted machine, app access and system entry points for the desktop activity. */
final class HomeCoordinator implements SlotView.PanelHost, MachinePanelController.Listener,
        AppsDrawer.Actions, AppMenuView.Actions, InputManager.InputDeviceListener {
    /** System settings tab; app-level information lives in the about tab below it. */
    static final String SYSTEM_GROUP = "system";
    static final String ABOUT_GROUP = "about";
    private final Activity activity;
    private final AppLanguage language;
    private final SlotView shelf;
    private final MachinePanelController panel;
    private final AppRepository repository;
    private final AppIconCache icons;
    private final AppsDrawer apps;
    private final SettingsPanel settings;
    private final RetroArchSettings retroArchSettings;
    private final RetroArchLauncher retroArchLauncher;
    private final AppMenuView menu;
    private final MachinePanelView panelView;
    private final HomeRole homeRole;
    private final InputManager inputs;
    private final LibraryRepository library;
    private final LibraryPanel libraryPanel;
    private final LocalStudioDialog localStudio;
    private final OnboardingPanel onboarding;
    private boolean onboardingChecked;
    private final ConnectionPanel connectionPanel;
    private final LibraryImportSession imports;
    private boolean ownsImports;
    private final ShelfCollections collections;
    private final CartridgeDetails details;
    private final GameCoreDialog gameCoreDialog;
    private final WallpaperCropDialog wallpaperCropDialog;
    private final boolean fixture;
    private final Handler main = new Handler(Looper.getMainLooper());
    AppLauncher launcher;
    // Replaceable only by in-process checks, like the ordinary app launcher.
    java.util.function.Function<RomRecord, RetroArchLauncher.Result> gameLauncher;
    private boolean wasClosed = true, openedByKey;

    HomeCoordinator(Activity activity, SlotView shelf) {
        this(activity, shelf, new LibraryImportSession(activity));
        ownsImports = true;
    }
    HomeCoordinator(Activity activity, SlotView shelf, LibraryImportSession imports) {
        this.activity = activity;
        this.imports = imports;
        this.shelf = shelf;
        language = shelf.language();
        panel = shelf.panel();
        launcher = new AppLauncher(activity, language);
        homeRole = new HomeRole(activity);
        inputs = activity.getSystemService(InputManager.class);
        repository = new AppRepository(activity);
        icons = new AppIconCache(activity.getResources().getDisplayMetrics().densityDpi);
        apps = new AppsDrawer(activity, icons, language);
        apps.setActions(this);
        menu = new AppMenuView(activity, language);
        menu.setActions(this);
        RetroArchPreferences retroArchPreferences = new RetroArchPreferences(activity);
        GameCorePreferences gameCores = new GameCorePreferences(activity, retroArchPreferences);
        retroArchLauncher = new RetroArchLauncher(activity, retroArchPreferences, gameCores);
        gameLauncher = retroArchLauncher::launch;
        shelf.setGameLauncher(this::launchGame);
        retroArchSettings = new RetroArchSettings(language, retroArchPreferences, retroArchLauncher);
        settings = new SettingsPanel(activity, language, settingsGroups());
        retroArchSettings.setListener(this::retroArchChanged);
        retroArchSettings.setMessageListener(resource -> Toast.makeText(activity, language.text(resource), Toast.LENGTH_SHORT).show());
        fixture = MainActivity.testShelf != null;
        library = new LibraryRepository(activity);
        libraryPanel = new LibraryPanel(activity, language, settings, library, imports);
        connectionPanel = new ConnectionPanel(activity, language, settings, library, libraryPanel::open);
        localStudio = new LocalStudioDialog(activity, language, library);
        libraryPanel.setEditor(() -> localStudio.show(null));
        onboarding = new OnboardingPanel(activity, language, settings, library, libraryPanel, retroArchSettings,
                this::openSettingsForGuide, panel::close, this::finishOnboarding, () -> localStudio.show(null),
                () -> { leaveOnboarding(); settings.showGroup(ConnectionPanel.GROUP); });
        libraryPanel.setOnboarding(onboarding);
        gameCoreDialog = new GameCoreDialog(activity, language, gameCores);
        collections = new ShelfCollections(activity);
        details = new CartridgeDetails(activity, language, library, gameCoreDialog, collections,
                () -> shelf.updateCollections(collections.favorites(), collections.recents(), null));
        details.setEditor(localStudio::show);
        wallpaperCropDialog = new WallpaperCropDialog(activity, language, cropped -> {
            shelf.setCustomWallpaper(cropped);
            shelf.interactionPreferences().wallpaper = InteractionPreferences.Wallpaper.CUSTOM;
            shelf.interactionPreferences().save();
            shelf.onPreferenceChanged(-1);
            settings.refresh();
        });
        library.setListener((status, carts) -> { shelf.updateLibrary(status, carts); libraryPanel.changed(); connectionPanel.changed(); });
        if (!fixture) {
            shelf.updateCollections(collections.favorites(), collections.recents(), null);
            android.content.SharedPreferences saved = activity.getSharedPreferences("shelf", 0);
            Bundle state = new Bundle(); state.putString("shelf_category", saved.getString("category", "ALL"));
            for (ShelfModel.Category category : ShelfModel.Category.values()) state.putString("shelf_selected_" + category,
                    saved.getString(category.name(), null));
            shelf.restoreLibrarySelection(state);
        }
        settings.setListener(id -> {
            if (onboarding.isShowing() && !onboarding.expectsGroup(id)) leaveOnboarding();
            if (LibraryPanel.GROUP.equals(id)) libraryPanel.shown();
            else if (ConnectionPanel.GROUP.equals(id)) connectionPanel.connectionChanged();
            else if (RetroArchSettings.GROUP.equals(id)) retroArchSettings.reload();
        });
        libraryPanel.changed();
        connectionPanel.changed();
        panelView = new MachinePanelView(activity, panel, apps, settings, menu);
        shelf.setPanelHost(this);
        panel.addListener(this);
        repository.addListener((status, list) -> {
            apps.setApps(status, list);
            if (status == AppRepository.Status.READY) retroArchSettings.reload();
        });
    }

    /** Root content: the drawn shelf with the native bay layer and onboarding overlay above it. */
    FrameLayout createContent() {
        FrameLayout root = new FrameLayout(activity);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.addView(shelf, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(panelView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (onboarding.getParent() instanceof android.view.ViewGroup parent) parent.removeView(onboarding);
        root.addView(onboarding, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    private List<SettingsPanel.Group> settingsGroups() {
        InteractionPreferences preferences = shelf.interactionPreferences();
        List<SettingsPanel.Row> controls = List.of(
                new SettingsPanel.Row("insert", R.string.insert_method, SettingsPanel.Kind.SWITCH, false,
                        () -> language.text(preferences.swipeToInsert ? R.string.swipe : R.string.drag), direction -> toggleControlPreference(0)),
                new SettingsPanel.Row("haptics", R.string.haptics, SettingsPanel.Kind.SWITCH, false,
                        () -> language.text(preferences.haptics ? R.string.enabled : R.string.disabled), direction -> toggleControlPreference(1)),
                new SettingsPanel.Row("sound", R.string.sound, SettingsPanel.Kind.SWITCH, false,
                        () -> language.text(preferences.sound ? R.string.enabled : R.string.disabled), direction -> toggleControlPreference(2)),
                new SettingsPanel.Row("auto_launch", R.string.auto_launch, SettingsPanel.Kind.SWITCH, false,
                        () -> language.text(preferences.autoLaunchOnInsert ? R.string.enabled : R.string.disabled), direction -> toggleControlPreference(3)),
                new SettingsPanel.Row("button_prompts", R.string.button_prompts, SettingsPanel.Kind.CHOICE, true,
                        () -> preferences.buttonPrompts.label, direction -> {
                            preferences.cycleButtonPrompts(direction);
                            shelf.onPreferenceChanged(-1);
                            if (onboarding != null) onboarding.refresh();
                        }),
                new SettingsPanel.Row("confirm_back", R.string.confirm_back_buttons, SettingsPanel.Kind.CHOICE, false,
                        () -> preferences.confirmBadge() + " / " + preferences.backBadge(), direction -> {
                            preferences.toggleSwapConfirmBack();
                            shelf.onPreferenceChanged(-1);
                            if (onboarding != null) onboarding.refresh();
                        }));
        List<SettingsPanel.Row> appearance = List.of(
                new SettingsPanel.Row("theme", R.string.theme, SettingsPanel.Kind.SWITCH, false,
                        () -> language.text(preferences.lightTheme ? R.string.theme_light : R.string.theme_dark), direction -> toggleTheme()),
                new SettingsPanel.Row("game_titles", R.string.game_titles, SettingsPanel.Kind.SWITCH, false,
                        () -> language.text(preferences.showTitles ? R.string.enabled : R.string.disabled), direction -> {
                            preferences.toggleShowTitles();
                            shelf.onPreferenceChanged(-1);
                        }),
                new SettingsPanel.Row("wallpaper", R.string.wallpaper, SettingsPanel.Kind.CHOICE, true,
                        () -> language.text(preferences.wallpaper == InteractionPreferences.Wallpaper.CUSTOM && shelf.backdrop().hasCustom()
                                ? R.string.wallpaper_custom : R.string.wallpaper_none),
                        direction -> toggleOrPickWallpaper()),
                new SettingsPanel.Row("choose_wallpaper", R.string.choose_wallpaper, SettingsPanel.Kind.ENTRY, false,
                        null, direction -> wallpaperCropDialog.pick()),
                new SettingsPanel.Row("wallpaper_tint", R.string.wallpaper_tint, SettingsPanel.Kind.CHOICE, false,
                        () -> preferences.wallpaperTintLabel(language), direction -> {
                            preferences.cycleWallpaperTint(direction);
                            shelf.onPreferenceChanged(-1);
                        }));
        List<SettingsPanel.Row> system = List.of(
                new SettingsPanel.Row("home", R.string.default_home, SettingsPanel.Kind.ENTRY, false,
                        () -> homeRole.isHeld() ? language.text(R.string.home_is_default) : null, direction -> homeRole.request()),
                new SettingsPanel.Row("system", R.string.system_settings, SettingsPanel.Kind.ENTRY, false,
                        null, direction -> launcher.openSystemSettings()),
                new SettingsPanel.Row("wifi", R.string.wifi, SettingsPanel.Kind.ENTRY, false,
                        null, direction -> launcher.openWifiSettings()),
                new SettingsPanel.Row("language", R.string.language, SettingsPanel.Kind.CHOICE, false,
                        language::choiceLabel, direction -> { language.cycle(direction); onLanguageChanged(); }));
        List<SettingsPanel.Row> about = List.of(
                new SettingsPanel.Row("version", R.string.app_version, SettingsPanel.Kind.INFO, false,
                        this::versionName, direction -> {}),
                new SettingsPanel.Row("guide", R.string.usage_guide, SettingsPanel.Kind.ENTRY, false,
                        null, direction -> onboarding.open()),
                new SettingsPanel.Row("app_info", R.string.app_info, SettingsPanel.Kind.ENTRY, false,
                        null, direction -> launcher.openAppInfo()));
        return List.of(
                new SettingsPanel.Group("controls", R.string.settings_controls, controls),
                new SettingsPanel.Group("appearance", R.string.settings_appearance, appearance),
                new SettingsPanel.Group(LibraryPanel.GROUP, R.string.settings_library, List.of()),
                new SettingsPanel.Group(ConnectionPanel.GROUP, R.string.settings_connection, List.of()),
                retroArchSettings.group(),
                new SettingsPanel.Group(SYSTEM_GROUP, R.string.settings_system, system),
                new SettingsPanel.Group(ABOUT_GROUP, R.string.settings_about, about));
    }

    void onLanguageChanged() {
        localStudio.dismiss();
        settings.refresh();
        apps.onLanguageChanged();
        menu.onLanguageChanged();
        shelf.onLanguageChanged();
        libraryPanel.changed();
        connectionPanel.changed();
        onboarding.refresh();
        details.dismiss();
        wallpaperCropDialog.dismiss();
    }

    private void toggleControlPreference(int row) {
        shelf.interactionPreferences().toggle(row);
        shelf.onPreferenceChanged(row);
    }

    private String versionName() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            return "";
        }
    }

    private void toggleTheme() {
        shelf.interactionPreferences().toggleTheme();
        shelf.onPreferenceChanged(-1);
        if (panelView != null) panelView.refreshTheme();
    }

    private void toggleOrPickWallpaper() {
        if (shelf.backdrop().hasCustom()) {
            shelf.interactionPreferences().toggleWallpaper();
            shelf.onPreferenceChanged(-1);
        } else {
            wallpaperCropDialog.pick();
        }
    }

    // Lifecycle

    void start() {
        repository.start();
        retroArchSettings.reload();
        if (inputs != null) inputs.registerInputDeviceListener(this, main);
        settings.refresh();
        if (!fixture) library.start();
        connectionPanel.start();
        if (!onboardingChecked) {
            onboardingChecked = true;
            if (!onboarding.isShowing() && onboarding.shouldOpen(fixture)) onboarding.open();
        }
    }

    void stop() {
        if (!localStudio.isPickingFile()) localStudio.dismiss();
        retroArchSettings.stop();
        libraryPanel.pause();
        connectionPanel.stop();
        repository.stop();
        if (inputs != null) inputs.unregisterInputDeviceListener(this);
        shelf.cancelInteraction();
        if (!fixture) library.stop();
    }

    void resume() {
        retroArchSettings.reload();
        if (language.refresh()) onLanguageChanged();
        else settings.refresh();
        if (!fixture) library.refresh();
        libraryPanel.resume();
    }

    void release() {
        localStudio.release();
        panel.removeListener(this);
        repository.release();
        icons.release();
        retroArchSettings.release();
        libraryPanel.release(); details.release(); wallpaperCropDialog.release(); library.release();
        if (ownsImports) imports.release();
    }

    /** Home keeps the desktop stable: everything temporary closes and the machine settles down. */
    void onHome(boolean animate) {
        localStudio.dismiss();
        leaveOnboarding();
        libraryPanel.home();
        details.dismiss();
        wallpaperCropDialog.dismiss();
        menu.hide();
        if (animate && !panel.isClosed()) {
            shelf.cancelInteraction();
            panel.close();
        } else shelf.settleForHome();
    }

    void onRoleResult() { settings.refresh(); }

    void onLibraryFolder(int resultCode, android.content.Intent data) { libraryPanel.picked(resultCode, data); }
    void onGamesPicked(int resultCode, android.content.Intent data) { libraryPanel.gamesPicked(resultCode, data); }
    void onWallpaperPicked(int resultCode, android.content.Intent data) {
        wallpaperCropDialog.onPicked(resultCode, data, shelf.homeLayout().aspect());
    }
    void onStudioImagePicked(int resultCode, android.content.Intent data) {
        localStudio.onImagePicked(resultCode, data);
    }

    void save(Bundle bundle) {
        bundle.putString("apps_selected", apps.selectedKey());
        bundle.putInt("apps_index", apps.selectedIndex());
        bundle.putBoolean("apps_key_mode", apps.isKeyMode());
        settings.save(bundle);
        libraryPanel.save(bundle);
        onboarding.save(bundle);
        bundle.putBoolean("onboarding_checked", onboardingChecked);
    }

    void restore(Bundle bundle) {
        if (bundle == null) return;
        apps.restoreSelection(bundle.getString("apps_selected"), bundle.getInt("apps_index"),
                bundle.getBoolean("apps_key_mode"));
        onboardingChecked = bundle.getBoolean("onboarding_checked");
        libraryPanel.restore(bundle);
        settings.restore(bundle);
        onboarding.restore(bundle);
    }

    // Input

    boolean dispatchKeyEvent(KeyEvent event) {
        if (libraryPanel.dispatchImportKey(event)) return true;
        if (details.isShowing()) { details.dispatchKey(event); return true; }
        if (onboarding.isShowing() && onboarding.dispatchKey(event)) return true;
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (panel.isTargetOpen() && handleContentKey(key, event)) return true;
            return shelf.handleKeyDown(key, event);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) return shelf.handleKeyUp(key, event);
        return false;
    }

    private boolean handleContentKey(int key, KeyEvent event) {
        if (SlotView.isBackKey(key) || SlotView.entryForKey(key) != null && key != KeyEvent.KEYCODE_MENU) return false;
        if (menu.isShowing()) {
            if (AppsDrawer.isConfirm(key) || AppsDrawer.isNavigation(key) || AppsDrawer.isMenuKey(key)) {
                return menu.handleKey(key, event);
            }
            return false;
        }
        if (panel.content() == Content.APPS) {
            if (AppsDrawer.isMenuKey(key) || AppsDrawer.isConfirm(key) || AppsDrawer.isNavigation(key)) {
                return apps.handleKey(key, event);
            }
            return false;
        }
        if (key == KeyEvent.KEYCODE_MENU) return false;
        if (AppsDrawer.isConfirm(key) || AppsDrawer.isNavigation(key)) return settings.handleKey(key, event);
        return false;
    }

    void goBack() {
        if (localStudio.isShowing()) { localStudio.back(); return; }
        if (onboarding.isShowing()) { finishOnboarding(); return; }
        if (!libraryPanel.importBack()) shelf.goBack();
    }

    @Override public void onEntry(Content content, boolean viaKey) {
        if (!panel.isTargetOpen()) {
            openedByKey = viaKey;
            panel.open(content);
            return;
        }
        if (panel.content() == content) { onPanelBack(); return; }
        menu.hide();
        panel.open(content);
    }

    /** Back closes the app menu, then onboarding, then a settings page, then lowers the machine. */
    @Override public void onPanelBack() {
        if (menu.isShowing()) { menu.hide(); return; }
        if (onboarding.isShowing()) { finishOnboarding(); return; }
        if (panel.content() == Content.SETTINGS && (libraryPanel.back() || settings.back())) return;
        panel.close();
    }

    @Override public void onLibrary() {
        if (library.root() == null) { onboarding.open(); return; }
        if (!panel.isTargetOpen()) { onEntry(Content.SETTINGS, false); }
        else if (panel.content() != Content.SETTINGS) panel.open(Content.SETTINGS);
        libraryPanel.open();
    }
    @Override public void onCartridgeMenu(Cartridge cartridge) { details.menu(cartridge); }

    private void openSettingsForGuide(String group) {
        if (!panel.isTargetOpen()) onEntry(Content.SETTINGS, false);
        else if (panel.content() != Content.SETTINGS) panel.open(Content.SETTINGS);
        settings.showGroup(group);
    }
    private void leaveOnboarding() {
        if (!onboarding.isShowing()) return;
        onboarding.dismissed();
        onboarding.hide();
    }
    private void finishOnboarding() { leaveOnboarding(); panel.close(); }
    private void retroArchChanged() {
        settings.refresh();
        if (onboarding != null && onboarding.isShowing()) onboarding.refresh();
    }

    private boolean launchGame(RomRecord rom) {
        RetroArchLauncher.Result result = gameLauncher.apply(rom);
        if (result == RetroArchLauncher.Result.STARTED) {
            collections.recordLaunch(rom);
            shelf.updateCollections(collections.favorites(), collections.recents(), rom.id);
            return true;
        }
        int message = result == RetroArchLauncher.Result.NOT_INSTALLED ? R.string.launch_retroarch_missing
                : result == RetroArchLauncher.Result.ROM_MISSING ? R.string.launch_rom_missing : R.string.launch_retroarch_failed;
        Toast.makeText(activity, language.text(message), Toast.LENGTH_SHORT).show();
        if (result == RetroArchLauncher.Result.ROM_MISSING) library.refresh();
        return false;
    }

    @Override public void onPanelChanged(MachinePanelController changed) {
        if (changed.isTargetOpen() && changed.content() == Content.APPS) leaveOnboarding();
        boolean closed = changed.isClosed();
        if (wasClosed && changed.isTargetOpen()) {
            libraryPanel.resetPage();
            apps.reset(openedByKey);
            settings.reset(openedByKey);
            menu.hide();
            if (onboarding.isShowing()) onboarding.syncStep();
        }
        if (!changed.isTargetOpen()) menu.hide();
        wasClosed = closed;
        openedByKey = false;
    }

    // Apps

    @Override public void launch(AppEntry entry, Rect sourceBounds) {
        if (!panel.isFullyOpen() && !panel.isTargetOpen()) return;
        if (launcher.launch(entry, sourceBounds)) {
            menu.hide();
            shelf.settleForHome();
        } else repository.scheduleReload();
    }

    @Override public void showMenu(AppEntry entry) { menu.show(entry, apps.selectedIcon()); }

    @Override public void retry() { repository.reloadNow(); }

    @Override public void showDetails(AppEntry entry) {
        menu.hide();
        if (!launcher.showDetails(entry)) repository.scheduleReload();
    }

    @Override public void dismissMenu() { menu.hide(); }

    // Controllers connecting or leaving must never leave a held direction behind.

    @Override public void onInputDeviceAdded(int deviceId) { shelf.cancelInteraction(); }
    @Override public void onInputDeviceRemoved(int deviceId) { shelf.cancelInteraction(); }
    @Override public void onInputDeviceChanged(int deviceId) { shelf.cancelInteraction(); }

    // Observations used by on-device interaction checks.
    AppsDrawer appsDrawer() { return apps; }
    SettingsPanel settingsPanel() { return settings; }
    AppMenuView appMenu() { return menu; }
    MachinePanelView panelView() { return panelView; }
    AppRepository repository() { return repository; }
    LibraryRepository library() { return library; }
    ShelfCollections collections() { return collections; }
    CartridgeDetails cartridgeDetails() { return details; }
    GameCoreDialog gameCoreDialog() { return gameCoreDialog; }
    WallpaperCropDialog wallpaperCropDialog() { return wallpaperCropDialog; }
    LibraryPanel libraryPanel() { return libraryPanel; }
    ConnectionPanel connectionPanel() { return connectionPanel; }
    LibraryImportSession importSession() { return imports; }
    LocalStudioDialog localStudio() { return localStudio; }
    OnboardingPanel onboarding() { return onboarding; }
}
