package com.wordmatch.assist.matching;

import com.wordmatch.assist.model.WordBox;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class VisualConfirmationGeometryTest {
    private static final WordBox CARD = new WordBox("", 16, 558, 389, 623);
    private static final WordBox SPILL = new WordBox("溢出", 185, 580, 221, 600);
    private static final WordBox ELSEWHERE = new WordBox("airline", 569, 820, 630, 850);

    @Test public void completedTextCanFadeWhileItsGreenCardSlotStaysPresent() {
        assertTrue(VisualConfirmationGeometry.matches(Arrays.asList(SPILL, ELSEWHERE), SPILL, CARD, CARD));
        assertTrue(VisualConfirmationGeometry.matches(Collections.singletonList(ELSEWHERE), SPILL, CARD, CARD));
        assertTrue(VisualConfirmationGeometry.matches(Collections.emptyList(), SPILL, CARD, CARD));
    }

    @Test public void missingTextAndMissingCardAreInsufficientEvenWithUnrelatedWords() {
        assertFalse(VisualConfirmationGeometry.matches(Collections.singletonList(ELSEWHERE), SPILL, CARD, null));
        assertTrue(VisualConfirmationGeometry.matches(Collections.singletonList(SPILL), SPILL, CARD, null));
    }

    @Test public void replacementTextMovedLabelAndMovedCardInvalidateCapturedPixels() {
        WordBox replacement = new WordBox("新词", 185, 580, 221, 600);
        WordBox moved = new WordBox("溢出", 195, 580, 231, 600);
        WordBox shiftedCard = new WordBox("", 16, 563, 389, 628);
        assertFalse(VisualConfirmationGeometry.matches(Collections.singletonList(replacement), SPILL, CARD, CARD));
        assertFalse(VisualConfirmationGeometry.matches(Collections.singletonList(moved), SPILL, CARD, CARD));
        assertFalse(VisualConfirmationGeometry.matches(Collections.singletonList(SPILL), SPILL, CARD, shiftedCard));
        assertFalse(VisualConfirmationGeometry.matches(null, SPILL, CARD, CARD));
    }

    @Test public void smallDisabledBorderChangeNeedsTheSameTextAnchor() {
        WordBox shiftedCard = new WordBox("", 18, 560, 387, 627);
        assertTrue(VisualConfirmationGeometry.matches(Collections.singletonList(SPILL), SPILL, CARD, shiftedCard));
        assertFalse(VisualConfirmationGeometry.matches(Collections.emptyList(), SPILL, CARD, shiftedCard));
    }
    @Test public void highDensityPressedCardCanReturnSixPixelsToItsRestingPosition() {
        WordBox pressed = new WordBox("", 42, 734, 508, 923);
        WordBox resting = new WordBox("", 42, 740, 508, 929);
        WordBox before = new WordBox("意思", 225, 795, 325, 867);
        WordBox after = new WordBox("意思", 225, 801, 325, 873);
        assertTrue(VisualConfirmationGeometry.matches(Collections.singletonList(after), before, pressed, resting));
        assertFalse(VisualConfirmationGeometry.matches(Collections.emptyList(), before, pressed, resting));
        assertFalse(VisualConfirmationGeometry.matches(Collections.singletonList(
                new WordBox("新词", 225, 801, 325, 873)), before, pressed, resting));
        assertFalse(VisualConfirmationGeometry.matches(Collections.singletonList(
                new WordBox("意思", 225, 810, 325, 882)), before, pressed, resting));
    }
    @org.junit.Test public void clickRetryAcceptsPressReleaseButNeverAnEmptyOrReplacedSlot() {
        WordBox card = new WordBox("", 30, 740, 510, 929);
        WordBox target = new WordBox("skill", 200, 800, 300, 860);
        WordBox pressedCard = new WordBox("", 30, 734, 510, 923);
        WordBox pressed = new WordBox("skill", 200, 794, 300, 854);
        org.junit.Assert.assertTrue(VisualConfirmationGeometry.matchesVisible(
                java.util.Collections.singletonList(pressed), target, card, pressedCard));
        org.junit.Assert.assertFalse(VisualConfirmationGeometry.matchesVisible(
                java.util.Collections.emptyList(), target, card, card));
        org.junit.Assert.assertFalse(VisualConfirmationGeometry.matchesVisible(
                java.util.Collections.singletonList(new WordBox("other", 200, 800, 300, 860)), target, card, card));
        org.junit.Assert.assertFalse(VisualConfirmationGeometry.matchesVisible(
                java.util.Collections.singletonList(new WordBox("skill", 200, 900, 300, 960)), target, card, card));
    }

}
