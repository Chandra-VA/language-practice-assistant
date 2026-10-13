package com.wordmatch.assist.exercise;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
public final class ExerciseSettings {
    private static final String FILE = "automatic_question_types";
    private ExerciseSettings() {}
    public static boolean enabled(Context c, ExerciseType type) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(type.name(), true);
    }
    public static void save(Context c, Set<ExerciseType> enabled) {
        SharedPreferences.Editor edit = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit();
        for (ExerciseType type : ExerciseType.values()) edit.putBoolean(type.name(), enabled.contains(type));
        edit.apply();
    }
}
