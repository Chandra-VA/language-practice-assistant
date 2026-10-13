package com.wordmatch.assist.ai;

import com.wordmatch.assist.matching.PairMatcher;
import com.wordmatch.assist.matching.WordNormalizer;
import com.wordmatch.assist.model.MatchPair;
import com.wordmatch.assist.model.WordBox;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public final class AiLookupPlanTest {
    private static WordBox word(String text, int x, int y) {
        return new WordBox(text, x, y, x + 80, y + 30);
    }

    private static PairMatcher matcher(String... translations) {
        Set<String> edges = new HashSet<>();
        for (int i = 0; i < translations.length; i += 2) {
            edges.add(key(translations[i], translations[i + 1]));
        }
        return new PairMatcher((a, b) -> edges.contains(key(a, b)) ? 1.0 : 0.0);
    }

    private static String key(String a, String b) {
        a = WordNormalizer.normalize(a);
        b = WordNormalizer.normalize(b);
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    @Test public void recordingFiveKnownPairsRemainLocalEvenWhenPauseDisablesControls() {
        String[] left = {"简历", "不喜欢", "感觉", "垃圾", "意思"};
        String[] right = {"dislike", "résumé", "garbage", "feeling", "meaning"};
        List<WordBox> board = new ArrayList<>();
        for (int i = 0; i < left.length; i++) {
            board.add(word(left[i], 100, 300 + i * 80));
            board.add(word(right[i], 700, 300 + i * 80));
        }
        PairMatcher matcher = matcher("简历", "résumé", "不喜欢", "dislike",
                "感觉", "feeling", "垃圾", "garbage", "意思", "meaning");
        List<MatchPair> matches = matcher.match(board, 1000);
        AiLookupPlan disabled = AiLookupPlan.from(matches, board, Collections.emptyList(), 1000);
        assertEquals(5, disabled.localPairCount);
        assertFalse(disabled.request.needsModel());
        AiLookupPlan restored = AiLookupPlan.from(matches, board, board, 1000);
        assertEquals(5, restored.localPairCount);
        assertFalse(restored.request.needsModel());
    }

    @Test public void disabledPartnersDoNotManufactureUnknownCardsOnOppositeSides() {
        List<WordBox> board = Arrays.asList(word("苹果",100,300),word("apple",700,400),
                word("水",100,400),word("water",700,300),
                word("车费",100,500),word("fare",700,600),
                word("类似",100,600),word("similar",700,500));
        List<WordBox> active = new ArrayList<>(board);
        active.remove(board.get(1));
        active.remove(board.get(2));
        AiLookupPlan plan = AiLookupPlan.from(matcher("苹果","apple","水","water").match(board,1000),
                board, active, 1000);
        assertEquals(2, plan.localPairCount);
        assertEquals(Arrays.asList("车费","类似"), plan.request.left);
        assertEquals(Arrays.asList("fare","similar"), plan.request.right);
        assertTrue(plan.request.needsModel()); // Prefetch may continue; local state recovery stays first.
    }

    @Test public void completedKnownPairsDoNotBlockUnknownWordLookup() {
        List<WordBox> board = Arrays.asList(word("苹果",100,300),word("apple",700,300),
                word("车费",100,400),word("fare",700,500),
                word("类似",100,500),word("similar",700,400));
        List<WordBox> remaining = board.subList(2, board.size());
        AiLookupPlan plan = AiLookupPlan.from(matcher("苹果","apple").match(board,1000),
                remaining, remaining,1000);
        assertFalse(plan.hasLocalPairs());
        assertTrue(plan.request.needsModel());
        assertEquals(Arrays.asList("车费","类似"), plan.request.left);
    }

    @Test public void partiallyFadedCompletedPartnerIsNotSentAsUnknown() {
        List<WordBox> board = Arrays.asList(word("苹果",100,300),word("apple",700,300),
                word("车费",100,400),word("fare",700,500),
                word("类似",100,500),word("similar",700,400));
        List<WordBox> remaining = board.subList(1, board.size());
        AiLookupPlan plan = AiLookupPlan.from(matcher("苹果","apple").match(board,1000),
                remaining,remaining,1000);
        assertFalse(plan.hasLocalPairs());
        assertEquals(Arrays.asList("fare","similar"), plan.request.right);
    }

    @Test public void fuzzyHighlightsDoNotAuthorizeLocalAutomaticAnswers() {
        List<WordBox> board = Arrays.asList(word("未知一",100,300),word("first",700,300),
                word("未知二",100,400),word("second",700,400));
        AiLookupPlan plan = AiLookupPlan.from(new PairMatcher((a,b) -> .9).match(board,1000),
                board,board,1000);
        assertFalse(plan.hasLocalPairs());
        assertTrue(plan.request.needsModel());
        assertEquals(2,plan.request.left.size());
    }

    @Test public void duplicateUnknownOccurrencesStillReachTheResponseValidator() {
        List<WordBox> board = Arrays.asList(word("x",100,300),word("x",100,400),
                word("y",700,300),word("z",700,400));
        AiLookupPlan plan = AiLookupPlan.from(Collections.emptyList(),board,board,1000);
        assertEquals(Arrays.asList("x","x"),plan.request.left);
        assertTrue(plan.request.needsModel());
    }

    @Test public void oneUnknownPairAfterKnownCardsNeedsNoModel() {
        List<WordBox> board = Arrays.asList(word("苹果",100,300),word("apple",700,300),
                word("尾词",100,400),word("last",700,400));
        AiLookupPlan plan = AiLookupPlan.from(matcher("苹果","apple").match(board,1000),board,board,1000);
        assertTrue(plan.hasLocalPairs());
        assertFalse(plan.request.needsModel());
    }
    @Test public void greyDuplicateCannotManufactureUnknownActiveTranslation() {
        WordBox oldApple = word("苹果",100,300);
        List<WordBox> board = Arrays.asList(oldApple, word("苹果",100,400),
                word("apple",700,400), word("车费",100,500), word("fare",700,600),
                word("类似",100,600), word("similar",700,500));
        List<WordBox> active = board.subList(1, board.size());
        PairMatcher matcher = matcher("苹果","apple");
        AiLookupPlan plan = AiLookupPlan.from(matcher.match(board,1000), matcher.match(active,1000),
                active,active,1000);
        assertEquals(1,plan.localPairCount);
        assertEquals(Arrays.asList("车费","类似"),plan.request.left);
        assertEquals(Arrays.asList("fare","similar"),plan.request.right);
    }

    @Test public void knownActiveBoardIsNotBlockedByStaleGreyAmbiguity() {
        List<WordBox> board = Arrays.asList(word("苹果",100,300),word("apple",700,300),
                word("水",100,400),word("water",700,400),word("旧译",100,500));
        List<WordBox> active = board.subList(0,4);
        PairMatcher matcher = matcher("苹果","apple","水","water","旧译","apple");
        AiLookupPlan plan = AiLookupPlan.from(matcher.match(board,1000), matcher.match(active,1000),
                active,active,1000);
        assertEquals(2,plan.localPairCount);
        assertFalse(plan.request.needsModel());
        assertTrue(plan.request.left.isEmpty());
        assertTrue(plan.request.right.isEmpty());
    }

    @Test public void activeAndWholeBoardKnowledgeCountEachPairOnce() {
        List<WordBox> board = Arrays.asList(word("苹果",100,300),word("apple",700,300));
        List<MatchPair> matches = matcher("苹果","apple").match(board,1000);
        AiLookupPlan plan = AiLookupPlan.from(matches,matches,board,board,1000);
        assertEquals(1,plan.localPairCount);
        assertFalse(plan.request.needsModel());
    }

}
