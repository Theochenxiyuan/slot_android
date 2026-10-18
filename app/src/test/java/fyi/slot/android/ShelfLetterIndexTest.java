package fyi.slot.android;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertEquals;

public final class ShelfLetterIndexTest {
    private final ShelfLetterIndex letters = new ShelfLetterIndex(List.of(
            "1942", "2 Games", "Advance", "Adventure", "Bomberman", "Bubble", "Zelda"));

    @Test public void nextSkipsTheRestOfTheCurrentGroup() {
        assertEquals(2, letters.jumpDelta(0, 1));
        assertEquals(2, letters.jumpDelta(2, 1));
        assertEquals(1, letters.jumpDelta(3, 1));
    }

    @Test public void previousReturnsToCurrentGroupStartFirst() {
        assertEquals(-1, letters.jumpDelta(3, -1));
        assertEquals(-2, letters.jumpDelta(2, -1));
        assertEquals(-2, letters.jumpDelta(4, -1));
    }

    @Test public void groupsWrapInBothDirections() {
        assertEquals(1, letters.jumpDelta(6, 1));
        assertEquals(-1, letters.jumpDelta(0, -1));
    }

    @Test public void emptySingleAndSameInitialAreInert() {
        for (List<String> titles : List.of(List.<String>of(), List.of("Alpha"), List.of("Alpha", "advance"))) {
            ShelfLetterIndex index = new ShelfLetterIndex(titles);
            assertEquals(0, index.jumpDelta(0, 1));
            assertEquals(0, index.jumpDelta(0, -1));
        }
    }

    @Test public void initialsIgnoreCaseAndLeadingWhitespace() {
        assertEquals('A', ShelfLetterIndex.initial(" \tadvance"));
        assertEquals('A', ShelfLetterIndex.initial("\u00a0Alpha"));
        assertEquals('É', ShelfLetterIndex.initial("éclair"));
        assertEquals('S', ShelfLetterIndex.initial("ß game"));
    }

    @Test public void digitsSymbolsAndEmptyNamesShareHash() {
        for (String title : List.of("123", "!Game", "", "   ", "🎮 Game"))
            assertEquals('#', ShelfLetterIndex.initial(title));
    }

    @Test public void unicodeUsesWholeFirstCodePoint() {
        assertEquals('\u5B9D', ShelfLetterIndex.initial("\u5B9D\u53EF\u68A6"));
        assertEquals(0x10400, ShelfLetterIndex.initial("\uD801\uDC28 game"));
    }

    @Test public void sameInitialAcrossTheSeamIsOneGroup() {
        ShelfLetterIndex index = new ShelfLetterIndex(List.of("Alpha", "Beta", "Bravo", "Advance"));
        assertEquals(-1, index.jumpDelta(0, -1));
        assertEquals(-2, index.jumpDelta(3, -1));
        assertEquals(2, index.jumpDelta(3, 1));
    }

    @Test public void longGroupsAndContinuousPositionsStayDirectional() {
        List<String> titles = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) titles.add(i < 800 ? "Alpha " + i : "Beta " + i);
        ShelfLetterIndex index = new ShelfLetterIndex(titles);
        assertEquals(800, index.jumpDelta(0, 1));
        assertEquals(-200, index.jumpDelta(0, -1));
        assertEquals(200, index.jumpDelta(1800, 1));
        assertEquals(-799, index.jumpDelta(-201, -1));
    }
}
