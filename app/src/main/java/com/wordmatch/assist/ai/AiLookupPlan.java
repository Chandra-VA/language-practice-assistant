package com.wordmatch.assist.ai;

import com.wordmatch.assist.matching.ExactPairSelector;
import com.wordmatch.assist.model.MatchPair;
import com.wordmatch.assist.model.WordBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keep translation knowledge separate from temporary clickability and completion state. */
public final class AiLookupPlan {
    public final AiBoardRequest request;
    public final int localPairCount;

    private AiLookupPlan(AiBoardRequest request, int localPairCount) {
        this.request = request;
        this.localPairCount = localPairCount;
    }

    public static AiLookupPlan from(List<MatchPair> visibleMatches,
                                    List<WordBox> remaining, List<WordBox> active, int width) {
        return from(visibleMatches, Collections.emptyList(), remaining, active, width);
    }

    public static AiLookupPlan from(List<MatchPair> visibleMatches, List<MatchPair> activeMatches,
                                    List<WordBox> remaining, List<WordBox> active, int width) {
        // Clickable duplicates take priority over stale grey occurrences. Retain full-board
        // knowledge too: a temporarily disabled partner must not manufacture an AI question.
        List<MatchPair> known = new ArrayList<>(ExactPairSelector.allExact(activeMatches));
        known.addAll(ExactPairSelector.allExact(visibleMatches));
        Set<WordBox> counted = new HashSet<>();
        int pending = 0;
        for (MatchPair pair : known) {
            if (remaining.contains(pair.getFirst()) && remaining.contains(pair.getSecond())
                    && !counted.contains(pair.getFirst()) && !counted.contains(pair.getSecond())) {
                pending++;
                counted.add(pair.getFirst());
                counted.add(pair.getSecond());
            }
        }
        return new AiLookupPlan(AiBoardRequest.from(active, known, width), pending);
    }

    public boolean hasLocalPairs() { return localPairCount > 0; }
}
