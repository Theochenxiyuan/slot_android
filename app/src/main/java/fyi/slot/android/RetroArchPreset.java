package fyi.slot.android;

import java.util.List;

/** The small set of launch overrides owned by Slot. First choice is the default. */
enum RetroArchPreset {
    VIDEO("video", R.string.retroarch_video, List.of("video_driver"),
            Option.VULKAN, Option.GLCORE, Option.GL, Option.FOLLOW),
    CLOSE_CONTENT("close_content", R.string.retroarch_close_content, List.of("quit_on_close_content"),
            Option.QUIT_ON, Option.FOLLOW),
    MENU_COMBO("menu_combo", R.string.retroarch_menu_combo, List.of("input_menu_toggle_gamepad_combo"),
            Option.START_SELECT, Option.STICKS, Option.SHOULDERS_START_SELECT, Option.HOLD_START, Option.FOLLOW),
    QUIT_COMBO("quit_combo", R.string.retroarch_quit_combo, List.of("input_quit_gamepad_combo"),
            Option.FOLLOW, Option.SHOULDERS_START_SELECT, Option.STICKS, Option.START_SELECT, Option.HOLD_START),
    HIDE_OVERLAY("hide_overlay", R.string.retroarch_hide_overlay, List.of("input_overlay_hide_when_gamepad_connected"),
            Option.ON, Option.FOLLOW),
    AUTO_STATE("auto_state", R.string.retroarch_auto_state, List.of("savestate_auto_save", "savestate_auto_load"),
            Option.FOLLOW, Option.ON, Option.OFF);

    enum Option {
        FOLLOW(null, R.string.retroarch_follow),
        VULKAN("vulkan", "Vulkan"), GLCORE("glcore", "GLCore"), GL("gl", "OpenGL"),
        QUIT_ON("1", R.string.enabled), ON("true", R.string.enabled), OFF("false", R.string.disabled),
        START_SELECT("4", "Start + Select"), STICKS("2", "L3 + R3"),
        SHOULDERS_START_SELECT("3", "L1 + R1 + Start + Select"), HOLD_START("7", R.string.retroarch_hold_start);

        final String value;
        private final int label;
        private final String text;

        Option(String value, int label) { this.value = value; this.label = label; this.text = null; }
        Option(String value, String text) { this.value = value; this.text = text; this.label = 0; }

        String label(AppLanguage language) { return text == null ? language.text(label) : text; }
    }

    final String id;
    final int label;
    final List<String> keys;
    final List<Option> options;

    RetroArchPreset(String id, int label, List<String> keys, Option... options) {
        this.id = id; this.label = label; this.keys = keys; this.options = List.of(options);
    }
}
