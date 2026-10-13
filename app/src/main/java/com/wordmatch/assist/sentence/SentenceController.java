package com.wordmatch.assist.sentence;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityEvent;
import android.graphics.Rect;
import com.wordmatch.assist.ai.AiMatchClient;
import com.wordmatch.assist.ai.AiMatchSettings;
import java.util.*;
import java.util.concurrent.*;

/** One observed selection change per click; asynchronous models cannot act on a later question. */
public final class SentenceController {
    public interface Host { void status(String text, boolean warning); void refresh(); }
    private final AccessibilityService service;
    private final Handler handler;
    private final Host host;
    private final SentenceStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AiMatchClient client = new AiMatchClient();
    private final Runnable wake;
    private AiMatchClient.Call call;
    private final SentenceRetry retry = new SentenceRetry();
    private final SentenceLearning learning = new SentenceLearning();
    private Runnable pendingRetry;
    private long generation, stableSince, actionAt, pauseAt, submittedAt, feedbackAt;
    private String identity = "", pendingIdentity = "", prompt = "", failure = "";
    private List<String> answer = Collections.emptyList();
    private List<String> expected, beforeClick;
    private boolean requested, busy, ownsPause, pauseSeen, feedbackHandled, submitted, continued;
    private int attempts;

    public SentenceController(AccessibilityService service, Handler handler, Host host) {
        this.service = service; this.handler = handler; this.host = host; store = new SentenceStore(service);
        wake = host::refresh;
    }
    private void poll(long delay) { handler.removeCallbacks(wake); handler.postDelayed(wake, delay); }
    public boolean active() { return !identity.isEmpty() || ownsPause; }
    /** Opening/closing a modal can briefly expose only the header or an empty root. */
    public boolean awaitPauseTransition() {
        if (!ownsPause || SystemClock.uptimeMillis() - actionAt >= 1500) return false;
        poll(80); return true;
    }
    public void reset() { resetRuntime(); learning.reset(); }
    /** Switching the floating mode cancels clicks/requests but retains the observed attempt. */
    public void modeChanged() { resetRuntime(); }
    private void resetRuntime() {
        generation++; if (call != null) call.cancel(); call = null;
        retry.reset(); pendingRetry = null;
        handler.removeCallbacks(wake);
        identity = ""; pendingIdentity = ""; prompt = ""; failure = "";
        answer = Collections.emptyList(); expected = null; beforeClick = null;
        requested = busy = ownsPause = pauseSeen = feedbackHandled = submitted = continued = false;
        actionAt = pauseAt = submittedAt = feedbackAt = 0; attempts = 0;
    }
    public void close() { reset(); worker.shutdownNow(); }
    private void status(String text) { host.status(text, false); }
    private void hold(String reason) { failure = reason; host.status(reason + "｜切换学习模式可接管", true); }

    public void observeClick(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
        AccessibilityNodeInfo node = event.getSource();
        if (node == null) return;
        try {
            Rect r = new Rect(); node.getBoundsInScreen(r);
            String label = node.getText() == null ? "" : node.getText().toString().trim();
            if (label.isEmpty() && node.getContentDescription() != null) label = node.getContentDescription().toString().trim();
            if (label.isEmpty() && event.getText().size() == 1) label = event.getText().get(0).toString().trim();
            learning.checkClicked(label, r.left, r.top, r.right, r.bottom, SystemClock.uptimeMillis());
        } finally { node.recycle(); }
    }
    public void handle(AccessibilityNodeInfo root, SentencePage page, boolean automatic) {
        long now = SystemClock.uptimeMillis();
        if (page.recognized && page.feedback) { handleFeedback(root, page, automatic, now); return; }
        if (retry.pending()) {
            Runnable action = pendingRetry; pendingRetry = null;
            if (retry.take(automatic && page.paused && ownsPause) && action != null) {
                action.run(); status("组句请求超时｜保持暂停，重试 1/1"); poll(150);
            } else hold("组句请求超时，暂停已结束｜请重新启动自动模式重试");
            return;
        }
        if (page.paused) {
            if (!automatic || !ownsPause) { status("组句已暂停｜等待手动返回"); return; }
            pauseSeen = true;
            if (!failure.isEmpty()) { hold(failure); return; }
            if (busy) { status("组句推理中｜计时已暂停"); poll(150); return; }
            if (!answer.isEmpty() && now - actionAt >= 500 && SentenceReader.click(root, page.resume)) {
                actionAt = now; status("答案已就绪｜恢复组句");
            }
            poll(120); return;
        }
        if (!page.recognized || (!page.complete && !page.feedback)) { status("等待完整词块组句题"); poll(150); return; }
        learning.observe(page, now);
        if (ownsPause && pauseSeen) { ownsPause = false; pauseSeen = false; }
        if (ownsPause && !pauseSeen && now - pauseAt < 550) { poll(80); return; }
        if (ownsPause && !pauseSeen) ownsPause = false;

        String current = page.identity();
        if (!current.equals(identity) || (feedbackHandled && !page.feedback)) {
            // Tile animations can briefly remove a token from both lists. Do not turn that
            // intermediate frame into a new question or cancel its in-flight answer.
            if (prompt.equals(page.prompt) && !feedbackHandled && (expected != null || submitted)) {
                if (now - actionAt > 2500) hold("词块变化后未恢复完整题面"); else poll(80);
                return;
            }
            if (!current.equals(pendingIdentity)) { pendingIdentity = current; stableSince = now; poll(100); return; }
            if (now - stableSince < 100) { poll(100); return; }
            resetRuntime(); identity = current; prompt = page.prompt;
            Log.d("WordMatchPerf", "sentence_enter direction=" + SentencePlan.direction(prompt) + " tokens=" + page.allWords().size());
        }
        List<String> selection = page.selectedWords();
        if (!automatic) { status("学习组句｜答对后记住整句"); poll(150); return; }
        if (!failure.isEmpty()) { hold(failure); return; }
        if (submitted) {
            if (now - submittedAt > 3000) { hold("尚未收到组句判题结果"); return; }
            status("组句已提交｜等待判题"); poll(100); return;
        }
        if (answer.isEmpty()) {
            if (busy) { status("大模型正在排列词块"); poll(150); return; }
            SentenceMemory memory = store.read();
            answer = memory.lookup(prompt, page.allWords());
            if (answer.isEmpty()) Log.d("WordMatchPerf", "sentence_cache_miss known_prompt=" + memory.hasPrompt(prompt));
            if (!answer.isEmpty()) { status("整句记忆命中｜开始组句"); Log.d("WordMatchPerf", "sentence_cache_hit words=" + answer.size()); }
            else {
                if (requested) { hold("没有可用的完整组句答案"); return; }
                if (!AiMatchSettings.isEnabled(service)) { status("未记住这句｜请手动答题或开启大模型"); return; }
                request(page);
                if (busy && page.timed) {
                    ownsPause = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    pauseAt = actionAt = now;
                }
                poll(100); return;
            }
        }
        if (!SentencePlan.available(answer, page.allWords())) { hold("当前词块与答案不一致"); return; }
        if (expected != null) {
            if (SentencePlan.same(expected, selection)) { expected = null; attempts = 0; }
            else if (now - actionAt < 650) { poll(60); return; }
            else if (SentencePlan.same(beforeClick, selection) && attempts < 2) expected = null;
            else { hold("词块点击未确认"); return; }
        }
        if (now - actionAt < 70) { poll(70); return; }
        int next = SentencePlan.next(answer, selection);
        if (next == -1) {
            if (SentenceReader.click(root, page.check)) {
                learning.checkClicked("检查", page.check.left, page.check.top, page.check.right, page.check.bottom, now);
                submitted = true; submittedAt = actionAt = now;
                Log.d("WordMatchPerf", "sentence_submit words=" + selection.size());
            } else if (++attempts >= 3) { hold("无法点击组句检查按钮"); return; }
            poll(120); return;
        }
        SentencePage.Node target = null;
        List<String> wanted = new ArrayList<>(selection);
        if (next == -2 && !page.selected.isEmpty()) {
            target = page.selected.get(page.selected.size() - 1); wanted.remove(wanted.size() - 1);
        } else if (next >= 0) {
            String word = answer.get(next);
            for (SentencePage.Node tile : page.bank) if (SentencePlan.normalize(tile.label()).equals(SentencePlan.normalize(word))) { target = tile; break; }
            if (target != null) wanted.add(target.label());
        }
        attempts++; actionAt = now;
        if (SentenceReader.click(root, target)) {
            beforeClick = new ArrayList<>(selection); expected = wanted;
            status("组句 " + wanted.size() + "/" + answer.size());
        } else if (attempts >= 2) { hold("词块暂不可点击"); return; }
        poll(70);
    }
    private void handleFeedback(AccessibilityNodeInfo root, SentencePage page, boolean automatic, long now) {
        if (!prompt.equals(page.prompt)) {
            prompt = page.prompt; feedbackHandled = false; continued = false; feedbackAt = 0;
        }
        if (!page.correct && !page.wrong) {
            if (feedbackAt == 0) feedbackAt = now;
            if (now - feedbackAt < 1000) { status("等待组句判题文字"); poll(80); }
            else host.status("判题反馈暂未识别｜本次尚未保存", true);
            return;
        }
        if (!feedbackHandled) {
            feedbackHandled = true;
            List<String> selection = page.selectedWords();
            boolean eligible = learning.canRemember(page, now);
            boolean saved = page.correct && eligible && store.remember(page.prompt, selection, true, selection);
            boolean rejected = page.wrong && page.selectedComplete && store.reject(page.prompt, selection);
            String reason = saved ? "saved" : page.correct ? eligible ? "storage_error" : learning.proof(page, now) : "incorrect";
            Log.i("WordMatchPerf", "sentence_feedback correct=" + page.correct + " saved=" + saved
                    + " rejected=" + rejected + " words=" + selection.size() + " reason=" + reason);
            if (saved) status("组句正确｜整句已记住");
            else if (page.correct) host.status(eligible ? "答对了，但保存失败｜请检查存储空间" : "答对了，但提交过程未记录完整｜本次未保存", true);
            else host.status(rejected ? "组句错误｜已记住此错答，避免再次使用" : "组句错误｜未取得完整错答记录", true);
            // A result page never launches a model or resumes an old request.
            if (call != null) call.cancel(); call = null; busy = false;
            retry.reset(); pendingRetry = null;
        }
        if (automatic && page.correct && !continued && now - actionAt > 220) {
            continued = SentenceReader.click(root, page.next); actionAt = now;
        }
        if (automatic && page.correct && !continued) poll(250);
    }
    private void request(SentencePage page) {
        AiMatchSettings.Settings settings = AiMatchSettings.load(service);
        requested = true;
        if (!settings.isConfigured()) { hold("大模型设置不完整"); return; }
        request(settings, prompt, new ArrayList<>(page.allWords()));
    }
    private void request(AiMatchSettings.Settings settings, String requestPrompt, List<String> tokens) {
        if (!retry.begin()) { hold("组句请求重试已用完"); return; }
        busy = true; call = new AiMatchClient.Call();
        AiMatchClient.Call requestCall = call;
        long ticket = generation; String requestIdentity = identity;
        long startedAt = SystemClock.uptimeMillis(); int attempt = retry.attempts();
        Log.d("WordMatchPerf", "sentence_ai_start tokens=" + tokens.size() + " attempt=" + attempt
                + " limit_ms=" + settings.getTimeoutMs());
        worker.submit(() -> {
            AiMatchClient.SentenceResult result = client.sentence(settings, requestPrompt, SentencePlan.direction(requestPrompt), tokens, requestCall);
            handler.post(() -> {
                if (ticket != generation || !requestIdentity.equals(identity) || call != requestCall) return;
                call = null; busy = false;
                long elapsed = SystemClock.uptimeMillis() - startedAt;
                if (result.timedOut) Log.i("WordMatchPerf", "sentence_ai_timeout elapsed_ms=" + elapsed
                        + " attempt=" + attempt + " limit_ms=" + settings.getTimeoutMs());
                if (result.words.isEmpty() && !result.validationSummary.isEmpty())
                    Log.i("WordMatchPerf", "sentence_ai_rejected elapsed_ms=" + elapsed + " " + result.validationSummary);
                if (retry.offer(result.timedOut, ownsPause && pauseSeen)) {
                    pendingRetry = () -> request(settings, requestPrompt, tokens);
                    status("组句请求超时｜确认暂停后重试一次");
                } else if (result.words.isEmpty()) hold(result.error
                        + (result.timedOut && attempt == 2 ? "｜已重试一次" : ""));
                else if (store.read().isRejected(requestPrompt, result.words)) {
                    hold("模型再次返回已判错答案｜请用学习模式纠正");
                    Log.i("WordMatchPerf", "sentence_repeated_wrong_blocked words=" + result.words.size());
                } else { answer = new ArrayList<>(result.words); status("完整组句已生成"); }
                Log.d("WordMatchPerf", "sentence_ai_done accepted=" + !answer.isEmpty()
                        + " elapsed_ms=" + elapsed + " attempt=" + attempt + " " + result.validationSummary);
                host.refresh();
            });
        });
    }
}
