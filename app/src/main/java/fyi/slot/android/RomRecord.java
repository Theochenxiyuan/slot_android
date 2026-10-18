package fyi.slot.android;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class RomRecord {
    final String root, path, filename, id, title, tags;
    final Cartridge.Platform platform;
    final long size, modified;
    final byte[] head;
    final Long crc;
    final boolean hidden;

    RomRecord(String root, String path, Cartridge.Platform platform, long size, long modified,
              byte[] head, Long crc, boolean hidden) {
        this.root = root; this.path = path; this.platform = platform; this.size = size; this.modified = modified;
        this.head = head.clone(); this.crc = crc; this.hidden = hidden;
        filename = new File(path).getName();
        id = LibraryPaths.identity(new File(root), platform, filename);
        title = RomNaming.title(filename); tags = RomNaming.tags(filename);
    }
    File file() { return new File(root, path); }
    int cgbFlag() { return head[0x143] & 255; }
    String headerTitle() { return ascii(platform == Cartridge.Platform.GBA ? 0xa0 : 0x134,
            platform == Cartridge.Platform.GBA ? 12 : 11); }
    String code() {
        if (platform == Cartridge.Platform.GBA) return ascii(0xac, 4);
        String code = ascii(0x13f, 4);
        return code.matches("[A-Z0-9]{4}") ? code : "";
    }
    private String ascii(int start, int length) {
        int end = start;
        while (end < start + length && head[end] >= 32 && head[end] < 127) end++;
        return new String(Arrays.copyOfRange(head, start, end), StandardCharsets.US_ASCII).trim();
    }
}
