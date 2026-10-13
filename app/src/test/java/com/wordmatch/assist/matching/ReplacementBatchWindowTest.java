package com.wordmatch.assist.matching;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ReplacementBatchWindowTest {
    @Test public void repeatedGreyFramesDoNotPostponePauseIndefinitely() {
        ReplacementBatchWindow window = new ReplacementBatchWindow();
        assertTrue(window.shouldWait(true, 1000));
        assertTrue(window.shouldWait(true, 1100));
        assertTrue(window.shouldWait(true, 1159));
        assertFalse(window.shouldWait(true, 1160));
        assertFalse(window.shouldWait(true, 2000));
    }
    @Test public void readyPairOrNewBatchStartsWithFreshBudget() {
        ReplacementBatchWindow window = new ReplacementBatchWindow();
        assertTrue(window.shouldWait(true, 1000));
        assertFalse(window.shouldWait(false, 1020));
        assertTrue(window.shouldWait(true, 2000));
        window.reset();
        assertTrue(window.shouldWait(true, 3000));
    }
    @Test public void backwardClockDoesNotCreateAnUnboundedWait() {
        ReplacementBatchWindow window = new ReplacementBatchWindow();
        assertTrue(window.shouldWait(true, 1000));
        assertFalse(window.shouldWait(true, 999));
    }
}
