package com.wordmatch.assist.matching;

import com.wordmatch.assist.model.MatchPair;
import com.wordmatch.assist.model.WordBox;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class FinalPairResolverTest {
    private static final int WIDTH = 1000;
    private static final WordBox LEFT = box("未知的最后一个词", 100, 300);
    private static final WordBox RIGHT = box("unlisted-last-word", 700, 300);
    private static final List<WordBox> FINAL_PAIR = Arrays.asList(LEFT, RIGHT);

    @Test
    public void onlyRemainingPairDoesNotNeedVocabularyOrAi() {
        assertTrue(FinalPairResolver.hasOnlyPair(FINAL_PAIR, WIDTH));
        assertTrue(FinalPairResolver.hasOnlyPair(Arrays.asList(RIGHT, LEFT), WIDTH));
        assertFalse(FinalPairResolver.hasOnlyPair(null, WIDTH));
        assertFalse(FinalPairResolver.hasOnlyPair(Collections.emptyList(), WIDTH));
        assertFalse(FinalPairResolver.hasOnlyPair(Collections.singletonList(LEFT), WIDTH));
        assertFalse(FinalPairResolver.hasOnlyPair(Arrays.asList(LEFT, null), WIDTH));
        assertFalse(FinalPairResolver.hasOnlyPair(FINAL_PAIR, 0));
    }

    @Test
    public void repeatedTextMustNotCollapseMultipleCardsIntoOnePair() {
        List<WordBox> duplicateCards = Arrays.asList(LEFT, RIGHT,
                box(LEFT.getText(), 100, 500), box(RIGHT.getText(), 700, 500));
        assertFalse(FinalPairResolver.hasOnlyPair(duplicateCards, WIDTH));
        assertFalse(FinalPairResolver.hasOnlyPair(
                Arrays.asList(LEFT, box("other", 100, 500)), WIDTH));
        assertFalse(FinalPairResolver.hasOnlyPair(
                Arrays.asList(box("center", 450, 300), RIGHT), WIDTH));
    }

    @Test
    public void aiFailureShapeCheckDoesNotSkipStabilityAfterDialogDismissal() {
        FinalPairResolver resolver = new FinalPairResolver();
        assertTrue(FinalPairResolver.hasOnlyPair(FINAL_PAIR, WIDTH));
        assertNull(resolver.observe(FINAL_PAIR, WIDTH, false, 1000L));
        assertNull(resolver.observe(FINAL_PAIR, WIDTH, true, 2000L));
        assertNull(resolver.observe(FINAL_PAIR, WIDTH, true, 2199L));
        assertNotNull(resolver.observe(FINAL_PAIR, WIDTH, true, 2200L));
    }

    @Test
    public void unknownWordsRequireTwoHundredMillisecondsOfStableObservation() {
        FinalPairResolver resolver = new FinalPairResolver();

        assertNull(resolver.observe(FINAL_PAIR, WIDTH, true, 1000L));
        assertTrue(resolver.hasCandidate());
        assertNull(resolver.observe(FINAL_PAIR, WIDTH, true, 1150L));

        MatchPair pair = resolver.observe(FINAL_PAIR, WIDTH, true, 1200L);
        assertNotNull(pair);
        assertSame(LEFT, pair.getFirst());
        assertSame(RIGHT, pair.getSecond());
        assertEquals(1.0, pair.getScore(), 0.0);
        assertEquals(1, pair.getLabel());
    }

    @Test
    public void repeatedTimestampDoesNotCountAsStability() {
        FinalPairResolver resolver = new FinalPairResolver();
        assertNull(resolver.observe(FINAL_PAIR, WIDTH, true, 1000L));
        assertNull(resolver.observe(FINAL_PAIR, WIDTH, true, 1000L));
    }

    @Test
    public void rejectsTilesOnTheSameSideOrCrossingTheCenter() {
        assertRejected(Arrays.asList(LEFT, box("other", 250, 500)));
        assertRejected(Arrays.asList(RIGHT, box("other", 750, 500)));
        assertRejected(Arrays.asList(box("crossing", 450, 300), RIGHT));
    }

    @Test
    public void rejectsInvalidTextAndRectangles() {
        assertRejected(Arrays.asList(box("", 100, 300), RIGHT));
        assertRejected(Arrays.asList(box("   ", 100, 300), RIGHT));
        assertRejected(Arrays.asList(box("!!!", 100, 300), RIGHT));
        assertRejected(Arrays.asList(new WordBox("word", 100, 300, 100, 350), RIGHT));
        assertRejected(Arrays.asList(new WordBox("word", 100, 350, 200, 300), RIGHT));
        assertRejected(Arrays.asList(box("word", -1, 300), RIGHT));
        assertRejected(Arrays.asList(box("word", 100, -1), RIGHT));
        assertRejected(Arrays.asList(LEFT, box("word", 900, 300)));
        assertRejected(Arrays.asList(LEFT, null));
    }

    @Test
    public void extraWordsEmptyFrameAndIncompleteBoardResetStability() {
        assertResets(Arrays.asList(LEFT, RIGHT, box("extra", 100, 500)), true);
        assertResets(Collections.emptyList(), true);
        assertResets(Collections.singletonList(LEFT), true);
        assertResets(null, true);
        assertResets(FINAL_PAIR, false);
    }

    @Test
    public void changedWordRestartsStability() {
        FinalPairResolver resolver = new FinalPairResolver();
        List<WordBox> replacement = Arrays.asList(box("replacement", 100, 300), RIGHT);
        resolver.observe(FINAL_PAIR, WIDTH, true, 1000L);

        assertNull(resolver.observe(replacement, WIDTH, true, 1200L));
        assertTrue(resolver.hasCandidate());
        assertNull(resolver.observe(replacement, WIDTH, true, 1350L));
        assertNotNull(resolver.observe(replacement, WIDTH, true, 1400L));
    }

    @Test
    public void movedWordRestartsStability() {
        FinalPairResolver resolver = new FinalPairResolver();
        List<WordBox> moved = Arrays.asList(box(LEFT.getText(), 100, 313), RIGHT);
        resolver.observe(FINAL_PAIR, WIDTH, true, 1000L);

        assertNull(resolver.observe(moved, WIDTH, true, 1200L));
        assertNull(resolver.observe(moved, WIDTH, true, 1350L));
        assertNotNull(resolver.observe(moved, WIDTH, true, 1400L));
    }

    @Test
    public void reversedInputAndSmallJitterReturnLatestLeftAndRightCoordinates() {
        FinalPairResolver resolver = new FinalPairResolver();
        WordBox latestLeft = box(LEFT.getText(), 112, 288);
        WordBox latestRight = box(RIGHT.getText(), 690, 305);
        resolver.observe(FINAL_PAIR, WIDTH, true, 1000L);

        MatchPair pair = resolver.observe(Arrays.asList(latestRight, latestLeft), WIDTH, true, 1200L);
        assertNotNull(pair);
        assertSame(latestLeft, pair.getFirst());
        assertSame(latestRight, pair.getSecond());
    }

    @Test
    public void smallContinuousMovementCannotAccumulateIntoStableCandidate() {
        FinalPairResolver resolver = new FinalPairResolver();
        resolver.observe(FINAL_PAIR, WIDTH, true, 1000L);
        assertNull(resolver.observe(Arrays.asList(box(LEFT.getText(), 110, 300), RIGHT),
                WIDTH, true, 1100L));
        assertNull(resolver.observe(Arrays.asList(box(LEFT.getText(), 120, 300), RIGHT),
                WIDTH, true, 1200L));
    }

    @Test
    public void longObservationGapRestartsStability() {
        FinalPairResolver resolver = new FinalPairResolver();
        resolver.observe(FINAL_PAIR, WIDTH, true, 1000L);

        assertNull(resolver.observe(FINAL_PAIR, WIDTH, true, 1501L));
        assertTrue(resolver.hasCandidate());
        assertNull(resolver.observe(FINAL_PAIR, WIDTH, true, 1651L));
        assertNotNull(resolver.observe(FINAL_PAIR, WIDTH, true, 1701L));
    }

    @Test
    public void fiveHundredMillisecondGapIsAllowed() {
        FinalPairResolver resolver = new FinalPairResolver();
        resolver.observe(FINAL_PAIR, WIDTH, true, 1000L);
        assertNotNull(resolver.observe(FINAL_PAIR, WIDTH, true, 1500L));
    }

    @Test
    public void backwardsClockRestartsStability() {
        FinalPairResolver resolver = new FinalPairResolver();
        resolver.observe(FINAL_PAIR, WIDTH, true, 1000L);
        assertNull(resolver.observe(FINAL_PAIR, WIDTH, true, 900L));
        assertNull(resolver.observe(FINAL_PAIR, WIDTH, true, 1050L));
        assertNotNull(resolver.observe(FINAL_PAIR, WIDTH, true, 1100L));
    }

    @Test
    public void resetAndScreenWidthChangesInvalidateCandidate() {
        FinalPairResolver resolver = new FinalPairResolver();
        resolver.observe(FINAL_PAIR, WIDTH, true, 1000L);
        resolver.reset();
        assertFalse(resolver.hasCandidate());
        assertNull(resolver.observe(FINAL_PAIR, WIDTH, true, 1200L));
        assertNull(resolver.observe(FINAL_PAIR, WIDTH + 10, true, 1400L));
        assertNotNull(resolver.observe(FINAL_PAIR, WIDTH + 10, true, 1600L));
        assertNull(resolver.observe(FINAL_PAIR, 0, true, 1800L));
        assertFalse(resolver.hasCandidate());
    }

    private static void assertRejected(List<WordBox> words) {
        FinalPairResolver resolver = new FinalPairResolver();
        resolver.observe(FINAL_PAIR, WIDTH, true, 1000L);
        assertNull(resolver.observe(words, WIDTH, true, 1200L));
        assertFalse(resolver.hasCandidate());
    }

    private static void assertResets(List<WordBox> interruption, boolean completeMatchingBoard) {
        FinalPairResolver resolver = new FinalPairResolver();
        resolver.observe(FINAL_PAIR, WIDTH, true, 1000L);
        assertNull(resolver.observe(interruption, WIDTH, completeMatchingBoard, 1100L));
        assertFalse(resolver.hasCandidate());
        assertNull(resolver.observe(FINAL_PAIR, WIDTH, true, 1200L));
        assertNull(resolver.observe(FINAL_PAIR, WIDTH, true, 1350L));
        assertNotNull(resolver.observe(FINAL_PAIR, WIDTH, true, 1400L));
    }

    private static WordBox box(String text, int x, int y) {
        return new WordBox(text, x, y, x + 120, y + 50);
    }
    @Test public void filteredRemainderCannotHideOtherActivePartners() {
        WordBox resume = box("简历",100,300), interview = box("interview",700,300);
        List<WordBox> active = Arrays.asList(resume,interview,box("面试",100,400),box("résumé",700,400));
        List<WordBox> filtered = Arrays.asList(resume,interview);
        List<WordBox> candidate = FinalPairResolver.unsubmittedFinalPair(active,filtered);
        FinalPairResolver resolver = new FinalPairResolver();
        assertNull(resolver.observe(candidate,WIDTH,true,1000));
        assertNull(resolver.observe(candidate,WIDTH,true,1300));
        assertFalse(resolver.hasCandidate());
    }

    @Test public void alreadySubmittedActiveCardCannotEnterUnknownTailInference() {
        assertTrue(FinalPairResolver.unsubmittedFinalPair(FINAL_PAIR,Collections.singletonList(LEFT)).isEmpty());
    }

    @Test public void trueUnknownTailStillResolvesAfterGreyCardsAreExcluded() {
        List<WordBox> candidate = FinalPairResolver.unsubmittedFinalPair(FINAL_PAIR,FINAL_PAIR);
        FinalPairResolver resolver = new FinalPairResolver();
        assertNull(resolver.observe(candidate,WIDTH,true,1000));
        assertNotNull(resolver.observe(candidate,WIDTH,true,1200));
    }

}
