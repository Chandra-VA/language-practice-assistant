package com.wordmatch.assist.matching;

/** A bounded chance for already-fading cards to reveal their replacements before opening a modal. */
public final class ReplacementBatchWindow {
    private static final long MAX_WAIT_MS = 160L;
    private long startedAt = -1L;

    public boolean shouldWait(boolean needsReplacement, long now) {
        if (!needsReplacement) { reset(); return false; }
        if (startedAt < 0L) startedAt = now;
        // Repeated empty scans must not extend the deadline.
        return now >= startedAt && now - startedAt < MAX_WAIT_MS;
    }

    public void reset() { startedAt = -1L; }
}
