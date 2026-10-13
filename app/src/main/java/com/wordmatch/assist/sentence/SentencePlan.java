package com.wordmatch.assist.sentence;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.Normalizer;
import java.util.*;

/** Pure planning: positions are deliberately never part of a remembered answer. */
public final class SentencePlan {
    private SentencePlan() {}
    public static final double MIN_CONFIDENCE = 0.92;
    public static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .replace('’', '\'').replace('‘', '\'').trim().replaceAll("\\s+", " ");
    }
    public static String direction(String prompt) {
        for (int i = 0; i < prompt.length(); i++) {
            char c = prompt.charAt(i);
            if ((c >= 0x3400 && c <= 0x9fff) || (c >= 0xf900 && c <= 0xfaff)) return "zh-en";
        }
        return "en-zh";
    }
    public static String key(String prompt) { return direction(prompt) + ":" + normalize(prompt); }
    public static List<String> normalized(List<String> words) {
        List<String> result = new ArrayList<>();
        for (String word : words) result.add(normalize(word));
        return result;
    }
    public static String bankKey(List<String> words) {
        List<String> sorted = normalized(words); Collections.sort(sorted);
        return new JSONArray(sorted).toString();
    }
    public static boolean available(List<String> answer, List<String> all) {
        if (answer.isEmpty() || answer.size() > 40 || all.size() > 60) return false;
        List<String> remaining = normalized(all);
        for (String word : answer) if (normalize(word).isEmpty() || !remaining.remove(normalize(word))) return false;
        return true;
    }
    public static boolean same(List<String> a, List<String> b) { return normalized(a).equals(normalized(b)); }
    /** -2: clear last selected tile; -1: ready to submit; >=0: next answer offset. */
    public static int next(List<String> answer, List<String> selected) {
        if (selected.size() > answer.size()) return -2;
        for (int i = 0; i < selected.size(); i++) if (!normalize(selected.get(i)).equals(normalize(answer.get(i)))) return -2;
        return selected.size() == answer.size() ? -1 : selected.size();
    }
    public static String validationError(List<String> tokens, JSONObject response) {
        JSONArray ids = response.optJSONArray("order");
        if (ids == null) return "模型组句顺序缺失或格式错误";
        if (ids.length() == 0) return "模型未给出完整组句（空答案）";
        if (ids.length() > 40 || tokens.size() > 60) return "组句词块数量超限";
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) {
            Object value = ids.opt(i);
            if (!(value instanceof Number)) return "模型词块编号不是数字";
            double number = ((Number) value).doubleValue(); int id = ((Number) value).intValue();
            if (number != id || id < 0 || id >= tokens.size()) return "模型返回了不存在的词块编号";
            if (!seen.add(id)) {
                // Equivalent occurrences are interchangeable. Some models repeat the first
                // ID for a repeated word: repair only if another identical tile really exists.
                boolean found = false;
                for (int spare = 0; spare < tokens.size(); spare++) {
                    if (!seen.contains(spare) && normalize(tokens.get(spare)).equals(normalize(tokens.get(id)))) {
                        seen.add(spare); found = true; break;
                    }
                }
                if (!found) return "模型使用的重复词数量超过题面";
            }
            if (normalize(tokens.get(id)).isEmpty()) return "模型选中了空词块";
        }
        if (!response.has("confidence") || response.isNull("confidence")) return "模型未返回置信度";
        double confidence = response.optDouble("confidence", Double.NaN);
        if ((Double.isNaN(confidence) || Double.isInfinite(confidence)) || confidence < 0 || confidence > 1) return "模型置信度格式无效（须为 0～1）";
        if (confidence < MIN_CONFIDENCE) return String.format(Locale.ROOT,
                "模型置信度 %.3f，未达到 %.2f", confidence, MIN_CONFIDENCE);
        return "";
    }
    /** Numeric/status metadata only; never copy raw model strings into diagnostics. */
    public static String responseSummary(JSONObject response) {
        String score;
        double value = response.optDouble("confidence", Double.NaN);
        if (!response.has("confidence") || response.isNull("confidence")) score = "missing";
        else if ((Double.isNaN(value) || Double.isInfinite(value)) || value < 0 || value > 1) score = "invalid";
        else score = String.format(Locale.ROOT, "%.3f", value);
        JSONArray ids = response.optJSONArray("order");
        return "confidence=" + score + " order_count=" + (ids == null ? -1 : ids.length());
    }
    public static List<String> validateIds(List<String> tokens, JSONObject response) {
        if (!validationError(tokens, response).isEmpty()) return Collections.emptyList();
        JSONArray ids = response.optJSONArray("order"); List<String> answer = new ArrayList<>();
        for (int i = 0; i < ids.length(); i++) answer.add(tokens.get(((Number) ids.opt(i)).intValue()));
        return answer;
    }
}
