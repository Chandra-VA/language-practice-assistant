package com.wordmatch.assist.matching;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalization shared by accessible text, imported words and learned pairs. */
public final class WordNormalizer {
    private static final Pattern LEADING_PUNCTUATION = Pattern.compile("^[\\p{P}]+");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[\\p{P}]+$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private WordNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201c', '"')
                .replace('\u201d', '"')
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .trim();

        value = LEADING_PUNCTUATION.matcher(value).replaceAll("");
        value = TRAILING_PUNCTUATION.matcher(value).replaceAll("");
        value = WHITESPACE.matcher(value).replaceAll(" ").trim();
        return value;
    }

    public static double similarity(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.equals(b)) {
            return 1.0;
        }
        int maxLength = Math.max(a.length(), b.length());
        if (maxLength == 0) {
            return 1.0;
        }
        return 1.0 - ((double) levenshtein(a, b) / maxLength);
    }

    static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitutionCost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + substitutionCost
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
