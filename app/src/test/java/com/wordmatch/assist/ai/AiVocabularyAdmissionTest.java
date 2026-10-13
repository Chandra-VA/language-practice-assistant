package com.wordmatch.assist.ai;

import com.wordmatch.assist.matching.*;
import com.wordmatch.assist.model.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AiVocabularyAdmissionTest {
    private static WordBox word(String text, int x, int y) {
        return new WordBox(text, x, y, x + 80, y + 30);
    }
    private static String key(String a, String b) {
        a = WordNormalizer.normalize(a); b = WordNormalizer.normalize(b);
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }
    private static TranslationLookup known(String... pairs) {
        Set<String> edges = new HashSet<>();
        for (int i=0; i<pairs.length; i+=2) edges.add(key(pairs[i],pairs[i+1]));
        return (a,b) -> edges.contains(key(a,b)) ? 1.0 : 0.0;
    }
    private static List<WordBox> board() {
        return Arrays.asList(word("西",100,300),word("东",100,600),
                word("east",700,300),word("west",700,600));
    }

    @Test public void observedEastWestErrorCannotPoisonExistingWestPair() {
        TranslationLookup vocabulary = known("西","west");
        assertTrue(AiVocabularyAdmission.conflicts("东","west",board(),1000,vocabulary));
        assertFalse(AiVocabularyAdmission.conflicts("东","east",board(),1000,vocabulary));
        TranslationLookup after = known("西","west","东","east");
        assertEquals(2, new PairMatcher(after).match(board(),1000).size());
        assertEquals(0, new PairMatcher(known("西","west","东","east","东","west"))
                .match(board(),1000).size());
    }
    @Test public void existingPartnerInEitherColumnPreventsConflictingAddition() {
        assertTrue(AiVocabularyAdmission.conflicts("西","east",board(),1000,known("西","west")));
        List<WordBox> reversed = Arrays.asList(word("east",100,300),word("west",100,600),
                word("西",700,300),word("东",700,600));
        assertTrue(AiVocabularyAdmission.conflicts("west","东",reversed,1000,known("西","west")));
    }
    @Test public void repeatedOccurrencesOfTheSameWordAreNotDifferentTranslations() {
        List<WordBox> duplicate = new ArrayList<>(board()); duplicate.add(word("西",100,900));
        assertFalse(AiVocabularyAdmission.conflicts("西","west",duplicate,1000,known("西","west")));
    }
    @Test public void offBoardSynonymsAndFuzzySimilarityDoNotRejectValidModelPairs() {
        assertFalse(AiVocabularyAdmission.conflicts("东","east",board(),1000,known("东边","east")));
        assertFalse(AiVocabularyAdmission.conflicts("东","east",board(),1000,(a,b)->0.9));
    }
    @Test public void repeatedExistingResponsesAreFailureRatherThanEndlessSuccessCooldowns() {
        for(int attempt=0;attempt<10;attempt++) {
            assertEquals("模型重复返回已有词对，未解决当前匹配",
                    AiVocabularyAdmission.failureReason(0,2,0));
        }
    }
    @Test public void rejectedConflictsAndStorageFailuresGiveSpecificReasons() {
        assertTrue(AiVocabularyAdmission.failureReason(0,0,1).contains("冲突"));
        assertTrue(AiVocabularyAdmission.failureReason(0,2,1).contains("冲突"));
        assertTrue(AiVocabularyAdmission.failureReason(0,0,0).contains("保存失败"));
    }
    @Test public void usefulPartialAnswersStillContinueOtherPairs() {
        assertEquals("",AiVocabularyAdmission.failureReason(1,1,1));
        assertEquals("",AiVocabularyAdmission.failureReason(2,3,0));
    }
}
