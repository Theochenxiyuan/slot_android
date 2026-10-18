package fyi.slot.android;

import android.content.Context;
import android.content.SharedPreferences;

/** User-selectable interaction and appearance choices, separate from the cartridge session. */
final class InteractionPreferences {
    enum Wallpaper {
        NONE("none", R.string.wallpaper_none),
        CUSTOM("custom", R.string.wallpaper_custom);

        final String key;
        final int label;
        Wallpaper(String key, int label) { this.key = key; this.label = label; }

        static Wallpaper fromKey(String key) {
            for (Wallpaper item : values()) if (item.key.equals(key)) return item;
            return NONE;
        }
    }

    enum WallpaperTint {
        OFF("off", 0f),
        LOW("low", .25f),
        MEDIUM("medium", .45f),
        HIGH("high", .65f);

        final String key;
        final float alpha;
        WallpaperTint(String key, float alpha) { this.key = key; this.alpha = alpha; }

        static WallpaperTint fromKey(String key) {
            for (WallpaperTint item : values()) if (item.key.equals(key)) return item;
            return OFF;
        }
    }

    enum ButtonPrompts {
        XBOX("xbox", "Xbox", "A", "B", "X", "Y", "LB", "RB"),
        NINTENDO("nintendo", "Nintendo", "B", "A", "Y", "X", "L", "R"),
        PLAYSTATION("playstation", "PlayStation", "✕", "○", "□", "△", "L1", "R1");

        final String key, label;
        final String south, east, west, north, leftShoulder, rightShoulder;

        ButtonPrompts(String key, String label, String south, String east, String west, String north,
                      String leftShoulder, String rightShoulder) {
            this.key = key;
            this.label = label;
            this.south = south;
            this.east = east;
            this.west = west;
            this.north = north;
            this.leftShoulder = leftShoulder;
            this.rightShoulder = rightShoulder;
        }

        static ButtonPrompts fromKey(String key) {
            for (ButtonPrompts item : values()) if (item.key.equals(key)) return item;
            return XBOX;
        }
    }

    private static volatile boolean swapConfirmBackActive;

    private final SharedPreferences storage;
    boolean swipeToInsert;
    boolean haptics;
    boolean sound;
    boolean autoLaunchOnInsert;
    ButtonPrompts buttonPrompts;
    boolean swapConfirmBack;
    boolean lightTheme;
    Wallpaper wallpaper;
    WallpaperTint wallpaperTint;
    boolean showTitles;

    InteractionPreferences(Context context) {
        storage = context.getSharedPreferences("interaction", Context.MODE_PRIVATE);
        swipeToInsert = storage.getBoolean("swipe_to_insert", true);
        haptics = storage.getBoolean("haptics", true);
        sound = storage.getBoolean("sound", true);
        autoLaunchOnInsert = storage.getBoolean("auto_launch_on_insert", true);
        buttonPrompts = ButtonPrompts.fromKey(storage.getString("button_prompts", ButtonPrompts.XBOX.key));
        swapConfirmBack = storage.getBoolean("swap_confirm_back", false);
        lightTheme = storage.getBoolean("light_theme", false);
        Wallpaper saved = Wallpaper.fromKey(storage.getString("wallpaper", Wallpaper.NONE.key));
        wallpaper = saved == Wallpaper.CUSTOM && BackdropRenderer.hasCustomWallpaper(context)
                ? Wallpaper.CUSTOM : Wallpaper.NONE;
        wallpaperTint = WallpaperTint.fromKey(storage.getString("wallpaper_tint", WallpaperTint.OFF.key));
        showTitles = storage.getBoolean("show_titles", false);
        swapConfirmBackActive = swapConfirmBack;
        PanelStyle.setLight(lightTheme);
    }

    static boolean isSwappedConfirmBack() {
        return swapConfirmBackActive;
    }

    static int primaryConfirmKey() {
        return swapConfirmBackActive ? android.view.KeyEvent.KEYCODE_BUTTON_B : android.view.KeyEvent.KEYCODE_BUTTON_A;
    }

    static int primaryBackKey() {
        return swapConfirmBackActive ? android.view.KeyEvent.KEYCODE_BUTTON_A : android.view.KeyEvent.KEYCODE_BUTTON_B;
    }

    String confirmBadge() {
        return swapConfirmBack ? buttonPrompts.east : buttonPrompts.south;
    }

    String backBadge() {
        return swapConfirmBack ? buttonPrompts.south : buttonPrompts.east;
    }

    String appsBadge() {
        return buttonPrompts.west;
    }

    String settingsBadge() {
        return buttonPrompts.north;
    }

    String leftShoulderBadge() {
        return buttonPrompts.leftShoulder;
    }

    String rightShoulderBadge() {
        return buttonPrompts.rightShoulder;
    }

    void cycleButtonPrompts(int direction) {
        int step = direction < 0 ? -1 : 1;
        ButtonPrompts[] items = ButtonPrompts.values();
        buttonPrompts = items[Math.floorMod(buttonPrompts.ordinal() + step, items.length)];
        save();
    }

    void toggleSwapConfirmBack() {
        swapConfirmBack = !swapConfirmBack;
        save();
    }

    void toggle(int row) {
        switch (row) {
            case 0 -> swipeToInsert = !swipeToInsert;
            case 1 -> haptics = !haptics;
            case 2 -> sound = !sound;
            case 3 -> autoLaunchOnInsert = !autoLaunchOnInsert;
            default -> throw new IllegalArgumentException("Unknown controls row: " + row);
        }
        save();
    }

    void toggleTheme() {
        lightTheme = !lightTheme;
        PanelStyle.setLight(lightTheme);
        save();
    }

    void toggleWallpaper() {
        wallpaper = wallpaper == Wallpaper.CUSTOM ? Wallpaper.NONE : Wallpaper.CUSTOM;
        save();
    }

    void cycleWallpaperTint(int direction) {
        int step = direction < 0 ? -1 : 1;
        WallpaperTint[] items = WallpaperTint.values();
        wallpaperTint = items[Math.floorMod(wallpaperTint.ordinal() + step, items.length)];
        save();
    }

    void toggleShowTitles() {
        showTitles = !showTitles;
        save();
    }

    String wallpaperTintLabel(AppLanguage language) {
        return switch (wallpaperTint) {
            case OFF -> language.text(R.string.disabled);
            case LOW -> "25%";
            case MEDIUM -> "45%";
            case HIGH -> "65%";
        };
    }

    void save() {
        swapConfirmBackActive = swapConfirmBack;
        PanelStyle.setLight(lightTheme);
        storage.edit()
                .putBoolean("swipe_to_insert", swipeToInsert)
                .putBoolean("haptics", haptics)
                .putBoolean("sound", sound)
                .putBoolean("auto_launch_on_insert", autoLaunchOnInsert)
                .putString("button_prompts", buttonPrompts.key)
                .putBoolean("swap_confirm_back", swapConfirmBack)
                .putBoolean("light_theme", lightTheme)
                .putString("wallpaper", wallpaper.key)
                .putString("wallpaper_tint", wallpaperTint.key)
                .putBoolean("show_titles", showTitles)
                .apply();
    }
}
