package com.wordmatch.assist.exercise;

import android.accessibilityservice.AccessibilityService;
import android.os.*;
import android.graphics.Rect;
import android.view.accessibility.*;
import android.util.Log;
import com.wordmatch.assist.ai.*;
import com.wordmatch.assist.sentence.*;
import com.wordmatch.assist.sentence.SentencePage.Node;
import java.util.*;
import java.util.concurrent.*;

/** Observed-state execution for text, selection, ordered tokens, and explicit skip controls. */
public final class ExerciseController {
    private final AccessibilityService service;
    private final Handler handler;
    private final SentenceController.Host host;
    private final SentenceStore store;
    private final ExerciseAttempt learning = new ExerciseAttempt();
    private final AiMatchClient client = new AiMatchClient();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable wake;
    private AiMatchClient.Call call;
    private ExerciseType type;
    private String identity = "", context = "", pending = "", failure = "";
    private List<String> answer = Collections.emptyList(), expected;
    private boolean busy, requested, ownsPause, pauseSeen, submitted, feedbackHandled, continued, automatic, wasSubmitDisabled;
    private long generation, stableAt, actionAt, submittedAt, feedbackAt;
    private int skipAttempts;
    public ExerciseController(AccessibilityService service, Handler handler, SentenceController.Host host) {
        this.service = service; this.handler = handler; this.host = host; store = new SentenceStore(service, true); wake = host::refresh;
    }
    private void poll(long ms) { handler.removeCallbacks(wake); handler.postDelayed(wake, ms); }
    private void status(String text) { host.status(text, false); }
    private void hold(String reason) { failure = reason; host.status(reason + "｜可切换学习模式", true); }
    public void reset() { runtimeReset(); learning.reset(); }
    public void modeChanged() { runtimeReset(); }
    private void runtimeReset() {
        generation++; if (call != null) call.cancel(); call = null; handler.removeCallbacks(wake);
        type = null; identity = context = pending = failure = ""; answer = Collections.emptyList(); expected = null;
        busy = requested = ownsPause = pauseSeen = submitted = feedbackHandled = continued = automatic = false;
        stableAt = actionAt = submittedAt = feedbackAt = 0; skipAttempts = 0;
    }
    public void close() { reset(); worker.shutdownNow(); }
    public boolean awaitTransition() {
        if (!ownsPause || SystemClock.uptimeMillis() - actionAt > 1500) return false;
        poll(80); return true;
    }
    public void observeClick(AccessibilityEvent event) {
        if (automatic || event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
        AccessibilityNodeInfo node = event.getSource(); if (node == null) return;
        try {
            Rect r = new Rect(); node.getBoundsInScreen(r);
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) try {
                if ("com.duolingo".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                    Rect screen = new Rect(); root.getBoundsInScreen(screen);
                    SentenceReader reader = new SentenceReader();
                    ExercisePage page = ExercisePage.parse(reader.snapshot(root), screen.right, screen.bottom, reader.truncated());
                    if (page.submit != null && page.submit.left == r.left && page.submit.top == r.top
                            && page.submit.right == r.right && page.submit.bottom == r.bottom) learning.observe(page, SystemClock.uptimeMillis());
                }
            } finally { root.recycle(); }
            learning.click(r.left,r.top,r.right,r.bottom,SystemClock.uptimeMillis());
        }
        finally { node.recycle(); }
    }
    public boolean handle(AccessibilityNodeInfo root, ExercisePage page, boolean autoMode) {
        long now = SystemClock.uptimeMillis();
        automatic = autoMode && type != null && ExerciseSettings.enabled(service, type);
        if (page.paused && ownsPause) {
            pauseSeen = true;
            if (!automatic) { reset(); return false; }
            if (!failure.isEmpty()) { hold(failure); return true; }
            if (!busy && !answer.isEmpty() && now - actionAt > 400 && SentenceReader.click(root, page.resume)) actionAt = now;
            status(busy ? "已暂停计时｜大模型答题中" : "答案就绪｜恢复答题"); poll(120); return true;
        }
        if (!page.recognized) {
            if (awaitTransition()) return true;
            reset(); return false;
        }
        if (page.skipConfirmation) {
            if (autoMode && type == page.type && ExerciseSettings.enabled(service, type) && skipAttempts > 0 && now - actionAt < 10000 && page.complete) {
                if (now - actionAt < 250) poll(100);
                else if (SentenceReader.click(root, page.next)) { skipAttempts = 0; actionAt = now; status("已确认跳过"); poll(200); }
            } else status("跳过提示｜等待手动确认");
            return true;
        }
        type = page.type; automatic = autoMode && ExerciseSettings.enabled(service, type);
        if (page.feedback) { feedback(root, page, now); return true; }
        if (ownsPause && pauseSeen) { ownsPause = pauseSeen = false; }
        if (ownsPause && !pauseSeen && now - actionAt < 600) { poll(80); return true; }
        if (ownsPause && !pauseSeen) ownsPause = false;
        if (!page.complete) {
            status(type.skip() ? "未找到对应跳过按钮｜等待手动处理" : "题面尚不完整或布局未支持｜等待手动处理");
            poll(400); return true;
        }
        String current = page.identity();
        if (!identity.equals(current) || feedbackHandled) {
            if (page.contextKey().equals(context) && !feedbackHandled && (expected != null || submitted)) {
                if (now - actionAt > 2500) hold("答题后题面未恢复完整"); else poll(100);
                return true;
            }
            if (!pending.equals(current)) { pending = current; stableAt = now; poll(100); return true; }
            if (now - stableAt < 100) { poll(100); return true; }
            if (feedbackHandled) learning.reset();
            runtimeReset(); type = page.type; identity = current; context = page.contextKey();
            automatic = autoMode && ExerciseSettings.enabled(service, type);
            Log.d("WordMatchPerf", "exercise_enter type=" + type + " mode=" + page.mode + " options=" + page.inventory().size());
        }
        learning.observe(page, now);
        if (!automatic) {
            status(autoMode ? type.label + "｜自动答题已关闭" : "学习模式｜手动提交答对后保存"); poll(200); return true;
        }
        if (!failure.isEmpty()) { hold(failure); return true; }
        if (type.skip()) {
            if (skipAttempts > 0 && now - actionAt < 1500) { poll(200); return true; }
            if (skipAttempts >= 2) { hold("跳过按钮点击后题目未切换"); return true; }
            if (SentenceReader.click(root, page.skip)) { actionAt = now; skipAttempts++; status("已点击“" + page.skip.label() + "”"); }
            else { hold("跳过按钮暂不可用"); return true; }
            poll(250); return true;
        }
        if (submitted) {
            if (now - submittedAt > 3500) hold("已提交，尚未收到判题结果"); else { status("已提交｜等待判题"); poll(120); }
            return true;
        }
        if (answer.isEmpty()) {
            if (busy) { status(type.label + "｜大模型答题中"); poll(160); return true; }
            SentenceMemory memory = store.read();
            for (SentenceMemory.Entry entry : memory.entries()) if (entry.prompt.equals(page.memoryKey()) && ExerciseAnswer.valid(page, entry.words)
                    && !memory.isRejected(entry.prompt, entry.words)) { answer = entry.words; break; }
            if (!answer.isEmpty()) { status("已验证答案命中｜开始答题"); Log.d("WordMatchPerf", "exercise_cache_hit type=" + type); }
            else {
                if (requested) { hold("本题暂无可用答案"); return true; }
                if (!AiMatchSettings.isEnabled(service)) { status("未记住本题｜请学习或开启大模型"); return true; }
                request(page);
                if (busy && page.timed) { ownsPause = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); actionAt = now; }
                poll(120); return true;
            }
        }
        if (!ExerciseAnswer.valid(page, answer)) { hold("答案与当前题面不一致"); return true; }
        List<String> visible = page.visibleAnswer();
        if (expected != null) {
            boolean confirmed = same(page, expected, visible);
            if (page.mode == ExercisePage.Mode.CHOICE && !page.multiple && visible.isEmpty() && wasSubmitDisabled && page.submit != null) confirmed = true;
            if (confirmed) expected = null;
            else if (now - actionAt < 900) { poll(70); return true; }
            else { hold("点击或输入结果未确认"); return true; }
        }
        if (now - actionAt < 90) { poll(90); return true; }
        Node target = null; List<String> wanted = new ArrayList<>(visible); String text = null;
        if (page.mode == ExercisePage.Mode.TEXT) {
            for (int i = 0; i < answer.size(); i++) if (!SentencePlan.normalize(answer.get(i)).equals(SentencePlan.normalize(page.fields.get(i).text))) {
                target = page.fields.get(i); text = answer.get(i); wanted.set(i, text); break;
            }
        } else if (page.mode == ExercisePage.Mode.ORDER) {
            int next = SentencePlan.next(answer, visible);
            if (next == -2) { target = page.selected.get(page.selected.size()-1); wanted.remove(wanted.size()-1); }
            else if (next >= 0) for (Node n : page.options) if (SentencePlan.normalize(n.label()).equals(SentencePlan.normalize(answer.get(next)))) {
                target = n; wanted.add(n.label()); break;
            }
        } else {
            if (visible.isEmpty()) visible = learning.chosen();
            wanted = new ArrayList<>(visible);
            for (Node n : page.options) if (visible.contains(n.text) && !answer.contains(n.text)) {
                if (!page.multiple) break;
                target = n; wanted.remove(n.text); break;
            }
            if (target == null) for (Node n : page.options) if (answer.contains(n.text) && !visible.contains(n.text)) {
                target = n; if (!page.multiple) wanted.clear(); wanted.add(n.text); break;
            }
        }
        if (target == null) {
            if (!same(page, answer, visible)) { hold("未找到下一项可操作答案"); return true; }
            if (page.submit == null) { if (now - actionAt < 1200) poll(100); else hold("提交按钮尚不可用"); return true; }
            if (SentenceReader.click(root, page.submit)) {
                learning.click(page.submit.left,page.submit.top,page.submit.right,page.submit.bottom,now);
                submitted = true; submittedAt = actionAt = now;
                Log.d("WordMatchPerf", "exercise_submit type=" + type);
                poll(120);
            } else hold("提交按钮点击失败");
            return true;
        }
        wasSubmitDisabled = page.submit == null;
        boolean done = text == null ? SentenceReader.click(root, target) : SentenceReader.setText(root, target, text);
        actionAt = now;
        if (done) {
            if (page.mode == ExercisePage.Mode.CHOICE) learning.click(target.left,target.top,target.right,target.bottom,now);
            if (page.mode == ExercisePage.Mode.CHOICE && page.gradesOnChoice) {
                submitted = true; submittedAt = now; expected = null;
                Log.d("WordMatchPerf", "exercise_submit type=" + type + " via=choice");
            } else expected = wanted;
            status(type.label + "｜正在填写"); poll(90);
        } else hold("当前选项或输入框暂不可操作");
        return true;
    }
    private boolean same(ExercisePage p, List<String> a, List<String> b) {
        return p.mode == ExercisePage.Mode.CHOICE ? SentencePlan.bankKey(a).equals(SentencePlan.bankKey(b)) : SentencePlan.same(a,b);
    }
    private void request(ExercisePage page) {
        requested = true; AiMatchSettings.Settings settings = AiMatchSettings.load(service);
        if (!settings.isConfigured()) { hold("请先配置大模型接口"); return; }
        busy = true; AiMatchClient.Call active = new AiMatchClient.Call(); call = active;
        long token = generation; String original = identity;
        worker.execute(() -> {
            AiMatchClient.SentenceResult result = client.exercise(settings, page, active);
            handler.post(() -> {
                if (generation != token || call != active || !identity.equals(original)) return;
                busy = false; call = null;
                if (!ExerciseSettings.enabled(service, page.type) || !AiMatchSettings.isEnabled(service)) { reset(); return; }
                if (!result.error.isEmpty()) hold(result.error);
                else if (store.read().isRejected(page.memoryKey(), page.memoryAnswer(result.words))) hold("此答案曾被判错｜请用学习模式纠正");
                else { answer = result.words; status("模型答案已校验"); }
                poll(1);
            });
        });
    }
    private void feedback(AccessibilityNodeInfo root, ExercisePage page, long now) {
        if (call != null) call.cancel(); call = null; busy = false;
        if (!page.correct && !page.wrong) {
            if (feedbackAt == 0) feedbackAt = now;
            if (now - feedbackAt < 1200) { status("等待明确判题反馈"); poll(100); }
            else host.status("判题反馈未识别｜本次未保存", true);
            return;
        }
        if (!feedbackHandled) {
            feedbackHandled = true; boolean proof = learning.proof(page, now);
            boolean saved = page.correct && proof && store.remember(learning.key(), learning.submitted(), true, learning.submitted());
            boolean rejected = page.wrong && proof && store.reject(learning.key(), learning.submitted());
            Log.i("WordMatchPerf", "exercise_feedback type=" + page.type + " correct=" + page.correct + " saved=" + saved + " rejected=" + rejected + " reason=" + learning.proofReason(page, now));
            if (page.wrong) host.status("答错了｜切换学习模式教它正确答案", true);
            else status(saved ? "答对了｜答案已记住" : "答对了｜本次未取得完整提交记录");
        }
        if (automatic && page.correct && !continued && now - actionAt > 220) {
            continued = SentenceReader.click(root, page.next); actionAt = now;
        }
        if (automatic && page.correct && !continued) poll(250);
    }
}
