package com.wordmatch.assist.matching;

import com.wordmatch.assist.model.MatchPair;
import com.wordmatch.assist.model.WordBox;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.wordmatch.assist.matching.CardVisualState.State.*;

public final class DeferredPairConfirmationTest {
    private static WordBox word(String text, int x, int y) { return new WordBox(text, x, y, x + 40, y + 24); }
    private static final WordBox LEFT = word("接受", 180, 520), RIGHT = word("accept", 580, 520);
    private static final WordBox OTHER_LEFT = word("讲座", 180, 620), OTHER_RIGHT = word("lecture", 580, 620);
    private static DeferredPairConfirmation entry() {
        return new DeferredPairConfirmation(new MatchPair(LEFT, RIGHT, 1.0, 1),
                new WordBox("", 20, 500, 380, 565), new WordBox("", 420, 500, 780, 565), 800, 1280, 1000);
    }
    @Test public void dispatchAndDisabledNodesAloneNeverCountAsSuccess() {
        DeferredPairConfirmation entry = entry();
        List<WordBox> board = Arrays.asList(LEFT, RIGHT, OTHER_LEFT, OTHER_RIGHT);
        for (long now = 1100; now < 3000; now += 100) {
            assertFalse(entry.observeNodes(board, true, now));
            assertFalse(entry.observeColors(UNKNOWN, UNKNOWN, false, false, true, true, false, now));
        }
        assertTrue(entry.contains(LEFT));
        assertFalse(entry.contains(word("接受", 180, 620)));
        assertFalse(entry.contains(word("another", 180, 520)));
    }
    @Test public void independentPairDisappearanceCannotConfirmAnotherSubmittedPair() {
        DeferredPairConfirmation first = entry();
        DeferredPairConfirmation second = new DeferredPairConfirmation(new MatchPair(OTHER_LEFT, OTHER_RIGHT, 1.0, 1),
                null, null, 800, 1280, 1050);
        List<WordBox> remaining = Arrays.asList(OTHER_LEFT, OTHER_RIGHT);
        assertFalse(first.observeNodes(remaining, true, 1200));
        assertTrue(first.observeNodes(remaining, true, 1280));
        assertFalse(second.observeNodes(remaining, true, 1200));
        assertFalse(second.observeNodes(remaining, true, 1280));
    }
    @Test public void pauseAndEmptyTransientBoardCannotImplySuccess() {
        DeferredPairConfirmation entry = entry();
        assertFalse(entry.observeNodes(Arrays.asList(OTHER_LEFT, OTHER_RIGHT), true, 1100));
        entry.resetObservations(1150);
        assertFalse(entry.observeNodes(Collections.emptyList(), false, 1300));
        assertFalse(entry.observeNodes(Collections.emptyList(), true, 1400));
        assertFalse(entry.observeNodes(Arrays.asList(LEFT, RIGHT), true, 1600));
    }
    @Test public void liveGreenFramesNeedFreshConsecutiveEvidence() {
        DeferredPairConfirmation entry = entry();
        assertFalse(entry.observeColors(GREEN, GREEN, false, false, true, true, false, 1100));
        assertFalse(entry.observeColors(GREEN, GREEN, false, false, true, true, false, 1132));
        assertTrue(entry.observeColors(GREEN, GREEN, false, false, true, true, false, 1164));
        entry.resetObservations(1200);
        assertFalse(entry.observeColors(GREEN, GREEN, false, false, true, true, false, 1300));
    }
    @Test public void greyTailNeedsRepeatedPixelsDisabledTargetsAndCoverageOrActivePeer() {
        DeferredPairConfirmation entry = entry();
        assertFalse(entry.observeColors(FADED, FADED, false, false, false, true, true, 1160));
        assertFalse(entry.observeColors(FADED, FADED, false, false, false, true, true, 1500));
        assertFalse(entry.observeColors(FADED, FADED, false, false, true, true, true, 1600));
        assertTrue(entry.observeColors(FADED, FADED, false, false, true, true, true, 1950));
    }
    @Test public void stillActiveMixedBlankSelectedOrObscuredTargetsCannotUseGreyEvidence() {
        for (int invalid = 0; invalid < 4; invalid++) {
            DeferredPairConfirmation entry = entry();
            assertFalse(entry.observeColors(FADED, FADED, false, false, true, true, true, 1200));
            assertFalse(entry.observeColors(invalid == 0 ? SELECTED : FADED, invalid == 1 ? UNKNOWN : FADED,
                    invalid == 2, false, true, invalid != 3, true, 1550));
            assertFalse(entry.observeColors(FADED, FADED, false, false, true, true, true, 1900));
        }
    }
    @Test public void oneScreenshotCanConfirmSeveralPairsButDoesNotCreditAWrongOrUnreadablePair() {
        List<DeferredPairConfirmation> batch = new ArrayList<>();
        for (int i = 0; i < 5; i++) batch.add(entry());
        int confirmed = 0;
        for (int i = 0; i < batch.size(); i++) {
            CardVisualState.State state = i == 3 ? UNKNOWN : GREEN;
            if (batch.get(i).observeColors(state, state, false, false, true, true, true, 1500)) confirmed++;
        }
        assertEquals(4, confirmed);
        assertFalse(entry().observeColors(GREEN, GREEN, false, false, true, false, true, 1500));
        assertFalse(entry().observeColors(GREEN, GREEN, false, false, true, true, true, 900));
    }
    @Test public void asymmetricReplacementAlsoSettlesTheBackgroundConfirmationLane() {
        DeferredPairConfirmation entry = entry();
        WordBox newLeft = word("new", 180, 520);
        List<WordBox> board = Arrays.asList(newLeft, RIGHT, OTHER_LEFT, OTHER_RIGHT);
        assertFalse(entry.observeReplacement(board, entry.firstCard, entry.secondCard,
                false, true, READY, FADED, true, 1200));
        assertTrue(entry.observeReplacement(board, entry.firstCard, entry.secondCard,
                false, true, READY, FADED, true, 1296));
        entry.resetObservations(1300);
        assertFalse(entry.observeReplacement(board, entry.firstCard, entry.secondCard,
                false, true, READY, FADED, true, 1400));
    }

}
