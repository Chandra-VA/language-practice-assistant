package com.wordmatch.assist.sentence;
import org.junit.Test;
import org.json.JSONObject;
import java.util.*;
import static org.junit.Assert.*;
public class SentencePlanTest {
    private List<String> words(String... w) { return Arrays.asList(w); }
    @Test public void bothDirectionsKeepEntireSentenceAndIgnoreDistractors() throws Exception {
        assertEquals("zh-en",SentencePlan.direction("我也开始制作网站了。"));
        assertEquals("en-zh",SentencePlan.direction("Have you made your decision yet?"));
        assertEquals(words("I've","also","begun","making","websites"), SentencePlan.validateIds(
                words("also","begun","I've","isn't","making","to","travel","websites"),
                new JSONObject("{order:[2,0,1,4,7],confidence:0.99}")));
        assertEquals(words("你","决定","了","吗"), SentencePlan.validateIds(words("吗","一个","决定","你","了"),
                new JSONObject("{order:[3,2,4,0],confidence:0.99}")));
    }
    @Test public void repeatedWordsRequireDistinctOccurrences() throws Exception {
        List<String> tokens = words("I","that","know","that");
        assertEquals(words("I","know","that","that"),SentencePlan.validateIds(tokens,new JSONObject("{order:[0,2,1,3],confidence:1}")));
        assertEquals(words("I","know","that","that"),SentencePlan.validateIds(tokens,new JSONObject("{order:[0,2,1,1],confidence:1}")));
        assertTrue(SentencePlan.validateIds(tokens,new JSONObject("{order:[0,2,1,1,3],confidence:1}")).isEmpty());
        assertFalse(SentencePlan.available(words("that","that"),words("that")));
    }
    @Test public void rejectsMalformedUnsafeOrUncertainIds() throws Exception {
        for (String ids : words("[0,4]","[-1]","[0.5]","[\"0\"]","[true]","[]"))
            assertTrue(SentencePlan.validateIds(words("hello"),new JSONObject("{order:"+ids+",confidence:1}")).isEmpty());
        for (double confidence : new double[]{0.5,0.91,1.1})
            assertTrue(SentencePlan.validateIds(words("hello"),new JSONObject("{order:[0],confidence:"+confidence+"}")).isEmpty());
    }
    @Test public void reportsEmptyAnswerMissingScoreAndInvalidScoreSeparately() throws Exception {
        assertEquals("模型未给出完整组句（空答案）",SentencePlan.validationError(words("hello"),new JSONObject("{order:[],confidence:0}")));
        assertEquals("模型未返回置信度",SentencePlan.validationError(words("hello"),new JSONObject("{order:[0]}")));
        assertEquals("模型未返回置信度",SentencePlan.validationError(words("hello"),new JSONObject("{order:[0],confidence:null}")));
        for(String invalid:words("1.1","-0.1","true","\"high\"","\"NaN\"","\"Infinity\""))
            assertEquals("模型置信度格式无效（须为 0～1）",SentencePlan.validationError(words("hello"),new JSONObject("{order:[0],confidence:"+invalid+"}")));
        assertEquals("模型返回了不存在的词块编号",SentencePlan.validationError(words("hello"),new JSONObject("{order:[8],confidence:0.5}")));
    }
    @Test public void lowConfidenceDisplaysActualValueWithoutChangingAcceptanceThreshold() throws Exception {
        assertEquals("模型置信度 0.900，未达到 0.92",SentencePlan.validationError(words("hello"),new JSONObject("{order:[0],confidence:0.90}")));
        assertTrue(SentencePlan.validateIds(words("hello"),new JSONObject("{order:[0],confidence:0.919}" )).isEmpty());
        assertEquals(words("hello"),SentencePlan.validateIds(words("hello"),new JSONObject("{order:[0],confidence:0.92}")));
    }
    @Test public void diagnosticMetadataNeverIncludesArbitraryResponseStrings() throws Exception {
        assertEquals("confidence=invalid order_count=1",SentencePlan.responseSummary(new JSONObject("{order:[0],confidence:\"private arbitrary content\"}")));
        assertEquals("confidence=missing order_count=-1",SentencePlan.responseSummary(new JSONObject("{}")));
        assertEquals("confidence=0.000 order_count=0",SentencePlan.responseSummary(new JSONObject("{order:[],confidence:0}")));
    }
    @Test public void partialPrefixResumesAndWrongPrefixClearsFromEnd() {
        List<String> answer=words("你","决定","了","吗");
        assertEquals(2,SentencePlan.next(answer,words("你","决定")));
        assertEquals(-2,SentencePlan.next(answer,words("你","吗")));
        assertEquals(-2,SentencePlan.next(answer,words("你","决定","了","吗","吗")));
        assertEquals(-1,SentencePlan.next(answer,answer));
    }
    @Test public void shuffledBankHasSameIdentityButChangedCountsDoNot() {
        assertEquals(SentencePlan.bankKey(words("a","b","a")),SentencePlan.bankKey(words("b","a","a")));
        assertNotEquals(SentencePlan.bankKey(words("a","b","a")),SentencePlan.bankKey(words("a","b")));
        assertNotEquals(SentencePlan.key("You are here."),SentencePlan.key("You are here?"));
    }
    @Test public void memoryRequiresCorrectFeedbackForSameObservedSelection() throws Exception {
        SentenceMemory memory = new SentenceMemory(); List<String> answer=words("You","are","here");
        assertFalse(memory.remember("你在这里。",answer,false,answer));
        assertFalse(memory.remember("你在这里。",answer,true,words("You","here")));
        assertTrue(memory.entries().isEmpty());
        assertTrue(memory.remember("你在这里。",answer,true,answer));
        SentenceMemory restored=SentenceMemory.decode(memory.encode());
        assertEquals(answer,restored.lookup("你在这里。",words("here","are","You","there")));
        assertTrue(restored.lookup("你在这里。",words("here","You")).isEmpty());
        assertTrue(restored.lookup("你在哪里？",answer).isEmpty());
        restored.remove("你在这里。",answer);
        assertTrue(restored.lookup("你在这里。",answer).isEmpty());
    }
    @Test public void rejectedAnswerIsRemovedAndCannotReappearAfterReload() throws Exception {
        SentenceMemory m=new SentenceMemory(); List<String> wrong=words("Tom","best","thing");
        m.remember("汤姆最喜欢的事",wrong,true,wrong);
        assertTrue(m.reject("汤姆最喜欢的事",wrong));
        m=SentenceMemory.decode(m.encode());
        assertTrue(m.lookup("汤姆最喜欢的事",wrong).isEmpty());
        assertTrue(m.isRejected("汤姆最喜欢的事",wrong));
        assertFalse(m.isRejected("其他题目",wrong));
    }
    @Test public void verifiedCorrectionWinsWithoutForgettingKnownWrongAnswer() throws Exception {
        SentenceMemory m=new SentenceMemory(); List<String> wrong=words("Tom","best","thing");
        List<String> correct=words("the","thing","that","Tom","likes","best");
        m.reject("汤姆最喜欢的事",wrong);
        assertTrue(m.remember("汤姆最喜欢的事",correct,true,correct));
        m=SentenceMemory.decode(m.encode());
        assertEquals(correct,m.lookup("汤姆最喜欢的事",words("likes","best","Tom","thing","the","that","extra")));
        assertTrue(m.isRejected("汤姆最喜欢的事",wrong));
        assertFalse(m.isRejected("汤姆最喜欢的事",correct));
    }
    @Test public void olderMemoryWithoutRejectedSectionStillLoads() {
        SentenceMemory m=SentenceMemory.decode("{\"version\":1,\"verified\":[{\"prompt\":\"你好\",\"words\":[\"Hello\"]}]}");
        assertEquals(words("Hello"),m.lookup("你好",words("Hello","bye")));
        assertFalse(m.isRejected("你好",words("Hello")));
        assertTrue(m.hasPrompt("你好"));
    }
    @Test public void memoryKeepsCompatibleTokenizationsSeparately() {
        SentenceMemory m=new SentenceMemory();
        m.remember("你好吗？",words("How","are","you"),true,words("How","are","you"));
        m.remember("你好吗？",words("How are","you"),true,words("How are","you"));
        assertEquals(words("How","are","you"),m.lookup("你好吗？",words("are","you","How")));
    }
}
