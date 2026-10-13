package com.wordmatch.assist.matching;

import com.wordmatch.assist.model.MatchPair;
import com.wordmatch.assist.model.WordBox;

import java.util.Collections;
import java.util.List;

/** Resolves the last remaining left/right pair without requiring a dictionary entry. */
public final class FinalPairResolver {
    private static final long STABLE_DURATION_MS = 200L;
    private static final long MAX_OBSERVATION_GAP_MS = 500L;
    private static final int MAX_POSITION_JITTER_PX = 12;

    private WordBox candidateLeft;
    private WordBox candidateRight;
    private int candidateScreenWidth;
    private long candidateSinceMs;
    private long lastObservedMs;

    /**
     * Only a complete, unobscured matching board may establish a candidate. Two surviving
     * tiles must remain stable across observations before they can bypass normal matching.
     */
    public MatchPair observe(
            List<WordBox> words,
            int screenWidth,
            boolean completeMatchingBoard,
            long nowMs
    ) {
        if (!completeMatchingBoard || !hasOnlyPair(words, screenWidth)) {
            reset();
            return null;
        }

        WordBox first = words.get(0);
        WordBox second = words.get(1);
        if (!isValid(first, screenWidth) || !isValid(second, screenWidth)) {
            reset();
            return null;
        }

        WordBox left;
        WordBox right;
        if (isLeft(first, screenWidth) && isRight(second, screenWidth)) {
            left = first;
            right = second;
        } else if (isLeft(second, screenWidth) && isRight(first, screenWidth)) {
            left = second;
            right = first;
        } else {
            reset();
            return null;
        }

        if (!hasCandidate()
                || screenWidth != candidateScreenWidth
                || nowMs < lastObservedMs
                || nowMs - lastObservedMs > MAX_OBSERVATION_GAP_MS
                || !isSameTile(candidateLeft, left)
                || !isSameTile(candidateRight, right)) {
            candidateLeft = left;
            candidateRight = right;
            candidateScreenWidth = screenWidth;
            candidateSinceMs = nowMs;
            lastObservedMs = nowMs;
            return null;
        }

        lastObservedMs = nowMs;
        return nowMs - candidateSinceMs >= STABLE_DURATION_MS
                ? new MatchPair(left, right, 1.0, 1)
                : null;
    }

    public boolean hasCandidate() {
        return candidateLeft != null;
    }

    /** Submission filtering must never make a larger active board look like a lone pair. */
    public static List<WordBox> unsubmittedFinalPair(List<WordBox> activeWords, List<WordBox> unsubmittedWords) {
        if (activeWords == null || unsubmittedWords == null || activeWords.size() != 2
                || !unsubmittedWords.containsAll(activeWords)) return Collections.emptyList();
        return activeWords;
    }

    /** Shape check only. After dismissing a dialog, observe() must still verify stability. */
    public static boolean hasOnlyPair(List<WordBox> remainingWords, int screenWidth) {
        if (remainingWords == null || remainingWords.size() != 2 || screenWidth < 2) {
            return false;
        }
        WordBox first = remainingWords.get(0);
        WordBox second = remainingWords.get(1);
        return isValid(first, screenWidth) && isValid(second, screenWidth)
                && ((isLeft(first, screenWidth) && isRight(second, screenWidth))
                || (isLeft(second, screenWidth) && isRight(first, screenWidth)));
    }

    public void reset() {
        candidateLeft = null;
        candidateRight = null;
        candidateScreenWidth = 0;
        candidateSinceMs = 0L;
        lastObservedMs = 0L;
    }

    private static boolean isValid(WordBox word, int screenWidth) {
        return word != null
                && !word.getNormalized().isEmpty()
                && word.getLeft() >= 0
                && word.getTop() >= 0
                && word.getRight() > word.getLeft()
                && word.getBottom() > word.getTop()
                && word.getRight() <= screenWidth;
    }

    private static boolean isLeft(WordBox word, int screenWidth) {
        return (long) word.getRight() * 2 <= screenWidth;
    }

    private static boolean isRight(WordBox word, int screenWidth) {
        return (long) word.getLeft() * 2 >= screenWidth;
    }

    private static boolean isSameTile(WordBox original, WordBox current) {
        // Compare with the original rectangle so repeated small shifts cannot accumulate.
        return original.getNormalized().equals(current.getNormalized())
                && close(original.getLeft(), current.getLeft())
                && close(original.getTop(), current.getTop())
                && close(original.getRight(), current.getRight())
                && close(original.getBottom(), current.getBottom());
    }

    private static boolean close(int first, int second) {
        return Math.abs((long) first - second) <= MAX_POSITION_JITTER_PX;
    }
}
