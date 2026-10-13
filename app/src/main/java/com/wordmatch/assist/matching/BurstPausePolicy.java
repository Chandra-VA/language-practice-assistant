package com.wordmatch.assist.matching;

/** Called after the original dispatch queue drains; grey/unknown cards need not belong to the batch. */
public final class BurstPausePolicy {
    private BurstPausePolicy() {
    }

    public static boolean shouldPauseForReplacementWords(
            int visibleBoardWordCount,
            int issuedPairCount
    ) {
        return shouldPauseForReplacementWords(visibleBoardWordCount, issuedPairCount, false);
    }

    /** Ready means freshly validated, active cards with a safe selected-card plan. */
    public static boolean shouldPauseForReplacementWords(
            int visibleBoardWordCount, int issuedPairCount, boolean hasReadyReplacementPair
    ) {
        return visibleBoardWordCount > 2 && issuedPairCount > 0 && !hasReadyReplacementPair;
    }
}
