package com.wordmatch.assist.accessibility;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.wordmatch.assist.matching.MatchingExerciseDetector;
import com.wordmatch.assist.matching.MatchStreakReader;
import com.wordmatch.assist.matching.MatchProgressReader;
import com.wordmatch.assist.matching.TileControlState;
import com.wordmatch.assist.matching.PauseConfirmationDetector;
import com.wordmatch.assist.model.WordBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Extracts likely word tiles directly from another app's accessibility node tree. */
final class AccessibilityWordExtractor {
    private static final int MAX_NODES = 2400;

    private AccessibilityWordExtractor() {
    }

    static List<WordBox> extract(AccessibilityNodeInfo root, int width, int height) {
        return extractPage(root, width, height).getWords();
    }

    static Extraction extractPage(AccessibilityNodeInfo root, int width, int height) {
        List<WordBox> candidates = new ArrayList<>();
        List<Rect> selectedBounds = new ArrayList<>();
        boolean matchingPromptFound = false;
        PauseConfirmationDetector pauseDetector = new PauseConfirmationDetector();
        MatchStreakReader streakReader = new MatchStreakReader();
        MatchProgressReader progressReader = new MatchProgressReader();
        TileControlState controls = new TileControlState();
        int visited = 0;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        if (root != null) {
            queue.add(root);
        }
        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            controls.observe(node.isClickable(), node.isEnabled(), node.isVisibleToUser(),
                    bounds.left, bounds.top, bounds.right, bounds.bottom, width, height);
            // Selection is often exposed on the clickable parent, not its text leaf.
            if ((node.isSelected() || node.isChecked()) && isTileControlBounds(bounds, width, height)) {
                selectedBounds.add(new Rect(bounds));
            }
            CharSequence nodeText = node.getText();
            CharSequence description = node.getContentDescription();
            progressReader.observeText(nodeText == null ? null : nodeText.toString(),
                    bounds.left, bounds.top, bounds.right, bounds.bottom, width, height);
            progressReader.observeText(description == null ? null : description.toString(),
                    bounds.left, bounds.top, bounds.right, bounds.bottom, width, height);
            streakReader.observe(nodeText == null ? null : nodeText.toString(),
                    bounds.left, bounds.top, bounds.right, bounds.bottom, width, height);
            streakReader.observe(description == null ? null : description.toString(),
                    bounds.left, bounds.top, bounds.right, bounds.bottom, width, height);
            pauseDetector.observe(nodeText == null ? null : nodeText.toString(),
                    bounds.left, bounds.top, bounds.right, bounds.bottom, width, height);
            pauseDetector.observe(description == null ? null : description.toString(),
                    bounds.left, bounds.top, bounds.right, bounds.bottom, width, height);
            if (!matchingPromptFound && MatchingExerciseDetector.isMatchingPromptNode(
                    nodeText == null ? null : nodeText.toString(),
                    description == null ? null : description.toString(),
                    bounds.left, bounds.top, bounds.right, bounds.bottom, width, height
            )) {
                matchingPromptFound = true;
            }
            if (nodeText == null || nodeText.toString().trim().isEmpty()) {
                nodeText = node.getContentDescription();
            }
            if (nodeText != null) {
                String text = nodeText.toString().replace('\n', ' ').trim();
                // Text leaves may report disabled/hidden while their tile parent
                // is clickable. Validate interactability when locating the click.
                if (isLikelyTile(text, bounds, width, height)) {
                    outputWord(candidates, text, bounds, width, height);
                }
            }
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) {
                    queue.addLast(child);
                }
            }
            if (node != root) {
                //noinspection deprecation
                node.recycle();
            }
        }
        boolean nodeLimitReached = !queue.isEmpty();
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node != root) {
                //noinspection deprecation
                node.recycle();
            }
        }
        Collections.sort(candidates, new Comparator<WordBox>() {
            @Override
            public int compare(WordBox first, WordBox second) {
                int topOrder = Integer.compare(first.getTop(), second.getTop());
                return topOrder != 0
                        ? topOrder
                        : Integer.compare(first.getLeft(), second.getLeft());
            }
        });
        List<WordBox> words = removeNearDuplicates(candidates);
        List<WordBox> selectedWords = new ArrayList<>();
        for (WordBox word : words) {
            if (controls.isInactive(word)) {
                continue;
            }
            for (Rect selected : selectedBounds) {
                if (selected.contains(word.getCenterX(), word.getCenterY())) {
                    selectedWords.add(word);
                    break;
                }
            }
        }
        return new Extraction(
                words,
                selectedWords,
                controls,
                streakReader.getCount(width),
                progressReader.getReading(width),
                matchingPromptFound,
                pauseDetector.isVisible(),
                visited,
                nodeLimitReached
        );
    }

    private static void outputWord(
            List<WordBox> output,
            String text,
            Rect bounds,
            int width,
            int height
    ) {
        output.add(new WordBox(
                text,
                clamp(bounds.left, 0, width),
                clamp(bounds.top, 0, height),
                clamp(bounds.right, 0, width),
                clamp(bounds.bottom, 0, height)
        ));
    }

    private static boolean isTileControlBounds(Rect bounds, int width, int height) {
        return bounds.width() > 0 && bounds.height() > 0
                && bounds.width() <= width * 0.55f && bounds.height() <= height * 0.18f
                && bounds.top >= height * 0.12f && bounds.bottom <= height * 0.96f
                && Math.abs(bounds.centerX() - width / 2) >= width * 0.035f;
    }

    private static boolean isLikelyTile(String text, Rect bounds, int width, int height) {
        return TileTextPolicy.accepts(text, bounds.left, bounds.top,
                bounds.right, bounds.bottom, width, height);
    }
    private static List<WordBox> removeNearDuplicates(List<WordBox> words) {
        List<WordBox> unique = new ArrayList<>();
        for (WordBox candidate : words) {
            boolean duplicate = false;
            for (WordBox existing : unique) {
                if (candidate.getNormalized().equals(existing.getNormalized())
                        && Math.abs(candidate.getCenterX() - existing.getCenterX()) < 24
                        && Math.abs(candidate.getCenterY() - existing.getCenterY()) < 24) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique.add(candidate);
            }
        }
        return unique;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class Extraction {
        private final List<WordBox> words;
        private final List<WordBox> selectedWords;
        private final TileControlState controls;
        private final int streakCount;
        private final MatchProgressReader.Reading progress;
        private final boolean matchingExercise;
        private final boolean pauseConfirmationVisible;
        private final int visitedNodeCount;
        private final boolean nodeLimitReached;

        private Extraction(
                List<WordBox> words,
                List<WordBox> selectedWords,
                TileControlState controls,
                int streakCount,
                MatchProgressReader.Reading progress,
                boolean matchingExercise,
                boolean pauseConfirmationVisible,
                int visitedNodeCount,
                boolean nodeLimitReached
        ) {
            this.words = words;
            this.selectedWords = selectedWords;
            this.controls = controls;
            this.streakCount = streakCount;
            this.progress = progress;
            this.matchingExercise = matchingExercise;
            this.pauseConfirmationVisible = pauseConfirmationVisible;
            this.visitedNodeCount = visitedNodeCount;
            this.nodeLimitReached = nodeLimitReached;
        }

        List<WordBox> getWords() {
            return words;
        }

        List<WordBox> getSelectedWords() {
            return selectedWords;
        }

        Extraction withVisualSelection(List<WordBox> visualSelected) {
            if (visualSelected.isEmpty()) return this;
            List<WordBox> combined = new ArrayList<>(selectedWords);
            for (WordBox word : visualSelected) {
                if (words.contains(word) && !combined.contains(word)) combined.add(word);
            }
            // Conflicting node/pixel selections remain multiple selections and block new clicks.
            return new Extraction(words, combined, controls, streakCount, progress, matchingExercise,
                    pauseConfirmationVisible, visitedNodeCount, nodeLimitReached);
        }

        boolean isInactive(WordBox word) {
            return controls.isInactive(word);
        }

        boolean hasActiveControl(WordBox word) { return controls.hasActiveControl(word); }

        List<WordBox> withoutInactiveWords(List<WordBox> remaining) {
            return controls.withoutInactiveWords(remaining);
        }

        WordBox getCardBounds(WordBox word, int screenWidth) {
            return controls.cardBounds(word, screenWidth);
        }

        int getStreakCount() {
            return streakCount;
        }

        MatchProgressReader.Reading getProgress() {
            return progress;
        }

        boolean isMatchingExercise() {
            return matchingExercise;
        }

        boolean isPauseConfirmationVisible() {
            return pauseConfirmationVisible;
        }

        int getVisitedNodeCount() {
            return visitedNodeCount;
        }

        boolean isNodeLimitReached() {
            return nodeLimitReached;
        }
    }
}
