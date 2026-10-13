package com.wordmatch.assist.matching;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.wordmatch.assist.matching.SlowProgressPause.Action.*;

public final class SlowProgressPauseTest {
    @Test public void pausesAnUnconfirmedPairWellBeforeTheSixSecondTimeout() {
        SlowProgressPause policy = new SlowProgressPause();
        policy.start(1000);
        assertEquals(NONE, policy.action(1479));
        assertEquals(RECOVER, policy.action(1480));
    }

    @Test public void scansClicksAndAnimationsDoNotPretendToBeConfirmedProgress() {
        SlowProgressPause policy = new SlowProgressPause();
        policy.start(1000);
        policy.start(1200);
        policy.start(1400);
        assertEquals(RECOVER, policy.action(1480));
    }

    @Test public void fastConfirmedPairsRemainUninterruptedAndDetectLaterSlowdownEarlier() {
        SlowProgressPause policy = new SlowProgressPause();
        policy.start(1000);
        for (long now = 1100; now <= 2000; now += 100) {
            assertEquals(NONE, policy.action(now));
            policy.completed(now, 100);
        }
        assertEquals(320, policy.thresholdMs());
        assertEquals(NONE, policy.action(2319));
        assertEquals(RECOVER, policy.action(2320));
    }

    @Test public void slowerSuccessfulDeviceHasABoundedGracePeriod() {
        SlowProgressPause policy = new SlowProgressPause();
        policy.completed(1000, 240);
        assertEquals(600, policy.thresholdMs());
        for (int i = 0; i < 10; i++) policy.completed(2000 + i * 1000L, 1000);
        assertEquals(800, policy.thresholdMs());
        assertEquals(NONE, policy.action(11799));
        assertEquals(RECOVER, policy.action(11800));
    }

    @Test public void twoPauseCyclesAllowOneRealRecoveryPassBeforeHolding() {
        SlowProgressPause policy = new SlowProgressPause();
        policy.start(1000);
        assertEquals(RECOVER, policy.action(1480));
        policy.requested();
        assertEquals(NONE, policy.action(9000));
        policy.resumed(10000);
        assertEquals(NONE, policy.action(10479));
        assertEquals(RECOVER, policy.action(10480));
        policy.requested();
        policy.resumed(11000);
        assertEquals(RECHECK, policy.action(11480));
        policy.rechecking(11480);
        assertEquals(NONE, policy.action(12979));
        assertEquals(HOLD, policy.action(12980));
        policy.requested();
        assertEquals(NONE, policy.action(999999));
    }

    @Test public void pausedPairDoesNotInflatePaceAndOnlySuccessReplenishesRecovery() {
        SlowProgressPause policy = new SlowProgressPause();
        policy.completed(1000, 100);
        policy.requested();
        policy.resumed(10000);
        policy.requested();
        policy.resumed(11000);
        assertEquals(RECHECK, policy.action(11320));
        policy.rechecking(11320);
        policy.completed(11400, 0);
        assertEquals(320, policy.thresholdMs());
        assertEquals(RECOVER, policy.action(11720));
    }

    @Test public void failedNavigationBacksOffAndUnverifiedPauseCannotLoop() {
        SlowProgressPause policy = new SlowProgressPause();
        policy.start(1000);
        policy.retryLater(1480);
        assertEquals(NONE, policy.action(2479));
        assertEquals(RECOVER, policy.action(2480));
        policy.requested();
        policy.openingFailed();
        policy.resumed(3000);
        assertEquals(NONE, policy.action(9000));
        policy.completed(9100, 100);
        assertEquals(RECOVER, policy.action(9420));
    }

    @Test public void disabledSessionAndClockRollbackCannotRequestPause() {
        SlowProgressPause policy = new SlowProgressPause();
        assertEquals(NONE, policy.action(999999));
        policy.start(1000);
        assertEquals(NONE, policy.action(999));
        policy.reset();
        assertEquals(NONE, policy.action(999999));
        policy.start(1000000);
        assertEquals(NONE, policy.action(1000479));
        assertEquals(RECOVER, policy.action(1000480));
    }
    @Test public void unknownOnlyBoardNeverConsumesRecoveryAttempts() {
        SlowProgressPause policy = new SlowProgressPause();
        for (long now = 1000; now <= 10000; now += 64) {
            policy.observeWork(false, now);
            assertEquals(NONE, policy.action(now));
        }
        policy.observeWork(true, 11000);
        assertEquals(NONE, policy.action(11479));
        assertEquals(RECOVER, policy.action(11480));
    }

    @Test public void vocabularyArrivingAfterIdleGetsAFullProgressWindow() {
        SlowProgressPause policy = new SlowProgressPause();
        policy.completed(1000, 100);
        policy.observeWork(false, 1100);
        assertEquals(NONE, policy.action(10000));
        policy.observeWork(true, 10000);
        assertEquals(NONE, policy.action(10319));
        assertEquals(RECOVER, policy.action(10320));
    }

    @Test public void unchangedPendingWorkDoesNotRefreshStallDeadline() {
        SlowProgressPause policy = new SlowProgressPause();
        policy.observeWork(true, 1000);
        policy.observeWork(true, 1300);
        policy.observeWork(true, 1479);
        assertEquals(RECOVER, policy.action(1480));
    }

    @Test public void temporaryIdleDoesNotReplenishUnsuccessfulRecoveryBudget() {
        SlowProgressPause policy = new SlowProgressPause();
        policy.observeWork(true, 1000);
        policy.requested();
        policy.resumed(2000);
        policy.requested();
        policy.resumed(3000);
        policy.observeWork(false, 3100);
        assertEquals(NONE, policy.action(10000));
        policy.observeWork(true, 10000);
        assertEquals(RECHECK, policy.action(10480));
    }

    @Test public void repeatedReviewsCannotExtendTheBoundedRecoveryWindow() {
        SlowProgressPause policy = new SlowProgressPause();
        policy.start(1000);
        policy.requested(); policy.resumed(2000);
        policy.requested(); policy.resumed(3000);
        policy.rechecking(3480);
        policy.rechecking(4000);
        policy.observeWork(false, 4100);
        policy.observeWork(true, 4200);
        assertEquals(NONE, policy.action(4680));
        assertEquals(HOLD, policy.action(4980));
    }

    @Test public void recoveryGetsTimeToSettleAndRetryBeforeWatchdogHolds() {
        SlowProgressPause policy = new SlowProgressPause();
        policy.completed(1000, 100);
        policy.requested(); policy.resumed(2000);
        policy.requested(); policy.resumed(3000);
        assertEquals(RECHECK, policy.action(3320));
        policy.rechecking(3320);
        AutoPairRecovery recovery = new AutoPairRecovery();
        recovery.beginReview(3320);
        assertFalse(recovery.isSettled(3819));
        assertEquals(NONE, policy.action(3819));
        assertTrue(recovery.isSettled(3820));
        assertEquals(NONE, policy.action(3820));
        assertEquals(NONE, policy.action(4819));
        assertEquals(HOLD, policy.action(4820));
    }

    @Test public void successfulRecheckReplenishesRecoveryButDoesNotInflatePace() {
        SlowProgressPause policy = new SlowProgressPause();
        policy.completed(1000, 100);
        policy.requested(); policy.resumed(2000);
        policy.requested(); policy.resumed(3000);
        policy.rechecking(3320);
        policy.completed(4100, 0);
        assertEquals(320, policy.thresholdMs());
        assertEquals(RECOVER, policy.action(4420));
        policy.requested(); policy.resumed(5000);
        policy.requested(); policy.resumed(6000);
        assertEquals(RECHECK, policy.action(6320));
    }

    @org.junit.Test public void resultPageMustBeStableBeforeEndingAPauseAttempt() {
        SlowProgressPause pause = new SlowProgressPause();
        pause.requested();
        org.junit.Assert.assertFalse(pause.observeNonMatchingPage(true, 1000));
        org.junit.Assert.assertFalse(pause.observeNonMatchingPage(true, 1199));
        org.junit.Assert.assertTrue(pause.observeNonMatchingPage(true, 1200));
    }
    @org.junit.Test public void modalOrPartialFrameBreaksResultPageEvidence() {
        SlowProgressPause pause = new SlowProgressPause();
        org.junit.Assert.assertFalse(pause.observeNonMatchingPage(true, 1000));
        org.junit.Assert.assertFalse(pause.observeNonMatchingPage(false, 1199));
        org.junit.Assert.assertFalse(pause.observeNonMatchingPage(true, 1300));
        org.junit.Assert.assertTrue(pause.observeNonMatchingPage(true, 1500));
        pause.requested();
        org.junit.Assert.assertFalse(pause.observeNonMatchingPage(true, 2000));
    }

}
