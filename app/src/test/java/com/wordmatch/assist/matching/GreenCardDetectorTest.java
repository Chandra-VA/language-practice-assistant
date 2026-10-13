package com.wordmatch.assist.matching;

import com.wordmatch.assist.model.WordBox;
import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class GreenCardDetectorTest {
    private static final WordBox CARD = new WordBox("", 0, 0, 100, 100);

    @Test public void replaysRecordedColorSetsIndependentlyOfSamplePlacement() throws Exception {
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/green_card_recording_samples.csv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] fields = line.split(",");
                boolean first = sampledCard(fields, 2);
                boolean second = sampledCard(fields, 14);
                assertEquals(fields[0], Boolean.parseBoolean(fields[1]), first && second);
                assertFalse(fields[0] + " untouched card", sampledCard(fields, 26));
                count++;
            }
        }
        assertEquals(10, count);
    }

    @Test public void rejectsBadgeOnlyWhiteBlueRedDimmedAndBlackBackgrounds() {
        assertFalse(GreenCardDetector.isGreenCard((x, y) ->
                x > 35 && x < 65 ? 0xff58cc02 : 0xffffffff, 100, 100, CARD));
        for (int color : new int[]{0xffffffff, 0xffdbf2fd, 0xffffdede, 0xff989898, 0xff000000}) {
            assertFalse(GreenCardDetector.isGreenCard((x, y) -> color, 100, 100, CARD));
        }
    }

    @Test public void oneGreenCardOrGreenHalfIsNotEnough() {
        assertTrue(GreenCardDetector.isGreenCard((x, y) -> 0xffd8febb, 100, 100, CARD));
        assertFalse(GreenCardDetector.isGreenCard((x, y) -> x < 50 ? 0xffd8febb : 0xffffffff,
                100, 100, CARD));
        assertFalse(GreenCardDetector.isGreenCard((x, y) -> 0xffd8febb, 99, 100, CARD));
        assertFalse(GreenCardDetector.isGreenCard((x, y) -> 0xffd8febb, 100, 100,
                new WordBox("", -1, 0, 99, 100)));
    }

    @Test public void highlightCanRemainVisibleWhileSamplePatchesExposeOriginalBackground() {
        int[][] points = GreenCardDetector.samplePoints(CARD);
        assertEquals(12, points.length);
        // Simulate a full green overlay with the exact sample patches clipped out.
        GreenCardDetector.Pixels highlightedWhite = (x, y) -> isSample(points, x, y) ? 0xffffffff : 0xffd8febb;
        assertFalse(GreenCardDetector.isGreenCard(highlightedWhite, 100, 100, CARD));
        GreenCardDetector.Pixels highlightedGreen = (x, y) -> isSample(points, x, y) ? 0xffd8febb : 0xffffdede;
        assertTrue(GreenCardDetector.isGreenCard(highlightedGreen, 100, 100, CARD));
        WordBox shifted = new WordBox("", 37, 81, 411, 147);
        int[][] shiftedPoints = GreenCardDetector.samplePoints(shifted);
        assertEquals(37 + (int) (374 * .12f), shiftedPoints[0][0]);
        assertEquals(81 + (int) (66 * .18f), shiftedPoints[0][1]);
    }

    @Test public void wideDarkTextDoesNotObscureGreenOrWhiteBackgroundSamples() {
        WordBox card = new WordBox("", 0, 0, 466, 189);
        WordBox label = new WordBox("payment method", 56, 60, 408, 132);
        GreenCardDetector.Pixels greenWithText = (x,y) -> y >= 60 && y <= 132 && x >= 56 && x <= 408
                ? 0xff4c7500 : 0xffd8febb;
        assertTrue(GreenCardDetector.isGreenCard(greenWithText, 466, 189, card));
        GreenCardDetector.Pixels whiteWithGreyText = (x,y) -> y >= 60 && y <= 132 && x >= 56 && x <= 408
                ? 0xffe5e5e5 : 0xffffffff;
        assertEquals(CardVisualState.State.FADED, CardVisualState.read(whiteWithGreyText,466,189,card,label));
        assertFalse(GreenCardDetector.isGreenCard(whiteWithGreyText,466,189,card));
    }

    private static boolean isSample(int[][] points, int x, int y) {
        for (int[] point : points) if (point[0] == x && point[1] == y) return true;
        return false;
    }

    private static boolean sampledCard(String[] fields, int offset) {
        return GreenCardDetector.isGreenCard((x, y) -> {
            // Recorded colors still test the classifier, not coordinates from the old grid.
            int[][] points = GreenCardDetector.samplePoints(CARD);
            for (int i = 0; i < points.length; i++) if (points[i][0] == x && points[i][1] == y)
                return (int) Long.parseLong(fields[offset + i], 16);
            throw new AssertionError("Unexpected sample");
        }, 100, 100, CARD);
    }
}
