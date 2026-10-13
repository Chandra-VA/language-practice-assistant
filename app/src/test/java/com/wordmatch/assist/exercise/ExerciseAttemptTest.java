package com.wordmatch.assist.exercise;
import com.wordmatch.assist.sentence.*;
import com.wordmatch.assist.sentence.SentencePage.Node;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ExerciseAttemptTest {
    private void click(ExerciseAttempt a, Node n,long now){a.click(n.left,n.top,n.right,n.bottom,now);}
    @Test public void selectedClickedSubmittedCorrectIsLearnableAndReplayUsesText() throws Exception {
        Node root=ExercisePageTest.board("challenge_assist");ExercisePage p=ExercisePageTest.parse(root);
        ExerciseAttempt a=new ExerciseAttempt();a.observe(p,1000);click(a,p.options.get(0),1100);
        root.children.get(1).enabled=true;p=ExercisePageTest.parse(root);a.observe(p,1200);click(a,p.submit,1300);
        p.feedback=true;p.correct=true;assertTrue(a.proof(p,1400));assertEquals(Arrays.asList("apple"),a.submitted());
        SentenceMemory m=new SentenceMemory();assertTrue(m.remember(a.key(),a.submitted(),true,a.submitted()));
        Collections.reverse(root.children.get(0).children.get(2).children);ExercisePage reordered=ExercisePageTest.parse(root);
        assertEquals(reordered.memoryKey(),a.key());assertTrue(ExerciseAnswer.valid(reordered,m.entries().get(0).words));
    }
    @Test public void feedbackWithoutSubmitOrAfterBackgroundCannotTeach() {
        ExercisePage p=ExercisePageTest.parse(ExercisePageTest.board("challenge_assist"));ExerciseAttempt a=new ExerciseAttempt();
        p.gradesOnChoice=false;a.observe(p,1000);click(a,p.options.get(0),1100);p.feedback=true;p.correct=true;assertFalse(a.proof(p,1400));
        a.reset();assertFalse(a.proof(p,1500));
    }
    @Test public void staleChangedQuestionOrExpiredSubmissionCannotTeach() {
        Node root=ExercisePageTest.board("challenge_assist");root.children.get(1).enabled=true;
        ExercisePage p=ExercisePageTest.parse(root);p.gradesOnChoice=false;ExerciseAttempt a=new ExerciseAttempt();a.observe(p,1000);click(a,p.options.get(0),1100);
        click(a,p.submit,4000);p.feedback=true;assertFalse(a.proof(p,4100));p.feedback=false;
        a.observe(p,4200);click(a,p.submit,4300);p.feedback=true;assertTrue(a.proof(p,4400));assertFalse(a.proof(p,20000));
        p.prompt="different";assertFalse(a.proof(p,4500));
    }
    @Test public void learnedCorrectionRemovesExactRejectedAnswer() {
        SentenceMemory m=new SentenceMemory();String key="choice|question";List<String> a=Arrays.asList("apple");
        assertTrue(m.reject(key,a));assertTrue(m.isRejected(key,a));assertTrue(m.remember(key,a,true,a));assertFalse(m.isRejected(key,a));
        assertFalse(m.remember(key,a,false,a));
    }
    @Test public void immediateGradingChoiceNeedsObservedTapButNoCheck() {
        ExercisePage p=ExercisePageTest.parse(ExercisePageTest.board("challenge_assist"));
        ExerciseAttempt a=new ExerciseAttempt();a.observe(p,1000);p.feedback=true;assertFalse(a.proof(p,1050));p.feedback=false;
        click(a,p.options.get(0),1100);p.feedback=true;p.correct=true;assertTrue(a.proof(p,1200));
        assertEquals(Arrays.asList("apple"),a.submitted());
    }

    @Test public void feedbackThatDisablesChoiceTargetsRetainsQuestionAndSubmissionProof() {
        Node root=ExercisePageTest.board("challenge_assist");ExercisePage p=ExercisePageTest.parse(root);
        ExerciseAttempt a=new ExerciseAttempt();a.observe(p,1000);click(a,p.options.get(0),1100);
        for(Node option:root.children.get(0).children.get(2).children) option.clickable=false;
        Node next=ExercisePageTest.n("continueButtonGreenStub","继续",true);next.top=2158;next.bottom=2295;root.children.set(1,next);
        root.children.add(ExercisePageTest.n("composeBottomContainer","",false,ExercisePageTest.n("","非常好！",false)));
        ExercisePage feedback=ExercisePageTest.parse(root);assertTrue(feedback.correct);assertTrue(feedback.options.isEmpty());
        assertEquals(p.contextKey(),feedback.contextKey());assertTrue(a.proof(feedback,1200));
    }

}
