package com.wordmatch.assist.matching;

/** Watches confirmed progress, never treating a click, repaint, or pause as a solved pair. */
public final class SlowProgressPause {
    public enum Action { NONE, RECOVER, RECHECK, HOLD }
    private static final long RECHECK_WINDOW_MS = 1500L;
    private long progressAt = -1, retryAt;
    private long averagePairMs;
    private long nonMatchingSince = -1L;
    private int pausesWithoutProgress;
    private boolean paused, blocked, recheckUsed;

    public void start(long now) {
        if (progressAt < 0) progressAt = now;
    }

    /** Unknown vocabulary is not an execution stall. Keep recovery history while idle. */
    public void observeWork(boolean available, long now) {
        if (available) start(now);
        else { progressAt = -1L; if (!recheckUsed) retryAt = 0L; }
    }

    public long thresholdMs() {
        return averagePairMs == 0 ? 480L : Math.max(320L, Math.min(800L, averagePairMs * 5L / 2L));
    }

    public Action action(long now) {
        if (progressAt < 0 || paused || blocked || now < progressAt || now < retryAt
                || now - progressAt < thresholdMs()) return Action.NONE;
        return pausesWithoutProgress < 2 ? Action.RECOVER
                : !recheckUsed ? Action.RECHECK : Action.HOLD;
    }

    /** Two pause cycles are not two click retries. Allow one bounded real recovery pass. */
    public void rechecking(long now) {
        if (recheckUsed) return;
        recheckUsed = true;
        retryAt = now + RECHECK_WINDOW_MS;
    }

    public void requested() { paused = true; pausesWithoutProgress++; nonMatchingSince = -1L; }

    /** Resume grants a fresh observation window, but does not replenish the recovery budget. */
    public void resumed(long now) { paused = false; progressAt = now; }

    public void retryLater(long now) { retryAt = now + 1000L; }

    /** An unverified BACK must not produce an endless sequence of navigation actions. */
    public void openingFailed() { blocked = true; }

    public void completed(long now, long unpausedPairDurationMs) {
        if (unpausedPairDurationMs > 0) {
            long sample = Math.min(1000L, unpausedPairDurationMs);
            averagePairMs = averagePairMs == 0 ? sample : (averagePairMs * 3L + sample) / 4L;
        }
        progressAt = now;
        retryAt = 0;
        pausesWithoutProgress = 0;
        paused = blocked = recheckUsed = false;
    }

    /** A result/entry page can replace the lesson while Android is opening BACK. */
    public boolean observeNonMatchingPage(boolean completeNonMatching, long now) {
        if (!completeNonMatching) { nonMatchingSince = -1L; return false; }
        if (nonMatchingSince < 0L || now < nonMatchingSince) { nonMatchingSince = now; return false; }
        return now - nonMatchingSince >= 200L;
    }

    public void reset() {
        nonMatchingSince = -1L;
        progressAt = -1;
        retryAt = averagePairMs = 0;
        pausesWithoutProgress = 0;
        paused = blocked = recheckUsed = false;
    }
}
