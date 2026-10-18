package fyi.slot.android;

import android.content.Context;
import android.content.SharedPreferences;
import fyi.slot.android.RetroArchPreferences.Core;

/** ROM identity overrides; inheritance is resolved against the current platform choice. */
final class GameCorePreferences {
    private final SharedPreferences storage;
    private final RetroArchPreferences global;

    GameCorePreferences(Context context, RetroArchPreferences global) {
        storage = context.getSharedPreferences("game_cores", Context.MODE_PRIVATE);
        this.global = global;
    }

    Core override(RomRecord rom) {
        String id = storage.getString(rom.id, null);
        for (Core core : RetroArchPreferences.cores(rom.platform)) if (core.id.equals(id)) return core;
        return null;
    }

    Core effective(RomRecord rom) {
        Core selected = override(rom);
        return selected == null ? global.core(rom.platform) : selected;
    }

    Core inherited(RomRecord rom) { return global.core(rom.platform); }

    void select(RomRecord rom, Core core) {
        if (core != null && !RetroArchPreferences.cores(rom.platform).contains(core)) {
            throw new IllegalArgumentException("Unsupported platform core");
        }
        SharedPreferences.Editor editor = storage.edit();
        if (core == null) editor.remove(rom.id); else editor.putString(rom.id, core.id);
        editor.apply();
    }
}
