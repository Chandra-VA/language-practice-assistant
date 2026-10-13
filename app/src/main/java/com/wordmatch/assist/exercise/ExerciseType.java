package com.wordmatch.assist.exercise;

/** Independent automatic policies. Learning never invokes these actions. */
public enum ExerciseType {
    MATCH("单词配对"), TRANSLATE_ZH_EN("中译英词块组句"), TRANSLATE_EN_ZH("英译中词块组句"),
    CHOICE("词义、图片文字选项"), FILL("填空、补全句子"), READING("阅读理解"), DIALOGUE("对话应答"),
    TEXT("文字输入翻译"), ORDER("其他词块排列"), LISTEN("听力：自动点“现在不做听力题”"),
    SPEAK("口语：自动点“现在不能说话”");
    public final String label;
    ExerciseType(String label) { this.label = label; }
    public boolean skip() { return this == LISTEN || this == SPEAK; }
}
