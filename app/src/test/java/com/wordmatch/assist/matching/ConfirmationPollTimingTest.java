package com.wordmatch.assist.matching;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ConfirmationPollTimingTest {
    @Test public void liveFramesGetTheMainThreadWhileNodesRemainAWatchdog() {
        assertEquals(96L, ConfirmationPollTiming.nodePollDelay(true, 1000, 1016));
        assertEquals(16L, ConfirmationPollTiming.nodePollDelay(false, 1000, 1016));
        assertEquals(16L, ConfirmationPollTiming.nodePollDelay(true, 1000, 1200));
        assertEquals(16L, ConfirmationPollTiming.nodePollDelay(true, 0, 100));
        assertEquals(16L, ConfirmationPollTiming.nodePollDelay(true, 1100, 1000));
    }

    @Test public void liveButUnsuccessfulCaptureCannotDisableScreenshotRecoveryForever() {
        assertFalse(ConfirmationPollTiming.needsScreenshotBackup(true, 1100, 1000, 1120));
        assertTrue(ConfirmationPollTiming.needsScreenshotBackup(true, 1200, 1000, 1240));
        assertTrue(ConfirmationPollTiming.needsScreenshotBackup(false, 1100, 1000, 1120));
        assertTrue(ConfirmationPollTiming.needsScreenshotBackup(true, 900, 1000, 1120));
    }
    @Test public void queuedPairGetsEarlyAcceptanceChecksWithoutProlongedFastPolling() {
        assertEquals(32L, ConfirmationPollTiming.dispatchPollDelay(true,1032,1040,true,1000));
        assertEquals(32L, ConfirmationPollTiming.dispatchPollDelay(true,1150,1159,true,1000));
        assertEquals(96L, ConfirmationPollTiming.dispatchPollDelay(true,1150,1160,true,1000));
    }

    @Test public void idleAndInvalidSubmissionDoNotStartAnAcceptanceWindow() {
        assertEquals(96L, ConfirmationPollTiming.dispatchPollDelay(true,1032,1040,false,1000));
        assertEquals(96L, ConfirmationPollTiming.dispatchPollDelay(true,1032,1040,true,0));
        assertEquals(96L, ConfirmationPollTiming.dispatchPollDelay(true,1032,1040,true,1100));
    }

    @Test public void missingCaptureRetainsItsFasterNodeFallback() {
        assertEquals(16L, ConfirmationPollTiming.dispatchPollDelay(false,1032,1040,true,1000));
    }

}
