package fyi.slot.android;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;
import fyi.slot.android.AppLanguage.Choice;
import fyi.slot.android.MachinePanelController.Content;

/** Language switching must update both UI layers without disturbing the desktop session. */
final class LanguageChecks {
    private final InteractionChecks t;

    LanguageChecks(InteractionChecks checks) { t = checks; }

    void run() {
        Choice original = language().choice();
        try {
            t.check(99, "languageResolvesSystemChineseEnglishAndFallback", () -> {
                t.require(AppLanguage.resolve(Choice.SYSTEM, new LocaleList(Locale.SIMPLIFIED_CHINESE)).equals(Locale.SIMPLIFIED_CHINESE), "Chinese system must select simplified Chinese");
                t.require(AppLanguage.resolve(Choice.SYSTEM, new LocaleList(Locale.TRADITIONAL_CHINESE)).equals(Locale.SIMPLIFIED_CHINESE), "Other Chinese variants must use the available Chinese UI");
                for (Locale locale : new Locale[]{Locale.US, Locale.JAPAN, Locale.forLanguageTag("ar")}) {
                    t.require(AppLanguage.resolve(Choice.SYSTEM, new LocaleList(locale)).equals(Locale.US), "Other system languages must use a complete English locale");
                }
                t.require(AppLanguage.resolve(Choice.SYSTEM, LocaleList.getEmptyLocaleList()).equals(Locale.US), "Missing system locale must use English");
                t.require(AppLanguage.resolve(Choice.CHINESE, new LocaleList(Locale.US)).equals(Locale.SIMPLIFIED_CHINESE), "Explicit Chinese must override system");
                t.require(AppLanguage.resolve(Choice.ENGLISH, new LocaleList(Locale.CHINA)).equals(Locale.US), "Explicit English must override system");
            });
            t.check(100, "touchLanguageSwitchUpdatesImmediatelyAndKeepsDesktopState", () -> {
                select(Choice.CHINESE);
                t.reset(7, false);
                t.key(KeyEvent.KEYCODE_BUTTON_X); t.settle(550);
                t.key(KeyEvent.KEYCODE_DPAD_RIGHT);
                String app = t.home().appsDrawer().selectedKey();
                t.key(KeyEvent.KEYCODE_BUTTON_Y);
                t.runOnMainSync(() -> {
                    t.home().settingsPanel().showGroup(HomeCoordinator.SYSTEM_GROUP);
                    t.home().settingsPanel().scroller().scrollTo(0, 20);
                });
                awaitSettingsReady();
                int scroll = t.home().settingsPanel().scroller().getScrollY();
                t.tapSettingsRow(3);
                t.require(language().choice() == Choice.ENGLISH && "Settings".equals(t.view().panel().title()),
                        "Touch must immediately select English, got " + language().choice() + " title=" + t.view().panel().title()
                                + " group=" + t.home().settingsPanel().groupId() + " row=" + t.home().settingsPanel().selectedRow());
                t.require("Back".equals(t.view().entryText(Content.SETTINGS)) && "Apps".equals(t.view().entryText(Content.APPS)), "Canvas entries must switch together");
                t.require("Language".equals(label(3).getText().toString()) && "‹ English ›".equals(value(3).getText().toString()), "Native labels and values must switch together, including choice arrows");
                t.require(t.view().language().text(R.string.setting_description, "Language", "English")
                        .contentEquals(t.home().settingsPanel().rowView(3).getContentDescription()),
                        "Accessibility must describe the value without decorative arrows");
                t.require(t.home().settingsPanel().selectedRow() == 3 && t.home().settingsPanel().scroller().getScrollY() == scroll, "Switch must keep row and scroll");
                t.require(t.view().selectedIndex() == 7 && t.view().panel().isFullyOpen() && !t.view().panel().isAnimating(), "Switch must keep cartridge and lifted machine");
                t.key(KeyEvent.KEYCODE_BUTTON_X);
                t.require(app == null || app.equals(t.home().appsDrawer().selectedKey()), "Switch must keep app selection");
                t.key(KeyEvent.KEYCODE_BUTTON_Y);
                awaitSettingsReady();
                t.tapSettingsRow(3);
                t.require(language().choice() == Choice.SYSTEM, "English must wrap to follow system");
                t.tapSettingsRow(3);
                t.require(language().choice() == Choice.CHINESE && "\u8BED\u8A00".equals(label(3).getText().toString()), "System must advance to Chinese");
            });
            t.check(101, "languageConfirmCyclesForwardAndIgnoresAutoRepeat", () -> {
                openLanguage(Choice.SYSTEM);
                t.key(KeyEvent.KEYCODE_BUTTON_A);
                t.require(language().choice() == Choice.CHINESE, "A must advance");
                t.runOnMainSync(() -> t.home().dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 2)));
                t.require(language().choice() == Choice.CHINESE, "Held key must not repeatedly switch language");
                t.key(KeyEvent.KEYCODE_ENTER);
                t.require(language().choice() == Choice.ENGLISH, "Enter must advance");
                t.key(KeyEvent.KEYCODE_BUTTON_A);
                t.require(language().choice() == Choice.SYSTEM, "A must wrap to follow system");
                t.key(KeyEvent.KEYCODE_DPAD_RIGHT);
                t.require(language().choice() == Choice.CHINESE, "Right must cycle a choice row forward");
                t.key(KeyEvent.KEYCODE_DPAD_LEFT);
                t.require(language().choice() == Choice.SYSTEM && t.home().settingsPanel().isInRows(), "Left must cycle a choice row back without leaving the rows");
                t.key(KeyEvent.KEYCODE_BUTTON_B);
                t.require(!t.home().settingsPanel().isInRows() && language().choice() == Choice.SYSTEM, "B must return to tabs without changing a value");
            });
            t.check(102, "languagePreferenceAndSelectionSurviveRecreation", () -> {
                openLanguage(Choice.CHINESE);
                t.key(KeyEvent.KEYCODE_BUTTON_A);
                t.recreateActivity();
                t.require(language().choice() == Choice.ENGLISH && new AppLanguage(t.getTargetContext()).choice() == Choice.ENGLISH, "Language must be persisted");
                t.require("Settings".equals(t.view().panel().title()) && "Language".equals(label(3).getText().toString()), "Recreated UI must use saved language");
                t.require(t.view().isSettingsOpen() && t.home().settingsPanel().isInRows() && t.home().settingsPanel().selectedRow() == 3, "Panel and row must survive recreation");
            });
            t.check(103, "systemLanguageChangesUpdateLiveAndRespectExplicitChoice", () -> {
                openLanguage(Choice.SYSTEM);
                Configuration system = new Configuration(t.activity().getResources().getConfiguration());
                try {
                    configure(Locale.CHINA);
                    t.require("\u8BBE\u7F6E".equals(t.view().panel().title()) && "\u2039 \u8DDF\u968F\u7CFB\u7EDF \u203A".equals(value(3).getText().toString()),
                            "System change must update live: title=" + t.view().panel().title()
                                    + " value=" + value(3).getText() + " locale=" + language().locale());
                    configure(Locale.US);
                    t.require("Settings".equals(t.view().panel().title()) && "\u2039 Follow system \u203A".equals(value(3).getText().toString()),
                            "English system language must update live: " + LanguageResourceProbe.describe(language()));
                    select(Choice.CHINESE);
                    configure(Locale.US);
                    t.require("\u8BBE\u7F6E".equals(t.view().panel().title()), "Explicit Chinese must stay Chinese");
                    select(Choice.ENGLISH);
                    configure(Locale.CHINA);
                    t.require("Settings".equals(t.view().panel().title()), "Explicit English must stay English");
                } finally {
                    t.runOnMainSync(() -> t.activity().onConfigurationChanged(system));
                }
            });
            t.check(104, "bothLanguagesCoverDrawerStatesMenuAndFailureMessages", () -> t.runOnMainSync(() -> {
                AppIconCache icons = new AppIconCache(160);
                try {
                    AppLanguage language = new AppLanguage(t.getTargetContext());
                    AppsDrawer drawer = new AppsDrawer(t.getTargetContext(), icons, language);
                    AppMenuView menu = new AppMenuView(t.getTargetContext(), language);
                    int[] retries = new int[1];
                    drawer.setActions(new AppsDrawer.Actions() {
                        @Override public void launch(AppEntry entry, Rect bounds) { }
                        @Override public void showMenu(AppEntry entry) { }
                        @Override public void retry() { retries[0]++; }
                    });
                    AppLauncher launcher = new AppLauncher(t.activity(), language) {
                        @Override boolean start(android.content.Intent intent) { return false; }
                        @Override void notice(String message) {
                            boolean chinese = language.choice() == Choice.CHINESE;
                            t.require(message.equals(chinese ? "\u65E0\u6CD5\u6253\u5F00\u7CFB\u7EDF\u8BBE\u7F6E" : "Couldn’t open system settings")
                                    || message.equals(chinese ? "\u65E0\u6CD5\u6253\u5F00 Wi-Fi \u8BBE\u7F6E" : "Couldn’t open Wi-Fi settings"),
                                    "Failure message must use selected language");
                        }
                    };
                    for (Choice choice : new Choice[]{Choice.CHINESE, Choice.ENGLISH}) {
                        language.select(choice);
                        for (AppRepository.Status state : AppRepository.Status.values()) {
                            drawer.setApps(state, List.of());
                            t.require(expectedStatus(choice, state).equals(drawer.statusText()), "Every drawer state must be translated");
                        }
                        drawer.setApps(AppRepository.Status.LOADING, List.of());
                        language.select(choice == Choice.CHINESE ? Choice.ENGLISH : Choice.CHINESE);
                        drawer.onLanguageChanged();
                        t.require(expectedStatus(language.choice(), AppRepository.Status.LOADING).equals(drawer.statusText()), "Visible status must refresh without a reload");
                        drawer.setApps(AppRepository.Status.FAILED, List.of());
                        drawer.handleKey(KeyEvent.KEYCODE_BUTTON_A, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A));
                        menu.onLanguageChanged();
                        LinearLayout card = (LinearLayout) menu.getChildAt(0);
                        t.require((language.choice() == Choice.CHINESE ? "\u5E94\u7528\u4FE1\u606F" : "App info")
                                .contentEquals(((TextView) card.getChildAt(1)).getText()), "Menu action must update");
                        t.require(!launcher.openSystemSettings() && !launcher.openWifiSettings(), "System failures must remain handled");
                    }
                    t.require(retries[0] == 2, "Retry must work in both languages");
                } finally { icons.release(); }
            }));
            t.check(105, "accessibilityAndCategoryLabelsUseSelectedLanguage", () -> t.runOnMainSync(() -> {
                SlotView shelf = new SlotView(t.getTargetContext(), ShelfModel.demo());
                SlotView empty = new SlotView(t.getTargetContext(), new ShelfModel(List.of()));
                try {
                    for (Choice choice : new Choice[]{Choice.CHINESE, Choice.ENGLISH}) {
                        shelf.language().select(choice); shelf.onLanguageChanged();
                        empty.language().select(choice); empty.onLanguageChanged();
                        t.require(shelf.categoryLabel(ShelfModel.Category.ALL).equals(choice == Choice.CHINESE ? "\u5168\u90E8" : "ALL"), "ALL category must be translated");
                        t.require(shelf.categoryLabel(ShelfModel.Category.FAVORITES).equals(choice == Choice.CHINESE ? "\u6536\u85CF" : "FAVORITES"), "FAVORITES category must be translated");
                        t.require(shelf.categoryLabel(ShelfModel.Category.RECENTS).equals(choice == Choice.CHINESE ? "\u6700\u8FD1" : "RECENTS"), "RECENTS category must be translated");
                        t.require(shelf.categoryLabel(ShelfModel.Category.GBA).equals("GBA"), "Platform names must stay unchanged");
                        t.require(empty.getContentDescription().equals(empty.language().text(R.string.shelf_description)), "Empty shelf accessibility must be translated");
                        t.require(shelf.getContentDescription().toString().contains("MOSS"), "Game name must stay unchanged");
                        shelf.panel().snap(true, Content.APPS);
                        t.require(shelf.getContentDescription().equals(shelf.language().text(R.string.apps)), "Panel accessibility must be translated");
                        shelf.panel().snap(false, Content.APPS);
                    }
                } finally { shelf.onDetachedFromWindow(); empty.onDetachedFromWindow(); }
            }));
            t.check(106, "bilingualSettingsRemainReadableAtAllSupportedRatios", () -> t.runOnMainSync(() -> {
                for (Choice choice : new Choice[]{Choice.CHINESE, Choice.ENGLISH}) {
                    for (int[] size : new int[][]{{960, 640}, {960, 960}, {1024, 768}, {1280, 720}}) {
                        SlotView shelf = new SlotView(t.activity(), ShelfModel.demo());
                        HomeCoordinator home = new HomeCoordinator(t.activity(), shelf);
                        try {
                            shelf.language().select(choice); home.onLanguageChanged();
                            shelf.panel().snap(true, Content.SETTINGS);
                            FrameLayout root = home.createContent();
                            for (String group : new String[]{"controls", "appearance", "connection", RetroArchSettings.GROUP, "system", "about"}) {
                                home.settingsPanel().showGroup(group);
                                root.measure(View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
                                root.layout(0, 0, size[0], size[1]);
                                List<TextView> texts = new java.util.ArrayList<>();
                                for (int tab = 0; tab < home.settingsPanel().groupCount(); tab++) texts.add((TextView) home.settingsPanel().tabView(tab));
                                for (int row = 0; row < home.settingsPanel().rowCount(); row++) {
                                    LinearLayout view = (LinearLayout) home.settingsPanel().rowView(row);
                                    for (int child = 0; child < view.getChildCount(); child++) texts.add((TextView) view.getChildAt(child));
                                }
                                for (TextView text : texts) {
                                    t.require(text.getLayout() != null && text.getLayout().getEllipsisCount(0) == 0, "Settings must fit at " + size[0] + "x" + size[1] + " in " + choice + ": " + text.getText());
                                    t.require(text.getWidth() >= text.getPaint().measureText(text.getText().toString()) - 1, "Labels and values must not clip");
                                }
                            }
                        } finally { home.release(); shelf.onDetachedFromWindow(); }
                    }
                }
            }));
            t.check(107, "completeEnglishLocaleLoadsEnglishResourcesOnTheActualSystem", () -> {
                for (Choice choice : new Choice[]{Choice.CHINESE, Choice.ENGLISH}) {
                    select(choice);
                    t.require(language().text(R.string.settings).equals(choice == Choice.CHINESE ? "\u8BBE\u7F6E" : "Settings"),
                            "Resolved UI resources must match the selected language on this OS");
                    Configuration configuration = new Configuration(t.getTargetContext().getResources().getConfiguration());
                    configuration.setLocales(new LocaleList(language().locale()));
                    configuration.setLayoutDirection(language().locale());
                    android.content.res.Resources actual = t.getTargetContext().getApplicationContext()
                            .createConfigurationContext(configuration).getResources();
                    t.require(actual.getConfiguration().getLocales().get(0).getLanguage()
                            .equals(choice == Choice.CHINESE ? "zh" : "en"), "The OS must honor the requested resource language");
                    t.require(actual.getString(R.string.settings).equals(choice == Choice.CHINESE ? "\u8BBE\u7F6E" : "Settings"),
                            "Direct resource lookup must load the correct language");
                }
                select(Choice.SYSTEM);
                // Resolve unsupported locales before constructing Configuration: some systems normalize
                // unsupported Configuration locales back to the device language in setLocales().
                Locale fallback = AppLanguage.resolve(Choice.SYSTEM, new LocaleList(Locale.JAPAN));
                Configuration unsupported = new Configuration(t.getTargetContext().getResources().getConfiguration());
                unsupported.setLocales(new LocaleList(fallback));
                unsupported.setLayoutDirection(fallback);
                android.content.res.Resources resources = t.getTargetContext().getApplicationContext()
                        .createConfigurationContext(unsupported).getResources();
                t.require("Settings".equals(resources.getString(R.string.settings)),
                        "Unsupported system language must resolve to working English resources");
            });
        } finally {
            select(original);
            // The process ends as soon as the run finishes, before an apply() reaches the disk.
            t.getTargetContext().getSharedPreferences("language", 0).edit().putString("choice", original.name()).commit();
        }
    }

    private AppLanguage language() { return t.view().language(); }

    private static String expectedStatus(Choice choice, AppRepository.Status state) {
        boolean chinese = choice == Choice.CHINESE;
        return switch (state) {
            case LOADING -> chinese ? "\u8BFB\u53D6\u4E2D\u2026" : "Loading…";
            case FAILED -> chinese ? "\u8BFB\u53D6\u5931\u8D25\uFF0C\u70B9\u6309\u91CD\u8BD5" : "Couldn’t load apps. Tap to retry";
            case READY -> chinese ? "\u6CA1\u6709\u53EF\u6253\u5F00\u7684\u5E94\u7528" : "No apps available";
        };
    }
    private TextView label(int row) { return (TextView) ((LinearLayout) t.home().settingsPanel().rowView(row)).getChildAt(0); }
    private TextView value(int row) { return (TextView) ((LinearLayout) t.home().settingsPanel().rowView(row)).getChildAt(1); }

    private void select(Choice choice) {
        t.runOnMainSync(() -> { language().select(choice); t.home().onLanguageChanged(); });
    }

    private void openLanguage(Choice choice) {
        select(choice); t.reset(1, false);
        t.key(KeyEvent.KEYCODE_BUTTON_Y); t.settle(550);
        t.runOnMainSync(() -> t.home().settingsPanel().showGroup(HomeCoordinator.SYSTEM_GROUP));
        t.key(KeyEvent.KEYCODE_BUTTON_A);
        for (int i = 0; i < 3; i++) t.key(KeyEvent.KEYCODE_DPAD_DOWN);
    }

    /** Touch input is deliberately blocked while the bay lifts or pages cross-fade. */
    private void awaitSettingsReady() {
        long deadline = SystemClock.uptimeMillis() + 3000;
        boolean[] ready = new boolean[1];
        do {
            t.waitForIdleSync();
            t.runOnMainSync(() -> {
                SettingsPanel settings = t.home().settingsPanel();
                MachinePanelController panel = t.view().panel();
                ready[0] = panel.isFullyOpen() && !panel.isSwapping() && panel.content() == Content.SETTINGS
                        && settings.isShown() && settings.getAlpha() == 1f && !settings.isLayoutRequested()
                        && settings.rowView(3).isLaidOut();
            });
            if (ready[0]) return;
            SystemClock.sleep(16);
        } while (SystemClock.uptimeMillis() < deadline);
        t.require(false, "Settings must finish its transition and layout before a touch");
    }

    private void configure(Locale locale) {
        t.runOnMainSync(() -> {
            Configuration configuration = new Configuration(t.activity().getResources().getConfiguration());
            configuration.setLocales(new LocaleList(locale));
            t.activity().onConfigurationChanged(configuration);
        });
    }
}
