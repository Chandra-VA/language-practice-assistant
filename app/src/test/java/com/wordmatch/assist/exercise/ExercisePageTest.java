package com.wordmatch.assist.exercise;
import com.wordmatch.assist.sentence.SentencePage.Node;
import org.junit.Test;
import org.json.JSONObject;
import java.util.*;
import static org.junit.Assert.*;
public class ExercisePageTest {
    static Node n(String id,String text,boolean click,Node... children) {
        Node n=new Node(); n.id=id.isEmpty()?"":"com.duolingo:id/"+id;n.text=text;n.clickable=click;
        n.left=42;n.top=400;n.right=1038;n.bottom=550;n.children.addAll(Arrays.asList(children));return n;
    }
    static void paths(Node n,int... path) {
        n.path=path;
        for(int i=0;i<n.children.size();i++){int[] p=Arrays.copyOf(path,path.length+1);p[path.length]=i;paths(n.children.get(i),p);}
    }
    static Node board(String type) {
        Node a=n("","",true,n("optionText","apple",false));a.top=1300;a.bottom=1450;
        Node b=n("","",true,n("optionText","orange",false));b.top=1500;b.bottom=1650;
        Node exercise=n(type,"",false,n("challengeInstruction","选择正确的翻译",true),n("hintablePrompt","苹果",false),n("optionsContainer","",false,a,b));
        Node submit=n("submitButton","继续",true);submit.top=2158;submit.bottom=2295;submit.enabled=false;
        Node root=n("root","",false,exercise,submit); paths(root);return root;
    }
    static ExercisePage parse(Node root) {paths(root);return ExercisePage.parse(root,1080,2400,false);}
    @Test public void actualChoiceHierarchyExcludesOptionsAndFindsClickableParents() {
        ExercisePage p=parse(board("challenge_assist")); assertTrue(p.complete);assertEquals(ExerciseType.CHOICE,p.type);
        assertEquals("苹果",p.prompt);assertEquals(Arrays.asList("apple","orange"),p.inventory());assertNull(p.submit);
        assertEquals(1300,p.options.get(0).top);assertEquals(ExercisePage.Mode.CHOICE,p.mode);
    }
    @Test public void multipleAndSingleChoicesValidateDifferentCardinality() throws Exception {
        ExercisePage p=parse(board("challenge_assist"));JSONObject result=new JSONObject("{choice_ids:[0,1],confidence:0.99}");
        assertTrue(ExerciseAnswer.validate(p,result).isEmpty()); p.multiple=true;assertEquals(2,ExerciseAnswer.validate(p,result).size());
    }
    @Test public void invalidModelIdsConfidenceAndDuplicatesAreRejected() throws Exception {
        ExercisePage p=parse(board("challenge_assist"));
        String[] bad={"{choice_ids:[-1],confidence:1}","{choice_ids:[2],confidence:1}","{choice_ids:[0.5],confidence:1}","{choice_ids:[0,0],confidence:1}","{choice_ids:['0'],confidence:1}","{choice_ids:[0],confidence:0.91}","{choice_ids:[0],confidence:1.1}","{choice_ids:[0],confidence:'1'}","{choice_ids:[0]}","{choice_ids:[],confidence:1}"};
        for(String s:bad)assertTrue(s,ExerciseAnswer.validate(p,new JSONObject(s)).isEmpty());
        assertEquals(Arrays.asList("apple"),ExerciseAnswer.validate(p,new JSONObject("{choice_ids:[0],confidence:0.99}")));
    }
    @Test public void disabledOffscreenDuplicateOrTruncatedOptionPreventsSolving() {
        Node root=board("challenge_select");root.children.get(0).children.get(2).children.get(0).enabled=false;assertFalse(parse(root).complete);
        root=board("challenge_select");root.children.get(0).children.get(2).children.get(0).right=1200;assertFalse(parse(root).complete);
        root=board("challenge_select");root.children.get(0).children.get(2).children.get(1).children.get(0).text="apple";assertFalse(parse(root).complete);
        root=board("challenge_select");assertFalse(ExercisePage.parse(root,1080,2400,true).complete);
    }
    @Test public void optionShufflePreservesSemanticMemoryButDifferentAlternativesDont() {
        Node root=board("challenge_assist");String key=parse(root).memoryKey();
        Collections.reverse(root.children.get(0).children.get(2).children);assertEquals(key,parse(root).memoryKey());
        root.children.get(0).children.get(2).children.get(0).children.get(0).text="banana";assertNotEquals(key,parse(root).memoryKey());
    }
    @Test public void unknownForeignAccountAndPairContainersNeverEnterGeneralSolver() {
        for(String id:new String[]{"challenge_match","challenge_math_multi_select","challenge_music_key_play","loginForm","challenge_unseen"})assertFalse(parse(board(id)).recognized);
        Node root=board("challenge_assist");root.children.get(0).id="foreign:id/challenge_assist";assertFalse(parse(root).recognized);
    }
    @Test public void listeningSpeakingSkipOnlyExactCorrespondingAction() {
        for(String id:new String[]{"challenge_listen","challenge_listen_tap","challenge_partial_listen","challenge_speak_repeat","challenge_immersive_speak"}) {
            Node root=board(id);ExerciseType type=id.contains("listen")?ExerciseType.LISTEN:ExerciseType.SPEAK;
            root.children.add(n("skipButton",type==ExerciseType.LISTEN?"现在不做听力题":"现在不能说话",true));
            ExercisePage p=parse(root);assertTrue(p.complete);assertEquals(type,p.type);assertEquals(ExercisePage.Mode.SKIP,p.mode);assertNotNull(p.skip);
        }
        assertTrue(ExercisePage.skipLabel(ExerciseType.SPEAK,"Can’t speak now"));
        assertFalse(ExercisePage.skipLabel(ExerciseType.LISTEN,"现在不能说话"));
        assertFalse(ExercisePage.skipLabel(ExerciseType.LISTEN,"跳过"));
        Node root=board("challenge_speak");root.children.add(n("skipButton","现在不做听力题",true));assertFalse(parse(root).complete);
        root=board("challenge_assist");root.children.add(n("skipButton","现在不能说话",true));assertNull(parse(root).skip);
    }
    @Test public void pauseDialogWinsOverExerciseAndSkipUnderIt() {
        Node root=board("challenge_listen");root.children.add(n("skipButton","现在不做听力题",true));
        Node back=n("","返回",true);back.top=1979;back.bottom=2116;Node exit=n("","退出",true);exit.top=2158;exit.bottom=2295;
        root.children.add(back);root.children.add(exit);ExercisePage p=parse(root);assertTrue(p.paused);assertFalse(p.recognized);assertNull(p.skip);
    }
    @Test public void textInputIdentityExcludesMutableAnswerButIncludesQuestion() throws Exception {
        Node root=board("challenge_translate");Node field=n("textInput","",true);field.editable=true;
        root.children.get(0).children.set(2,field);ExercisePage p=parse(root);assertTrue(p.complete);assertEquals(ExercisePage.Mode.TEXT,p.mode);
        assertTrue(p.prompt.contains("[blank]"));String key=p.memoryKey();field.text="apple";assertEquals(key,parse(root).memoryKey());
        assertEquals(Arrays.asList("apple"),ExerciseAnswer.validate(p,new JSONObject("{texts:['apple'],confidence:1}")));
        assertTrue(ExerciseAnswer.validate(p,new JSONObject("{texts:['apple','extra'],confidence:1}")).isEmpty());
        field.password=true;assertFalse(parse(root).complete);
    }
    @Test public void fillReadingAndDialogueHaveIndependentTypes() {
        assertEquals(ExerciseType.FILL,parse(board("challenge_gap_fill")).type);
        assertEquals(ExerciseType.READING,parse(board("challenge_read_comprehension")).type);
        assertEquals(ExerciseType.DIALOGUE,parse(board("challenge_dialogue")).type);
    }
    @Test public void feedbackRequiresKnownPositiveAndGreenControl() {
        Node root=board("challenge_assist");Node next=n("continueButtonGreenStub","继续",true);next.top=2158;next.bottom=2295;root.children.set(1,next);
        root.children.add(n("composeBottomContainer","",false,n("","泰裤辣！",false)));
        ExercisePage p=parse(root);assertTrue(p.feedback);assertTrue(p.correct);assertNull(p.submit);
        root.children.get(2).children.add(n("","正确答案：orange",false));p=parse(root);assertFalse(p.correct);assertTrue(p.wrong);
    }
    @Test public void skipConfirmationRequiresExactAppFeedbackAndBoundedContinue() {
        Node button=n("","继续",true);button.top=2150;button.bottom=2280;
        Node root=n("root","",false,n("","将跳过本单元的所有口\u2060语练\u2060习",false),button);
        ExercisePage p=parse(root);assertTrue(p.skipConfirmation);assertTrue(p.complete);assertEquals(ExerciseType.SPEAK,p.type);
        root.children.get(0).text="购买后继续";assertFalse(parse(root).recognized);
    }

    @Test public void gapFillPromptKeepsBlankLocationsAndMeaningFeedbackIsPositive() {
        Node root=board("challenge_gap_fill");
        Node blank=n("","",false,n("","",false));blank.top=798;blank.bottom=920;blank.children.get(0).top=915;blank.children.get(0).bottom=920;
        Node prompt=n("prompt","",false,n("","I ",true),blank,n(""," apples.",true));root.children.get(0).children.set(1,prompt);
        ExercisePage p=parse(root);assertEquals("I  [blank]  apples.",p.prompt);assertTrue(p.complete);
        Node next=n("continueButtonGreenStub","继续",true);next.top=2158;next.bottom=2295;root.children.set(1,next);
        root.children.add(n("composeBottomContainer","",false,n("","答得漂亮！意思是：",false)));
        assertTrue(parse(root).correct);root.children.get(2).children.add(n("","正确答案：wrong",false));assertFalse(parse(root).correct);
    }

    @Test public void textTranslationPromptRemainsStableWhenResultFieldBecomesReadOnly() {
        Node root=board("challenge_translate");Node field=n("textInput","",true);field.editable=true;
        root.children.get(0).children.set(2,field);String context=parse(root).contextKey();
        field.editable=false;field.text="apple";assertEquals(context,parse(root).contextKey());
    }
    @Test public void confidenceDiagnosticsDistinguishLowMissingAndBadStructure() throws Exception {
        ExercisePage p=parse(board("challenge_assist"));
        assertTrue(ExerciseAnswer.validationError(p,new JSONObject("{confidence:0.85}")).contains("0.850"));
        assertTrue(ExerciseAnswer.validationError(p,new JSONObject("{}")).contains("未返回"));
        assertTrue(ExerciseAnswer.validationError(p,new JSONObject("{confidence:0.99}")).contains("选项编号"));
    }

    @Test public void serviceSnapshotPreservesWhitespaceAndBlankWhoseUnderlineIsNotAccessible() {
        assertEquals(" ",com.wordmatch.assist.sentence.SentenceReader.nodeText(" ",null));
        assertEquals("description",com.wordmatch.assist.sentence.SentenceReader.nodeText("", "description"));
        Node root=board("challenge_gap_fill");Node blank=n("","",false);blank.className="android.widget.FrameLayout";
        root.children.get(0).children.set(1,n("prompt","",false,n("","I",true),n(""," ",true),blank,n(""," apples.",true)));
        assertEquals("I  [blank]  apples.",parse(root).prompt);
    }

    @Test public void wrappedRichPromptDoesNotConcatenateAdjacentEnglishWords() {
        Node root=board("challenge_gap_fill");root.children.get(0).children.set(1,n("prompt","",false,n("","correct",true),n("","picture",true),n("",".",true)));
        assertEquals("correct picture.",parse(root).prompt);
    }
    @Test public void multipleChoiceMemoryTreatsSameSetAsSameAnswerRegardlessOfClickOrder() {
        ExercisePage p=parse(board("challenge_assist"));p.multiple=true;
        assertEquals(p.memoryAnswer(Arrays.asList("orange","apple")),p.memoryAnswer(Arrays.asList("apple","orange")));
        p.mode=ExercisePage.Mode.ORDER;assertNotEquals(p.memoryAnswer(Arrays.asList("orange","apple")),p.memoryAnswer(Arrays.asList("apple","orange")));
    }
    @Test public void genericOrderUsesCurrentBankAndSelectionWithoutTreatingItAsPrompt() throws Exception {
        Node root=board("challenge_order_tap_complete");
        Node selectedTile=n("","",true,n("","I",false));selectedTile.left=50;selectedTile.right=150;selectedTile.top=1020;selectedTile.bottom=1130;
        Node answer=n("","",false,selectedTile);answer.top=1000;answer.bottom=1460;
        Node tile=n("","",true,n("","run",false));tile.left=100;tile.right=280;tile.top=1650;tile.bottom=1780;
        Node group=n("","",false,answer,tile);group.top=1000;group.bottom=2100;
        Node input=n("tapInputViewContainer","",false,group);input.top=1000;input.bottom=2100;
        root.children.get(0).children.set(2,input);ExercisePage p=parse(root);
        assertTrue(p.complete);assertEquals(ExercisePage.Mode.ORDER,p.mode);assertEquals(Arrays.asList("I"),p.visibleAnswer());
        assertEquals(Arrays.asList("run","I"),p.inventory());assertFalse(p.prompt.contains("run"));
        assertEquals(Arrays.asList("I","run"),ExerciseAnswer.validate(p,new JSONObject("{order:[1,0],confidence:0.99}")));
    }

}
