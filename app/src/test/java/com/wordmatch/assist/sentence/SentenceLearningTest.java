package com.wordmatch.assist.sentence;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SentenceLearningTest {
    private SentencePage.Node tile(String word) { SentencePage.Node n=new SentencePage.Node(); n.text=word; return n; }
    private SentencePage frame(String prompt, String[] selected, String[] bank, boolean feedback) {
        SentencePage p=new SentencePage(); p.prompt=prompt; p.recognized=p.complete=true;
        for(String w:selected) p.selected.add(tile(w));
        for(String w:bank) p.bank.add(tile(w));
        p.selectedComplete=selected.length>0; p.feedback=p.correct=feedback;
        if(!feedback) {p.check=new SentencePage.Node();p.check.left=42;p.check.top=2158;p.check.right=1038;p.check.bottom=2295;}
        return p;
    }
    private boolean click(SentenceLearning l,long now) {return l.checkClicked("检查",42,2158,1038,2295,now);}
    @Test public void completedManualAttemptSurvivesFeedbackBankBeingHiddenAndReplaysAfterReload() throws Exception {
        SentenceLearning l=new SentenceLearning();
        SentencePage input=frame("你好",new String[]{"hello","there"},new String[]{"extra"},false);
        l.observe(input,100);
        SentencePage result=frame("你好",new String[]{"hello","there"},new String[]{},true);result.complete=false;
        assertNotEquals(input.identity(),result.identity());
        assertTrue(l.canRemember(result,200));
        SentenceMemory m=new SentenceMemory();
        assertTrue(m.remember(result.prompt,result.selectedWords(),true,result.selectedWords()));
        m=SentenceMemory.decode(m.encode());
        assertEquals(Arrays.asList("hello","there"),m.lookup("你好",Arrays.asList("there","extra","hello")));
    }
    @Test public void fastFinalTileThenCheckCanLearnOnlyWithObservedSubmitAndPositiveResult() {
        SentenceLearning l=new SentenceLearning();
        l.observe(frame("你好",new String[]{"hello"},new String[]{"there","extra"},false),100);
        SentencePage result=frame("你好",new String[]{"hello","there"},new String[]{"extra"},true);
        assertFalse(l.canRemember(result,180));
        assertTrue(click(l,150));assertTrue(l.canRemember(result,180));
        assertEquals("observed_submit",l.proof(result,180));
        result.correct=false;result.wrong=true;assertFalse(l.canRemember(result,180));
    }
    @Test public void latePreResultRefreshDoesNotEraseObservedCheckOrItsInventory() {
        SentenceLearning l=new SentenceLearning();
        l.observe(frame("你好",new String[]{"hello"},new String[]{"there","extra"},false),100);
        assertTrue(click(l,150));
        // During the final tile animation, it can briefly be absent from both groups.
        l.observe(frame("你好",new String[]{"hello"},new String[]{"extra"},false),160);
        SentencePage result=frame("你好",new String[]{"hello","there"},new String[]{},true);
        assertEquals("observed_submit",l.proof(result,200));
        assertFalse(l.canRemember(frame("你好",new String[]{"hello","absent"},new String[]{},true),200));
        l.observe(frame("你好",new String[]{"hello"},new String[]{"there","extra"},false),17000);
        assertFalse(l.canRemember(result,17001));
    }
    @Test public void merelyOpeningCorrectFeedbackDoesNotLearn() {
        SentenceLearning l=new SentenceLearning();
        SentencePage p=frame("你好",new String[]{"hello"},new String[]{},true);
        l.observe(p,100);assertFalse(l.canRemember(p,101));assertFalse(click(l,101));
    }
    @Test public void otherButtonsAndDifferentQuestionCannotAuthorizeLearning() {
        SentenceLearning l=new SentenceLearning();
        l.observe(frame("你好",new String[]{"hello"},new String[]{"there"},false),100);
        assertFalse(l.checkClicked("继续",42,2158,1038,2295,150));
        assertFalse(l.checkClicked("检查",42,1000,1038,1130,150));
        assertTrue(click(l,150));
        assertFalse(l.canRemember(frame("再见",new String[]{"hello","there"},new String[]{},true),180));
    }
    @Test public void resetAndExpiredAttemptCannotLearnFromOldSubmission() {
        SentenceLearning l=new SentenceLearning();
        l.observe(frame("你好",new String[]{"hello"},new String[]{"there"},false),100);click(l,150);
        SentencePage result=frame("你好",new String[]{"hello","there"},new String[]{},true);
        assertFalse(l.canRemember(result,16000));l.reset();assertFalse(l.canRemember(result,180));
    }
    @Test public void feedbackMustUseActualAvailableCountsAndCompleteSelection() {
        SentenceLearning l=new SentenceLearning();
        l.observe(frame("你好",new String[]{"hello"},new String[]{"there"},false),100);click(l,150);
        assertFalse(l.canRemember(frame("你好",new String[]{"hello","hello"},new String[]{},true),180));
        SentencePage p=frame("你好",new String[]{"hello","there"},new String[]{},true);p.selectedComplete=false;
        assertFalse(l.canRemember(p,180));
    }
    @Test public void nextQuestionClearsPriorSubmitEvidence() {
        SentenceLearning l=new SentenceLearning();
        l.observe(frame("你好",new String[]{"hello"},new String[]{"there"},false),100);click(l,150);
        l.observe(frame("再见",new String[]{"good"},new String[]{"bye"},false),160);
        assertFalse(l.canRemember(frame("再见",new String[]{"good","bye"},new String[]{},true),180));
    }
}
