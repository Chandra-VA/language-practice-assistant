package com.wordmatch.assist.matching;

import com.wordmatch.assist.model.WordBox;

/** Samples the card background on both sides of its text, not the colored word badge. */
public final class GreenCardDetector {
    private static final float[] X = {0.12f, 0.22f, 0.78f, 0.88f};
    // Above/below the text band, including wide multi-word labels.
    private static final float[] Y = {0.18f, 0.25f, 0.82f};

    private GreenCardDetector() {}

    public interface Pixels {
        int colorAt(int x, int y);
    }

    public static boolean isGreenCard(Pixels pixels, int width, int height, WordBox card) {
        if (pixels == null || card == null || card.getWidth() < 20 || card.getHeight() < 12
                || card.getLeft() < 0 || card.getTop() < 0
                || card.getRight() > width || card.getBottom() > height) {
            return false;
        }
        int green = 0;
        for (int[] point : samplePoints(card)) {
            if (isSuccessGreen(pixels.colorAt(point[0], point[1]))) green++;
        }
        return green >= 11;
    }

    /** Also used by the overlay to leave these exact background pixels unobstructed. */
    public static int[][] samplePoints(WordBox card) {
        int[][] points = new int[X.length * Y.length][2];
        int index = 0;
        for (float x : X) {
            for (float y : Y) {
                points[index][0] = card.getLeft() + (int) (card.getWidth() * x);
                points[index++][1] = card.getTop() + (int) (card.getHeight() * y);
            }
        }
        return points;
    }

    private static boolean isSuccessGreen(int color) {
        int red = (color >>> 16) & 255;
        int green = (color >>> 8) & 255;
        int blue = color & 255;
        return (color >>> 24) >= 220 && red >= 110 && green >= 180
                && green - red >= 15 && red - blue >= 10 && green - blue >= 30;
    }
}
