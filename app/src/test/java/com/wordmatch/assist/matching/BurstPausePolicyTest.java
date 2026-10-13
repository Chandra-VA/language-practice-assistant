package com.wordmatch.assist.matching;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BurstPausePolicyTest {
    @Test
    public void pausesWhenBurstConsumedWholeBoard() {
        assertTrue(BurstPausePolicy.shouldPauseForReplacementWords(10, 5));
        assertTrue(BurstPausePolicy.shouldPauseForReplacementWords(6, 3));
        assertTrue(BurstPausePolicy.shouldPauseForReplacementWords(5, 3));
    }

    @Test
    public void pausesWhenOriginalBatchDrainsEvenWithGreyOrUnmatchedCards() {
        assertTrue(BurstPausePolicy.shouldPauseForReplacementWords(10, 2));
        assertTrue(BurstPausePolicy.shouldPauseForReplacementWords(10, 4));
        assertFalse(BurstPausePolicy.shouldPauseForReplacementWords(0, 2));
        assertFalse(BurstPausePolicy.shouldPauseForReplacementWords(10, 0));
    }

    @Test
    public void neverPausesAfterTheFinalPair() {
        assertFalse(BurstPausePolicy.shouldPauseForReplacementWords(2, 1));
        assertFalse(BurstPausePolicy.shouldPauseForReplacementWords(2, 2));
    }
    @Test
    public void readyReplacementBatchDoesNotPayAnotherPause() {
        assertFalse(BurstPausePolicy.shouldPauseForReplacementWords(10, 2, true));
        assertFalse(BurstPausePolicy.shouldPauseForReplacementWords(10, 5, true));
    }

    @Test
    public void greyOrUnknownReplacementCardsStillNeedAPause() {
        assertTrue(BurstPausePolicy.shouldPauseForReplacementWords(10, 2, false));
        assertFalse(BurstPausePolicy.shouldPauseForReplacementWords(2, 1, false));
        assertFalse(BurstPausePolicy.shouldPauseForReplacementWords(10, 0, false));
    }
}
