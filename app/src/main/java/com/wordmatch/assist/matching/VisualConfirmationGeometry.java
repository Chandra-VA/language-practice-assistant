package com.wordmatch.assist.matching;

import com.wordmatch.assist.model.WordBox;
import java.util.List;

/** Validate a green card even when its completed text has already faded out. */
public final class VisualConfirmationGeometry {
    private VisualConfirmationGeometry() {}

    public static boolean matches(List<WordBox> words, WordBox target, WordBox capturedCard,
                                  WordBox currentCard) {
        if (words == null || target == null || capturedCard == null) return false;
        int feedbackOffset = Math.max(4, Math.min(8, capturedCard.getHeight() / 20));
        if (currentCard != null && !sameSlot(capturedCard, currentCard, feedbackOffset)) return false;
        boolean targetPresent = false;
        for (WordBox word : words) {
            boolean inCard = word.getCenterX() >= capturedCard.getLeft()
                    && word.getCenterX() < capturedCard.getRight()
                    && word.getCenterY() >= capturedCard.getTop()
                    && word.getCenterY() < capturedCard.getBottom();
            if (!inCard) continue;
            // A replacement word or moved label invalidates these captured pixels.
            if (!word.getNormalized().equals(target.getNormalized())
                    || Math.abs(word.getCenterX() - target.getCenterX()) > feedbackOffset
                    || Math.abs(word.getCenterY() - target.getCenterY()) > feedbackOffset) return false;
            targetPresent = true;
        }
        // An empty slot is valid only with its unchanged real card control.
        return targetPresent || capturedCard.equals(currentCard);
    }

    /** A click/retry needs the same visible label, in addition to unchanged slot geometry. */
    public static boolean matchesVisible(List<WordBox> words, WordBox target, WordBox capturedCard,
                                         WordBox currentCard) {
        if (!matches(words, target, capturedCard, currentCard)) return false;
        for (WordBox word : words) {
            if (word.getNormalized().equals(target.getNormalized())
                    && word.getCenterX() >= capturedCard.getLeft() && word.getCenterX() < capturedCard.getRight()
                    && word.getCenterY() >= capturedCard.getTop() && word.getCenterY() < capturedCard.getBottom()) return true;
        }
        return false;
    }

    static boolean sameSlot(WordBox a, WordBox b, int feedbackOffset) {
        // Pressed 189px cards in the China app shift their border and label by 6px.
        // Bound this tolerance by card size; keep the same text and the same slot.
        return Math.abs((long) a.getLeft() - b.getLeft()) <= feedbackOffset
                && Math.abs((long) a.getRight() - b.getRight()) <= feedbackOffset
                && Math.abs((long) a.getTop() - b.getTop()) <= feedbackOffset
                && Math.abs((long) a.getBottom() - b.getBottom()) <= feedbackOffset;
    }
}
