package com.wordmatch.assist.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Region;
import android.view.View;

import com.wordmatch.assist.model.MatchPair;
import com.wordmatch.assist.model.WordBox;
import com.wordmatch.assist.matching.GreenCardDetector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Non-interactive transparent layer that marks both tiles in a pair. */
public final class MatchOverlayView extends View {
    public static final int STATUS_WORKING = 0xFF1CB0F6;
    public static final int STATUS_OK = 0xFF58A700;
    public static final int STATUS_WARNING = 0xFFFF9600;
    public static final int STATUS_ERROR = 0xFFFF4B4B;

    private static final int[] COLORS = {
            0xFF58CC02,
            0xFF1CB0F6,
            0xFFFF9600,
            0xFFCE82FF,
            0xFFFF4B4B,
            0xFF2B70C9
    };

    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final int[] screenLocation = new int[2];
    private final StablePairHighlights labels = new StablePairHighlights();
    private List<StablePairHighlights.Tile> tiles = Collections.emptyList();
    private List<WordBox> visibleWords = Collections.emptyList();
    private List<WordBox> samplingCards = Collections.emptyList();
    private boolean matchesHidden;
    private String statusText = "";
    private int statusColor = STATUS_WORKING;

    public MatchOverlayView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        // Draw on the accelerated overlay window; avoid uploading a full-screen
        // software bitmap on every status/selection update.
        setLayerType(View.LAYER_TYPE_NONE, null);

        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(3.5f * density);

        highlightPaint.setStyle(Paint.Style.FILL);
        fillPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(15f * density);

        statusPaint.setStyle(Paint.Style.FILL);
        statusTextPaint.setTextAlign(Paint.Align.CENTER);
        statusTextPaint.setColor(0xFFFFFFFF);
        statusTextPaint.setFakeBoldText(true);
        statusTextPaint.setTextSize(13f * density);
    }

    public void updateHighlights(List<WordBox> words, List<MatchPair> pairs, boolean complete) {
        if (!complete) return;
        if (words == null || words.isEmpty()) {
            hideMatches();
            return;
        }
        boolean changed = matchesHidden || !visibleWords.equals(words);
        List<StablePairHighlights.Tile> next = labels.update(words, pairs, true);
        if (next.size() != tiles.size()) changed = true;
        for (int i = 0; !changed && i < next.size(); i++) {
            if (next.get(i).label != tiles.get(i).label || !next.get(i).word.equals(tiles.get(i).word)) changed = true;
        }
        visibleWords = new ArrayList<>(words);
        tiles = next;
        matchesHidden = false;
        if (changed) invalidate();
    }

    public void addMatch(MatchPair pair) {
        updateHighlights(visibleWords, Collections.singletonList(pair), true);
    }

    public void hideMatches() {
        if (matchesHidden) return;
        matchesHidden = true;
        invalidate();
    }

    public void setSamplingCards(List<WordBox> cards) {
        if (samplingCards.equals(cards)) return;
        samplingCards = new ArrayList<>(cards);
        invalidate();
    }

    public void clear() {
        clearMatches();
        statusText = "";
        invalidate();
    }

    public void clearMatches() {
        labels.clear();
        tiles = Collections.emptyList();
        visibleWords = Collections.emptyList();
        samplingCards = Collections.emptyList();
        matchesHidden = false;
        invalidate();
    }

    public void showStatus(String message, int color) {
        String next = message == null ? "" : message.trim();
        if (next.equals(statusText) && statusColor == color) return;
        statusText = next;
        statusColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float padding = 9f * density;
        float radius = 11f * density;
        float badgeRadius = 13f * density;
        int save = canvas.save();
        // Accessibility bounds / captured pixels use screen coordinates. Overlay windows
        // can begin below a cutout/status bar even with FLAG_LAYOUT_IN_SCREEN.
        getLocationOnScreen(screenLocation);
        canvas.translate(-screenLocation[0], -screenLocation[1]);
        for (WordBox card : samplingCards) {
            if (card == null) continue;
            // Clip only small sample patches; keep every word's marker visible.
            for (int[] point : GreenCardDetector.samplePoints(card)) {
                // Include up to 8px of press/release movement used by confirmation.
                float margin = Math.max(12f, 5f * density);
                //noinspection deprecation
                canvas.clipRect(point[0] - margin, point[1] - margin,
                        point[0] + margin + 1, point[1] + margin + 1, Region.Op.DIFFERENCE);
            }
        }
        for (WordBox word : visibleWords) {
            // The label itself must remain unpainted so live grey-state sampling
            // sees Duolingo's glyphs, even under a long colored highlight.
            canvas.clipRect(word.getLeft(), word.getTop(), word.getRight(), word.getBottom(), Region.Op.DIFFERENCE);
        }
        for (StablePairHighlights.Tile tile : tiles) {
            if (matchesHidden) break;
            int color = COLORS[(tile.label - 1) % COLORS.length];
            outlinePaint.setColor(color);
            highlightPaint.setColor((color & 0x00FFFFFF) | 0x50000000);
            fillPaint.setColor(color);
            drawWord(canvas, tile.word, tile.label, padding, radius, badgeRadius);
        }
        canvas.restoreToCount(save);
        drawStatus(canvas);
    }

    private void drawStatus(Canvas canvas) {
        if (statusText.isEmpty() || getWidth() <= 0) {
            return;
        }
        float horizontalPadding = 13f * density;
        float maxTextWidth = Math.max(1f, getWidth() - 40f * density);
        String visibleText = ellipsize(statusText, maxTextWidth);
        float textWidth = statusTextPaint.measureText(visibleText);
        float centerX = getWidth() / 2f;
        float top = 38f * density;
        float bottom = top + 38f * density;
        RectF banner = new RectF(
                centerX - textWidth / 2f - horizontalPadding,
                top,
                centerX + textWidth / 2f + horizontalPadding,
                bottom
        );
        statusPaint.setColor(statusColor);
        canvas.drawRoundRect(banner, 19f * density, 19f * density, statusPaint);
        float baseline = banner.centerY()
                - (statusTextPaint.ascent() + statusTextPaint.descent()) / 2f;
        canvas.drawText(visibleText, centerX, baseline, statusTextPaint);
    }

    private String ellipsize(String value, float maxWidth) {
        if (statusTextPaint.measureText(value) <= maxWidth) {
            return value;
        }
        String suffix = "…";
        int end = value.length();
        while (end > 1
                && statusTextPaint.measureText(value.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return value.substring(0, Math.max(1, end)) + suffix;
    }

    private void drawWord(
            Canvas canvas,
            WordBox word,
            int label,
            float padding,
            float radius,
            float badgeRadius
    ) {
        RectF rect = new RectF(
                word.getLeft() - padding,
                word.getTop() - padding,
                word.getRight() + padding,
                word.getBottom() + padding
        );
        canvas.drawRoundRect(rect, radius, radius, highlightPaint);
        canvas.drawRoundRect(rect, radius, radius, outlinePaint);

        float badgeX = Math.max(badgeRadius + 2f * density, rect.left);
        float badgeY = Math.max(badgeRadius + 2f * density, rect.top);
        canvas.drawCircle(badgeX, badgeY, badgeRadius, fillPaint);
        float baseline = badgeY - (textPaint.ascent() + textPaint.descent()) / 2f;
        canvas.drawText(String.valueOf(label), badgeX, baseline, textPaint);
    }
}
