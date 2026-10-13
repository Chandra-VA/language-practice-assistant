package com.wordmatch.assist.matching;
import org.junit.Test;
import static org.junit.Assert.*;
public final class FreshGreenFramesTest {
    @Test public void staleFirstFrameAndSingleGreenFrameNeverConfirm() {
        FreshGreenFrames frames = new FreshGreenFrames();
        assertFalse(frames.observe(1, true, 100));
        assertFalse(frames.observe(1, true, 116));
        assertFalse(frames.observe(1, true, 131));
        assertTrue(frames.observe(1, true, 132));
        assertFalse(frames.observe(2, true, 148));
    }
    @Test public void whiteFrameGapOrCancellationRestartsConfirmation() {
        FreshGreenFrames frames = new FreshGreenFrames();
        frames.observe(1, false, 0);
        frames.observe(1, true, 16);
        assertFalse(frames.observe(1, false, 32));
        assertFalse(frames.observe(1, true, 48));
        assertFalse(frames.observe(1, true, 400));
        assertFalse(frames.observe(1, true, 416));
        assertTrue(frames.observe(1, true, 432));
        frames.reset();
        assertFalse(frames.observe(1, true, 448));
    }
    @Test public void firstClickCanDiscardTheStaleFrameWithoutCreditingPrePartnerColors() {
        FreshGreenFrames frames = new FreshGreenFrames();
        assertFalse(frames.observe(5, false, 100)); // First click: never inspect green yet.
        assertFalse(frames.observe(5, false, 120)); // Partner has not settled.
        assertFalse(frames.observe(5, true, 160));
        assertFalse(frames.observe(5, true, 175));
        assertTrue(frames.observe(5, true, 176));
        assertFalse(frames.observe(6, true, 192)); // No early frame: still discard first green.
        assertFalse(frames.observe(6, true, 208));
        assertTrue(frames.observe(6, true, 224));
    }

}
