package com.wordmatch.assist.exercise;
import com.wordmatch.assist.sentence.SentencePlan;
import org.json.*;
import java.util.*;

/** The model supplies values, never coordinates, resource IDs, or executable actions. */
public final class ExerciseAnswer {
    private ExerciseAnswer() {}
    public static List<String> validate(ExercisePage page, JSONObject json) {
        Object raw = json.opt("confidence");
        if (!(raw instanceof Number)) return Collections.emptyList();
        double confidence = ((Number) raw).doubleValue();
        if (!Double.isFinite(confidence) || confidence < .92 || confidence > 1) return Collections.emptyList();
        if (page.mode == ExercisePage.Mode.ORDER) return SentencePlan.validateIds(page.inventory(), json);
        JSONArray values = json.optJSONArray(page.mode == ExercisePage.Mode.TEXT ? "texts" : "choice_ids");
        if (values == null) return Collections.emptyList();
        List<String> answer = new ArrayList<>(); Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            Object value = values.opt(i);
            if (page.mode == ExercisePage.Mode.TEXT) {
                if (!(value instanceof String)) return Collections.emptyList();
                answer.add((String) value);
            } else if (page.mode == ExercisePage.Mode.CHOICE) {
                if (!(value instanceof Number)) return Collections.emptyList();
                double number = ((Number) value).doubleValue(); int id = ((Number) value).intValue();
                if (!Double.isFinite(number) || number != id || id < 0 || id >= page.options.size() || !seen.add(id)) return Collections.emptyList();
                answer.add(page.options.get(id).text);
            } else return Collections.emptyList();
        }
        return valid(page, answer) ? answer : Collections.emptyList();
    }
    public static String validationError(ExercisePage page, JSONObject json) {
        Object raw = json.opt("confidence");
        if (raw == null || raw == JSONObject.NULL) return "模型未返回置信度";
        if (!(raw instanceof Number)) return "模型置信度格式无效（须为数字）";
        double score = ((Number) raw).doubleValue();
        if (!Double.isFinite(score) || score < 0 || score > 1) return "模型置信度格式无效（须为 0～1）";
        if (score < .92) return String.format(Locale.ROOT, "模型置信度 %.3f，未达到 0.92", score);
        return page.mode == ExercisePage.Mode.TEXT ? "模型输入答案数量或内容不符合当前输入框" : "模型返回的选项编号或顺序不符合当前题面";
    }
    public static boolean valid(ExercisePage p, List<String> answer) {
        if (answer.isEmpty() || answer.size() > 40) return false;
        for (String s : answer) if (s == null || s.trim().isEmpty() || s.length() > 2000 || s.matches("(?s).*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f].*")) return false;
        if (p.mode == ExercisePage.Mode.TEXT) return answer.size() == p.fields.size();
        if (p.mode == ExercisePage.Mode.CHOICE && !p.multiple && answer.size() != 1) return false;
        return p.mode != ExercisePage.Mode.SKIP && SentencePlan.available(answer, p.inventory());
    }
}
