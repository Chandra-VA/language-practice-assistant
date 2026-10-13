package com.wordmatch.assist.ai;

import com.wordmatch.assist.matching.TranslationLookup;
import com.wordmatch.assist.matching.WordNormalizer;
import com.wordmatch.assist.model.WordBox;
import java.util.List;

/** Do not let a model response manufacture ambiguous exact edges on the observed board. */
public final class AiVocabularyAdmission {
    private AiVocabularyAdmission() {}

    public static boolean conflicts(String left, String right, List<WordBox> board,
                                    int width, TranslationLookup known) {
        String a = WordNormalizer.normalize(left), b = WordNormalizer.normalize(right);
        for (WordBox word : board) {
            String visible = word.getNormalized();
            if (word.getCenterX() < width / 2) {
                if (!visible.equals(a) && known.score(visible, b) >= 0.999999) return true;
            } else {
                if (!visible.equals(b) && known.score(a, visible) >= 0.999999) return true;
            }
        }
        return false;
    }

    /** A transport success with no newly usable vocabulary must not restart the same lookup forever. */
    public static String failureReason(int added, int usable, int rejectedConflicts) {
        if (added > 0) return "";
        if (rejectedConflicts > 0) return "模型词对与当前已有配对冲突，已拒绝保存";
        if (usable > 0) return "模型重复返回已有词对，未解决当前匹配";
        return "模型已返回，但词库保存失败";
    }
}
