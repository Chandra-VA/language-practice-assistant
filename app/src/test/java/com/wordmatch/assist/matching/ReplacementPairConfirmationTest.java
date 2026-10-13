package com.wordmatch.assist.matching;

import com.wordmatch.assist.model.WordBox;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class ReplacementPairConfirmationTest {
    private final WordBox a = new WordBox("old", 150, 800, 280, 860);
    private final WordBox b = new WordBox("旧", 710, 800, 810, 860);
    private final WordBox ca = new WordBox("", 30, 740, 510, 929);
    private final WordBox cb = new WordBox("", 570, 740, 1050, 929);
    private final WordBox next = new WordBox("new", 150, 800, 280, 860);
    private boolean observe(ReplacementPairConfirmation c, List<WordBox> board, boolean inactive,
                            CardVisualState.State color, boolean complete, long now) {
        return c.observe(board, a, ca, ca, b, cb, cb, false, inactive,
                CardVisualState.State.READY, color, true, complete, 1000, now);
    }
    @Test public void replacementAndInactiveGreyPartnerConfirmOnlyAfterStableEvidence() {
        ReplacementPairConfirmation c = new ReplacementPairConfirmation();
        List<WordBox> board = Arrays.asList(next,b);
        assertFalse(observe(c,board,true,CardVisualState.State.FADED,true,1200));
        assertFalse(observe(c,board,true,CardVisualState.State.FADED,true,1295));
        assertTrue(observe(c,board,true,CardVisualState.State.FADED,true,1296));
    }
    @Test public void replacementAloneOrActiveGreyPartnerNeverConfirms() {
        ReplacementPairConfirmation c = new ReplacementPairConfirmation();
        for (long t : new long[]{1200,1400,2000}) {
            assertFalse(observe(c,Arrays.asList(next,b),true,CardVisualState.State.READY,true,t));
            assertFalse(observe(c,Arrays.asList(next,b),false,CardVisualState.State.FADED,true,t));
            assertFalse(observe(c,Arrays.asList(a,b),true,CardVisualState.State.FADED,true,t));
            assertFalse(observe(c,Collections.singletonList(b),true,CardVisualState.State.FADED,true,t));
        }
    }
    @Test public void modalOrSelectionInterruptsTheEvidenceWindow() {
        ReplacementPairConfirmation c = new ReplacementPairConfirmation();
        List<WordBox> board = Arrays.asList(next,b);
        assertFalse(observe(c,board,true,CardVisualState.State.GREEN,true,1200));
        assertFalse(observe(c,board,true,CardVisualState.State.GREEN,false,1296));
        assertFalse(observe(c,board,true,CardVisualState.State.GREEN,true,1400));
        assertTrue(observe(c,board,true,CardVisualState.State.GREEN,true,1496));
    }
    @Test public void changedCardGeometryAndDuplicateTextElsewhereDoNotProveReplacement() {
        WordBox otherRow = new WordBox("new", 150, 1050, 280, 1110);
        assertFalse(ReplacementPairConfirmation.replaced(Arrays.asList(a, otherRow),a,ca,ca));
        assertFalse(ReplacementPairConfirmation.replaced(Arrays.asList(next,b),a,ca,
                new WordBox("",30,840,510,1029)));
        assertFalse(ReplacementPairConfirmation.replaced(Arrays.asList(next,b),a,ca,null));
    }
    @Test public void neverConfirmAnUnsubmittedOrInitiallyInactivePair() {
        ReplacementPairConfirmation c = new ReplacementPairConfirmation();
        for (long t : new long[]{1200,1400}) assertFalse(c.observe(Arrays.asList(next,b),a,ca,ca,b,cb,cb,
                false,true,CardVisualState.State.READY,CardVisualState.State.FADED,false,true,1000,t));
    }
    @Test public void supportsEitherReplacementSideButDoesNotCombineOppositeObservations() {
        ReplacementPairConfirmation c = new ReplacementPairConfirmation();
        assertFalse(observe(c,Arrays.asList(next,b),true,CardVisualState.State.FADED,true,1200));
        WordBox nextB = new WordBox("新",710,800,810,860);
        assertFalse(c.observe(Arrays.asList(a,nextB),a,ca,ca,b,cb,cb,true,false,
                CardVisualState.State.FADED,CardVisualState.State.READY,true,true,1000,1400));
        assertTrue(c.observe(Arrays.asList(a,nextB),a,ca,ca,b,cb,cb,true,false,
                CardVisualState.State.FADED,CardVisualState.State.READY,true,true,1000,1496));
    }
}
