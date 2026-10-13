package com.wordmatch.assist.sentence;

import org.junit.Test;
import static org.junit.Assert.*;

public class SentenceRetryTest {
    @Test public void timedOutQuestionGetsOnlyOneRetryWhilePaused() {
        SentenceRetry r = new SentenceRetry();
        assertTrue(r.begin());
        assertTrue(r.offer(true, true));
        assertTrue(r.take(true));
        assertFalse(r.take(true));
        assertTrue(r.begin());
        assertEquals(2, r.attempts());
        assertFalse(r.offer(true, true));
        assertFalse(r.begin());
    }
    @Test public void timeoutWithoutConfirmedPauseAndOtherFailuresDoNotRetry() {
        SentenceRetry r = new SentenceRetry(); r.begin();
        assertFalse(r.offer(true, false));
        assertFalse(r.take(true));
        assertFalse(r.offer(false, true));
        assertFalse(r.pending());
    }
    @Test public void manualResumeDiscardsOfferedRetry() {
        SentenceRetry r = new SentenceRetry(); r.begin(); r.offer(true, true);
        assertFalse(r.take(false));
        assertFalse(r.pending());
        assertFalse(r.take(true));
    }
    @Test public void questionOrModeResetDiscardsOldRetryAndRestoresBudget() {
        SentenceRetry r = new SentenceRetry(); r.begin(); r.offer(true, true);
        r.reset();
        assertFalse(r.take(true)); assertEquals(0, r.attempts());
        assertTrue(r.begin()); assertTrue(r.offer(true, true));
        assertTrue(r.take(true));
    }
}
