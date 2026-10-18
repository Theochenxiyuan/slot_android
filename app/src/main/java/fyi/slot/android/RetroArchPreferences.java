package fyi.slot.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;

/** External emulator choices, independent of shelf and cartridge state. */
final class RetroArchPreferences {
    enum Core {
        MGBA("mgba", "mGBA"), VBAM("vbam", "VBA-M"), GPSP("gpsp", "gpSP"),
        GAMBATTE("gambatte", "Gambatte"), SAMEBOY("sameboy", "SameBoy");

        final String id, label;

        Core(String id, String label) { this.id = id; this.label = label; }
    }

    private final SharedPreferences storage;

    RetroArchPreferences(Context context) {
        storage = context.getSharedPreferences("retroarch", Context.MODE_PRIVATE);
    }

    String preferredPackage() { return storage.getString("package", ""); }

    void selectPackage(String packageName) {
        if (!RetroArchLauncher.PACKAGES.contains(packageName)) throw new IllegalArgumentException("Unknown RetroArch package");
        storage.edit().putString("package", packageName).apply();
    }

    static List<Core> cores(Cartridge.Platform platform) {
        return platform == Cartridge.Platform.GBA ? List.of(Core.MGBA, Core.VBAM, Core.GPSP)
                : List.of(Core.MGBA, Core.GAMBATTE, Core.SAMEBOY);
    }

    Core core(Cartridge.Platform platform) {
        String id = storage.getString(key(platform), Core.MGBA.id);
        for (Core core : cores(platform)) if (core.id.equals(id)) return core;
        return Core.MGBA;
    }

    void selectCore(Cartridge.Platform platform, Core core) {
        if (!cores(platform).contains(core)) throw new IllegalArgumentException("Unsupported platform core");
        storage.edit().putString(key(platform), core.id).apply();
    }

    void cycleCore(Cartridge.Platform platform, int direction) {
        List<Core> choices = cores(platform);
        selectCore(platform, choices.get(Math.floorMod(choices.indexOf(core(platform)) + direction, choices.size())));
    }

    RetroArchPreset.Option option(RetroArchPreset preset) {
        String selected = storage.getString("preset_" + preset.id, preset.options.get(0).name());
        for (RetroArchPreset.Option option : preset.options) if (option.name().equals(selected)) return option;
        return preset.options.get(0);
    }

    void select(RetroArchPreset preset, RetroArchPreset.Option option) {
        if (!preset.options.contains(option)) throw new IllegalArgumentException("Unsupported launch preset");
        storage.edit().putString("preset_" + preset.id, option.name()).apply();
    }

    void cycle(RetroArchPreset preset, int direction) {
        List<RetroArchPreset.Option> choices = preset.options;
        select(preset, choices.get(Math.floorMod(choices.indexOf(option(preset)) + direction, choices.size())));
    }

    private static String key(Cartridge.Platform platform) { return "core_" + platform.name(); }
}
