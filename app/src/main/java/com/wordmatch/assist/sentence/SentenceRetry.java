package com.wordmatch.assist.sentence;

/** One retry per question, offered and consumed only while our pause is confirmed. */
public final class SentenceRetry {
    private int attempts;
    private boolean pending;
    public void reset() { attempts = 0; pending = false; }
    public boolean begin() {
        if (attempts >= 2) return false;
        attempts++; pending = false; return true;
    }
    public int attempts() { return attempts; }
    public boolean offer(boolean timedOut, boolean confirmedPause) {
        pending = timedOut && confirmedPause && attempts == 1;
        return pending;
    }
    public boolean pending() { return pending; }
    public boolean take(boolean stillPaused) {
        boolean allowed = pending && stillPaused && attempts == 1;
        pending = false; return allowed;
    }
}
