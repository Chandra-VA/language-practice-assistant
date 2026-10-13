package com.wordmatch.assist.sentence;

import org.json.*;
import java.util.*;

/** Verified translations only. Multiple tokenizations for a prompt can coexist. */
public final class SentenceMemory {
    public static final class Entry {
        public final String prompt;
        public final List<String> words;
        Entry(String prompt, List<String> words) { this.prompt = prompt; this.words = new ArrayList<>(words); }
        public String display() { StringBuilder text = new StringBuilder(prompt).append(" → ");
            for (int i = 0; i < words.size(); i++) { if (i > 0) text.append(" · "); text.append(words.get(i)); }
            return text.toString(); }
    }
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> rejected = new ArrayList<>();
    public List<Entry> entries() { return new ArrayList<>(entries); }
    public List<String> lookup(String prompt, List<String> bank) {
        for (Entry e : entries) if (SentencePlan.key(e.prompt).equals(SentencePlan.key(prompt))
                && SentencePlan.available(e.words, bank) && !isRejected(prompt, e.words)) return new ArrayList<>(e.words);
        return Collections.emptyList();
    }
    public boolean remember(String prompt, List<String> words, boolean correct, List<String> observedBeforeFeedback) {
        if (!correct || prompt.trim().isEmpty() || prompt.length() > 2000 || words.isEmpty()
                || !SentencePlan.same(words, observedBeforeFeedback) || !SentencePlan.available(words, words)) return false;
        remove(prompt, words); removeFrom(rejected, prompt, words);
        entries.add(0, new Entry(prompt, words));
        while (entries.size() > 1000) entries.remove(entries.size() - 1);
        return true;
    }
    public boolean hasPrompt(String prompt) {
        for (Entry e : entries) if (SentencePlan.key(e.prompt).equals(SentencePlan.key(prompt))) return true;
        return false;
    }
    public boolean isRejected(String prompt, List<String> words) {
        for (Entry e : rejected) if (SentencePlan.key(e.prompt).equals(SentencePlan.key(prompt)) && SentencePlan.same(e.words, words)) return true;
        return false;
    }
    public boolean reject(String prompt, List<String> words) {
        if (prompt.trim().isEmpty() || prompt.length() > 2000 || !SentencePlan.available(words, words)) return false;
        remove(prompt, words); removeFrom(rejected, prompt, words);
        rejected.add(0, new Entry(prompt, words));
        while (rejected.size() > 1000) rejected.remove(rejected.size() - 1);
        return true;
    }
    public void remove(String prompt, List<String> words) { removeFrom(entries, prompt, words); }
    private void removeFrom(List<Entry> source, String prompt, List<String> words) {
        for (Iterator<Entry> iterator = source.iterator(); iterator.hasNext();) {
            Entry e = iterator.next();
            if (SentencePlan.key(e.prompt).equals(SentencePlan.key(prompt)) && SentencePlan.same(e.words, words)) iterator.remove();
        }
    }
    public String encode() throws JSONException {
        JSONArray array = new JSONArray();
        for (Entry e : entries) array.put(new JSONObject().put("prompt", e.prompt).put("words", new JSONArray(e.words)));
        JSONArray wrong = new JSONArray();
        for (Entry e : rejected) wrong.put(new JSONObject().put("prompt", e.prompt).put("words", new JSONArray(e.words)));
        return new JSONObject().put("version",1).put("verified",array).put("rejected",wrong).toString();
    }
    public static SentenceMemory decode(String text) {
        SentenceMemory result = new SentenceMemory();
        try {
            JSONObject data = new JSONObject(text);
            if (data.optInt("version") != 1) return result;
            JSONArray array = data.getJSONArray("verified");
            for (int i = 0; i < Math.min(1000, array.length()); i++) {
                JSONObject entry = array.getJSONObject(i); String prompt = entry.getString("prompt");
                JSONArray tokens = entry.getJSONArray("words"); List<String> words = new ArrayList<>();
                for (int j = 0; j < Math.min(41, tokens.length()); j++) words.add(tokens.getString(j));
                if (!prompt.trim().isEmpty() && prompt.length() <= 2000 && SentencePlan.available(words, words)) result.entries.add(new Entry(prompt, words));
            }
            JSONArray wrong = data.optJSONArray("rejected");
            if (wrong != null) for (int i = Math.min(1000, wrong.length()) - 1; i >= 0; i--) {
                JSONObject entry = wrong.getJSONObject(i); List<String> words = new ArrayList<>();
                JSONArray tokens = entry.getJSONArray("words");
                for (int j = 0; j < Math.min(41, tokens.length()); j++) words.add(tokens.getString(j));
                result.reject(entry.getString("prompt"), words);
            }
        } catch (JSONException ignored) { /* Recover valid prefix; never fabricate an answer. */ }
        return result;
    }
}
