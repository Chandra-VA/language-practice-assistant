package com.wordmatch.assist.sentence;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** A separate private file; no word-pair vocabulary or model credentials are changed. */
public final class SentenceStore {
    private static final Object LOCK = new Object();
    private final AtomicFile file;
    public SentenceStore(Context context) { this(context, false); }
    public SentenceStore(Context context, boolean exercises) {
        file = new AtomicFile(new File(context.getFilesDir(), exercises ? "verified_exercises.json" : "verified_sentences.json"));
    }
    public SentenceMemory read() {
        synchronized (LOCK) {
            try {
                if (file.getBaseFile().length() > 4_000_000) return new SentenceMemory();
                return SentenceMemory.decode(new String(file.readFully(), StandardCharsets.UTF_8));
            } catch (IOException ignored) { return new SentenceMemory(); }
        }
    }
    private boolean write(SentenceMemory memory) {
        FileOutputStream output = null;
        try {
            byte[] data = memory.encode().getBytes(StandardCharsets.UTF_8);
            output = file.startWrite(); output.write(data); file.finishWrite(output); return true;
        } catch (IOException | JSONException error) { if (output != null) file.failWrite(output); return false; }
    }
    public boolean remember(String prompt, List<String> words, boolean correct, List<String> observed) {
        synchronized (LOCK) {
            SentenceMemory memory = read();
            return memory.remember(prompt, words, correct, observed) && write(memory);
        }
    }
    public boolean reject(String prompt, List<String> words) {
        synchronized (LOCK) { SentenceMemory memory = read(); return memory.reject(prompt, words) && write(memory); }
    }
    public boolean remove(String prompt, List<String> words) {
        synchronized (LOCK) { SentenceMemory memory = read(); memory.remove(prompt, words); return write(memory); }
    }
}
