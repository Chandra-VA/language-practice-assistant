package com.wordmatch.assist.matching;

import com.wordmatch.assist.model.WordBox;
import java.util.List;

/** Pair-specific completion when replacement animations finish at different times. */
public final class ReplacementPairConfirmation {
    private long since = -1L;
    private int side;

    public boolean observe(List<WordBox> words, WordBox first, WordBox firstCard, WordBox currentFirstCard,
                           WordBox second, WordBox secondCard, WordBox currentSecondCard,
                           boolean firstInactive, boolean secondInactive,
                           CardVisualState.State firstColor, CardVisualState.State secondColor,
                           boolean startedActive, boolean completeUnselected, long clickedAt, long now) {
        int nextSide = 0;
        if (startedActive && completeUnselected && now >= clickedAt && now - clickedAt >= 32L) {
            if (replaced(words, first, firstCard, currentFirstCard)
                    && firstColor != CardVisualState.State.SELECTED
                    && settled(words, second, secondCard, currentSecondCard, secondInactive, secondColor)) nextSide = 1;
            if (replaced(words, second, secondCard, currentSecondCard)
                    && secondColor != CardVisualState.State.SELECTED
                    && settled(words, first, firstCard, currentFirstCard, firstInactive, firstColor)) nextSide = 2;
        }
        if (nextSide == 0) { reset(); return false; }
        if (nextSide != side || since < 0 || now < since) { side = nextSide; since = now; return false; }
        return now - since >= 96L;
    }

    private static boolean settled(List<WordBox> words, WordBox target, WordBox card, WordBox current,
                                   boolean inactive, CardVisualState.State color) {
        return inactive && (color == CardVisualState.State.GREEN || color == CardVisualState.State.FADED)
                && VisualConfirmationGeometry.matchesVisible(words, target, card, current);
    }

    public static boolean replaced(List<WordBox> words, WordBox target, WordBox card, WordBox current) {
        if (words == null || target == null || card == null || current == null
                || !VisualConfirmationGeometry.sameSlot(card, current, Math.max(4, Math.min(8, card.getHeight() / 20)))) return false;
        boolean replacement = false;
        for (WordBox word : words) {
            if (word.getCenterX() < card.getLeft() || word.getCenterX() >= card.getRight()
                    || word.getCenterY() < card.getTop() || word.getCenterY() >= card.getBottom()) continue;
            if (word.getNormalized().equals(target.getNormalized())) return false;
            if (!word.getNormalized().isEmpty()) replacement = true;
        }
        return replacement;
    }

    public void reset() { since = -1L; side = 0; }
}
