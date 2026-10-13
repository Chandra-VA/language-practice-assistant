package com.wordmatch.assist.overlay;

import com.wordmatch.assist.model.MatchPair;
import com.wordmatch.assist.model.WordBox;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

/** Labels belong to visible pair occurrences, independently of the automatic click queue. */
public final class StablePairHighlights {
    private final List<Entry> entries = new ArrayList<>();

    public List<Tile> update(List<WordBox> words, List<MatchPair> pairs, boolean complete) {
        if (!complete || words == null || words.isEmpty()) return tiles();
        Set<Integer> used = new HashSet<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (Entry entry : entries) {
            for (WordBox word : new WordBox[]{entry.first, entry.second}) {
                String key = word.getNormalized();
                occurrences.put(key, occurrences.containsKey(key) ? occurrences.get(key) + 1 : 1);
            }
        }
        Map<String, Integer> visibleOccurrences = new HashMap<>();
        for (WordBox word : words) {
            String key = word.getNormalized();
            visibleOccurrences.put(key, visibleOccurrences.getOrDefault(key, 0) + 1);
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            WordBox first = find(words, entry.first, used, occurrences.get(entry.first.getNormalized()) == 1
                    && visibleOccurrences.getOrDefault(entry.first.getNormalized(), 0) == 1);
            WordBox second = find(words, entry.second, used, occurrences.get(entry.second.getNormalized()) == 1
                    && visibleOccurrences.getOrDefault(entry.second.getNormalized(), 0) == 1);
            entry.firstVisible = first != null;
            entry.secondVisible = second != null;
            if (first != null) entry.first = first;
            if (second != null) entry.second = second;
            if (!entry.firstVisible && !entry.secondVisible) entries.remove(i);
        }
        if (pairs != null) {
            for (MatchPair pair : pairs) {
                int first = words.indexOf(pair.getFirst());
                int second = words.indexOf(pair.getSecond());
                if (first < 0 || second < 0 || first == second || used.contains(first) || used.contains(second)) continue;
                entries.add(new Entry(pair.getFirst(), pair.getSecond(), freeLabel()));
                used.add(first);
                used.add(second);
            }
        }
        return tiles();
    }

    public void clear() {
        entries.clear();
    }

    private int freeLabel() {
        int label = 1;
        while (true) {
            boolean occupied = false;
            for (Entry entry : entries) {
                if (entry.label == label) { occupied = true; break; }
            }
            if (!occupied) return label;
            label++;
        }
    }

    private static WordBox find(List<WordBox> words, WordBox old, Set<Integer> used, boolean unique) {
        int best = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < words.size(); i++) {
            WordBox word = words.get(i);
            if (used.contains(i) || !old.getNormalized().equals(word.getNormalized())) continue;
            long dx = (long) word.getCenterX() - old.getCenterX();
            long dy = (long) word.getCenterY() - old.getCenterY();
            long distance = dx * dx + dy * dy;
            long tolerance = Math.max(24, old.getHeight());
            if (!unique && distance > tolerance * tolerance) continue;
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        if (best < 0) return null;
        used.add(best);
        return words.get(best);
    }

    private List<Tile> tiles() {
        List<Tile> tiles = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.firstVisible) tiles.add(new Tile(entry.first, entry.label));
            if (entry.secondVisible) tiles.add(new Tile(entry.second, entry.label));
        }
        return tiles;
    }

    private static final class Entry {
        WordBox first, second;
        final int label;
        boolean firstVisible = true, secondVisible = true;
        Entry(WordBox first, WordBox second, int label) {
            this.first = first;
            this.second = second;
            this.label = label;
        }
    }

    public static final class Tile {
        public final WordBox word;
        public final int label;
        Tile(WordBox word, int label) {
            this.word = word;
            this.label = label;
        }
    }
}
