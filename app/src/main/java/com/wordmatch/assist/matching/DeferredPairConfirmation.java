package com.wordmatch.assist.matching;

import com.wordmatch.assist.model.MatchPair;
import com.wordmatch.assist.model.WordBox;
import java.util.List;

/** A dispatched pair stays unconfirmed and excluded from clicks until pair-specific evidence arrives. */
public final class DeferredPairConfirmation {
    public final MatchPair pair;
    public final WordBox firstCard, secondCard;
    public final long clickedAt;
    private final AutoPairConfirmation disappearance = new AutoPairConfirmation();
    private final FadedPairConfirmation faded = new FadedPairConfirmation();
    private final ReplacementPairConfirmation replacements = new ReplacementPairConfirmation();
    private final FreshGreenFrames greens = new FreshGreenFrames();

    public DeferredPairConfirmation(MatchPair pair, WordBox firstCard, WordBox secondCard,
                                    int width, int height, long clickedAt) {
        this.pair = pair; this.firstCard = firstCard; this.secondCard = secondCard; this.clickedAt = clickedAt;
        // Other submitted pairs can disappear concurrently. Require two visible words and
        // stable loss of these exact targets, never a global counter belonging to another pair.
        disappearance.start(pair.getFirst(), pair.getSecond(), 4, width, height);
    }

    public boolean contains(WordBox word) { return same(word, pair.getFirst()) || same(word, pair.getSecond()); }
    private static boolean same(WordBox a, WordBox b) {
        return a != null && b != null && a.getNormalized().equals(b.getNormalized())
                && Math.abs((long)a.getCenterX() - b.getCenterX()) <= 4L
                && Math.abs((long)a.getCenterY() - b.getCenterY()) <= 4L;
    }
    public boolean observeNodes(List<WordBox> words, boolean completeBoard, long now) {
        return disappearance.observe(words, completeBoard, now);
    }
    public boolean observeColors(CardVisualState.State first, CardVisualState.State second,
                                 boolean firstActive, boolean secondActive, boolean peerOrSubmittedBoard,
                                 boolean completeUnselectedBoard, boolean screenshot, long now) {
        if (!completeUnselectedBoard || now - clickedAt < 32L) { resetObservations(now); return false; }
        boolean green = first == CardVisualState.State.GREEN && second == CardVisualState.State.GREEN;
        boolean greenConfirmed = greens.observe(1, green, now);
        boolean fadedConfirmed = faded.observe(first, second, true, firstActive, secondActive,
                peerOrSubmittedBoard, true, clickedAt, now);
        return (green && screenshot) || greenConfirmed || fadedConfirmed;
    }
    public boolean observeReplacement(List<WordBox> words, WordBox currentFirst, WordBox currentSecond,
                                      boolean firstInactive, boolean secondInactive,
                                      CardVisualState.State first, CardVisualState.State second,
                                      boolean completeUnselected, long now) {
        return replacements.observe(words, pair.getFirst(), firstCard, currentFirst,
                pair.getSecond(), secondCard, currentSecond, firstInactive, secondInactive,
                first, second, true, completeUnselected, clickedAt, now);
    }
    public void resetObservations(long now) {
        greens.reset(); faded.reset(); replacements.reset(); disappearance.observe(null, false, now);
    }
}
