package com.wordmatch.assist.sentence;

import java.util.*;

/** Links a positive result to an observed attempt, including a fast manual Check click. */
public final class SentenceLearning {
    private static final long MAX_ATTEMPT_AGE_MS = 15000;
    private String key = "";
    private List<String> bank = Collections.emptyList(), selection = Collections.emptyList(), submittedBank = Collections.emptyList();
    private SentencePage.Node check;
    private long observedAt = -1, submittedAt = -1;
    public void reset() {
        key = ""; bank = selection = submittedBank = Collections.emptyList(); check = null;
        observedAt = submittedAt = -1;
    }
    public void observe(SentencePage page, long now) {
        if (!page.recognized || !page.complete || page.feedback || page.paused) return;
        String current = SentencePlan.key(page.prompt);
        if (!current.equals(key)) reset();
        key = current; bank = new ArrayList<>(page.allWords()); selection = new ArrayList<>(page.selectedWords());
        check = page.check; observedAt = now;
    }
    public boolean checkClicked(String label, int l, int t, int r, int b, long now) {
        if (!("检查".equals(label) || "check".equalsIgnoreCase(label)) || check == null
                || observedAt < 0 || now - observedAt > 2000 || selection.isEmpty()
                || l != check.left || t != check.top || r != check.right || b != check.bottom) return false;
        submittedAt = now; submittedBank = new ArrayList<>(bank); return true;
    }
    public String proof(SentencePage page, long now) {
        if (!page.correct || !page.selectedComplete || page.selected.isEmpty()) return "unconfirmed_result";
        if (key.isEmpty() || !key.equals(SentencePlan.key(page.prompt))) return "missing_attempt";
        if (observedAt < 0 || now - observedAt > MAX_ATTEMPT_AGE_MS) return "stale_attempt";
        if (SentencePlan.same(selection, page.selectedWords()) && SentencePlan.available(page.selectedWords(), bank)) return "observed_selection";
        // A late pre-result node refresh must not invalidate the already observed Check.
        if (submittedAt >= 0 && now - submittedAt <= MAX_ATTEMPT_AGE_MS
                && SentencePlan.available(page.selectedWords(), submittedBank)) return "observed_submit";
        if (!SentencePlan.available(page.selectedWords(), bank)) return "inventory_changed";
        return "missing_submit";
    }
    public boolean canRemember(SentencePage page, long now) {
        String proof = proof(page, now);
        return proof.equals("observed_selection") || proof.equals("observed_submit");
    }
}
