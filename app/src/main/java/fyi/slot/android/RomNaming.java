package fyi.slot.android;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RomNaming {
    private static final Pattern TAG = Pattern.compile("\\([^)]*\\)|\\[[^]]*\\]");
    static String stem(String filename) {
        int dot = filename.lastIndexOf('.');
        return LibraryPaths.nfc(dot < 0 ? filename : filename.substring(0, dot));
    }
    static String title(String filename) {
        String stem = stem(filename);
        String title = TAG.matcher(stem).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return title.isEmpty() ? stem : title;
    }
    static String tags(String filename) {
        Matcher matcher = TAG.matcher(stem(filename));
        StringBuilder out = new StringBuilder();
        while (matcher.find()) { if (out.length() > 0) out.append(' '); out.append(matcher.group()); }
        return out.toString();
    }
    static int compare(String a, String b) {
        a = LibraryPaths.nfc(a).toLowerCase(Locale.ROOT); b = LibraryPaths.nfc(b).toLowerCase(Locale.ROOT);
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (x >= '0' && x <= '9' && y >= '0' && y <= '9') {
                int ai = i, bj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String an = a.substring(ai, i).replaceFirst("^0+(?!$)", "");
                String bn = b.substring(bj, j).replaceFirst("^0+(?!$)", "");
                int result = Integer.compare(an.length(), bn.length());
                if (result == 0) result = an.compareTo(bn);
                if (result != 0) return result;
            } else { if (x != y) return Character.compare(x, y); i++; j++; }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
