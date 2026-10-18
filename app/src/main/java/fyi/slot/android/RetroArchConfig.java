package fyi.slot.android;

import android.annotation.SuppressLint;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** A disposable include wrapper; the user's RetroArch configuration is never modified. */
final class RetroArchConfig {
    static final String FILENAME = ".slot-retroarch.cfg";
    private final RetroArchPreferences preferences;

    RetroArchConfig(RetroArchPreferences preferences) { this.preferences = preferences; }

    @SuppressLint("SdCardPath")
    static String originalPath(String packageName) {
        if (!RetroArchLauncher.PACKAGES.contains(packageName)) throw new IllegalArgumentException("Unknown RetroArch package");
        return "/storage/emulated/0/Android/data/" + packageName + "/files/retroarch.cfg";
    }

    String content(String packageName) {
        String original = originalPath(packageName);
        StringBuilder text = new StringBuilder();
        RetroArchPreset.Option menu = preferences.option(RetroArchPreset.MENU_COMBO);
        for (RetroArchPreset preset : RetroArchPreset.values()) {
            RetroArchPreset.Option option = preferences.option(preset);
            if (option == RetroArchPreset.Option.FOLLOW) continue;
            if (preset == RetroArchPreset.QUIT_COMBO && option == menu) continue;
            for (String key : preset.keys) text.append(key).append(" = \"").append(option.value).append("\"\n");
        }
        if (text.length() == 0) return "";
        return text.append("config_save_on_exit = \"false\"\n#include \"").append(original).append("\"\n").toString();
    }

    String resolve(RomRecord rom, String packageName) {
        String original = originalPath(packageName);
        String content = content(packageName);
        if (content.isEmpty()) return original;
        Path temporary = null;
        try {
            File root = new File(rom.root);
            if (!root.isDirectory()) return original;
            File base = LibraryPaths.assetsRoot(root);
            if (!base.isDirectory() && !base.mkdirs()) base = root;
            File file = new File(base, FILENAME);
            Path target = file.toPath();
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (Files.isRegularFile(target) && Files.size(target) == bytes.length
                    && Arrays.equals(Files.readAllBytes(target), bytes)) return file.getAbsolutePath();
            temporary = Files.createTempFile(base.toPath(), ".slot-retroarch-", ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            return file.getAbsolutePath();
        } catch (IOException | SecurityException error) {
            Log.w("SlotRetroArch", "Using original configuration: launch wrapper unavailable", error);
            return original;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException | SecurityException error) { Log.w("SlotRetroArch", "Could not remove temporary configuration", error); }
            }
        }
    }
}
