package com.wordmatch.assist.sentence;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class SentencePageTest {
    private SentencePage.Node node(String id,String text,int l,int t,int r,int b,boolean click, SentencePage.Node... children) {
        SentencePage.Node n=new SentencePage.Node(); n.id="com.duolingo:id/"+id; n.text=text;
        n.left=l;n.top=t;n.right=r;n.bottom=b;n.clickable=click;n.children.addAll(Arrays.asList(children));return n;
    }
    private SentencePage.Node tile(String text,int x,int y) {
        return node("","",x,y,x+170,y+130,true,node("",text,x+20,y+20,x+150,y+100,false));
    }
    private SentencePage.Node board(boolean selected, String feedback) {
        SentencePage.Node answer=node("","",42,1000,1038,1460,true,selected?new SentencePage.Node[]{tile("你",42,1010),tile("好",233,1010)}:new SentencePage.Node[]{});
        answer.enabled=false;
        SentencePage.Node input=node("tapInputViewContainer","",42,1000,1038,2116,false,
                node("","",42,1000,1038,2116,false,tile("吗",80,1670),answer));
        SentencePage.Node exercise=node("challenge_translate","",42,296,1038,2116,false,
                node("challengeInstruction","翻译这句话",42,296,1038,392,true),
                node("hintablePrompt","Hello",494,667,994,747,false),input);
        SentencePage.Node control=node(feedback.isEmpty()?"submitButton":"continueButtonGreenStub","",42,2158,1038,2295,false,
                node("",feedback.isEmpty()?"检查":"继续",42,2158,1038,2295,true));
        if(feedback.isEmpty()) return node("root","",0,0,1080,2400,false,exercise,control);
        return node("root","",0,0,1080,2400,false,exercise,control,
                node("composeBottomContainer","",0,1962,1080,2400,false,node("",feedback,126,2025,600,2116,false)));
    }
    @Test public void separatesSelectedAndBankUsingHierarchyNotWords() {
        SentencePage p=SentencePage.parse(board(true,""),1080,2400,false);
        assertTrue(p.recognized);assertTrue(p.complete);assertEquals(Arrays.asList("你","好"),p.selectedWords());
        assertEquals(Arrays.asList("吗"),SentencePage.words(p.bank));assertNotNull(p.check);assertFalse(p.correct);
    }
    @Test public void blankSelectionAndFeedbackHaveDifferentSubmissionStates() {
        SentencePage p=SentencePage.parse(board(false,""),1080,2400,false);assertTrue(p.complete);assertTrue(p.selected.isEmpty());
        p=SentencePage.parse(board(true,"太棒了！"),1080,2400,false);assertTrue(p.correct);assertNotNull(p.next);assertNull(p.check);
        p=SentencePage.parse(board(true,"正确答案：你好"),1080,2400,false);assertTrue(p.wrong);assertFalse(p.correct);
        p=SentencePage.parse(board(true,"未识别的反馈"),1080,2400,false);assertFalse(p.correct);assertTrue(p.feedback);
    }
    @Test public void wrongFeedbackWithGotItButtonIsAResultNotAnUnansweredExercise() {
        SentencePage.Node root=board(true,"不正确");
        root.children.get(1).id="com.duolingo:id/continueButtonRedStub";
        root.children.get(1).children.get(0).text="知道了";
        SentencePage p=SentencePage.parse(root,1080,2400,false);
        assertTrue(p.feedback);assertTrue(p.wrong);assertFalse(p.correct);assertNull(p.check);
        assertTrue(p.selectedComplete);assertNotNull(p.next);
    }
    @Test public void truncatedAndOtherExerciseNeverProduceCompleteSentence() {
        assertFalse(SentencePage.parse(board(true,""),1080,2400,true).complete);
        SentencePage.Node other=board(true,"");other.children.get(0).id="com.duolingo:id/challenge_listen";
        assertFalse(SentencePage.parse(other,1080,2400,false).recognized);
    }
    @Test public void pauseSheetAlwaysWinsOverUnderlyingExercise() {
        SentencePage.Node root=board(true,"");
        root.children.add(node("","返回",42,1979,1038,2116,true));root.children.add(node("","退出",42,2158,1038,2295,true));
        SentencePage p=SentencePage.parse(root,1080,2400,false);
        assertTrue(p.paused);assertFalse(p.recognized);assertFalse(p.complete);assertNotNull(p.resume);assertNull(p.check);
    }
    @Test public void observedNiceFeedbackIsAcceptedOnlyWithGreenContinue() {
        SentencePage.Node root=board(true,"不错哦！");
        assertTrue(SentencePage.parse(root,1080,2400,false).correct);
        root.children.get(1).id="com.duolingo:id/continueButtonRedStub";
        assertFalse(SentencePage.parse(root,1080,2400,false).correct);
    }
    @Test public void observedSlangFeedbackRequiresGreenContinueAndNoCorrection() {
        assertTrue(SentencePage.parse(board(true,"泰裤辣！"),1080,2400,false).correct);
        SentencePage.Node root=board(true,"棒棒哒！");
        assertTrue(SentencePage.parse(root,1080,2400,false).correct);
        root.children.get(2).children.add(node("", "正确答案：你好",126,2116,600,2150,false));
        assertFalse(SentencePage.parse(root,1080,2400,false).correct);
        root=board(true,"棒棒哒！");
        root.children.get(1).id="com.duolingo:id/continueButtonRedStub";
        assertFalse(SentencePage.parse(root,1080,2400,false).correct);
    }
    @Test public void observedYouAreGreatFeedbackCanTeachButStillRequiresGreenAndNoError() {
        SentencePage.Node input=board(true, "");
        SentenceLearning learning=new SentenceLearning();
        learning.observe(SentencePage.parse(input,1080,2400,false),100);
        SentencePage.Node root=board(true,"你太棒了！");
        SentencePage result=SentencePage.parse(root,1080,2400,false);
        assertTrue(result.correct);assertTrue(learning.canRemember(result,200));
        root.children.get(1).id="com.duolingo:id/continueButtonRedStub";
        assertFalse(SentencePage.parse(root,1080,2400,false).correct);
        root.children.get(1).id="com.duolingo:id/continueButtonGreenStub";
        root.children.get(2).children.add(node("", "不正确",126,2116,600,2150,false));
        assertFalse(SentencePage.parse(root,1080,2400,false).correct);
    }
    @Test public void supportedAppCorrectFeedbackVariantsRequireGreenContinue() {
        String[] labels={"泰裤辣！","真棒！","正确！","非常好！","你太棒了！","棒棒哒！","太棒了！","好棒！","不错哦！","真厉害！","厉害！",
                "Amazing!","Awesome!","Correct!","Excellent!","Good job!","Great!","Great job!","Nice!","Nice job!","Nicely done!","Wow!"};
        for(String label:labels) {
            SentencePage.Node root=board(true,label);
            assertTrue(label,SentencePage.parse(root,1080,2400,false).correct);
            root.children.get(1).id="com.duolingo:id/continueButtonRedStub";
            assertFalse(label,SentencePage.parse(root,1080,2400,false).correct);
        }
    }
    @Test public void emptyFeedbackFrameDoesNotBecomeCorrectButNextFrameCan() {
        SentencePage.Node root=board(true,"等待");
        assertFalse(SentencePage.parse(root,1080,2400,false).correct);
        root.children.get(2).children.get(0).text="真棒！";
        assertTrue(SentencePage.parse(root,1080,2400,false).correct);
    }
    @Test public void fullyUsedBankStillExposesCompleteSelectedSentence() {
        SentencePage.Node root=board(true,"");
        SentencePage.Node group=root.children.get(0).children.get(2).children.get(0);
        group.children.remove(0);
        SentencePage p=SentencePage.parse(root,1080,2400,false);
        assertTrue(p.complete); assertTrue(p.bank.isEmpty()); assertEquals(2,p.selected.size());
    }
    @Test public void offscreenTilePreventsAutomaticSubmission() {
        SentencePage.Node root=board(true,"");
        SentencePage.Node group=root.children.get(0).children.get(2).children.get(0);
        group.children.get(0).right=1200;
        assertFalse(SentencePage.parse(root,1080,2400,false).complete);
    }
}
