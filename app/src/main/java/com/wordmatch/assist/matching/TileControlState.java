package com.wordmatch.assist.matching;

import com.wordmatch.assist.model.WordBox;

import java.util.ArrayList;
import java.util.List;

/** Uses actual card controls, never disabled text leaves, to defer animation-time clicks. */
public final class TileControlState {
    private final List<WordBox> active = new ArrayList<>();
    private final List<WordBox> inactive = new ArrayList<>();
    private final List<WordBox> cardContainers = new ArrayList<>();

    public void observe(boolean clickable, boolean enabled, boolean visible,
                        int left, int top, int right, int bottom, int width, int height) {
        if (width <= 0 || height <= 0 || right <= left || bottom <= top
                || right - left > width * 0.55f || bottom - top > height * 0.18f
                || top < height * 0.12f || bottom > height * 0.96f
                || Math.abs(((long) left + right) / 2.0 - width / 2.0) < width * 0.035f) {
            return;
        }
        // Successful cards can stop being clickable before their text fades.
        // Retain the slot geometry for color confirmation, never as click permission.
        if (visible && right - left >= width * 0.25f) {
            cardContainers.add(new WordBox("", left, top, right, bottom));
            // Some Duolingo versions keep grey cards enabled but remove ACTION_CLICK.
            // A full-height card with neither clickability nor an active parent is inactive.
            // Thin text leaves alone still cannot hide an active card parent.
            if ((!enabled || !clickable) && bottom - top >= height * 0.025f) {
                inactive.add(new WordBox("", left, top, right, bottom));
            }
        }
        if (!clickable) return;
        (enabled && visible ? active : inactive).add(new WordBox("", left, top, right, bottom));
    }

    public boolean isInactive(WordBox word) {
        // An enabled parent can still handle clicks when a child reports disabled.
        return contains(inactive, word) && !contains(active, word);
    }

    public boolean hasActiveControl(WordBox word) { return contains(active, word); }

    /** Unknown controls stay in the board; only positively inactive card slots are removed. */
    public List<WordBox> withoutInactiveWords(List<WordBox> words) {
        List<WordBox> remaining = new ArrayList<>();
        for (WordBox word : words) if (!isInactive(word)) remaining.add(word);
        return remaining;
    }

    /** Only a real wide card control can define an image sampling area. */
    public WordBox cardBounds(WordBox word, int screenWidth) {
        WordBox best = null;
        for (List<WordBox> group : java.util.Arrays.asList(active, inactive, cardContainers)) {
            for (WordBox control : group) {
                if (word != null && control.getWidth() >= screenWidth * 0.25f
                        && control.getLeft() <= word.getLeft() && control.getRight() >= word.getRight()
                        && control.getTop() <= word.getTop() && control.getBottom() >= word.getBottom()
                        && (best == null || (long) control.getWidth() * control.getHeight()
                        < (long) best.getWidth() * best.getHeight())) {
                    best = control;
                }
            }
            if (best != null) return best;
        }
        return best;
    }

    private static boolean contains(List<WordBox> controls, WordBox word) {
        for (WordBox control : controls) {
            if (word.getCenterX() >= control.getLeft() && word.getCenterX() < control.getRight()
                    && word.getCenterY() >= control.getTop() && word.getCenterY() < control.getBottom()) {
                return true;
            }
        }
        return false;
    }
}
