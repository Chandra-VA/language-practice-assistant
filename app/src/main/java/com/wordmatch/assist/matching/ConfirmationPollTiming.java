package com.wordmatch.assist.matching;

/** Leave the main thread available for live frames, with node scans as a watchdog. */
public final class ConfirmationPollTiming {
    private ConfirmationPollTiming() {}

    public static boolean hasLiveFrames(boolean running, long frameAt, long now) {
        return running && frameAt > 0 && now >= frameAt && now - frameAt < 200L;
    }

    public static long nodePollDelay(boolean running, long frameAt, long now) {
        return hasLiveFrames(running, frameAt, now) ? 96L : 16L;
    }

    /** Briefly observe dispatch acceptance while an independent queued pair can use the lane. */
    public static long dispatchPollDelay(boolean running, long frameAt, long now,
                                         boolean followingPairQueued, long secondClickAt) {
        long normal = nodePollDelay(running, frameAt, now);
        if (followingPairQueued && secondClickAt > 0 && now >= secondClickAt
                && now - secondClickAt < 160L) return Math.min(32L, normal);
        return normal;
    }

    public static boolean needsScreenshotBackup(boolean running, long frameAt, long clickedAt, long now) {
        return !hasLiveFrames(running, frameAt, now) || clickedAt <= 0 || now - clickedAt >= 240L;
    }
}
