package com.wordmatch.assist.overlay;

import com.wordmatch.assist.model.MatchPair;
import com.wordmatch.assist.model.WordBox;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public final class StablePairHighlightsTest {
    private final MatchPair a = pair("苹果", "apple", 300);
    private final MatchPair b = pair("牛奶", "milk", 500);
    private final MatchPair c = pair("面包", "bread", 700);

    @Test public void orderAndIncomingLabelsNeverRenumberExistingPairs() {
        StablePairHighlights labels = new StablePairHighlights();
        labels.update(words(a,b,c), Arrays.asList(a,b,c), true);
        List<StablePairHighlights.Tile> after = labels.update(words(c,b,a), Arrays.asList(c,b,a), true);
        assertLabel(after, a, 1);
        assertLabel(after, b, 2);
        assertLabel(after, c, 3);
    }

    @Test public void shrinkingClickQueueDoesNotHideLingeringGreenCardsOrOtherPairs() {
        StablePairHighlights labels = new StablePairHighlights();
        labels.update(words(a,b,c), Arrays.asList(a,b,c), true);
        List<StablePairHighlights.Tile> after = labels.update(words(a,b,c), Collections.singletonList(b), true);
        assertEquals(6, after.size());
        assertLabel(after, a, 1);
        assertLabel(after, c, 3);
        assertEquals(6, labels.update(words(a,b,c), Collections.emptyList(), true).size());
    }

    @Test public void replacementReusesOnlyFreedLabelAndKeepsOtherColors() {
        StablePairHighlights labels = new StablePairHighlights();
        labels.update(words(a,b,c), Arrays.asList(a,b,c), true);
        MatchPair replacement = pair("咖啡", "coffee", 300);
        List<StablePairHighlights.Tile> after = labels.update(words(replacement,b,c),
                Arrays.asList(replacement,b,c), true);
        assertLabel(after, replacement, 1);
        assertLabel(after, b, 2);
        assertLabel(after, c, 3);
    }

    @Test public void oneSurvivingCardKeepsLabelAndMissingCardHasNoGhostMarker() {
        StablePairHighlights labels = new StablePairHighlights();
        labels.update(words(a,b), Arrays.asList(a,b), true);
        List<WordBox> remaining = words(b);
        remaining.add(a.getSecond());
        List<StablePairHighlights.Tile> after = labels.update(remaining, Collections.singletonList(b), true);
        assertEquals(3, after.size());
        assertEquals(1, label(after, a.getSecond()));
        assertEquals(-1, label(after, a.getFirst()));
        assertLabel(after, b, 2);
    }

    @Test public void duplicateWordsAtDifferentPositionsKeepSeparatePairLabels() {
        StablePairHighlights labels = new StablePairHighlights();
        MatchPair duplicate = pair("苹果", "apple", 600);
        labels.update(words(a,duplicate), Arrays.asList(a,duplicate), true);
        List<StablePairHighlights.Tile> after = labels.update(words(a), Collections.singletonList(a), true);
        assertEquals(2, after.size());
        assertLabel(after, a, 1);
    }

    @Test public void pauseEmptyAndPartialFramesPreserveLabelsUntilExplicitReset() {
        StablePairHighlights labels = new StablePairHighlights();
        labels.update(words(a,b,c), Arrays.asList(a,b,c), true);
        labels.update(Collections.emptyList(), Collections.emptyList(), true);
        labels.update(words(c), Collections.singletonList(c), false);
        List<StablePairHighlights.Tile> restored = labels.update(words(c,b,a), Arrays.asList(c,b,a), true);
        assertLabel(restored, a, 1);
        assertLabel(restored, c, 3);
        labels.clear();
        assertLabel(labels.update(words(c), Collections.singletonList(c), true), c, 1);
    }

    @Test public void uniqueWordsMovingRowsKeepTheirOriginalLabels() {
        StablePairHighlights labels = new StablePairHighlights();
        labels.update(words(a,b), Arrays.asList(a,b), true);
        MatchPair movedA = pair("苹果", "apple", 500);
        MatchPair movedB = pair("牛奶", "milk", 300);
        List<StablePairHighlights.Tile> after = labels.update(words(movedB,movedA), Arrays.asList(movedB,movedA), true);
        assertLabel(after, movedA, 1);
        assertLabel(after, movedB, 2);
    }

    @Test public void incomingDuplicatesDoNotInheritMissingGreyCardsLabel() {
        StablePairHighlights labels = new StablePairHighlights();
        labels.update(words(a), Collections.singletonList(a), true);
        MatchPair newer = pair("苹果", "apple", 600);
        MatchPair newest = pair("苹果", "apple", 900);
        List<WordBox> board = words(newer, newest);
        board.add(a.getSecond()); // Only the old grey English card survives.
        List<StablePairHighlights.Tile> after = labels.update(board, Arrays.asList(newer, newest), true);
        assertEquals(1, label(after, a.getSecond()));
        assertLabel(after, newer, 2);
        assertLabel(after, newest, 3);
    }

    private static MatchPair pair(String left, String right, int top) {
        return new MatchPair(new WordBox(left, 150, top, 250, top + 40),
                new WordBox(right, 650, top, 750, top + 40), 1.0, 1);
    }
    private static List<WordBox> words(MatchPair... pairs) {
        List<WordBox> words = new ArrayList<>();
        for (MatchPair pair : pairs) { words.add(pair.getFirst()); words.add(pair.getSecond()); }
        return words;
    }
    private static int label(List<StablePairHighlights.Tile> tiles, WordBox word) {
        for (StablePairHighlights.Tile tile : tiles) if (tile.word.equals(word)) return tile.label;
        return -1;
    }
    private static void assertLabel(List<StablePairHighlights.Tile> tiles, MatchPair pair, int expected) {
        assertEquals(expected, label(tiles, pair.getFirst()));
        assertEquals(expected, label(tiles, pair.getSecond()));
    }
}
