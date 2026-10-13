package com.wordmatch.assist.exercise;
import com.wordmatch.assist.sentence.*;
import com.wordmatch.assist.sentence.SentencePage.Node;
import java.util.*;

/** A positive result is only learnable when this process observed its submission. */
public final class ExerciseAttempt {
    private ExercisePage current;
    private String context = "", key = "";
    private long observedAt, submittedAt;
    private List<String> chosen = new ArrayList<>(), submitted = new ArrayList<>();
    public void reset() { current = null; context = key = ""; observedAt = submittedAt = 0; chosen.clear(); submitted.clear(); }
    public void observe(ExercisePage page, long now) {
        if (page.feedback || !page.complete) return;
        if (current == null || !current.identity().equals(page.identity())) { reset(); }
        current = page; observedAt = now;
        List<String> visible = page.visibleAnswer();
        if (page.mode != ExercisePage.Mode.CHOICE || !visible.isEmpty()) chosen = new ArrayList<>(visible);
    }
    public void click(int l, int t, int r, int b, long now) {
        if (current == null || now - observedAt > 2000 || !current.complete) return;
        if (same(current.submit, l,t,r,b)) {
            if (!ExerciseAnswer.valid(current, chosen)) return;
            submitted = current.memoryAnswer(chosen); context = current.contextKey(); key = current.memoryKey(); submittedAt = now;
            return;
        }
        if (current.mode != ExercisePage.Mode.CHOICE) return;
        for (Node option : current.options) if (same(option,l,t,r,b)) {
            if (!current.multiple) chosen.clear();
            if (!current.multiple || !chosen.remove(option.text)) chosen.add(option.text);
            if (current.gradesOnChoice && !current.multiple && ExerciseAnswer.valid(current, chosen)) {
                submitted = current.memoryAnswer(chosen); context = current.contextKey(); key = current.memoryKey(); submittedAt = now;
            }
            return;
        }
    }
    private boolean same(Node n, int l,int t,int r,int b) { return n != null && n.left == l && n.top == t && n.right == r && n.bottom == b; }
    public List<String> chosen() { return new ArrayList<>(chosen); }
    public boolean proof(ExercisePage page, long now) {
        return proofReason(page, now).equals("observed_submit");
    }
    public String proofReason(ExercisePage page, long now) {
        if (!page.feedback) return "not_feedback";
        if (submitted.isEmpty() || submittedAt == 0) return "missing_submit";
        if (now - submittedAt > 15000) return "expired_submit";
        if (!page.contextKey().equals(context)) return "question_changed";
        return "observed_submit";
    }
    public String key() { return key; }
    public List<String> submitted() { return new ArrayList<>(submitted); }
}
