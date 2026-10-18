package fyi.slot.android;

import java.util.LinkedHashMap;
import java.util.Map;

/** Port of slot-store ini.rs/cart_shell.rs and slot-ui shell.rs (GPL-3.0-or-later). */
final class ShellConfig {
    record Choice(String outline, int color, CartridgeRenderer.Finish finish) {}
    static Map<String, String> entries(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("[")) continue;
            int equal = line.indexOf('=');
            if (equal > 0) out.put(LibraryPaths.nfc(line.substring(0, equal).trim()), line.substring(equal + 1).trim());
        }
        return out;
    }
    static Choice parse(String text) {
        String[] words = text.trim().split("\\s+");
        if (words.length != 3 || !words[0].matches("auto|notched|rounded") || !words[1].matches("[a-fA-F0-9]{6}")) return null;
        CartridgeRenderer.Finish finish = switch (words[2]) {
            case "solid" -> CartridgeRenderer.Finish.SOLID;
            case "clear" -> CartridgeRenderer.Finish.TRANSLUCENT;
            case "glitter" -> CartridgeRenderer.Finish.GLITTER;
            default -> null;
        };
        return finish == null ? null : new Choice(words[0], 0xff000000 | Integer.parseInt(words[1], 16), finish);
    }
    static Map<String, Choice> layered(String config, String labels) {
        Map<String, Choice> out = new LinkedHashMap<>();
        entries(config).forEach((key, value) -> { Choice choice = parse(value); if (choice != null) out.put(key, choice); });
        entries(labels).forEach((key, value) -> {
            if (value.equals("auto")) out.remove(key);
            else { Choice choice = parse(value); if (choice != null) out.put(key, choice); }
        });
        return out;
    }
    static Cartridge.Platform shape(RomRecord rom, Choice choice) {
        if (rom.platform == Cartridge.Platform.GBA) return Cartridge.Platform.GBA;
        String outline = choice == null ? "auto" : choice.outline();
        return outline.equals("rounded") || outline.equals("auto") && rom.cgbFlag() == 0xc0
                ? Cartridge.Platform.GBC : Cartridge.Platform.GB;
    }
    static Choice defaults(RomRecord rom) {
        String code = rom.code();
        if (rom.platform == Cartridge.Platform.GBA) {
            String[] codes = {"AXV", "AXP", "BPE", "BPR", "BPG", "U3I", "U32", "U33", "V49", "KYGE", "KYGP", "RZWJ", "RZW"};
            String[] values = {"c2332e clear", "2f5cc0 clear", "249c60 clear", "d85224 solid", "63b044 solid", "d9dbd8 clear", "d9dbd8 clear", "d9dbd8 clear", "a9513b solid", "2f8f4e solid", "2f8f4e solid", "eceee8 solid", "5f6264 clear"};
            for (int i = 0; i < codes.length; i++) if (code.startsWith(codes[i])) return parse("auto " + values[i]);
            return parse("auto " + (code.startsWith("M") ? "c6c6c9 solid" : "35353a solid"));
        }
        String[] codes = {"AAUJ", "AAU", "AAXJ", "AAX", "BYT", "BXT", "KTN", "KKK", "KCE", "VCA", "VPHJ", "BMG"};
        String[] values = {"333031 solid", "b38b3a solid", "333031 solid", "a9aaa7 solid", "86b9bf glitter", "86b9bf glitter", "ec94b4 clear", "ec94b4 clear", "1f9fb6 clear", "f0a95e clear", "e2b413 solid", "333031 solid"};
        for (int i = 0; i < codes.length; i++) if (code.startsWith(codes[i])) return parse("auto " + values[i]);
        if ((rom.head[0x14a] & 255) != 0) {
            String title = rom.headerTitle();
            if (title.equals("POKEMON RED")) return parse("auto c0282c solid");
            if (title.equals("POKEMON BLU")) return parse("auto 2b3a88 solid");
            if (title.equals("POKEMON YEL")) return parse("auto e9a826 solid");
        }
        return parse("auto " + (rom.cgbFlag() == 0xc0 ? "7c7a8a clear"
                : rom.cgbFlag() == 0x80 ? "333031 solid" : "9a978f solid"));
    }
}
