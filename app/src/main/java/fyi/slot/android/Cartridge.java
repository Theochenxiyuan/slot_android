package fyi.slot.android;

import java.io.File;

/** Immutable cartridge identity and presentation shared by all shelves. */
final class Cartridge {
    enum Platform {
        GBA("GAME BOY ADVANCE", "AGB", 360f, 212f),
        GB("GAME BOY", "DMG", 312f, 337f),
        GBC("GAME BOY COLOR", "CGB", 312f, 337f);

        private static final float DISPLAY_SCALE = .92f;
        final String title, code;
        final float width, height;

        Platform(String title, String code, float width, float height) {
            this.title = title; this.code = code;
            this.width = width * DISPLAY_SCALE; this.height = height * DISPLAY_SCALE;
        }
    }

    final String id, title, number;
    final int plastic, accent, scene;
    final Platform platform;
    final CartridgeRenderer.Finish finish;
    final Platform shape;
    final RomRecord rom;
    final File label;
    /** Box art shown on the seated screen; it never changes how the cartridge itself is drawn. */
    final File boxart;
    final String appearanceKey;

    Cartridge(String id, String title, String number, int plastic, int accent, int scene,
              Platform platform, CartridgeRenderer.Finish finish) {
        this.id = id; this.title = title; this.number = number;
        this.plastic = plastic; this.accent = accent; this.scene = scene;
        this.platform = platform; this.finish = finish;
        shape = platform; rom = null; label = null; boxart = null; appearanceKey = id;
    }

    Cartridge(RomRecord rom, ShellConfig.Choice shell, File label) { this(rom, shell, label, null); }

    Cartridge(RomRecord rom, ShellConfig.Choice shell, File label, File boxart) {
        this.rom = rom; this.label = label; this.boxart = boxart;
        id = rom.id; title = rom.title; number = ""; scene = -1; accent = 0xffe0e0e1;
        platform = rom.platform; shape = ShellConfig.shape(rom, shell); plastic = shell.color(); finish = shell.finish();
        appearanceKey = id + "|" + rom.size + "|" + rom.modified + "|" + shape + "|" + plastic + "|" + finish
                + "|" + (label == null ? "" : label.getAbsolutePath() + ":" + label.lastModified() + ":" + label.length());
    }
}
