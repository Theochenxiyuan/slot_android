package fyi.slot.android;

import java.util.List;
import java.util.Locale;

/** Circular initial groups in the shelf's existing display order. */
final class ShelfLetterIndex {
    private final int[] initials;

    ShelfLetterIndex(List<String> titles) {
        initials = titles.stream().mapToInt(ShelfLetterIndex::initial).toArray();
    }

    static int initial(String title) {
        int first = title.codePoints().filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))
                .findFirst().orElse('#');
        if (!Character.isAlphabetic(first)) return '#';
        return new String(Character.toChars(first)).toUpperCase(Locale.ROOT).codePointAt(0);
    }

    int jumpDelta(int from, int direction) {
        int count = initials.length;
        if (count < 2 || direction == 0) return 0;
        from = Math.floorMod(from, count);
        int here = initials[from], at = from;
        if (direction > 0) {
            for (int distance = 1; distance < count; distance++) {
                at = (at + 1) % count;
                if (initials[at] != here) return distance;
            }
            return 0;
        }
        int distance = 0;
        while (distance < count - 1 && initials[Math.floorMod(at - 1, count)] == here) {
            at = Math.floorMod(at - 1, count); distance++;
        }
        if (distance == count - 1) return 0;
        if (distance > 0) return -distance;
        at = Math.floorMod(at - 1, count); distance = 1;
        int previous = initials[at];
        while (distance < count - 1 && initials[Math.floorMod(at - 1, count)] == previous) {
            at = Math.floorMod(at - 1, count); distance++;
        }
        return -distance;
    }
}
