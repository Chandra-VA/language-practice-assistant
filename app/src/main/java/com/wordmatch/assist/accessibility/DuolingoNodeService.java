package com.wordmatch.assist.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import com.wordmatch.assist.R;
import com.wordmatch.assist.exercise.*;
import com.wordmatch.assist.sentence.SentencePlan;
import com.wordmatch.assist.sentence.SentenceController;
import com.wordmatch.assist.sentence.SentencePage;
import com.wordmatch.assist.sentence.SentenceReader;
import com.wordmatch.assist.MainActivity;
import com.wordmatch.assist.ai.AiMatchClient;
import com.wordmatch.assist.ai.AiMatchSettings;
import com.wordmatch.assist.ai.AiPairValidator;
import com.wordmatch.assist.ai.AiBoardRequest;
import com.wordmatch.assist.ai.AiLookupPlan;
import com.wordmatch.assist.ai.AiFailureRecheck;
import com.wordmatch.assist.ai.AiVocabularyAdmission;
import com.wordmatch.assist.capture.FastCaptureService;
import com.wordmatch.assist.matching.GreenCardDetector;
import com.wordmatch.assist.matching.FreshGreenFrames;
import com.wordmatch.assist.matching.FadedPairConfirmation;
import com.wordmatch.assist.matching.ScreenshotCadence;
import com.wordmatch.assist.matching.AutoPairConfirmation;
import com.wordmatch.assist.matching.AutoPairRecovery;
import com.wordmatch.assist.matching.CardVisualState;
import com.wordmatch.assist.matching.PartialPairRetry;
import com.wordmatch.assist.matching.ActivePairSelectionGuard;
import com.wordmatch.assist.matching.VisualBoardSelection;
import com.wordmatch.assist.matching.VisualConfirmationGeometry;
import com.wordmatch.assist.matching.ConfirmationPollTiming;
import com.wordmatch.assist.matching.VisualPairRetry;
import com.wordmatch.assist.matching.SelectedPairPlanner;
import com.wordmatch.assist.matching.SecondClickTiming;
import com.wordmatch.assist.matching.BurstPausePolicy;
import com.wordmatch.assist.matching.ReplacementBatchWindow;
import com.wordmatch.assist.matching.ReplacementPairConfirmation;
import com.wordmatch.assist.matching.SlowProgressPause;
import com.wordmatch.assist.matching.DispatchAcceptance;
import com.wordmatch.assist.matching.SubmittedBoardEvidence;
import com.wordmatch.assist.matching.ConfirmationPace;
import com.wordmatch.assist.matching.DeferredPairConfirmation;
import com.wordmatch.assist.matching.ExactPairSelector;
import com.wordmatch.assist.matching.FinalPairResolver;
import com.wordmatch.assist.matching.IssuedPairRetentionPolicy;
import com.wordmatch.assist.matching.PairMatcher;
import com.wordmatch.assist.matching.PauseDismissalPolicy;
import com.wordmatch.assist.matching.PauseConfirmationDetector;
import com.wordmatch.assist.matching.VocabularyStore;
import com.wordmatch.assist.matching.WordNormalizer;
import com.wordmatch.assist.learning.ManualLearningTracker;
import com.wordmatch.assist.model.MatchPair;
import com.wordmatch.assist.model.WordBox;
import com.wordmatch.assist.overlay.MatchOverlayView;
import com.wordmatch.assist.overlay.ModeControlView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Event-driven, no-OCR highlighter that reads Duolingo's accessibility node tree. */
public final class DuolingoNodeService extends AccessibilityService {
    private SentenceController sentences;
    private ExerciseController exercises;
    private boolean sentenceVisible;
    private ExerciseType sentenceType = ExerciseType.TRANSLATE_EN_ZH;
    private final SentenceReader sentenceReader = new SentenceReader();
    private static boolean automaticStartRequested;

    /** Consume a user's overlay selection; an external activity intent cannot select a mode. */
    public static boolean consumeAutomaticStartRequest() {
        boolean requested = automaticStartRequested;
        automaticStartRequested = false;
        return requested;
    }

    private static final String DUOLINGO_PACKAGE = "com.duolingo";
    private static final String PREFERENCES = "node_assist_preferences";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_FREEZE_MODE = "freeze_mode";
    private static final String KEY_AUTO_MODE = "auto_mode";
    private static final String KEY_ADAPTIVE_PAUSE_MS = "adaptive_pause_ms";
    private static final long ANIMATION_POLL_INTERVAL_MS = 16L;
    private static final long ANIMATION_POLL_DURATION_MS = 4000L;
    private static final long MANUAL_LEARNING_WINDOW_MS = 10_000L;
    private static final long INITIAL_PAUSE_LOAD_MS = 280L;
    private static final long MIN_PAUSE_LOAD_MS = 160L;
    private static final long MAX_PAUSE_LOAD_MS = 420L;
    private static final long RECOVERY_PAUSE_LOAD_MS = 160L;
    private static final long FULL_SCREEN_PEEK_POLL_MS = 8L;
    private static final long FULL_SCREEN_PEEK_MAX_MS = 120L;
    private static final long PARTIAL_BATCH_DECISION_MS = 32L;
    private static final long CONFIRMED_PAIR_GAP_MS = 0L;
    private static final long BURST_ROOT_RETRY_MS = 8L;
    private static final long BURST_WATCHDOG_INTERVAL_MS = 64L;
    private static final long FIRST_CLICK_STALL_MS = 800L;
    private static final long FREEZE_RETRY_DELAY_MS = 8L;
    private static final long DIALOG_DISMISS_SETTLE_MS = 12L;
    private static final long DIALOG_TAP_DURATION_MS = 16L;
    private static final long DIALOG_DISMISS_VERIFY_INTERVAL_MS = 8L;
    private static final long DIALOG_DISMISS_STABLE_MS = 96L;
    private static final long DIALOG_DISMISS_EMPTY_STABLE_MS = 240L;
    private static final long DIALOG_DISMISS_TIMEOUT_MS = 800L;
    private static final int MAX_DIALOG_DISMISS_ATTEMPTS = 2;
    private static final long POST_CLICK_FREEZE_DELAY_MS = 8L;
    private static final int MAX_FREEZE_REQUEST_ATTEMPTS = 8;
    // Some devices keep successful green cards in the node tree for several seconds.
    // Positive feedback advances immediately; missing feedback gets an animation grace period.
    private static final long AUTO_PAIR_TIMEOUT_MS = 6000L;
    private static final long AUTO_RETRY_DELAY_MS = 80L;
    private static final long PROGRAMMATIC_CLICK_EVENT_WINDOW_MS = 1100L;
    private static final long PROGRAMMATIC_CLICK_GUARD_MS = 180L;
    private static final long ISSUED_PAIR_MAX_AGE_MS = 4000L;
    private static final long USER_TAKEOVER_HOLD_MS = 280L;
    private static final long LEARNING_NOTICE_MS = 2500L;
    private static final long AI_SUCCESS_COOLDOWN_MS = 3000L;
    private static final long AI_ERROR_COOLDOWN_MS = 15000L;
    private static final int MAX_CLICK_SEARCH_NODES = 2400;
    private static final int MAX_CLICK_ANCESTORS = 5;
    private static volatile DuolingoNodeService connectedInstance;

    private boolean refreshScheduled;
    private long pendingSinceNanos;
    private boolean animationPollScheduled;
    private long animationPollUntil;
    private boolean freezeActive;
    private final SlowProgressPause slowProgressPause = new SlowProgressPause();
    private boolean slowWatchScheduled, slowPauseActive, slowPauseHolding, slowPairWasPaused;
    private long slowPauseStartedAt, slowPauseDialogSeenAt, pauseTransitionGuardUntil;
    private long adaptivePauseLoadMs;
    private int freezeExpectedWordCount;
    private boolean freezeDismissPending;
    private long freezeDismissVerificationStartedAt;
    private long freezeDialogAbsentSince;
    private int freezeDismissAttempts;
    private int pendingFreezeExpectedWordCount;
    private int freezeRequestAttempts;
    private boolean pendingFreezeRecovery;
    private long activeFreezeLoadMs;
    private boolean fullScreenPeekActive;
    private long fullScreenPeekStartedAt;
    private int fullScreenPeekExpectedWordCount;
    private int fullScreenPeekWordCount;
    private int fullScreenPeekLatestWordCount;
    private boolean fullScreenPeekSawPartialBoard;
    private boolean fullScreenPeekPartialAdapted;
    private final List<MatchPair> fullScreenPeekMatches = new ArrayList<>();
    private final List<PendingProgrammaticClick> pendingProgrammaticClicks = new ArrayList<>();
    private final List<IssuedPair> recentlyIssuedPairs = new ArrayList<>();
    private final List<DeferredPairConfirmation> deferredPairs = new ArrayList<>();
    private final DispatchAcceptance dispatchAcceptance = new DispatchAcceptance();
    private long lastBatchDispatchAt, lastDeferredFrameCheckAt;
    private int burstDispatchedPairs;
    private boolean hasDeferredHistory;
    private final ConfirmationPace confirmationPace = new ConfirmationPace();
    private final List<MatchPair> burstQueue = new ArrayList<>();
    private boolean burstActive;
    private boolean freezeRequestPending;
    private boolean autoBurstPair;
    private int burstBoardWordCount;
    private int burstClickedPairs;
    private boolean autoClickBusy;
    private boolean autoSecondClickIssued;
    private boolean autoConfirmPollScheduled;
    private long burstNextEligibleAt;
    private long lastBurstProgressAt;
    private long autoRetryBlockedUntil;
    private WordBox autoFirstTarget;
    private WordBox autoSecondTarget;
    private long autoFirstClickAt;
    private long autoSecondClickAt;
    private WordBox autoFirstCardBounds;
    private WordBox autoSecondCardBounds;
    private int visualGeneration;
    private boolean visualCaptureInFlight;
    private boolean visualCaptureUnavailable;
    private boolean visualGestureInFlight;
    private final PartialPairRetry partialPairRetry = new PartialPairRetry();
    private final ActivePairSelectionGuard activeSelectionGuard = new ActivePairSelectionGuard();
    private final VisualBoardSelection visualBoardSelection = new VisualBoardSelection();
    private final ScreenshotCadence screenshotCadence = new ScreenshotCadence();
    private final FadedPairConfirmation fadedPairConfirmation = new FadedPairConfirmation();
    private boolean autoPairStartedActive;
    private final FreshGreenFrames fastFrames = new FreshGreenFrames();
    private long lastFastFrameAt;
    private long lastMismatchedFrameAt;
    private String lastConfirmationSource = "";
    private String lastPixelStates = "none";
    private final ReplacementPairConfirmation replacementPairConfirmation = new ReplacementPairConfirmation();
    private final ReplacementBatchWindow replacementBatchWindow = new ReplacementBatchWindow();
    private final VisualPairRetry visualPairRetry = new VisualPairRetry();
    private List<WordBox> recoverySampleCards = Collections.emptyList();
    private long recoverySamplesPreparedAt;
    private List<WordBox> highlightBoardCache = Collections.emptyList();
    private List<MatchPair> highlightMatchesCache = Collections.emptyList();
    private PairMatcher highlightMatcherCache;
    private int highlightWidthCache;
    private int autoBoardWordCount;
    private long lastTileClickAt;
    private long userTakeoverUntil;
    private long programmaticClickGuardUntil;
    private long learningNoticeUntil;
    private String learningNotice = "";
    private boolean learningNoticeWarning;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final AiMatchClient aiMatchClient = new AiMatchClient();
    private boolean aiLookupInFlight;
    private boolean aiLookupHoldingPause;
    private AiMatchClient.Call activeAiCall;
    private Future<?> activeAiTask;
    private long aiLookupStartedAt;
    private String lastAiError = "";
    private int aiLookupGeneration;
    private String lastAiBoardSignature = "";
    private long aiLookupBlockedUntil;
    private final AiFailureRecheck aiFailureRecheck = new AiFailureRecheck();
    private final List<WordBox> previousWords = new ArrayList<>();
    private final ManualLearningTracker manualLearning = new ManualLearningTracker();
    private final FinalPairResolver finalPairResolver = new FinalPairResolver();
    private final AutoPairConfirmation autoPairConfirmation = new AutoPairConfirmation();
    private final AutoPairRecovery autoPairRecovery = new AutoPairRecovery();
    private String autoRecoveryReason = "";
    private static final long FAILED_PAIR_SETTLE_MS = 500L;
    private final Runnable refreshRunnable = () -> {
        refreshScheduled = false;
        long queuedMicros = pendingSinceNanos == 0L
                ? 0L
                : Math.max(0L, (SystemClock.elapsedRealtimeNanos() - pendingSinceNanos) / 1000L);
        pendingSinceNanos = 0L;
        refreshFromActiveWindow(queuedMicros);
    };
    private final Runnable animationPollRunnable = new Runnable() {
        @Override
        public void run() {
            animationPollScheduled = false;
            if (!isAssistEnabled(DuolingoNodeService.this)
                    || SystemClock.uptimeMillis() > animationPollUntil) {
                return;
            }
            scheduleRefresh();
            animationPollScheduled = true;
            handler.postDelayed(this, ANIMATION_POLL_INTERVAL_MS);
        }
    };
    private final Runnable finishFreezeRunnable = this::finishFreezeAndResume;
    private final Runnable slowProgressWatchRunnable = this::watchSlowProgress;
    private final Runnable slowPausePollRunnable = this::checkSlowPause;
    private final Runnable verifyFreezeDismissRunnable = this::verifyFreezeDismissal;
    private final Runnable finishFullScreenPeekRunnable = this::finishFullScreenPeek;
    private final Runnable fullScreenPeekPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!fullScreenPeekActive || !isAssistEnabled(DuolingoNodeService.this)) {
                return;
            }
            scheduleRefresh();
            handler.postDelayed(this, FULL_SCREEN_PEEK_POLL_MS);
        }
    };
    private final Runnable requestFreezeRunnable = this::tryBeginFreezeAfterClicks;
    private final Runnable freezeRequestWakeRunnable = () -> {
        if (freezeRequestPending) {
            handler.postAtFrontOfQueue(requestFreezeRunnable);
        }
    };
    private final Runnable burstNextRunnable = this::runNextBurstPair;
    private final Runnable autoSecondClickRunnable = this::clickAutoSecondTarget;
    private final Runnable burstPipelineWakeRunnable = () -> {
        if (burstActive) {
            handler.postAtFrontOfQueue(burstNextRunnable);
        }
    };
    private final Runnable autoSecondClickWakeRunnable = () -> {
        if (autoClickBusy) {
            handler.postAtFrontOfQueue(autoSecondClickRunnable);
        }
    };
    private final Runnable burstWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!burstActive
                    || !isAssistEnabled(DuolingoNodeService.this)
                    || !isPairAutomationEnabled(DuolingoNodeService.this)) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (autoClickBusy && !autoSecondClickIssued
                    && lastBurstProgressAt > 0L
                    && now - lastBurstProgressAt >= FIRST_CLICK_STALL_MS) {
                failActiveAutoPair("点击未能及时完成");
                return;
            }
            if (!autoClickBusy && now >= burstNextEligibleAt) {
                handler.removeCallbacks(burstNextRunnable);
                handler.removeCallbacks(burstPipelineWakeRunnable);
                handler.postAtFrontOfQueue(burstNextRunnable);
            }
            handler.postDelayed(this, BURST_WATCHDOG_INTERVAL_MS);
        }
    };
    private final Runnable autoConfirmPollRunnable = new Runnable() {
        @Override
        public void run() {
            autoConfirmPollScheduled = false;
            if (!autoClickBusy
                    || !autoSecondClickIssued
                    || freezeActive
                    || !isAssistEnabled(DuolingoNodeService.this)
                    || !isPairAutomationEnabled(DuolingoNodeService.this)) {
                return;
            }
            scheduleRefresh();
            autoConfirmPollScheduled = true;
            handler.postDelayed(this, ConfirmationPollTiming.dispatchPollDelay(
                    FastCaptureService.isRunning(), lastFastFrameAt, SystemClock.uptimeMillis(),
                    burstActive && !burstQueue.isEmpty() && deferredPairs.size() < 5, autoSecondClickAt));
        }
    };
    private final Runnable userTakeoverEndRunnable = new Runnable() {
        @Override
        public void run() {
            long remaining = userTakeoverUntil - SystemClock.uptimeMillis();
            if (remaining > 0L) {
                handler.postDelayed(this, remaining);
                return;
            }
            userTakeoverUntil = 0L;
            scheduleRefresh();
        }
    };
    private final Runnable autoPairTimeoutRunnable = () -> {
        if (autoClickBusy && !freezeActive) {
            failActiveAutoPair("词卡未确认消除");
        }
    };

    private WindowManager windowManager;
    private MatchOverlayView overlay;
    private ModeControlView modeControl;
    private WindowManager.LayoutParams modeControlParams;
    private VocabularyStore vocabulary;
    private PairMatcher matcher;
    private int screenWidth;
    private int screenHeight;
    private boolean matchingExerciseVisible;
    private boolean matchingSessionConfirmed;

    public static boolean isServiceEnabled(Context context) {
        AccessibilityManager manager =
                (AccessibilityManager) context.getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        ComponentName expected = new ComponentName(context, DuolingoNodeService.class);
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        );
        for (AccessibilityServiceInfo info : services) {
            if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) {
                continue;
            }
            ComponentName actual = new ComponentName(
                    info.getResolveInfo().serviceInfo.packageName,
                    info.getResolveInfo().serviceInfo.name
            );
            if (expected.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAssistEnabled(Context context) {
        return context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    public static void setAssistEnabled(Context context, boolean enabled) {
        if (!enabled) context.stopService(new Intent(context, FastCaptureService.class));
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
        DuolingoNodeService service = connectedInstance;
        if (service != null) {
            service.handler.post(() -> service.applyEnabledState(enabled));
        }
    }

    public static boolean isFreezeModeEnabled(Context context) {
        return context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getBoolean(KEY_FREEZE_MODE, false);
    }

    public static void setFreezeModeEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FREEZE_MODE, enabled)
                .apply();
        DuolingoNodeService service = connectedInstance;
        if (service != null && !enabled) {
            service.handler.post(() -> {
                service.cancelFreezeAndDismiss();
                service.cancelFullScreenPeek();
                service.scheduleRefresh();
            });
        }
    }

    public static boolean isAutoModeEnabled(Context context) {
        return context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getBoolean(KEY_AUTO_MODE, false);
    }

    private static boolean isGreenConfirmationEnabled() {
        return Build.VERSION.SDK_INT >= 30;
    }

    public static void setAutoModeEnabled(Context context, boolean enabled) {
        if (!enabled) context.stopService(new Intent(context, FastCaptureService.class));
        context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_MODE, enabled)
                .apply();
        DuolingoNodeService service = connectedInstance;
        if (service != null) {
            service.handler.post(() -> {
                if (service.sentences != null) service.sentences.modeChanged();
                if (service.exercises != null) service.exercises.modeChanged();
                if (enabled) {
                    service.slowProgressPause.reset();
                    service.confirmationPace.reset();
                    if (service.slowPauseActive) service.finishFreezeAndResume();
                    service.autoPairRecovery.reset();
                    service.autoRetryBlockedUntil = 0L;
                    service.scheduleRefresh();
                } else {
                    service.deferredPairs.clear();
                    service.hasDeferredHistory = false;
                    service.cancelAiLookup();
                    service.cancelFullScreenPeek();
                    service.cancelBurstQueue();
                    service.cancelAutoClickSequence(true);
                    service.cancelFreezeAndDismiss();
                }
            });
        }
    }

    /** Reloads learned, imported, and manually added pairs without restarting the service. */
    public static void reloadVocabulary() {
        DuolingoNodeService service = connectedInstance;
        if (service == null) {
            return;
        }
        service.handler.post(() -> {
            service.vocabulary = new VocabularyStore(service);
            service.matcher = new PairMatcher(service.vocabulary);
            service.autoPairRecovery.reset();
            service.scheduleRefresh();
        });
    }

    private static boolean isPairAutomationEnabled(Context context) {
        return isAutoModeEnabled(context) && ExerciseSettings.enabled(context, ExerciseType.MATCH);
    }

    public static void onExerciseSettingsChanged() {
        DuolingoNodeService service = connectedInstance;
        if (service == null) return;
        service.handler.post(() -> {
            if (service.sentences != null) service.sentences.reset();
            if (service.exercises != null) service.exercises.reset();
            service.handleNonMatchingPage();
            service.cancelFreezeAndDismiss();
            service.scheduleRefresh();
        });
    }

    public static void onAiSettingsChanged() {
        DuolingoNodeService service = connectedInstance;
        if (service == null) {
            return;
        }
        service.handler.post(() -> {
            if (service.sentences != null) service.sentences.reset();
            if (service.exercises != null) service.exercises.reset();
            if (!AiMatchSettings.isEnabled(service)) {
                service.cancelAiLookup();
                if (service.freezeActive) {
                    service.finishFreezeAndResume();
                    return;
                }
            }
            service.aiLookupBlockedUntil = 0L;
            service.lastAiBoardSignature = "";
            service.scheduleRefresh();
        });
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connectedInstance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        refreshScreenDimensions();
        adaptivePauseLoadMs = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getLong(KEY_ADAPTIVE_PAUSE_MS, INITIAL_PAUSE_LOAD_MS);
        adaptivePauseLoadMs = Math.max(
                MIN_PAUSE_LOAD_MS,
                Math.min(MAX_PAUSE_LOAD_MS, adaptivePauseLoadMs)
        );
        vocabulary = new VocabularyStore(this);
        matcher = new PairMatcher(vocabulary);
        sentences = new SentenceController(this, handler, new SentenceController.Host() {
            public void status(String text, boolean warning) {
                if (overlay != null) overlay.showStatus(text, warning
                        ? MatchOverlayView.STATUS_WARNING : MatchOverlayView.STATUS_WORKING);
            }
            public void refresh() { scheduleRefresh(); }
        });
        exercises = new ExerciseController(this, handler, new SentenceController.Host() {
            public void status(String text, boolean warning) {
                if (overlay != null) overlay.showStatus(text, warning ? MatchOverlayView.STATUS_WARNING : MatchOverlayView.STATUS_WORKING);
            }
            public void refresh() { scheduleRefresh(); }
        });
        applyEnabledState(isAssistEnabled(this));
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (refreshScreenDimensions()) {
            scheduleRefresh();
        }
    }

    /** Node bounds are screen coordinates; never keep dimensions from an old rotation. */
    private boolean refreshScreenDimensions() {
        if (windowManager == null) {
            return false;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        //noinspection deprecation
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0
                || (screenWidth == metrics.widthPixels && screenHeight == metrics.heightPixels)) {
            return false;
        }
        boolean hadDimensions = screenWidth > 0 && screenHeight > 0;
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        if (hadDimensions) {
            if (sentences != null) sentences.reset();
            if (exercises != null) exercises.reset();
            sentenceVisible = false;
            cancelFullScreenPeek();
            cancelBurstQueue();
            cancelAutoClickSequence(false);
            finalPairResolver.reset();
            autoPairRecovery.reset();
            manualLearning.clear();
            previousWords.clear();
            pendingProgrammaticClicks.clear();
            recentlyIssuedPairs.clear();
            deferredPairs.clear();
            hasDeferredHistory = false;
        }
        return hadDimensions;
    }

    private AccessibilityNodeInfo getCurrentScreenRoot() {
        // Also cover display changes delivered before the configuration callback.
        // Abort this operation so no caller uses targets from the old geometry.
        if (refreshScreenDimensions()) {
            scheduleRefresh();
            return null;
        }
        return getRootInActiveWindow();
    }

    private AccessibilityWordExtractor.Extraction readPage(AccessibilityNodeInfo root, int width, int height) {
        long scanStarted = SystemClock.uptimeMillis();
        AccessibilityWordExtractor.Extraction page = AccessibilityWordExtractor.extractPage(root, width, height);
        traceDuration("scan", scanStarted);
        if (!isPairAutomationEnabled(this) || !isAssistEnabled(this) || !page.isMatchingExercise()
                || page.isPauseConfirmationVisible() || page.isNodeLimitReached()) {
            visualBoardSelection.reset();
            return page;
        }
        List<WordBox> cards = new ArrayList<>();
        for (WordBox word : page.getWords()) cards.add(page.getCardBounds(word, width));
        long now = SystemClock.uptimeMillis();
        visualBoardSelection.prepare(page.getWords(), cards, width, height, now);
        // Expose background samples for every card, including long blue word highlights.
        if (overlay != null && isGreenConfirmationEnabled()) overlay.setSamplingCards(cards);
        return page.withVisualSelection(visualBoardSelection.selectedWords(now));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (refreshScreenDimensions()) {
            scheduleRefresh();
            return;
        }
        if (!isAssistEnabled(this)) {
            cancelAutoClickSequence(false);
            stopAnimationPolling();
            hideOverlay();
            return;
        }
        if (freezeActive) {
            return;
        }
        if (event != null
                && event.getPackageName() != null
                && DUOLINGO_PACKAGE.contentEquals(event.getPackageName())) {
            int eventType = event.getEventType();
            if (sentenceVisible && sentences != null) sentences.observeClick(event);
            if (!sentenceVisible && exercises != null) exercises.observeClick(event);
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                if (SystemClock.uptimeMillis() >= pauseTransitionGuardUntil) {
                    matchingExerciseVisible = false;
                    cancelBurstQueue();
                    cancelAutoClickSequence(false);
                }
            }
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED
                    && !freezeActive
                    && isMatchingExerciseActiveWindow()) {
                if (recordPossibleTileClick(event)) {
                    beginManualTakeover();
                }
            }
            if (!sentenceVisible && !burstActive && !autoClickBusy && !freezeRequestPending) {
                startAnimationPolling();
            }
        }
        // A 300ms+ node scan can otherwise be queued ahead of every click while
        // Duolingo's tile animation emits a stream of content-change events.
        // Programmatic clicks are relocated against a fresh root themselves, so
        // scans during an active click pipeline are redundant and may starve it.
        if (burstActive || autoClickBusy || freezeRequestPending) {
            return;
        }
        // Always confirm against the active root. Accessibility overlays can emit
        // their own window events even while Duolingo remains the foreground app.
        scheduleRefresh();
    }

    @Override
    public void onInterrupt() {
        if (sentences != null) sentences.reset();
        if (exercises != null) exercises.reset();
        sentenceVisible = false;
        matchingExerciseVisible = false;
        cancelFullScreenPeek();
        cancelBurstQueue();
        cancelAutoClickSequence(false);
        cancelFreezeAndDismiss();
        stopAnimationPolling();
        hideOverlay();
    }

    @Override
    public void onDestroy() {
        if (connectedInstance == this) connectedInstance = null;
        if (sentences != null) sentences.close();
        if (exercises != null) exercises.close();
        matchingExerciseVisible = false;
        cancelFullScreenPeek();
        cancelBurstQueue();
        cancelAutoClickSequence(false);
        cancelFreezeAndDismiss();
        handler.removeCallbacksAndMessages(null);
        cancelAiLookup();
        aiExecutor.shutdownNow();
        hideOverlay();
        previousWords.clear();
        pendingProgrammaticClicks.clear();
        recentlyIssuedPairs.clear();
        deferredPairs.clear();
        hasDeferredHistory = false;
        if (connectedInstance == this) {
            connectedInstance = null;
        }
        super.onDestroy();
    }

    private void applyEnabledState(boolean enabled) {
        if (sentences != null) sentences.reset();
        if (exercises != null) exercises.reset();
        sentenceVisible = false;
        if (enabled) {
            scheduleRefresh();
        } else {
            matchingExerciseVisible = false;
            handler.removeCallbacks(refreshRunnable);
            cancelFullScreenPeek();
            cancelBurstQueue();
            cancelAutoClickSequence(false);
            cancelFreezeAndDismiss();
            stopAnimationPolling();
            refreshScheduled = false;
            pendingSinceNanos = 0L;
            previousWords.clear();
            pendingProgrammaticClicks.clear();
            recentlyIssuedPairs.clear();
            deferredPairs.clear();
            hasDeferredHistory = false;
            userTakeoverUntil = 0L;
            hideOverlay();
        }
    }

    private void scheduleRefresh() {
        if (refreshScheduled) {
            return;
        }
        refreshScheduled = true;
        pendingSinceNanos = SystemClock.elapsedRealtimeNanos();
        handler.postAtFrontOfQueue(refreshRunnable);
    }

    private void startAnimationPolling() {
        animationPollUntil = Math.max(
                animationPollUntil,
                SystemClock.uptimeMillis() + ANIMATION_POLL_DURATION_MS
        );
        if (animationPollScheduled) {
            return;
        }
        animationPollScheduled = true;
        handler.postDelayed(animationPollRunnable, ANIMATION_POLL_INTERVAL_MS);
    }

    private void stopAnimationPolling() {
        handler.removeCallbacks(animationPollRunnable);
        animationPollScheduled = false;
        animationPollUntil = 0L;
    }

    private boolean isAnimationPolling() {
        return animationPollScheduled
                || SystemClock.uptimeMillis() <= animationPollUntil;
    }

    private void refreshFromActiveWindow(long queuedMicros) {
        if (!isAssistEnabled(this)) {
            hideOverlay();
            return;
        }
        if (freezeActive) {
            return;
        }
        AccessibilityNodeInfo root = getCurrentScreenRoot();
        if (root == null) {
            finalPairResolver.reset();
            if (autoClickBusy) {
                autoPairConfirmation.observe(null, false, SystemClock.uptimeMillis());
            }
            return;
        }
        try {
            CharSequence packageName = root.getPackageName();
            if (packageName == null || !DUOLINGO_PACKAGE.contentEquals(packageName)) {
                if (sentences != null) sentences.reset();
                if (exercises != null) exercises.reset();
                sentenceVisible = false;
                matchingExerciseVisible = false;
                cancelFullScreenPeek();
                cancelBurstQueue();
                cancelAutoClickSequence(false);
                cancelFreezeAndDismiss();
                stopAnimationPolling();
                hideOverlay();
                recentlyIssuedPairs.clear();
                deferredPairs.clear();
                hasDeferredHistory = false;
                return;
            }
            ensureOverlay();
            if (overlay == null || modeControl == null || !isAssistEnabled(this)) return;
            List<AccessibilityNodeInfo> sentenceRoots = root.findAccessibilityNodeInfosByViewId("com.duolingo:id/challenge_translate");
            boolean hasSentenceRoot = sentenceRoots != null && !sentenceRoots.isEmpty();
            if (sentenceRoots != null) for (AccessibilityNodeInfo node : sentenceRoots) node.recycle();
            if (sentences != null && (hasSentenceRoot || sentenceVisible)) {
                SentencePage sentencePage = sentenceReader.read(root, screenWidth, screenHeight);
                if (sentencePage.recognized || (sentenceVisible && sentencePage.paused)) {
                    if (!sentenceVisible) {
                        handleNonMatchingPage();
                        matchingExerciseVisible = false;
                        sentenceVisible = true;
                    }
                    if (exercises != null) exercises.reset();
                    if (sentencePage.recognized) sentenceType = SentencePlan.direction(sentencePage.prompt).equals("zh-en")
                            ? ExerciseType.TRANSLATE_ZH_EN : ExerciseType.TRANSLATE_EN_ZH;
                    ExerciseType type = sentenceType;
                    boolean enabled = ExerciseSettings.enabled(this, type);
                    sentences.handle(root, sentencePage, isAutoModeEnabled(this) && enabled);
                    if (isAutoModeEnabled(this) && !enabled) overlay.showStatus(type.label + "｜自动答题已关闭", MatchOverlayView.STATUS_WORKING);
                    return;
                }
                if (sentenceVisible && sentences.awaitPauseTransition()) return;
                sentences.reset(); sentenceVisible = false;
            }
            long startedAt = SystemClock.elapsedRealtimeNanos();
            AccessibilityWordExtractor.Extraction extraction =
                    readPage(
                            root,
                            screenWidth,
                            screenHeight
                    );
            // Only non-pair pages incur a general snapshot; the fast pair loop is unchanged.
            if (!extraction.isMatchingExercise() || extraction.isPauseConfirmationVisible()) {
                SentencePage.Node snapshot = sentenceReader.snapshot(root);
                ExercisePage exercisePage = ExercisePage.parse(snapshot, screenWidth, screenHeight, sentenceReader.truncated());
                if (exercisePage.recognized) { matchingExerciseVisible = false; handleNonMatchingPage(); }
                if (exercises != null && exercises.handle(root, exercisePage, isAutoModeEnabled(this))) return;
            } else if (exercises != null) exercises.reset();
            // A modal can hide the lesson title from the active node tree.
            // Recognize it before treating an absent title as a different page.
            if (extraction.isPauseConfirmationVisible()) {
                handleVisiblePauseDialog(extraction.isMatchingExercise());
                return;
            }
            if (!extraction.isMatchingExercise()) {
                matchingExerciseVisible = false;
                handleNonMatchingPage();
                return;
            }
            if (isAutoModeEnabled(this) && !ExerciseSettings.enabled(this, ExerciseType.MATCH)) {
                handleNonMatchingPage();
                overlay.showStatus("单词配对｜自动答题已关闭", MatchOverlayView.STATUS_WORKING);
                return;
            }
            matchingExerciseVisible = true;
            if (!matchingSessionConfirmed) tracePerformance("board_entered");
            matchingSessionConfirmed = true;
            if (isPairAutomationEnabled(this)) confirmationPace.start(SystemClock.uptimeMillis());
            ensureSlowProgressWatch();
            List<WordBox> words = extraction.getWords();
            reconcileDeferredNodes(extraction);
            slowProgressPause.observeWork(hasPendingProgressWork(extraction), SystemClock.uptimeMillis());
            updatePairHighlights(extraction);
            maybeStartAiLookup(extraction, true);
            if (!extraction.isNodeLimitReached()) {
                autoPairRecovery.pruneMissing(words, screenWidth, screenHeight);
                visualPairRetry.pruneMissing(words);
            }
            if (recoverAutoPair(root, extraction)) {
                return;
            }
            if (tryHandleFinalPair(root, extraction)) {
                previousWords.clear();
                previousWords.addAll(words);
                return;
            }
            if (fullScreenPeekActive) {
                handleFullScreenPeek(extraction);
                return;
            }
            if (recoverUnexpectedSelection(root, extraction)) return;
            boolean completedBurstPair = updateAutoClickState(extraction);
            if (completedBurstPair) {
                previousWords.clear();
                previousWords.addAll(words);
                return;
            }
            if (autoClickBusy) {
                // Highlights only redraw when the board changes; keep monitoring feedback.
                maybeRetrySelectedPartner(root, extraction);
                if (!autoClickBusy) return;
                maybeConfirmGreenPair(root, extraction);
                if (autoClickBusy) maybePipelineAcceptedPair(root, extraction);
                return;
            }
            if (!burstActive && !freezeRequestPending && maybeRestoreDeferredPair(extraction)) return;
            boolean completedPair = learnCompletedAttempt(words);
            if (maybeBeginFreeze(words, completedPair)) {
                previousWords.clear();
                previousWords.addAll(words);
                return;
            }
            boolean trackingAnimation = isAnimationPolling();
            List<MatchPair> matches = words.size() < 2
                    ? Collections.emptyList()
                    : matcher.match(words, screenWidth);
            List<MatchPair> knownPairs = ExactPairSelector.allExact(
                    matchExecutableBoard(extraction));
            long computeMicros = Math.max(
                    1L,
                    (SystemClock.elapsedRealtimeNanos() - startedAt) / 1000L
            );
            long totalMicros = queuedMicros + computeMicros;
            long manualTakeoverRemaining = Math.max(
                    0L,
                    userTakeoverUntil - SystemClock.uptimeMillis()
            );
            long now = SystemClock.uptimeMillis();
            boolean manualLearningPending = manualLearning.hasWork(
                    now,
                    MANUAL_LEARNING_WINDOW_MS
            );
            if (now < learningNoticeUntil && !learningNotice.isEmpty()) {
                overlay.showStatus(
                        learningNotice,
                        learningNoticeWarning
                                ? MatchOverlayView.STATUS_WARNING
                                : MatchOverlayView.STATUS_OK
                );
            } else if (manualTakeoverRemaining > 0L || manualLearningPending) {
                overlay.showStatus(
                        manualLearningStatus(manualTakeoverRemaining),
                        MatchOverlayView.STATUS_WORKING
                );
            } else if (!matches.isEmpty() && !isPairAutomationEnabled(this)) {
                overlay.showStatus(
                        "学习模式｜已识别 " + matches.size()
                                + " 对｜等待你的手动操作",
                        MatchOverlayView.STATUS_OK
                );
            } else if (!knownPairs.isEmpty()) {
                overlay.showStatus(
                        paceCaption() + "｜待配 " + knownPairs.size()
                                + " 对｜词库 " + vocabulary.size() + " 对",
                        MatchOverlayView.STATUS_OK
                );
            } else if (!words.isEmpty()) {
                overlay.showStatus(
                        !isPairAutomationEnabled(this)
                                ? "学习模式｜已读取 " + words.size()
                                        + " 词｜等待你的手动配对"
                                : (trackingAnimation ? "动画追帧 " : "节点读取 ")
                                        + totalMicros + "μs｜" + words.size()
                                        + "词 / 0对"
                                        + (extraction.isNodeLimitReached()
                                                ? "｜节点扫描已达 "
                                                        + extraction.getVisitedNodeCount()
                                                : "")
                                        + "｜词库 " + vocabulary.size()
                                        + " 对｜" + summarizeWords(words),
                        isPairAutomationEnabled(this)
                                ? MatchOverlayView.STATUS_WARNING
                                : MatchOverlayView.STATUS_OK
                );
            } else if (trackingAnimation) {
                overlay.showStatus(
                        "动画追帧 " + totalMicros + "μs｜等待新词节点出现",
                        MatchOverlayView.STATUS_WORKING
                );
            } else {
                overlay.showStatus(
                        "多邻国当前页面未提供可读词卡节点",
                        MatchOverlayView.STATUS_ERROR
                );
            }
            if (isPairAutomationEnabled(this)
                    && !autoClickBusy
                    && !freezeActive
                    && !burstActive
                    && !freezeRequestPending
                    && manualTakeoverRemaining == 0L
                    && !manualLearningPending
                    && SystemClock.uptimeMillis() >= autoRetryBlockedUntil) {
                List<MatchPair> exactPairs = executableExactPairs(knownPairs, extraction);
                List<WordBox> selectedWords = selectedWordsWithoutConfirmedPairs(extraction.getSelectedWords());
                SelectedPairPlanner.Plan plan = SelectedPairPlanner.choose(
                        exactPairs, selectedWords, screenWidth, screenHeight);
                if (extraction.isNodeLimitReached()) {
                    overlay.showStatus("词卡扫描不完整｜等待完整画面", MatchOverlayView.STATUS_WARNING);
                } else if (plan != null) {
                    if (isFreezeModeEnabled(this) && exactPairs.size() > 1) {
                        startBurstBatch(exactPairs, words.size());
                    } else {
                    startAutoClickSequence(root, extraction, plan.getPair(), false);
                    }
                } else if (maybeStartAiLookup(extraction)) {
                    // Completed fading cards must not prevent unknown-word lookup.
                } else if (!knownPairs.isEmpty() || !ExactPairSelector.allExact(matches).isEmpty()) {
                    // Known cards that are selected/disabled are a UI-state problem,
                    // not a missing translation. Never invoke AI for this board.
                    autoRecoveryReason = "已有确定配对，但词卡状态尚未恢复";
                    autoPairRecovery.beginReview(SystemClock.uptimeMillis());
                    startAnimationPolling();
                    overlay.showStatus("已有确定配对｜正在恢复词卡状态", MatchOverlayView.STATUS_WORKING);
                } else {
                    maybeStartAiLookup(extraction);
                }
            }
            previousWords.clear();
            previousWords.addAll(words);
        } finally {
            //noinspection deprecation
            root.recycle();
        }
    }

    private void handleVisiblePauseDialog(boolean promptVisible) {
        aiFailureRecheck.reset();
        boolean matchingContext = promptVisible || matchingSessionConfirmed
                || hasMatchingPromptInApplicationWindows();
        matchingExerciseVisible = false;
        finalPairResolver.reset();
        cancelPendingFreezeRequest();
        cancelFullScreenPeek();
        cancelBurstQueue();
        cancelAutoClickSequence(false);
        stopAnimationPolling();
        if (overlay != null) {
            overlay.hideMatches();
        }
        if (!isPairAutomationEnabled(this) || !matchingContext) {
            if (overlay != null) {
                overlay.showStatus("检测到退出确认框｜点“返回”可继续配对", MatchOverlayView.STATUS_WORKING);
            }
            return;
        }
        matchingSessionConfirmed = true;
        freezeActive = true;
        freezeDismissPending = false;
        freezeExpectedWordCount = Math.max(2, previousWords.size());
        resetFreezeDismissVerification();
        clearManualLearningAttempt();
        finishFreezeAndResume();
    }

    private boolean hasMatchingPromptInApplicationWindows() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) {
            return false;
        }
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) {
                    continue;
                }
                try {
                    CharSequence packageName = root.getPackageName();
                    if (packageName != null && DUOLINGO_PACKAGE.contentEquals(packageName)
                            && MatchingPageGuard.hasMatchingPrompt(root, screenWidth, screenHeight)) {
                        return true;
                    }
                } finally {
                    //noinspection deprecation
                    root.recycle();
                }
            }
            return false;
        } finally {
            for (AccessibilityWindowInfo window : windows) {
                //noinspection deprecation
                window.recycle();
            }
        }
    }

    /** Handles the whole two-card board before vocabulary, AI, or pause loading. */
    private boolean tryHandleFinalPair(
            AccessibilityNodeInfo root,
            AccessibilityWordExtractor.Extraction extraction
    ) {
        long now = SystemClock.uptimeMillis();
        if (!isAssistEnabled(this) || !isPairAutomationEnabled(this) || freezeActive
                || autoClickBusy || now < userTakeoverUntil
                || manualLearning.hasWork(now, MANUAL_LEARNING_WINDOW_MS)) {
            finalPairResolver.reset();
            return false;
        }
        boolean completeBoard = extraction.isMatchingExercise()
                && !extraction.isNodeLimitReached()
                && !extraction.isPauseConfirmationVisible();
        List<WordBox> remaining = finalPairWords(extraction);
        if (completeBoard && remaining.size() == 2 && now >= autoRetryBlockedUntil) {
            List<MatchPair> known = executableExactPairs(matcher.match(remaining, screenWidth), extraction);
            SelectedPairPlanner.Plan ready = SelectedPairPlanner.choose(known,
                    selectedWordsWithoutConfirmedPairs(extraction.getSelectedWords()), screenWidth, screenHeight);
            if (ready != null && extraction.hasActiveControl(ready.getPair().getFirst())
                    && extraction.hasActiveControl(ready.getPair().getSecond())) {
                // An exact known pair does not need the 200ms inference window for an unknown final pair.
                cancelPendingFreezeRequest(); cancelFullScreenPeek(); cancelBurstQueue(); finalPairResolver.reset();
                startAutoClickSequence(root, extraction, ready.getPair(), false);
                return true;
            }
        }
        MatchPair finalPair = finalPairResolver.observe(remaining, screenWidth, completeBoard, now);
        if (!finalPairResolver.hasCandidate()) {
            return false;
        }
        // A previously scheduled pause or stale full-screen target must not win
        // over the last pair. Stay on the question while its nodes settle.
        cancelPendingFreezeRequest();
        cancelFullScreenPeek();
        cancelBurstQueue();
        if (finalPair == null || now < autoRetryBlockedUntil) {
            if (overlay != null) {
                overlay.showStatus("仅剩一对｜确认词卡稳定后直接消除", MatchOverlayView.STATUS_WORKING);
            }
            startAnimationPolling();
            return true;
        }
        List<MatchPair> available = executableExactPairs(
                Collections.singletonList(finalPair), extraction
        );
        if (available.isEmpty()) {
            autoRecoveryReason = "最后一对仍未确认成功";
            autoPairRecovery.beginReview(now);
            startAnimationPolling();
            return true;
        }
        finalPairResolver.reset();
        startAutoClickSequence(root, extraction, finalPair, false);
        return true;
    }

    private void cancelPendingFreezeRequest() {
        handler.removeCallbacks(requestFreezeRunnable);
        handler.removeCallbacks(freezeRequestWakeRunnable);
        freezeRequestPending = false;
        pendingFreezeRecovery = false;
        pendingFreezeExpectedWordCount = 0;
        freezeRequestAttempts = 0;
    }

    private void ensureOverlay() {
        if (windowManager == null || connectedInstance != this || !isAssistEnabled(this)) {
            return;
        }
        if (overlay == null) {
            overlay = new MatchOverlayView(this);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.alpha = 0.75f;
            try {
                windowManager.addView(overlay, params);
            } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException error) {
                stopForOverlayFailure();
                return;
            }
        }
        ensureModeControl();
    }

    private void handleNonMatchingPage() {
        if (matchingSessionConfirmed) tracePerformance("board_left");
        stopSlowProgressWatch();
        autoPairRecovery.reset();
        confirmationPace.reset();
        visualPairRetry.reset();
        matchingSessionConfirmed = false;
        finalPairResolver.reset();
        cancelAiLookup();
        cancelFullScreenPeek();
        cancelBurstQueue();
        cancelAutoClickSequence(false);
        handler.removeCallbacks(requestFreezeRunnable);
        handler.removeCallbacks(freezeRequestWakeRunnable);
        freezeRequestPending = false;
        pendingFreezeRecovery = false;
        stopAnimationPolling();
        clearManualLearningAttempt();
        previousWords.clear();
        pendingProgrammaticClicks.clear();
        recentlyIssuedPairs.clear();
        deferredPairs.clear();
        hasDeferredHistory = false;
        if (overlay != null) {
            overlay.clearMatches();
            overlay.showStatus(
                    getString(R.string.not_matching_page_status),
                    MatchOverlayView.STATUS_WORKING
            );
        }
    }

    private boolean isMatchingExerciseActiveWindow() {
        AccessibilityNodeInfo root = getCurrentScreenRoot();
        if (root == null) {
            return false;
        }
        try {
            CharSequence packageName = root.getPackageName();
            boolean matching = packageName != null
                    && DUOLINGO_PACKAGE.contentEquals(packageName)
                    && MatchingPageGuard.hasMatchingPrompt(root, screenWidth, screenHeight);
            matchingExerciseVisible = matching;
            return matching;
        } finally {
            //noinspection deprecation
            root.recycle();
        }
    }

    private void ensureModeControl() {
        if (modeControl != null || windowManager == null || connectedInstance != this || !isAssistEnabled(this)) {
            return;
        }
        modeControl = new ModeControlView(this, new ModeControlView.Listener() {
            @Override
            public void onLearningModeSelected() {
                switchModeFromOverlay(false);
            }

            @Override
            public void onAutomaticModeSelected() {
                switchModeFromOverlay(true);
            }

            @Override
            public void onTakeoverSelected() {
                takeOverFromOverlay();
            }

            @Override
            public void onCloseSelected() {
                closeAssistFromOverlay();
            }
        });
        modeControl.setAutomatic(isAutoModeEnabled(this));
        modeControlParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        modeControlParams.gravity = Gravity.TOP | Gravity.END;
        modeControlParams.x = dp(12);
        modeControlParams.y = dp(92);
        modeControl.getDragHandle().setOnTouchListener(new ModeControlDragListener());
        try {
            windowManager.addView(modeControl, modeControlParams);
        } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException error) {
            stopForOverlayFailure();
        }
    }

    private void stopForOverlayFailure() {
        // Android can revoke the accessibility window token before teardown reaches us.
        // Cancel the current run; never keep clicking without a visible control window.
        setAssistEnabled(this, false);
        applyEnabledState(false);
        android.util.Log.w("WordMatchPerf", "overlay_unavailable assist_stopped=true");
    }

    private void switchModeFromOverlay(boolean automatic) {
        if (automatic && !isAutoModeEnabled(this) && Build.VERSION.SDK_INT >= 30
                && !FastCaptureService.isRunning()) {
            // Both entry points use the same optional screen-sharing startup.
            automaticStartRequested = true;
            startActivity(new Intent(this, MainActivity.class)
                    .setAction(MainActivity.ACTION_START_AUTOMATIC)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            return;
        }
        applyModeFromOverlay(
                automatic,
                automatic
                        ? R.string.floating_switched_automatic
                        : R.string.floating_switched_learning
        );
    }

    private void takeOverFromOverlay() {
        applyModeFromOverlay(false, R.string.floating_takeover_started);
    }

    private void applyModeFromOverlay(boolean automatic, int messageRes) {
        setAutoModeEnabled(this, automatic);
        setFreezeModeEnabled(this, automatic);
        if (modeControl != null) {
            modeControl.setAutomatic(automatic);
        }
        if (overlay != null) {
            overlay.hideMatches();
            overlay.showStatus(
                    getString(messageRes),
                    automatic
                            ? MatchOverlayView.STATUS_OK
                            : MatchOverlayView.STATUS_WORKING
            );
        }
        Toast.makeText(
                this,
                messageRes,
                Toast.LENGTH_SHORT
        ).show();
        scheduleRefresh();
    }

    private void closeAssistFromOverlay() {
        Toast.makeText(this, R.string.floating_assist_closed, Toast.LENGTH_SHORT).show();
        setAssistEnabled(this, false);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Returns true when this was a user's tile click rather than one injected by this service. */
    private boolean recordPossibleTileClick(AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) {
            return false;
        }
        boolean likelyTile;
        Rect bounds = new Rect();
        String clickedText;
        try {
            source.getBoundsInScreen(bounds);
            clickedText = readClickedText(source, event);
            likelyTile = bounds.width() > 0
                    && bounds.height() > 0
                    && bounds.top >= screenHeight * 0.12f
                    && bounds.bottom <= screenHeight * 0.96f
                    && bounds.width() <= screenWidth * 0.50f
                    && bounds.height() <= screenHeight * 0.15f
                    && Math.abs(bounds.centerX() - screenWidth / 2) >= screenWidth * 0.03f;
        } finally {
            //noinspection deprecation
            source.recycle();
        }
        if (!likelyTile) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        boolean programmatic = consumeProgrammaticClick(bounds, clickedText, now);
        if (!programmatic
                && WordNormalizer.normalize(clickedText).isEmpty()
                && now <= programmaticClickGuardUntil) {
            consumeMostRecentProgrammaticClick(now);
            programmatic = true;
        }
        if (!programmatic) {
            recordManualLearningClick(bounds, clickedText, now);
        }
        return !programmatic;
    }

    private String readClickedText(AccessibilityNodeInfo source, AccessibilityEvent event) {
        String sourceText = readFirstNodeText(source, 0);
        if (!sourceText.isEmpty()) {
            return sourceText;
        }
        for (CharSequence eventText : event.getText()) {
            if (eventText != null && !WordNormalizer.normalize(eventText.toString()).isEmpty()) {
                return eventText.toString().trim();
            }
        }
        return "";
    }

    private String readFirstNodeText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 3) {
            return "";
        }
        CharSequence text = node.getText();
        if (text == null || WordNormalizer.normalize(text.toString()).isEmpty()) {
            text = node.getContentDescription();
        }
        if (text != null && !WordNormalizer.normalize(text.toString()).isEmpty()) {
            return text.toString().trim();
        }
        int childLimit = Math.min(8, node.getChildCount());
        for (int index = 0; index < childLimit; index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) {
                continue;
            }
            String childText;
            try {
                childText = readFirstNodeText(child, depth + 1);
            } finally {
                //noinspection deprecation
                child.recycle();
            }
            if (!childText.isEmpty()) {
                return childText;
            }
        }
        return "";
    }

    private void recordManualLearningClick(Rect clickedBounds, String clickedText, long now) {
        if (now - lastTileClickAt > MANUAL_LEARNING_WINDOW_MS) {
            clearManualLearningAttempt();
        }
        List<WordBox> liveWords = readCurrentWordsForLearning();
        List<WordBox> stableWords = liveWords.size() >= 2 ? liveWords : previousWords;
        WordBox clickedWord = findClickedWord(clickedBounds, clickedText, stableWords);
        if (clickedWord == null) {
            return;
        }
        manualLearning.recordClick(clickedWord, stableWords, screenWidth, now);
        lastTileClickAt = now;
    }

    private List<WordBox> readCurrentWordsForLearning() {
        AccessibilityNodeInfo root = getCurrentScreenRoot();
        if (root == null) {
            return Collections.emptyList();
        }
        try {
            CharSequence packageName = root.getPackageName();
            if (packageName == null || !DUOLINGO_PACKAGE.contentEquals(packageName)) {
                return Collections.emptyList();
            }
            return AccessibilityWordExtractor.extract(root, screenWidth, screenHeight);
        } finally {
            //noinspection deprecation
            root.recycle();
        }
    }

    private WordBox findClickedWord(
            Rect clickedBounds,
            String clickedText,
            List<WordBox> candidates
    ) {
        int maximumX = Math.max(48, Math.round(screenWidth * 0.12f));
        int maximumY = Math.max(48, Math.round(screenHeight * 0.08f));
        String clickedNormalized = WordNormalizer.normalize(clickedText);
        WordBox nearestTextMatch = null;
        long nearestTextDistance = Long.MAX_VALUE;
        WordBox nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (WordBox word : candidates) {
            long deltaX = (long) word.getCenterX() - clickedBounds.centerX();
            long deltaY = (long) word.getCenterY() - clickedBounds.centerY();
            if (Math.abs(deltaX) > maximumX || Math.abs(deltaY) > maximumY) {
                continue;
            }
            long distance = deltaX * deltaX + deltaY * deltaY;
            if (distance < nearestDistance) {
                nearest = word;
                nearestDistance = distance;
            }
            if (!clickedNormalized.isEmpty()
                    && clickedNormalized.equals(word.getNormalized())
                    && distance < nearestTextDistance) {
                nearestTextMatch = word;
                nearestTextDistance = distance;
            }
        }
        if (nearestTextMatch != null) {
            return nearestTextMatch;
        }
        if (!clickedNormalized.isEmpty()) {
            return new WordBox(
                    clickedText,
                    clickedBounds.left,
                    clickedBounds.top,
                    clickedBounds.right,
                    clickedBounds.bottom
            );
        }
        return nearest;
    }

    private boolean consumeProgrammaticClick(Rect bounds, String clickedText, long now) {
        int maximumX = Math.max(48, Math.round(screenWidth * 0.09f));
        int maximumY = Math.max(48, Math.round(screenHeight * 0.06f));
        String clickedNormalized = WordNormalizer.normalize(clickedText);
        for (int index = pendingProgrammaticClicks.size() - 1; index >= 0; index--) {
            PendingProgrammaticClick click = pendingProgrammaticClicks.get(index);
            if (now - click.createdAt > PROGRAMMATIC_CLICK_EVENT_WINDOW_MS) {
                pendingProgrammaticClicks.remove(index);
                continue;
            }
            boolean hasClickedText = !clickedNormalized.isEmpty();
            boolean textMatches = hasClickedText
                    && clickedNormalized.equals(click.normalized);
            boolean positionMatches = Math.abs(bounds.centerX() - click.centerX) <= maximumX
                    && Math.abs(bounds.centerY() - click.centerY) <= maximumY;
            if ((hasClickedText && textMatches && positionMatches)
                    || (!hasClickedText && positionMatches)) {
                pendingProgrammaticClicks.remove(index);
                return true;
            }
        }
        return false;
    }

    private boolean consumeMostRecentProgrammaticClick(long now) {
        for (int index = pendingProgrammaticClicks.size() - 1; index >= 0; index--) {
            PendingProgrammaticClick click = pendingProgrammaticClicks.get(index);
            if (now - click.createdAt <= PROGRAMMATIC_CLICK_EVENT_WINDOW_MS) {
                pendingProgrammaticClicks.remove(index);
                return true;
            }
            pendingProgrammaticClicks.remove(index);
        }
        return false;
    }

    private void beginManualTakeover() {
        tracePerformance("manual_takeover");
        deferredPairs.clear();
        hasDeferredHistory = false;
        slowProgressPause.reset();
        visualBoardSelection.reset();
        autoPairRecovery.reset();
        userTakeoverUntil = SystemClock.uptimeMillis() + USER_TAKEOVER_HOLD_MS;
        cancelFullScreenPeek();
        cancelBurstQueue();
        cancelAutoClickSequence(false);
        handler.removeCallbacks(requestFreezeRunnable);
        handler.removeCallbacks(freezeRequestWakeRunnable);
        freezeRequestPending = false;
        pendingFreezeRecovery = false;
        handler.removeCallbacks(userTakeoverEndRunnable);
        handler.postDelayed(userTakeoverEndRunnable, USER_TAKEOVER_HOLD_MS);
        if (overlay != null) {
            overlay.showStatus(
                    manualLearningStatus(USER_TAKEOVER_HOLD_MS),
                    MatchOverlayView.STATUS_WORKING
            );
        }
    }

    private String manualLearningStatus(long remainingMs) {
        WordBox activeLeft = manualLearning.getActiveLeft();
        WordBox activeRight = manualLearning.getActiveRight();
        if (activeLeft != null) {
            return "新一对已记左词｜" + shortText(activeLeft.getText())
                    + "｜再点右词";
        }
        if (activeRight != null) {
            return "新一对已记右词｜" + shortText(activeRight.getText())
                    + "｜再点左词";
        }
        ManualLearningTracker.Attempt pending = manualLearning.getNewestPendingAttempt();
        if (pending != null) {
            return "已锁定独立词对｜" + shortText(pending.getLeft().getText())
                    + " ↔ " + shortText(pending.getRight().getText())
                    + "｜等待同步消失";
        }
        return "检测到手动点击，但未读到词卡文字｜自动让路 " + remainingMs + "ms";
    }

    private String shortText(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > 14 ? trimmed.substring(0, 14) + "…" : trimmed;
    }

    private boolean learnCompletedAttempt(List<WordBox> currentWords) {
        long now = SystemClock.uptimeMillis();
        manualLearning.pruneExpired(now, MANUAL_LEARNING_WINDOW_MS);
        boolean learnedAny = false;
        for (ManualLearningTracker.Attempt attempt : manualLearning.getPendingAttempts()) {
            ManualLearningTracker.Observation observation = attempt.observe(
                    currentWords,
                    screenWidth,
                    screenHeight,
                    now
            );
            if (observation == ManualLearningTracker.Observation.WAITING) {
                continue;
            }
            manualLearning.remove(attempt);
            if (observation == ManualLearningTracker.Observation.REJECTED) {
                continue;
            }
            pendingFreezeExpectedWordCount = Math.max(
                    pendingFreezeExpectedWordCount,
                    attempt.getBaseline().size()
            );
            String leftText = attempt.getLeft().getText();
            String rightText = attempt.getRight().getText();
            List<String> visibleAtClick = new ArrayList<>();
            for (WordBox word : attempt.getBaseline()) {
                visibleAtClick.add(word.getText());
            }
            VocabularyStore.LearnResult result = vocabulary.confirmLearnedPair(
                    leftText,
                    rightText,
                    visibleAtClick
            );
            learnedAny = result.isSuccess() || learnedAny;
            if (result.isSuccess()) {
                learningNotice = result.getConflictsRemoved() > 0
                        ? "学习并纠错成功｜已移除 " + result.getConflictsRemoved() + " 条冲突｜"
                        : (result.wasAdded() ? "学习成功｜" : "已确认词库已有｜");
                learningNotice += shortText(leftText) + " ↔ " + shortText(rightText);
                learningNoticeWarning = false;
            } else {
                learningNotice = "学习写入失败｜" + shortText(leftText)
                        + " ↔ " + shortText(rightText);
                learningNoticeWarning = true;
            }
            learningNoticeUntil = now + LEARNING_NOTICE_MS;
            if (overlay != null) {
                overlay.showStatus(
                        learningNotice,
                        learningNoticeWarning
                                ? MatchOverlayView.STATUS_WARNING
                                : MatchOverlayView.STATUS_OK
                );
            }
        }
        if (!manualLearning.hasWork(now, MANUAL_LEARNING_WINDOW_MS)) {
            lastTileClickAt = 0L;
        }
        return learnedAny;
    }

    private void clearManualLearningAttempt() {
        manualLearning.clear();
        lastTileClickAt = 0L;
    }

    private void ensureSlowProgressWatch() {
        if (slowWatchScheduled || !matchingSessionConfirmed || !isAssistEnabled(this)
                || !isPairAutomationEnabled(this) || !isFreezeModeEnabled(this)) return;
        slowWatchScheduled = true;
        handler.postDelayed(slowProgressWatchRunnable, 64L);
    }

    private void stopSlowProgressWatch() {
        handler.removeCallbacks(slowProgressWatchRunnable);
        slowWatchScheduled = false;
        slowProgressPause.reset();
    }

    private void watchSlowProgress() {
        slowWatchScheduled = false;
        if (!matchingSessionConfirmed || !isAssistEnabled(this) || !isPairAutomationEnabled(this)
                || !isFreezeModeEnabled(this)) { stopSlowProgressWatch(); return; }
        long now = SystemClock.uptimeMillis();
        if (now < userTakeoverUntil || manualLearning.hasWork(now, MANUAL_LEARNING_WINDOW_MS)) {
            slowProgressPause.reset();
        } else if (!freezeActive && !freezeRequestPending && !fullScreenPeekActive && !visualGestureInFlight) {
            SlowProgressPause.Action action = slowProgressPause.action(now);
            if (action != SlowProgressPause.Action.NONE
                    && (!burstActive || now - lastBatchDispatchAt >= slowProgressPause.thresholdMs())) pauseForSlowProgress(action);
        }
        ensureSlowProgressWatch();
    }

    private boolean hasPendingProgressWork(AccessibilityWordExtractor.Extraction page) {
        if (autoClickBusy || !deferredPairs.isEmpty()) return true;
        List<WordBox> remaining = finalPairWords(page);
        return FinalPairResolver.hasOnlyPair(remaining, screenWidth)
                || !ExactPairSelector.allExact(matchExecutableBoard(page)).isEmpty();
    }

    private void traceDuration(String step, long started) {
        if (android.util.Log.isLoggable("WordMatchPerf", android.util.Log.DEBUG))
            android.util.Log.d("WordMatchPerf", "step=" + step + " ms=" + (SystemClock.uptimeMillis() - started));
    }

    private void tracePerformance(String event) {
        if (android.util.Log.isLoggable("WordMatchPerf", android.util.Log.DEBUG)) {
            android.util.Log.d("WordMatchPerf", "t=" + SystemClock.uptimeMillis() + " event=" + event
                    + " pending=" + deferredPairs.size() + " busy=" + autoClickBusy);
        }
    }

    private void pauseForSlowProgress(SlowProgressPause.Action action) {
        long issuedAt = autoSecondClickIssued ? autoSecondClickAt : autoFirstClickAt;
        if (autoClickBusy && SystemClock.uptimeMillis() - issuedAt < 160L) return;
        AccessibilityNodeInfo root = getCurrentScreenRoot();
        if (root == null) return;
        int wordCount;
        try {
            if (!DUOLINGO_PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return;
            AccessibilityWordExtractor.Extraction page = readPage(root, screenWidth, screenHeight);
            if (page.isPauseConfirmationVisible() || !page.isMatchingExercise() || page.isNodeLimitReached()) return;
            // A confirmed completion always wins over a watchdog deadline.
            if (autoClickBusy && autoSecondClickIssued && updateAutoClickState(page)) return;
            if (!autoClickBusy && deferredPairs.isEmpty() && page.getWords().size() >= 2
                    && wordsWithoutConfirmedPairs(page.getWords()).isEmpty()) {
                slowProgressPause.resumed(SystemClock.uptimeMillis());
                return;
            }
            if (!autoClickBusy && FinalPairResolver.hasOnlyPair(finalPairWords(page), screenWidth)) {
                tryHandleFinalPair(root, page);
                startAnimationPolling();
                if (autoClickBusy) return;
            }
            if (action == SlowProgressPause.Action.HOLD
                    || android.util.Log.isLoggable("WordMatchPerf", android.util.Log.DEBUG)) {
                int priority = action == SlowProgressPause.Action.HOLD ? android.util.Log.WARN : android.util.Log.DEBUG;
                int active = 0, inactive = 0;
                for (WordBox word : page.getWords()) {
                    if (page.hasActiveControl(word)) active++;
                    if (page.isInactive(word)) inactive++;
                }
                android.util.Log.println(priority, "WordMatchPerf", "stall=" + action + " board words=" + page.getWords().size()
                        + " remaining=" + wordsWithoutConfirmedPairs(page.getWords()).size()
                        + " active=" + active + " inactive=" + inactive
                        + " known=" + ExactPairSelector.allExact(matchExecutableBoard(page)).size()
                        + " ready=" + executableExactPairs(matchExecutableBoard(page), page).size()
                        + " selected=" + page.getSelectedWords().size() + " issued=" + recentlyIssuedPairs.size()
                        + " recovery=" + autoPairRecovery.isPending() + " busy=" + autoClickBusy);
                if (autoClickBusy && autoFirstTarget != null && autoSecondTarget != null) {
                    android.util.Log.println(priority, "WordMatchPerf", "target slots=" + autoFirstTarget.getCenterY() + "/" + autoSecondTarget.getCenterY()
                            + " inactive=" + page.isInactive(autoFirstTarget) + "/" + page.isInactive(autoSecondTarget)
                            + " present=" + sameVisualTarget(page, autoFirstTarget, autoFirstCardBounds, screenWidth)
                            + "/" + sameVisualTarget(page, autoSecondTarget, autoSecondCardBounds, screenWidth)
                            + " replaced=" + ReplacementPairConfirmation.replaced(page.getWords(), autoFirstTarget, autoFirstCardBounds, page.getCardBounds(autoFirstTarget, screenWidth))
                            + "/" + ReplacementPairConfirmation.replaced(page.getWords(), autoSecondTarget, autoSecondCardBounds, page.getCardBounds(autoSecondTarget, screenWidth))
                            + " pixels=" + lastPixelStates + " initiallyActive=" + autoPairStartedActive);
                }
            }
            if (!hasPendingProgressWork(page)) {
                slowProgressPause.observeWork(false, SystemClock.uptimeMillis());
                return;
            }
            if (action == SlowProgressPause.Action.RECHECK) {
                // Pausing alone cannot repair an ignored click. The short watchdog used
                // to reach HOLD before the 500ms recovery settlement / 6s pair timeout.
                // Preserve success requirements, but release the stale in-flight pair
                // into the existing fresh-board + pixel-verified retry path once.
                slowProgressPause.rechecking(SystemClock.uptimeMillis());
                tracePerformance("slow_recheck");
                if (autoClickBusy) {
                    failActiveAutoPair("暂停恢复后仍未确认消除");
                } else {
                    cancelBurstQueue();
                    cancelPendingFreezeRequest();
                    if (!autoPairRecovery.isPending()) autoPairRecovery.beginReview(SystemClock.uptimeMillis());
                    startAnimationPolling();
                    scheduleRefresh();
                }
                if (overlay != null) overlay.showStatus("正在重查词卡并尝试恢复点击", MatchOverlayView.STATUS_WORKING);
                return;
            }
            wordCount = page.getWords().size();
        } finally { root.recycle(); }
        long now = SystemClock.uptimeMillis();
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
            slowProgressPause.retryLater(now);
            return;
        }
        tracePerformance("slow_pause_" + action.name());
        slowProgressPause.requested();
        slowPauseActive = true;
        slowPauseHolding = action == SlowProgressPause.Action.HOLD;
        slowPauseStartedAt = now;
        slowPauseDialogSeenAt = 0L;
        slowPairWasPaused |= autoClickBusy;
        freezeActive = true;
        freezeDismissPending = false;
        freezeExpectedWordCount = Math.max(2, wordCount);
        resetFreezeDismissVerification();
        cancelBurstQueue();
        cancelFullScreenPeek();
        cancelPendingFreezeRequest();
        stopAnimationPolling();
        handler.removeCallbacks(autoSecondClickRunnable);
        handler.removeCallbacks(autoSecondClickWakeRunnable);
        handler.removeCallbacks(autoConfirmPollRunnable);
        handler.removeCallbacks(autoPairTimeoutRunnable);
        handler.removeCallbacks(finishFreezeRunnable);
        autoConfirmPollScheduled = false;
        // Keep target identities and success baselines; invalidate only old observations/callbacks.
        visualGeneration++;
        fastFrames.reset();
        fadedPairConfirmation.reset();
        partialPairRetry.clearObservation();
        visualBoardSelection.reset();
        autoPairConfirmation.observe(null, false, now);
        lastConfirmationSource = "";
        if (overlay != null) {
            overlay.hideMatches();
            overlay.showStatus("配对进展变慢｜正在暂停", MatchOverlayView.STATUS_WORKING);
        }
        handler.postDelayed(slowPausePollRunnable, 32L);
    }

    /** Verify the real pause dialog before claiming the countdown is protected. */
    private void checkSlowPause() {
        if (!slowPauseActive || !freezeActive || freezeDismissPending || !isAssistEnabled(this)) return;
        long now = SystemClock.uptimeMillis();
        boolean dialogVisible = false, completeLesson = false;
        AccessibilityNodeInfo root = getCurrentScreenRoot();
        if (root != null) {
            try {
                CharSequence packageName = root.getPackageName();
                if (packageName != null && !DUOLINGO_PACKAGE.contentEquals(packageName)) {
                    // Never dismiss or send BACK into another app.
                    freezeActive = false;
                    slowPauseActive = slowPauseHolding = false;
                    freezeDismissPending = false;
                    matchingSessionConfirmed = matchingExerciseVisible = false;
                    clearFreezeScanState();
                    stopSlowProgressWatch();
                    cancelAutoClickSequence(false);
                    hideOverlay();
                    return;
                }
                if (packageName != null) {
                    AccessibilityWordExtractor.Extraction page = readPage(root, screenWidth, screenHeight);
                    dialogVisible = page.isPauseConfirmationVisible() && !page.isNodeLimitReached();
                    completeLesson = page.isMatchingExercise() && !page.isPauseConfirmationVisible() && !page.isNodeLimitReached();
                    // The challenge can finish between the fresh scan and Android handling BACK.
                    // A complete, stable result/entry page ends this pause attempt; do not
                    // latch freezeActive forever or send another navigation action.
                    if (finishPauseOnNonMatchingPage(page, now)) return;
                }
            } finally { root.recycle(); }
        }
        if (dialogVisible) {
            if (slowPauseDialogSeenAt == 0L) {
                slowPauseDialogSeenAt = now;
                if (overlay != null) overlay.showStatus(slowPauseHolding
                        ? "恢复后仍无进展｜已暂停，等待接管" : "已暂停｜短暂加载后继续复查", MatchOverlayView.STATUS_WORKING);
                if (slowPauseHolding && modeControl != null) modeControl.showTakeoverPrompt(
                        "已暂停恢复两次，并重新检查词卡，仍未确认消除；保持暂停，请接管检查");
            }
            if (!slowPauseHolding && now - slowPauseDialogSeenAt >= RECOVERY_PAUSE_LOAD_MS) {
                finishFreezeAndResume();
                return;
            }
        } else if (completeLesson && (slowPauseDialogSeenAt > 0L || now - slowPauseStartedAt >= DIALOG_DISMISS_TIMEOUT_MS)) {
            // User resumed, or BACK did not open a dialog. Require a fresh unobscured board.
            if (slowPauseDialogSeenAt == 0L) slowProgressPause.openingFailed();
            freezeDismissPending = true;
            beginFreezeDismissVerification();
            return;
        } else if (now - slowPauseStartedAt >= DIALOG_DISMISS_TIMEOUT_MS && slowPauseDialogSeenAt == 0L) {
            if (overlay != null) overlay.showStatus("尚未确认暂停｜请检查当前页面", MatchOverlayView.STATUS_WARNING);
        }
        handler.postDelayed(slowPausePollRunnable, slowPauseHolding || now - slowPauseStartedAt >= DIALOG_DISMISS_TIMEOUT_MS ? 120L : 32L);
    }

    private boolean maybeBeginFreeze(List<WordBox> currentWords, boolean completedPair) {
        if (!isFreezeModeEnabled(this)
                || freezeActive
                || burstActive
                || !completedPair
                || manualLearning.hasWork(
                        SystemClock.uptimeMillis(),
                        MANUAL_LEARNING_WINDOW_MS
                )
                || currentWords == null
                || currentWords.size() < 2) {
            return false;
        }
        int expectedWordCount = pendingFreezeExpectedWordCount >= 4
                ? pendingFreezeExpectedWordCount
                : currentWords.size() + 2;
        return beginFreezeLoad(expectedWordCount, false);
    }

    private boolean beginFreezeLoad(int expectedWordCount, boolean recoveryPause) {
        return beginFreezeLoad(expectedWordCount, recoveryPause, null);
    }

    private boolean beginFreezeLoad(int expectedWordCount, boolean recoveryPause, String aiSignature) {
        AccessibilityNodeInfo root = getCurrentScreenRoot();
        if (root == null) {
            finalPairResolver.reset();
            return false;
        }
        try {
            CharSequence packageName = root.getPackageName();
            if (packageName == null || !DUOLINGO_PACKAGE.contentEquals(packageName)) {
                matchingExerciseVisible = false;
                handleNonMatchingPage();
                return false;
            }
            AccessibilityWordExtractor.Extraction extraction =
                    readPage(root, screenWidth, screenHeight);
            if (extraction.isPauseConfirmationVisible()) {
                handleVisiblePauseDialog(extraction.isMatchingExercise());
                return false;
            }
            matchingExerciseVisible = extraction.isMatchingExercise();
            if (!matchingExerciseVisible) {
                handleNonMatchingPage();
                return false;
            }
            matchingSessionConfirmed = true;
            if (tryHandleFinalPair(root, extraction)) {
                return false;
            }
            if (aiSignature == null && deferredPairs.isEmpty() && extraction.getWords().size() >= 2
                    && wordsWithoutConfirmedPairs(extraction.getWords()).isEmpty()) {
                cancelPendingFreezeRequest();
                startAnimationPolling();
                scheduleRefresh();
                return false;
            }
            if (aiSignature == null && recoveryPause && !autoClickBusy && !extraction.isNodeLimitReached()) {
                // A replacement can become ready between queue exhaustion and this delayed BACK.
                // Revalidate the current active cards before opening an unnecessary pause dialog.
                List<MatchPair> ready = executableExactPairs(matchExecutableBoard(extraction), extraction);
                ready.removeIf(pair -> !extraction.hasActiveControl(pair.getFirst())
                        || !extraction.hasActiveControl(pair.getSecond()));
                if (SelectedPairPlanner.choose(ready, selectedWordsWithoutConfirmedPairs(extraction.getSelectedWords()),
                        screenWidth, screenHeight) != null) {
                    cancelPendingFreezeRequest();
                    tracePerformance("pause_replaced_by_ready_batch");
                    startBurstBatch(ready, extraction.getWords().size());
                    return false;
                }
                if (android.util.Log.isLoggable("WordMatchPerf", android.util.Log.DEBUG)) {
                    android.util.Log.d("WordMatchPerf", "batch words=" + extraction.getWords().size()
                            + " remaining=" + wordsWithoutConfirmedPairs(extraction.getWords()).size()
                            + " active=" + extraction.withoutInactiveWords(extraction.getWords()).size()
                            + " ready=" + ready.size() + " selected=" + extraction.getSelectedWords().size());
                }
            }
            if (aiSignature != null) {
                AiLookupPlan current = planAiLookup(extraction);
                if (extraction.isNodeLimitReached() || current.hasLocalPairs()
                        || !current.request.needsModel()
                        || !aiSignature.equals(aiBoardSignature(current.request.left, current.request.right))) {
                    aiFailureRecheck.reset();
                    startAnimationPolling();
                    scheduleRefresh();
                    return false;
                }
            }
        } finally {
            //noinspection deprecation
            root.recycle();
        }
        finalPairResolver.reset();
        userTakeoverUntil = 0L;
        handler.removeCallbacks(userTakeoverEndRunnable);
        cancelAutoClickSequence(false);
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
            return false;
        }
        freezeRequestPending = false;
        pendingFreezeRecovery = false;
        freezeActive = true;
        slowProgressPause.observeNonMatchingPage(false, SystemClock.uptimeMillis());
        freezeDismissPending = false;
        resetFreezeDismissVerification();
        tracePerformance(aiSignature == null ? "batch_pause" : "ai_pause");
        freezeExpectedWordCount = Math.max(4, expectedWordCount);
        activeFreezeLoadMs = recoveryPause
                ? RECOVERY_PAUSE_LOAD_MS
                : adaptivePauseLoadMs;
        clearManualLearningAttempt();
        stopAnimationPolling();
        if (overlay != null) {
            String noticePrefix = SystemClock.uptimeMillis() < learningNoticeUntil
                    && !learningNotice.isEmpty()
                    ? learningNotice + "｜"
                    : "";
            overlay.showStatus(
                    noticePrefix + "纯暂停加载｜" + activeFreezeLoadMs
                            + "ms｜暂停期间不读取节点",
                    MatchOverlayView.STATUS_WORKING
            );
        }
        handler.removeCallbacks(finishFreezeRunnable);
        handler.postDelayed(finishFreezeRunnable, activeFreezeLoadMs);
        return true;
    }

    private void finishFreezeAndResume() {
        if (!freezeActive) {
            return;
        }
        handler.removeCallbacks(finishFreezeRunnable);
        if (overlay != null) {
            overlay.showStatus("正在定位确认框的“返回 / 继续”按钮", MatchOverlayView.STATUS_WORKING);
        }
        dispatchContinueButtonTap();
        beginFreezeDismissVerification();
    }

    private boolean dispatchContinueButtonTap() {
        freezeDismissAttempts++;
        freezeDismissPending = true;
        AccessibilityNodeInfo root = getCurrentScreenRoot();
        if (root == null) {
            return false;
        }
        WordBox resumeTarget;
        try {
            CharSequence packageName = root.getPackageName();
            if (packageName == null || !DUOLINGO_PACKAGE.contentEquals(packageName)) {
                return false;
            }
            PauseDialogState dialog = scanPauseConfirmation(root);
            resumeTarget = dialog.resumeTarget;
            if (!dialog.visible || resumeTarget == null) {
                // It may already be gone. Verify rather than sending BACK and
                // accidentally reopening the dialog on the restored lesson.
                return false;
            }
            if (performResumeNodeClick(root, resumeTarget)) {
                return true;
            }
        } finally {
            //noinspection deprecation
            root.recycle();
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        Path tapPath = new Path();
        tapPath.moveTo(resumeTarget.getCenterX(), resumeTarget.getCenterY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(tapPath, 0L, DIALOG_TAP_DURATION_MS))
                .build();
        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                finishContinueButtonTap();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                finishContinueButtonTap();
            }
        }, handler);
    }

    private boolean performResumeNodeClick(AccessibilityNodeInfo root, WordBox target) {
        NodeCandidate best = new NodeCandidate();
        findMatchingNode(root, target, best);
        AccessibilityNodeInfo current = best.node;
        for (int depth = 0; current != null && depth < MAX_CLICK_ANCESTORS; depth++) {
            Rect bounds = new Rect();
            current.getBoundsInScreen(bounds);
            boolean safeControl = PauseConfirmationDetector.isResumeControl(
                    target.getText(), bounds.left, bounds.top, bounds.right, bounds.bottom,
                    screenWidth, screenHeight
            ) && Math.abs(bounds.centerY() - target.getCenterY()) <= screenHeight * 0.03f;
            boolean clicked = safeControl && current.isClickable() && current.isEnabled()
                    && current.isVisibleToUser()
                    && current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            AccessibilityNodeInfo parent = clicked ? null : current.getParent();
            //noinspection deprecation
            current.recycle();
            if (clicked) {
                return true;
            }
            current = parent;
        }
        if (current != null) {
            //noinspection deprecation
            current.recycle();
        }
        return false;
    }

    private void finishContinueButtonTap() {
        if (freezeActive && freezeDismissPending) {
            beginFreezeDismissVerification();
        }
    }

    private void beginFreezeDismissVerification() {
        if (!freezeActive || !freezeDismissPending) {
            return;
        }
        freezeDismissVerificationStartedAt = SystemClock.uptimeMillis();
        freezeDialogAbsentSince = 0L;
        handler.removeCallbacks(verifyFreezeDismissRunnable);
        handler.postDelayed(verifyFreezeDismissRunnable, DIALOG_DISMISS_VERIFY_INTERVAL_MS);
        if (overlay != null) {
            overlay.showStatus(
                    "等待确认框完全消失｜稳定后再点击词卡",
                    MatchOverlayView.STATUS_WORKING
            );
        }
    }

    private boolean finishPauseOnNonMatchingPage(AccessibilityWordExtractor.Extraction page, long now) {
        boolean leftLesson = !page.isNodeLimitReached() && !page.isMatchingExercise()
                && !page.isPauseConfirmationVisible() && page.getWords().isEmpty()
                && page.getVisitedNodeCount() >= 5;
        if (!slowProgressPause.observeNonMatchingPage(leftLesson, now)) return false;
        freezeActive = false;
        slowPauseActive = slowPauseHolding = freezeDismissPending = false;
        handler.removeCallbacks(finishFreezeRunnable);
        handler.removeCallbacks(slowPausePollRunnable);
        resetFreezeDismissVerification();
        clearFreezeScanState();
        handleNonMatchingPage();
        return true;
    }

    private void verifyFreezeDismissal() {
        if (!freezeActive || !freezeDismissPending || !isAssistEnabled(this)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean dialogVisible = true;
        boolean matchingPromptVisible = false;
        int visibleWordCount = 0;
        AccessibilityNodeInfo root = getCurrentScreenRoot();
        if (root != null) {
            try {
                CharSequence packageName = root.getPackageName();
                if (packageName != null && DUOLINGO_PACKAGE.contentEquals(packageName)) {
                    AccessibilityWordExtractor.Extraction page =
                            readPage(root, screenWidth, screenHeight);
                    if (finishPauseOnNonMatchingPage(page, now)) return;
                    dialogVisible = page.isPauseConfirmationVisible() || page.isNodeLimitReached();
                    matchingPromptVisible = page.isMatchingExercise();
                    visibleWordCount = page.getWords().size();
                } else if (packageName != null) {
                    // Stop without injecting BACK into another foreground app.
                    freezeActive = false;
                    freezeDismissPending = false;
                    slowPauseActive = slowPauseHolding = false;
                    matchingSessionConfirmed = matchingExerciseVisible = false;
                    handler.removeCallbacks(slowPausePollRunnable);
                    stopSlowProgressWatch();
                    cancelAutoClickSequence(false);
                    cancelBurstQueue();
                    resetFreezeDismissVerification();
                    clearFreezeScanState();
                    hideOverlay();
                    return;
                }
            } finally {
                //noinspection deprecation
                root.recycle();
            }
        }
        if (dialogVisible) {
            freezeDialogAbsentSince = 0L;
        } else if (freezeDialogAbsentSince == 0L) {
            freezeDialogAbsentSince = now;
        }
        long absentForMs = freezeDialogAbsentSince == 0L
                ? 0L
                : now - freezeDialogAbsentSince;
        if (PauseDismissalPolicy.isReady(
                dialogVisible,
                visibleWordCount,
                matchingPromptVisible,
                absentForMs,
                DIALOG_DISMISS_STABLE_MS,
                DIALOG_DISMISS_EMPTY_STABLE_MS
        )) {
            freezeDismissPending = false;
            resetFreezeDismissVerification();
            completeFreezeResume();
            return;
        }
        long verificationAge = now - freezeDismissVerificationStartedAt;
        if (verificationAge >= DIALOG_DISMISS_TIMEOUT_MS
                && freezeDismissAttempts < MAX_DIALOG_DISMISS_ATTEMPTS) {
            freezeDismissVerificationStartedAt = now;
            freezeDialogAbsentSince = 0L;
            dispatchContinueButtonTap();
            beginFreezeDismissVerification();
            return;
        }
        if (verificationAge >= DIALOG_DISMISS_TIMEOUT_MS && overlay != null) {
            overlay.showStatus(
                    dialogVisible ? "确认框仍在｜等待用户关闭，不会穿透点击词卡"
                            : "等待配对题节点恢复｜暂不点击词卡",
                    MatchOverlayView.STATUS_WARNING
            );
            freezeDismissVerificationStartedAt = now;
        }
        handler.postDelayed(
                verifyFreezeDismissRunnable,
                verificationAge >= DIALOG_DISMISS_TIMEOUT_MS
                        ? 32L
                        : DIALOG_DISMISS_VERIFY_INTERVAL_MS
        );
    }

    private boolean isPauseConfirmationVisible(AccessibilityNodeInfo root) {
        return scanPauseConfirmation(root).visible;
    }

    private PauseDialogState scanPauseConfirmation(AccessibilityNodeInfo root) {
        PauseConfirmationDetector detector = new PauseConfirmationDetector();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        if (root != null) {
            queue.add(root);
        }
        WordBox resumeTarget = null;
        int visited = 0;
        boolean complete;
        try {
            while (!queue.isEmpty() && visited++ < MAX_CLICK_SEARCH_NODES) {
                AccessibilityNodeInfo node = queue.removeFirst();
                try {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    CharSequence[] labels = {node.getText(), node.getContentDescription()};
                    for (CharSequence label : labels) {
                        if (label == null) {
                            continue;
                        }
                        String text = label.toString();
                        detector.observe(text, bounds.left, bounds.top, bounds.right, bounds.bottom,
                                screenWidth, screenHeight);
                        if (PauseConfirmationDetector.isResumeControl(text,
                                bounds.left, bounds.top, bounds.right, bounds.bottom,
                                screenWidth, screenHeight)) {
                            resumeTarget = new WordBox(text, bounds.left, bounds.top, bounds.right, bounds.bottom);
                        }
                    }
                    for (int index = 0; index < node.getChildCount(); index++) {
                        AccessibilityNodeInfo child = node.getChild(index);
                        if (child != null) {
                            queue.addLast(child);
                        }
                    }
                } finally {
                    if (node != root) {
                        //noinspection deprecation
                        node.recycle();
                    }
                }
            }
            complete = root != null && queue.isEmpty();
        } finally {
            while (!queue.isEmpty()) {
                //noinspection deprecation
                queue.removeFirst().recycle();
            }
        }
        return new PauseDialogState(detector.isVisible(), complete, resumeTarget);
    }

    private static final class PauseDialogState {
        final boolean visible;
        final boolean completeScan;
        final WordBox resumeTarget;

        PauseDialogState(boolean visible, boolean completeScan, WordBox resumeTarget) {
            this.visible = visible;
            this.completeScan = completeScan;
            this.resumeTarget = resumeTarget;
        }
    }

    private void resetFreezeDismissVerification() {
        for (DeferredPairConfirmation entry : deferredPairs) entry.resetObservations(SystemClock.uptimeMillis());
        handler.removeCallbacks(verifyFreezeDismissRunnable);
        freezeDismissVerificationStartedAt = 0L;
        freezeDialogAbsentSince = 0L;
        freezeDismissAttempts = 0;
    }

    private void completeFreezeResume() {
        if (!freezeActive) {
            return;
        }
        int expectedWordCount = freezeExpectedWordCount;
        boolean resumeSlowPair = slowPauseActive;
        long now = SystemClock.uptimeMillis();
        long pausedFor = resumeSlowPair ? Math.max(0L, now - slowPauseStartedAt) : 0L;
        slowPauseActive = slowPauseHolding = false;
        slowPauseDialogSeenAt = 0L;
        handler.removeCallbacks(slowPausePollRunnable);
        slowProgressPause.resumed(now);
        freezeDismissPending = false;
        resetFreezeDismissVerification();
        freezeActive = false;
        tracePerformance("pause_resumed");
        clearFreezeScanState();
        if (resumeSlowPair && isPairAutomationEnabled(this)) {
            pauseTransitionGuardUntil = now + 200L;
            if (modeControl != null) modeControl.setAutomatic(true);
            autoPairRecovery.deferForPause(pausedFor);
            if (autoRetryBlockedUntil > slowPauseStartedAt) autoRetryBlockedUntil += pausedFor;
            if (autoClickBusy) {
                if (autoFirstClickAt > 0L) autoFirstClickAt += pausedFor;
                if (autoSecondClickAt > 0L) autoSecondClickAt += pausedFor;
                if (autoSecondClickIssued) startAutoConfirmPolling();
                else handler.postAtFrontOfQueue(autoSecondClickRunnable);
                long issuedAt = autoSecondClickIssued ? autoSecondClickAt : autoFirstClickAt;
                handler.postDelayed(autoPairTimeoutRunnable, Math.max(1L, AUTO_PAIR_TIMEOUT_MS - (now - issuedAt)));
            }
            startAnimationPolling();
            scheduleRefresh();
            ensureSlowProgressWatch();
            return;
        }
        if (isPairAutomationEnabled(this)) {
            beginFullScreenPeek(expectedWordCount);
        } else {
            startAnimationPolling();
            scheduleRefresh();
        }
    }

    private void beginFullScreenPeek(int expectedWordCount) {
        cancelFullScreenPeek();
        fullScreenPeekActive = true;
        fullScreenPeekStartedAt = SystemClock.uptimeMillis();
        fullScreenPeekExpectedWordCount = Math.max(4, expectedWordCount);
        fullScreenPeekWordCount = 0;
        fullScreenPeekLatestWordCount = 0;
        fullScreenPeekSawPartialBoard = false;
        fullScreenPeekPartialAdapted = false;
        handler.postDelayed(finishFullScreenPeekRunnable, FULL_SCREEN_PEEK_MAX_MS);
        handler.postDelayed(fullScreenPeekPollRunnable, DIALOG_DISMISS_SETTLE_MS);
    }

    private void handleFullScreenPeek(AccessibilityWordExtractor.Extraction page) {
        List<WordBox> words = page.getWords();
        long now = SystemClock.uptimeMillis();
        List<MatchPair> matches = words.size() < 2
                ? Collections.emptyList()
                : matcher.match(words, screenWidth);
        List<MatchPair> exactMatches = executableExactPairs(matchExecutableBoard(page), page);
        fullScreenPeekLatestWordCount = words.size();
        if (words.size() >= fullScreenPeekExpectedWordCount
                || exactMatches.size() >= fullScreenPeekMatches.size()) {
            fullScreenPeekMatches.clear();
            fullScreenPeekMatches.addAll(exactMatches);
            fullScreenPeekWordCount = words.size();
        }
        if (words.size() > 0
                && words.size() < fullScreenPeekExpectedWordCount
                && !fullScreenPeekSawPartialBoard) {
            fullScreenPeekSawPartialBoard = true;
        }
        long elapsed = now - fullScreenPeekStartedAt;
        if (overlay != null) {
            updatePairHighlights(page);
            overlay.showStatus(
                    "非暂停抢读｜" + words.size() + "/" + fullScreenPeekExpectedWordCount
                            + " 词｜" + elapsed + "ms｜命中 " + exactMatches.size() + " 对",
                    MatchOverlayView.STATUS_WORKING
            );
        }
        boolean fullBoardVisible = words.size() >= fullScreenPeekExpectedWordCount;
        if (fullBoardVisible) {
            if (!fullScreenPeekSawPartialBoard && elapsed <= 32L) {
                decreaseAdaptivePause(8L);
            }
            finishFullScreenPeek();
        } else if (!exactMatches.isEmpty() && elapsed >= PARTIAL_BATCH_DECISION_MS) {
            adaptPauseForPartialBoard(words.size(), fullScreenPeekExpectedWordCount);
            fullScreenPeekPartialAdapted = true;
            finishFullScreenPeek();
        }
    }

    private void finishFullScreenPeek() {
        if (!fullScreenPeekActive) {
            return;
        }
        handler.removeCallbacks(finishFullScreenPeekRunnable);
        handler.removeCallbacks(fullScreenPeekPollRunnable);
        if (fullScreenPeekSawPartialBoard
                && !fullScreenPeekPartialAdapted
                && fullScreenPeekLatestWordCount < fullScreenPeekExpectedWordCount) {
            adaptPauseForPartialBoard(
                    fullScreenPeekLatestWordCount,
                    fullScreenPeekExpectedWordCount
            );
        }
        List<MatchPair> readyBatch = new ArrayList<>(fullScreenPeekMatches);
        int readyWordCount = Math.max(fullScreenPeekWordCount, fullScreenPeekExpectedWordCount);
        clearFullScreenPeekState();
        if (isAssistEnabled(this)
                && isPairAutomationEnabled(this)
                && SystemClock.uptimeMillis() >= userTakeoverUntil
                && !readyBatch.isEmpty()) {
            startBurstBatch(readyBatch, readyWordCount);
        } else {
            startAnimationPolling();
            scheduleRefresh();
        }
    }

    private void cancelFullScreenPeek() {
        handler.removeCallbacks(finishFullScreenPeekRunnable);
        handler.removeCallbacks(fullScreenPeekPollRunnable);
        clearFullScreenPeekState();
    }

    private void clearFullScreenPeekState() {
        fullScreenPeekActive = false;
        fullScreenPeekStartedAt = 0L;
        fullScreenPeekExpectedWordCount = 0;
        fullScreenPeekWordCount = 0;
        fullScreenPeekLatestWordCount = 0;
        fullScreenPeekSawPartialBoard = false;
        fullScreenPeekPartialAdapted = false;
        fullScreenPeekMatches.clear();
    }

    private void adaptPauseForPartialBoard(int visibleWords, int expectedWords) {
        if (visibleWords <= 0 || expectedWords <= visibleWords) {
            return;
        }
        long proportionalEstimate = (adaptivePauseLoadMs * expectedWords + visibleWords - 1L)
                / visibleWords;
        long adjusted = Math.max(adaptivePauseLoadMs + 64L, proportionalEstimate);
        saveAdaptivePause(Math.min(MAX_PAUSE_LOAD_MS, adjusted));
    }

    private void decreaseAdaptivePause(long decreaseMs) {
        long adjusted = Math.max(MIN_PAUSE_LOAD_MS, adaptivePauseLoadMs - decreaseMs);
        saveAdaptivePause(adjusted);
    }

    private void saveAdaptivePause(long adjusted) {
        if (adjusted == adaptivePauseLoadMs) {
            return;
        }
        adaptivePauseLoadMs = adjusted;
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .putLong(KEY_ADAPTIVE_PAUSE_MS, adaptivePauseLoadMs)
                .apply();
    }

    private void cancelFreezeAndDismiss() {
        stopSlowProgressWatch();
        handler.removeCallbacks(slowPausePollRunnable);
        slowPauseActive = slowPauseHolding = false;
        slowPauseDialogSeenAt = 0L;
        handler.removeCallbacks(finishFreezeRunnable);
        resetFreezeDismissVerification();
        handler.removeCallbacks(requestFreezeRunnable);
        handler.removeCallbacks(freezeRequestWakeRunnable);
        cancelAiLookup();
        if (freezeActive) {
            dispatchContinueButtonTap();
        }
        resetFreezeDismissVerification();
        freezeActive = false;
        freezeDismissPending = false;
        freezeRequestPending = false;
        pendingFreezeRecovery = false;
        clearFreezeScanState();
        clearManualLearningAttempt();
    }

    private void clearFreezeScanState() {
        freezeExpectedWordCount = 0;
        activeFreezeLoadMs = 0L;
    }

    private void scheduleFreezeAfterClicks(int expectedWordCount) {
        scheduleFreezeAfterClicks(expectedWordCount, false);
    }

    private void scheduleFreezeAfterClicks(int expectedWordCount, boolean recoveryPause) {
        if (!isFreezeModeEnabled(this) || !matchingExerciseVisible) {
            return;
        }
        pendingFreezeExpectedWordCount = Math.max(4, expectedWordCount);
        freezeRequestAttempts = 0;
        freezeRequestPending = true;
        pendingFreezeRecovery = recoveryPause;
        stopAnimationPolling();
        handler.removeCallbacks(refreshRunnable);
        refreshScheduled = false;
        pendingSinceNanos = 0L;
        handler.removeCallbacks(requestFreezeRunnable);
        handler.removeCallbacks(freezeRequestWakeRunnable);
        handler.postDelayed(freezeRequestWakeRunnable, POST_CLICK_FREEZE_DELAY_MS);
    }

    private void tryBeginFreezeAfterClicks() {
        if (!isAssistEnabled(this)
                || !isPairAutomationEnabled(this)
                || !isFreezeModeEnabled(this)
                || freezeActive
                || burstActive) {
            freezeRequestPending = false;
            pendingFreezeRecovery = false;
            return;
        }
        freezeRequestAttempts++;
        if (beginFreezeLoad(pendingFreezeExpectedWordCount, pendingFreezeRecovery)) {
            return;
        }
        if (!freezeRequestPending) {
            return;
        }
        if (!matchingExerciseVisible) {
            freezeRequestPending = false;
            pendingFreezeRecovery = false;
            return;
        }
        if (freezeRequestAttempts < MAX_FREEZE_REQUEST_ATTEMPTS) {
            if (overlay != null) {
                overlay.showStatus(
                        "正在暂停多邻国｜返回指令重试 " + freezeRequestAttempts
                                + "/" + MAX_FREEZE_REQUEST_ATTEMPTS,
                        MatchOverlayView.STATUS_WORKING
                );
            }
            handler.removeCallbacks(freezeRequestWakeRunnable);
            handler.postDelayed(freezeRequestWakeRunnable, FREEZE_RETRY_DELAY_MS);
        } else {
            freezeRequestPending = false;
            pendingFreezeRecovery = false;
            autoRetryBlockedUntil = SystemClock.uptimeMillis() + AUTO_RETRY_DELAY_MS;
            if (overlay != null) {
                overlay.showStatus(
                        "系统拒绝暂停｜80ms 后重新扫描",
                        MatchOverlayView.STATUS_WARNING
                );
            }
            startAnimationPolling();
            handler.postDelayed(this::scheduleRefresh, AUTO_RETRY_DELAY_MS);
        }
    }

    private boolean startAutoClickSequence(
            AccessibilityNodeInfo root,
            AccessibilityWordExtractor.Extraction page,
            MatchPair pair,
            boolean burstPair
    ) {
        if (page.isPauseConfirmationVisible()) {
            handleVisiblePauseDialog(page.isMatchingExercise());
            return false;
        }
        if (!page.isMatchingExercise()) {
            matchingExerciseVisible = false;
            handleNonMatchingPage();
            return false;
        }
        if (page.isNodeLimitReached()
                || executableExactPairs(Collections.singletonList(pair), page).isEmpty()
                || !containsVisibleTarget(page.getWords(), pair.getFirst(), screenWidth, screenHeight)
                || !containsVisibleTarget(page.getWords(), pair.getSecond(), screenWidth, screenHeight)) {
            deferPairUntilFreshBoard();
            return false;
        }
        SelectedPairPlanner.Plan plan = SelectedPairPlanner.choose(
                Collections.singletonList(pair), selectedWordsWithoutConfirmedPairs(page.getSelectedWords()),
                screenWidth, screenHeight);
        if (plan == null) {
            deferPairUntilFreshBoard();
            return false;
        }
        pair = plan.getPair();
        stopAnimationPolling();
        autoClickBusy = true;
        visualGeneration++;
        updatePairHighlights(page);
        if (overlay != null) overlay.addMatch(pair);
        autoSecondClickIssued = false;
        autoBurstPair = burstPair;
        // Previously accepted cards can linger while their fade-out overlaps this
        // pair. They must not inflate the minimum board size used for confirmation.
        autoBoardWordCount = wordsWithoutConfirmedPairs(page.getWords()).size();
        autoFirstTarget = pair.getFirst();
        autoSecondTarget = pair.getSecond();
        autoFirstCardBounds = page.getCardBounds(autoFirstTarget, screenWidth);
        autoPairStartedActive = page.hasActiveControl(autoFirstTarget) && page.hasActiveControl(autoSecondTarget);
        autoSecondCardBounds = page.getCardBounds(autoSecondTarget, screenWidth);
        // A late counter increment can belong to an earlier dispatched pair.
        autoPairConfirmation.start(autoFirstTarget, autoSecondTarget, autoBoardWordCount,
                screenWidth, screenHeight, !hasDeferredHistory && deferredPairs.isEmpty() ? page.getStreakCount() : -1,
                !hasDeferredHistory && deferredPairs.isEmpty() ? page.getProgress() : null);
        handler.removeCallbacks(autoSecondClickRunnable);
        handler.removeCallbacks(autoSecondClickWakeRunnable);
        handler.removeCallbacks(autoPairTimeoutRunnable);
        if (!plan.isFirstAlreadySelected() && !performAutoCardClick(root, page, autoFirstTarget)) {
            failActiveAutoPair("第一张词卡未能点击");
            return false;
        }
        lastBurstProgressAt = SystemClock.uptimeMillis();
        autoFirstClickAt = lastBurstProgressAt;
        slowProgressPause.observeWork(true, autoFirstClickAt);
        tracePerformance("first_click");
        if (overlay != null) {
            overlay.showStatus(plan.isFirstAlreadySelected()
                            ? "已有一张选中｜只点击它的搭档"
                            : "已点第一张｜等待界面处理后点击搭档",
                    MatchOverlayView.STATUS_WORKING);
        }
        handler.postDelayed(autoSecondClickWakeRunnable, SecondClickTiming.POLL_MS);
        handler.postDelayed(autoPairTimeoutRunnable, AUTO_PAIR_TIMEOUT_MS);
        return true;
    }

    private void deferPairUntilFreshBoard() {
        cancelBurstQueue();
        // No click was sent, so a changed/fading board only needs a fresh frame.
        // Keep the longer settlement delay exclusively for actual failed clicks.
        autoRetryBlockedUntil = SystemClock.uptimeMillis() + ANIMATION_POLL_INTERVAL_MS;
        startAnimationPolling();
        handler.postDelayed(this::scheduleRefresh, ANIMATION_POLL_INTERVAL_MS);
    }

    private void clickAutoSecondTarget() {
        if (freezeActive) return;
        if (!autoClickBusy || autoSecondTarget == null || !isAssistEnabled(this)
                || !isPairAutomationEnabled(this) || freezeActive) {
            cancelAutoClickSequence(false);
            return;
        }
        if (visualGestureInFlight) {
            handler.removeCallbacks(autoSecondClickWakeRunnable);
            handler.postDelayed(autoSecondClickWakeRunnable, 16L);
            return;
        }
        AccessibilityNodeInfo root = getCurrentScreenRoot();
        if (root == null) {
            failActiveAutoPair("暂时无法读取第二张词卡");
            return;
        }
        try {
            CharSequence packageName = root.getPackageName();
            if (packageName == null || !DUOLINGO_PACKAGE.contentEquals(packageName)) {
                matchingExerciseVisible = false;
                handleNonMatchingPage();
                return;
            }
            AccessibilityWordExtractor.Extraction page =
                    readPage(root, screenWidth, screenHeight);
            if (page.isPauseConfirmationVisible()) {
                handleVisiblePauseDialog(page.isMatchingExercise());
                return;
            }
            if (!page.isMatchingExercise()) {
                matchingExerciseVisible = false;
                handleNonMatchingPage();
                return;
            }
            if (page.isNodeLimitReached()) {
                failActiveAutoPair("词卡扫描不完整");
                return;
            }
            if (ActivePairSelectionGuard.isUnexpected(page.getSelectedWords(), autoFirstTarget, autoSecondTarget)) {
                failActiveAutoPair("选中词已变化｜重新定位搭档");
                return;
            }
            if (page.getSelectedWords().isEmpty() && autoPairConfirmation.hasSuccessEvidence(
                    wordsWithoutConfirmedPairs(page.getWords()), page.getStreakCount(), page.getProgress())) {
                // The first click may have finished a pair whose other card was
                // already selected but did not expose the selected flag.
                autoSecondClickIssued = true;
                updateAutoClickState(page);
            } else {
                MatchPair active = new MatchPair(autoFirstTarget, autoSecondTarget, 1.0, 1);
                SelectedPairPlanner.Plan selection = SelectedPairPlanner.choose(
                        Collections.singletonList(active), selectedWordsWithoutConfirmedPairs(page.getSelectedWords()),
                        screenWidth, screenHeight);
                if (!SecondClickTiming.isReady(selection != null && selection.isFirstAlreadySelected(),
                        SystemClock.uptimeMillis() - autoFirstClickAt)) {
                    handler.removeCallbacks(autoSecondClickWakeRunnable);
                    handler.postDelayed(autoSecondClickWakeRunnable, SecondClickTiming.POLL_MS);
                    return;
                }
                if (selection == null) {
                    failActiveAutoPair("选中状态发生变化");
                    return;
                }
                WordBox target = selection.isFirstAlreadySelected()
                        ? selection.getPair().getSecond() : autoSecondTarget;
                if (!performAutoCardClick(root, page, target)) {
                    failActiveAutoPair("第二张词卡未能点击");
                    return;
                }
                autoSecondClickIssued = true;
                autoSecondClickAt = SystemClock.uptimeMillis();
                tracePerformance("second_click");
            }
        } finally {
            //noinspection deprecation
            root.recycle();
        }
        if (!autoClickBusy) {
            return;
        }
        handler.removeCallbacks(autoPairTimeoutRunnable);
        handler.postDelayed(autoPairTimeoutRunnable, AUTO_PAIR_TIMEOUT_MS);
        startAutoConfirmPolling();
        showAutoConfirmationStatus();
        scheduleRefresh();
    }

    private boolean containsBatchPair(MatchPair pair) {
        for (MatchPair queued : burstQueue) if (samePair(queued, pair)) return true;
        return false;
    }

    private boolean isDeferredWord(WordBox word) {
        for (DeferredPairConfirmation deferred : deferredPairs) if (deferred.contains(word)) return true;
        return false;
    }

    private List<WordBox> finalPairWords(AccessibilityWordExtractor.Extraction page) {
        // Inactive grey slots remain in the text tree. They are not remaining answers.
        // Pending confirmations keep their own lane and must settle before inference.
        if (!deferredPairs.isEmpty()) return Collections.emptyList();
        // Count every active card before applying submission history. Otherwise two
        // unrelated replacements can look like the final pair while their real partners
        // are still present, hidden only by stale completion records.
        return FinalPairResolver.unsubmittedFinalPair(page.withoutInactiveWords(page.getWords()),
                wordsWithoutConfirmedPairs(page.getWords()));
    }

    private List<MatchPair> matchExecutableBoard(AccessibilityWordExtractor.Extraction page) {
        List<WordBox> words = new ArrayList<>();
        for (WordBox word : wordsWithoutConfirmedPairs(page.getWords())) {
            if (!page.isInactive(word)) words.add(word);
        }
        return matcher.match(words, screenWidth);
    }

    private boolean targetsSelected(AccessibilityWordExtractor.Extraction page, MatchPair pair) {
        for (WordBox selected : page.getSelectedWords()) {
            if (sameVisibleTarget(selected, pair.getFirst(), 4, 4)
                    || sameVisibleTarget(selected, pair.getSecond(), 4, 4)) return true;
        }
        return false;
    }

    private boolean isFullySubmittedInactiveBoard(AccessibilityWordExtractor.Extraction page) {
        if (page.getWords().size() < 2 || page.isNodeLimitReached() || !page.isMatchingExercise()
                || page.isPauseConfirmationVisible() || !page.getSelectedWords().isEmpty()) return false;
        List<MatchPair> submitted = new ArrayList<>();
        for (IssuedPair pair : recentlyIssuedPairs) submitted.add(new MatchPair(pair.first, pair.second, 1.0, 1));
        for (DeferredPairConfirmation pair : deferredPairs) submitted.add(pair.pair);
        if (autoClickBusy && autoSecondClickIssued) submitted.add(new MatchPair(autoFirstTarget, autoSecondTarget, 1.0, 1));
        for (WordBox word : page.getWords()) {
            if (page.hasActiveControl(word) || page.getCardBounds(word, screenWidth) == null) return false;
        }
        return SubmittedBoardEvidence.covers(page.getWords(), submitted);
    }

    private boolean hasOtherActiveCard(AccessibilityWordExtractor.Extraction page, MatchPair pair) {
        for (WordBox word : page.getWords()) {
            if (!sameVisibleTarget(word, pair.getFirst(), 4, 4) && !sameVisibleTarget(word, pair.getSecond(), 4, 4)
                    && page.hasActiveControl(word)) return true;
        }
        return false;
    }

    private boolean validDeferredGeometry(AccessibilityWordExtractor.Extraction page, DeferredPairConfirmation entry) {
        return VisualConfirmationGeometry.matches(page.getWords(), entry.pair.getFirst(), entry.firstCard,
                page.getCardBounds(entry.pair.getFirst(), screenWidth))
                && VisualConfirmationGeometry.matches(page.getWords(), entry.pair.getSecond(), entry.secondCard,
                page.getCardBounds(entry.pair.getSecond(), screenWidth));
    }

    private void commitDeferredPair(DeferredPairConfirmation entry) {
        if (!deferredPairs.remove(entry)) return;
        tracePerformance("confirmed_deferred");
        confirmationPace.confirmed(SystemClock.uptimeMillis());
        lastConfirmationSource = "";
        rememberIssuedPair(entry.pair.getFirst(), entry.pair.getSecond());
        autoPairRecovery.recordSuccess(entry.pair, screenWidth, screenHeight);
        slowProgressPause.completed(SystemClock.uptimeMillis(), 0L);
    }

    private void reconcileDeferredNodes(AccessibilityWordExtractor.Extraction page) {
        if (deferredPairs.isEmpty()) return;
        boolean complete = page.isMatchingExercise() && !page.isPauseConfirmationVisible() && !page.isNodeLimitReached();
        for (DeferredPairConfirmation entry : new ArrayList<>(deferredPairs)) {
            if (complete && targetsSelected(page, entry.pair)) {
                deferredPairs.remove(entry);
                continue;
            }
            if (entry.observeNodes(page.getWords(), complete && !targetsSelected(page, entry.pair), SystemClock.uptimeMillis())) {
                commitDeferredPair(entry);
            }
        }
    }

    private void maybePipelineAcceptedPair(AccessibilityNodeInfo root, AccessibilityWordExtractor.Extraction page) {
        if (!burstActive || !autoBurstPair || !autoSecondClickIssued || visualGestureInFlight || freezeActive
                || burstQueue.isEmpty() || deferredPairs.size() >= 5 || autoFirstCardBounds == null || autoSecondCardBounds == null) return;
        boolean another = false;
        for (MatchPair candidate : executableExactPairs(matchExecutableBoard(page), page)) {
            if (containsBatchPair(candidate) && page.hasActiveControl(candidate.getFirst()) && page.hasActiveControl(candidate.getSecond())) {
                another = true; break;
            }
        }
        long now = SystemClock.uptimeMillis();
        boolean stable = dispatchAcceptance.observe(autoPairStartedActive,
                !page.hasActiveControl(autoFirstTarget) && !page.hasActiveControl(autoSecondTarget),
                page.isMatchingExercise() && !page.isPauseConfirmationVisible() && !page.isNodeLimitReached() && page.getSelectedWords().isEmpty(),
                canConfirmGreenTargets(page, autoFirstCardBounds, autoSecondCardBounds, screenWidth), another, autoSecondClickAt, now);
        if (!stable) return;
        hasDeferredHistory = true;
        tracePerformance("accepted_deferred");
        deferredPairs.add(new DeferredPairConfirmation(new MatchPair(autoFirstTarget, autoSecondTarget, 1.0, 1),
                autoFirstCardBounds, autoSecondCardBounds, screenWidth, screenHeight, autoSecondClickAt));
        // Release only the dispatch lane, not success/learning. The old targets stay excluded.
        cancelAutoClickSequence(false);
        burstDispatchedPairs++;
        lastBatchDispatchAt = now;
        burstNextEligibleAt = now;
        handler.postAtFrontOfQueue(burstNextRunnable);
        if (overlay != null) overlay.showStatus("继续本批词对｜" + deferredPairs.size() + " 对后台确认", MatchOverlayView.STATUS_WORKING);
    }

    private boolean maybeRestoreDeferredPair(AccessibilityWordExtractor.Extraction page) {
        if (deferredPairs.isEmpty() || autoClickBusy || freezeActive || page.isNodeLimitReached()
                || page.isPauseConfirmationVisible() || !page.getSelectedWords().isEmpty()
                || !executableExactPairs(matchExecutableBoard(page), page).isEmpty()) return false;
        DeferredPairConfirmation entry = deferredPairs.remove(0);
        cancelAutoClickSequence(false);
        autoFirstTarget = entry.pair.getFirst(); autoSecondTarget = entry.pair.getSecond();
        autoFirstCardBounds = entry.firstCard; autoSecondCardBounds = entry.secondCard;
        autoPairStartedActive = true; autoClickBusy = autoSecondClickIssued = true;
        autoFirstClickAt = autoSecondClickAt = SystemClock.uptimeMillis() - 160L;
        autoBoardWordCount = Math.max(2, wordsWithoutConfirmedPairs(page.getWords()).size());
        autoPairConfirmation.start(autoFirstTarget, autoSecondTarget, autoBoardWordCount, screenWidth, screenHeight, -1, null);
        // Restoration only observes the old pair. It never replays its two clicks.
        startAutoConfirmPolling();
        handler.postDelayed(autoPairTimeoutRunnable, AUTO_PAIR_TIMEOUT_MS);
        scheduleRefresh();
        return true;
    }

    private void confirmDeferredColors(AccessibilityWordExtractor.Extraction page, DeferredPairConfirmation entry,
                                       CardVisualState.State first, CardVisualState.State second, boolean screenshot, long now) {
        if (!deferredPairs.contains(entry)) return;
        boolean complete = page.isMatchingExercise() && !page.isNodeLimitReached() && !page.isPauseConfirmationVisible()
                && !targetsSelected(page, entry.pair);
        if (entry.observeReplacement(page.getWords(), page.getCardBounds(entry.pair.getFirst(), screenWidth),
                page.getCardBounds(entry.pair.getSecond(), screenWidth), page.isInactive(entry.pair.getFirst()),
                page.isInactive(entry.pair.getSecond()), first, second,
                complete && page.getSelectedWords().isEmpty(), now)) { commitDeferredPair(entry); return; }
        complete &= validDeferredGeometry(page, entry);
        if (entry.observeColors(first, second, page.hasActiveControl(entry.pair.getFirst()), page.hasActiveControl(entry.pair.getSecond()),
                hasOtherActiveCard(page, entry.pair) || isFullySubmittedInactiveBoard(page), complete, screenshot, now)) commitDeferredPair(entry);
    }

    private void confirmDeferredFrame(GreenCardDetector.Pixels pixels, int width, int height, long now) {
        if (deferredPairs.isEmpty() || width != screenWidth || height != screenHeight || now - lastDeferredFrameCheckAt < 32L) return;
        List<DeferredPairConfirmation> entries = new ArrayList<>(deferredPairs);
        List<CardVisualState.State[]> states = new ArrayList<>();
        boolean evidence = false;
        for (DeferredPairConfirmation entry : entries) {
            CardVisualState.State first = CardVisualState.read(pixels, width, height, entry.firstCard, entry.pair.getFirst());
            CardVisualState.State second = CardVisualState.read(pixels, width, height, entry.secondCard, entry.pair.getSecond());
            states.add(new CardVisualState.State[]{first, second});
            evidence |= first == CardVisualState.State.GREEN || first == CardVisualState.State.FADED
                    || second == CardVisualState.State.GREEN || second == CardVisualState.State.FADED;
        }
        if (!evidence) {
            for (DeferredPairConfirmation entry : entries) entry.resetObservations(now);
            return;
        }
        lastDeferredFrameCheckAt = now;
        AccessibilityNodeInfo root = getCurrentScreenRoot();
        if (root == null) return;
        try {
            if (!DUOLINGO_PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return;
            AccessibilityWordExtractor.Extraction page = readPage(root, width, height);
            for (int i = 0; i < entries.size(); i++) confirmDeferredColors(page, entries.get(i), states.get(i)[0], states.get(i)[1], false, now);
        } finally { root.recycle(); }
    }

    private void runNextBurstPair() {
        if (!burstActive
                || freezeActive
                || !isAssistEnabled(this)
                || !isPairAutomationEnabled(this)) {
            cancelBurstQueue();
            return;
        }
        if (SystemClock.uptimeMillis() < userTakeoverUntil) {
            cancelBurstQueue();
            handler.removeCallbacks(userTakeoverEndRunnable);
            handler.postDelayed(
                    userTakeoverEndRunnable,
                    Math.max(1L, userTakeoverUntil - SystemClock.uptimeMillis())
            );
            return;
        }
        if (autoClickBusy) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long waitMs = burstNextEligibleAt - now;
        if (waitMs > 0L) {
            handler.removeCallbacks(burstPipelineWakeRunnable);
            handler.postDelayed(burstPipelineWakeRunnable, waitMs);
            return;
        }
        if (burstQueue.isEmpty()) {
            burstActive = false;
            handler.removeCallbacks(burstPipelineWakeRunnable);
            handler.removeCallbacks(burstWatchdogRunnable);
            finishBurstBatch();
            return;
        }
        AccessibilityNodeInfo root = getCurrentScreenRoot();
        if (root == null) {
            burstNextEligibleAt = SystemClock.uptimeMillis() + BURST_ROOT_RETRY_MS;
            handler.removeCallbacks(burstPipelineWakeRunnable);
            handler.postDelayed(burstPipelineWakeRunnable, BURST_ROOT_RETRY_MS);
            return;
        }
        try {
            CharSequence packageName = root.getPackageName();
            if (packageName == null
                    || !DUOLINGO_PACKAGE.contentEquals(packageName)) {
                if (packageName != null && DUOLINGO_PACKAGE.contentEquals(packageName)
                        && isPauseConfirmationVisible(root)) {
                    handleVisiblePauseDialog(false);
                    return;
                }
                matchingExerciseVisible = false;
                cancelBurstQueue();
                handleNonMatchingPage();
                return;
            }
            AccessibilityWordExtractor.Extraction page =
                    readPage(root, screenWidth, screenHeight);
            if (page.isPauseConfirmationVisible()) {
                handleVisiblePauseDialog(page.isMatchingExercise());
                return;
            }
            if (tryHandleFinalPair(root, page)) {
                return;
            }
            maybeStartAiLookup(page, true);
            reconcileDeferredNodes(page);
            List<MatchPair> livePairs = executableExactPairs(matchExecutableBoard(page), page);
            // Finish the original snapshot. New replacement words belong to the next batch.
            livePairs.removeIf(candidate -> !containsBatchPair(candidate));
            SelectedPairPlanner.Plan plan = SelectedPairPlanner.choose(
                    livePairs, selectedWordsWithoutConfirmedPairs(page.getSelectedWords()), screenWidth, screenHeight);
            if (page.isNodeLimitReached()) { deferPairUntilFreshBoard(); return; }
            if (plan == null) {
                if (burstDispatchedPairs > 0 && page.getSelectedWords().isEmpty()) {
                    burstQueue.clear();
                    handler.postAtFrontOfQueue(burstNextRunnable);
                } else deferPairUntilFreshBoard();
                return;
            }
            MatchPair chosen = plan.getPair();
            burstQueue.removeIf(candidate -> samePair(candidate, chosen));
            if (!startAutoClickSequence(root, page, plan.getPair(), true)) {
                return;
            }
        } finally {
            //noinspection deprecation
            root.recycle();
        }
        if (overlay != null) {
            overlay.showStatus(
                    paceCaption() + "｜本批剩余 " + burstQueue.size() + " 对",
                    MatchOverlayView.STATUS_WORKING
            );
        }
    }

    /** Start a new snapshot immediately when replacement cards are already ready. */
    private void finishBurstBatch() {
        AccessibilityNodeInfo root = getCurrentScreenRoot();
        if (root == null) { startAnimationPolling(); scheduleRefresh(); return; }
        try {
            if (!DUOLINGO_PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                handleNonMatchingPage(); return;
            }
            AccessibilityWordExtractor.Extraction page = readPage(root, screenWidth, screenHeight);
            if (page.isPauseConfirmationVisible()) { handleVisiblePauseDialog(page.isMatchingExercise()); return; }
            if (!page.isMatchingExercise() || page.isNodeLimitReached()) {
                startAnimationPolling(); scheduleRefresh(); return;
            }
            if (tryHandleFinalPair(root, page)) return;
            reconcileDeferredNodes(page);
            List<MatchPair> ready = executableExactPairs(matchExecutableBoard(page), page);
            ready.removeIf(pair -> !page.hasActiveControl(pair.getFirst()) || !page.hasActiveControl(pair.getSecond()));
            boolean hasReadyPair = SelectedPairPlanner.choose(ready,
                    selectedWordsWithoutConfirmedPairs(page.getSelectedWords()), screenWidth, screenHeight) != null;
            boolean needsPause = isFreezeModeEnabled(this) && BurstPausePolicy.shouldPauseForReplacementWords(
                    burstBoardWordCount, burstDispatchedPairs, hasReadyPair);
            if (replacementBatchWindow.shouldWait(needsPause, SystemClock.uptimeMillis())) {
                // Keep the empty batch alive only for this bounded animation window.
                // Every wake re-reads modal, geometry, selection and active controls.
                burstActive = true;
                burstNextEligibleAt = SystemClock.uptimeMillis() + ANIMATION_POLL_INTERVAL_MS;
                handler.removeCallbacks(burstPipelineWakeRunnable);
                handler.postDelayed(burstPipelineWakeRunnable, ANIMATION_POLL_INTERVAL_MS);
            } else if (needsPause) {
                scheduleFreezeAfterClicks(burstBoardWordCount, true);
            } else if (hasReadyPair) {
                tracePerformance("next_ready_batch");
                startBurstBatch(ready, page.getWords().size());
            } else {
                startAnimationPolling(); scheduleRefresh();
            }
        } finally { root.recycle(); }
    }

    private void startBurstBatch(List<MatchPair> pairs, int boardWordCount) {
        replacementBatchWindow.reset();
        if (SystemClock.uptimeMillis() < userTakeoverUntil) {
            handler.removeCallbacks(userTakeoverEndRunnable);
            handler.postDelayed(
                    userTakeoverEndRunnable,
                    Math.max(1L, userTakeoverUntil - SystemClock.uptimeMillis())
            );
            return;
        }
        cancelAutoClickSequence(false);
        stopAnimationPolling();
        burstQueue.clear();
        burstQueue.addAll(pairs);
        burstBoardWordCount = boardWordCount;
        burstClickedPairs = 0;
        burstDispatchedPairs = 0;
        burstActive = !burstQueue.isEmpty();
        burstNextEligibleAt = SystemClock.uptimeMillis();
        lastBurstProgressAt = burstNextEligibleAt;
        handler.removeCallbacks(burstNextRunnable);
        handler.removeCallbacks(burstPipelineWakeRunnable);
        handler.removeCallbacks(burstWatchdogRunnable);
        if (burstActive) {
            handler.postAtFrontOfQueue(burstNextRunnable);
            handler.postDelayed(burstWatchdogRunnable, BURST_WATCHDOG_INTERVAL_MS);
        }
    }

    private void cancelBurstQueue() {
        replacementBatchWindow.reset();
        handler.removeCallbacks(burstNextRunnable);
        handler.removeCallbacks(burstPipelineWakeRunnable);
        handler.removeCallbacks(burstWatchdogRunnable);
        burstQueue.clear();
        burstActive = false;
        burstClickedPairs = 0;
        burstBoardWordCount = 0;
        burstDispatchedPairs = 0;
        burstNextEligibleAt = 0L;
        lastBurstProgressAt = 0L;
        autoBurstPair = false;
    }

    private void failActiveAutoPair(String reason) {
        failActiveAutoPair(reason, false);
    }

    private void failActiveAutoPair(String reason, boolean knownSelectionObserved) {
        long now = SystemClock.uptimeMillis();
        if (autoFirstTarget != null && autoSecondTarget != null) {
            autoPairRecovery.recordFailure(new MatchPair(autoFirstTarget, autoSecondTarget, 1.0, 1),
                    screenWidth, screenHeight, now, !autoSecondClickIssued);
            if (knownSelectionObserved) autoPairRecovery.reviewObservedSelectionNow(now);
        }
        tracePerformance("pair_failed");
        autoRecoveryReason = reason;
        cancelAutoClickSequence(false);
        cancelBurstQueue();
        cancelPendingFreezeRequest();
        long settleMs = knownSelectionObserved ? 0L : FAILED_PAIR_SETTLE_MS;
        autoRetryBlockedUntil = now + settleMs;
        if (overlay != null) {
            overlay.showStatus(reason + "｜重读棋盘，优先继续其他确定配对", MatchOverlayView.STATUS_WORKING);
        }
        startAnimationPolling();
        handler.postDelayed(this::scheduleRefresh, settleMs);
    }

    /** An unrelated blue card is evidence that waiting on the old pair cannot make progress. */
    private boolean recoverUnexpectedSelection(AccessibilityNodeInfo root, AccessibilityWordExtractor.Extraction page) {
        if (!autoClickBusy || !autoSecondClickIssued || visualGestureInFlight || freezeActive
                || !isAssistEnabled(this) || !isPairAutomationEnabled(this)
                || !page.isMatchingExercise() || page.isPauseConfirmationVisible() || page.isNodeLimitReached()) return false;
        List<WordBox> selected = page.getSelectedWords();
        if (!ActivePairSelectionGuard.isUnexpected(selected, autoFirstTarget, autoSecondTarget)) {
            activeSelectionGuard.reset();
            return false;
        }
        // A counter change on this frame might belong to the OTHER pair, not our old target.
        autoPairConfirmation.observe(null, false, SystemClock.uptimeMillis());
        partialPairRetry.clearObservation();
        if (!activeSelectionGuard.observe(selected, autoFirstTarget, autoSecondTarget,
                autoSecondClickAt, SystemClock.uptimeMillis())) return true;
        List<MatchPair> pairs = executableExactPairs(matcher.match(page.getWords(), screenWidth), page);
        boolean knownPartner = SelectedPairPlanner.choose(pairs, selected, screenWidth, screenHeight) != null;
        failActiveAutoPair("已选中 " + selected.get(0).getText() + "｜重新定位它的搭档", knownPartner);
        return true;
    }

    private boolean recoverAutoPair(
            AccessibilityNodeInfo root,
            AccessibilityWordExtractor.Extraction page
    ) {
        if (!autoPairRecovery.isPending()) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (!isPairAutomationEnabled(this) || now < userTakeoverUntil
                || manualLearning.hasWork(now, MANUAL_LEARNING_WINDOW_MS)) {
            autoPairRecovery.reset();
            return false;
        }
        boolean complete = page.isMatchingExercise() && !page.isPauseConfirmationVisible()
                && !page.isNodeLimitReached();
        if (complete && page.getSelectedWords().isEmpty() && maybeStartAiLookup(page)) {
            autoPairRecovery.reset();
            return true;
        }
        List<MatchPair> known = ExactPairSelector.allExact(
                matchExecutableBoard(page));
        MatchPair finalPair = finalPairResolver.observe(
                finalPairWords(page), screenWidth, complete, now);
        if (finalPair != null) {
            known = Collections.singletonList(finalPair);
        }
        List<MatchPair> executable = interactableExactPairs(known, page);
        List<WordBox> selected = selectedWordsWithoutConfirmedPairs(page.getSelectedWords());
        // Try a different click mechanism after a failed lone pair, before
        // spending another full timeout repeating the same ineffective node action.
        if (complete && page.getSelectedWords().isEmpty() && executable.size() == 1
                && autoPairRecovery.inVisualReviewWindow(now)
                && autoPairRecovery.hasFailed(executable.get(0), screenWidth, screenHeight)
                && visualPairRetry.canRetry(executable.get(0)) && Build.VERSION.SDK_INT >= 30
                && !visualCaptureUnavailable && isGreenConfirmationEnabled()) {
            maybeRecoverVisiblePair(root, page);
            startAnimationPolling();
            return true;
        }
        AutoPairRecovery.Action action = autoPairRecovery.evaluate(executable, selected,
                complete && !(finalPairResolver.hasCandidate() && finalPair == null),
                !known.isEmpty() || !ExactPairSelector.allExact(matcher.match(page.getWords(), screenWidth)).isEmpty(),
                screenWidth, screenHeight, now);
        if (action == AutoPairRecovery.Action.RESUME) {
            autoRetryBlockedUntil = 0L;
            return false;
        }
        if (action == AutoPairRecovery.Action.WAIT && complete && selected.isEmpty()
                && autoPairRecovery.isSettled(now)) {
            maybeRecoverVisiblePair(root, page);
        }
        if (action == AutoPairRecovery.Action.TAKEOVER) {
            tracePerformance("recovery_takeover");
            setAutoModeEnabled(this, false);
            setFreezeModeEnabled(this, false);
            if (modeControl != null) {
                modeControl.setAutomatic(false);
                modeControl.showTakeoverPrompt(autoRecoveryReason
                        + (selected.isEmpty() ? "；已检查剩余配对，仍无法继续" : "；选中状态未恢复，无法安全继续其他配对"));
            }
            if (overlay != null) {
                overlay.showStatus("自动恢复未完成｜请检查提示中的词卡状态", MatchOverlayView.STATUS_WARNING);
            }
            return true;
        }
        if (action == AutoPairRecovery.Action.CLEAR_SELECTION) {
            // Clicking the selected card may deselect it. Never assume that the
            // action succeeded: the next complete scan must choose the next pair.
            performNodeClick(root, selected.get(0));
        }
        if (overlay != null) {
            updatePairHighlights(page);
            overlay.showStatus(action == AutoPairRecovery.Action.CLEAR_SELECTION
                    ? "正在取消残留选中｜确认恢复后继续已知配对"
                    : "正在重读词卡状态｜有可执行配对后自动继续", MatchOverlayView.STATUS_WORKING);
        }
        startAnimationPolling();
        handler.postDelayed(this::scheduleRefresh, AUTO_RETRY_DELAY_MS);
        return true;
    }

    private boolean samePair(MatchPair first, MatchPair second) {
        int x = Math.max(32, Math.round(screenWidth * 0.04f));
        int y = Math.max(32, Math.round(screenHeight * 0.04f));
        return (sameVisibleTarget(first.getFirst(), second.getFirst(), x, y)
                && sameVisibleTarget(first.getSecond(), second.getSecond(), x, y))
                || (sameVisibleTarget(first.getFirst(), second.getSecond(), x, y)
                && sameVisibleTarget(first.getSecond(), second.getFirst(), x, y));
    }

    private boolean updateAutoClickState(AccessibilityWordExtractor.Extraction page) {
        if (!page.getSelectedWords().isEmpty()) {
            // A visible selection contradicts completion; a global counter may belong to another pair.
            autoPairConfirmation.observe(null, false, SystemClock.uptimeMillis());
            return false;
        }
        if (!autoClickBusy || !autoSecondClickIssued || !autoPairConfirmation.observe(
                wordsWithoutConfirmedPairs(page.getWords()), page.isMatchingExercise() && !page.isPauseConfirmationVisible()
                        && !page.isNodeLimitReached(), page.getStreakCount(), page.getProgress(),
                SystemClock.uptimeMillis())) {
            return false;
        }
        completeAutoPair("nodes");
        return true;
    }

    private void completeAutoPair(String source) {
        tracePerformance("confirmed_" + source);
        long now = SystemClock.uptimeMillis();
        confirmationPace.confirmed(now);
        slowProgressPause.completed(now, slowPairWasPaused ? 0L : Math.max(0L, now - autoFirstClickAt));
        boolean completedBurstPair = autoBurstPair;
        rememberIssuedPair(autoFirstTarget, autoSecondTarget);
        autoPairRecovery.recordSuccess(new MatchPair(autoFirstTarget, autoSecondTarget, 1.0, 1),
                screenWidth, screenHeight);
        cancelAutoClickSequence(false);
        if (completedBurstPair) {
            burstDispatchedPairs++;
            burstClickedPairs++;
            if (overlay != null) {
                overlay.showStatus(paceCaption() + "｜词对已确认", MatchOverlayView.STATUS_OK);
            }
            burstNextEligibleAt = SystemClock.uptimeMillis() + CONFIRMED_PAIR_GAP_MS;
            handler.removeCallbacks(burstPipelineWakeRunnable);
            handler.postDelayed(burstPipelineWakeRunnable, CONFIRMED_PAIR_GAP_MS);
        } else {
            startAnimationPolling();
            scheduleRefresh();
        }
    }

    private boolean isCurrentVisualPair(int generation, int width, int height) {
        return generation == visualGeneration && autoClickBusy && autoSecondClickIssued
                && !freezeActive && isAssistEnabled(this) && isPairAutomationEnabled(this)
                && isGreenConfirmationEnabled() && screenWidth == width && screenHeight == height;
    }

    /** Called synchronously on the main thread; the image is released immediately afterwards. */
    public static void onFastColorFrame(GreenCardDetector.Pixels pixels, int width, int height) {
        DuolingoNodeService service = connectedInstance;
        if (service != null) service.confirmFastFrame(pixels, width, height);
    }

    public static void onFastCaptureStopped() {
        DuolingoNodeService service = connectedInstance;
        if (service != null) {
            service.lastFastFrameAt = 0L;
            service.fastFrames.reset();
            service.showAutoConfirmationStatus();
        }
    }

    private String paceCaption() {
        double rate = confirmationPace.perSecond(SystemClock.uptimeMillis());
        return Double.isNaN(rate) ? "配对测速中" : "近3秒 " + String.format(java.util.Locale.ROOT, "%.1f", rate) + " 对/秒";
    }

    private void showAutoConfirmationStatus() {
        if (overlay == null || freezeActive || !autoClickBusy || !autoSecondClickIssued
                || autoFirstTarget == null || autoSecondTarget == null) return;
        long now = SystemClock.uptimeMillis();
        String source;
        if (ConfirmationPollTiming.hasLiveFrames(FastCaptureService.isRunning(), lastFastFrameAt, now)) {
            source = "实时画面";
        } else {
            String reason = FastCaptureService.isRunning() && lastMismatchedFrameAt > lastFastFrameAt
                    && now - lastMismatchedFrameAt < 1000L ? "画面尺寸不符" : FastCaptureService.getFallbackReason();
            source = reason + "·备用确认";
        }
        if (source.equals(lastConfirmationSource)) return;
        lastConfirmationSource = source;
        overlay.showStatus(paceCaption() + "｜确认 " + autoFirstTarget.getText() + " ↔ " + autoSecondTarget.getText()
                + "｜" + source, MatchOverlayView.STATUS_WORKING);
    }

    private void confirmFastFrame(GreenCardDetector.Pixels pixels, int width, int height) {
        long now = SystemClock.uptimeMillis();
        if (width == screenWidth && height == screenHeight) lastFastFrameAt = now;
        else lastMismatchedFrameAt = now;
        showAutoConfirmationStatus();
        if (freezeActive || !isAssistEnabled(this) || !isPairAutomationEnabled(this)) {
            visualBoardSelection.reset();
            return;
        }
        confirmDeferredFrame(pixels, width, height, now);
        visualBoardSelection.observe(pixels, width, height, now);
        if (autoClickBusy && autoSecondClickIssued && !visualGestureInFlight
                && ActivePairSelectionGuard.isUnexpected(visualBoardSelection.selectedWords(now),
                autoFirstTarget, autoSecondTarget)) {
            AccessibilityNodeInfo live = getCurrentScreenRoot();
            if (live != null) {
                try {
                    if (DUOLINGO_PACKAGE.contentEquals(live.getPackageName() == null ? "" : live.getPackageName())
                            && recoverUnexpectedSelection(live, readPage(live, width, height))) return;
                } finally { live.recycle(); }
            }
        }
        if (!isCurrentVisualPair(visualGeneration, width, height) || autoSecondClickAt == 0L
                || now - autoSecondClickAt < 32L || autoFirstCardBounds == null || autoSecondCardBounds == null) {
            // Consume the generation's stale first frame while the first click is
            // being processed. It cannot contribute green evidence until both
            // clicks have been issued and their 32ms settlement window has elapsed.
            if (autoClickBusy && width == screenWidth && height == screenHeight
                    && autoFirstCardBounds != null && autoSecondCardBounds != null)
                fastFrames.observe(visualGeneration, false, now);
            else fastFrames.reset();
            return;
        }
        boolean green = GreenCardDetector.isGreenCard(pixels, width, height, autoFirstCardBounds)
                && GreenCardDetector.isGreenCard(pixels, width, height, autoSecondCardBounds);
        boolean confirmed = fastFrames.observe(visualGeneration, green, now);
        CardVisualState.State firstState = CardVisualState.State.UNKNOWN;
        CardVisualState.State secondState = CardVisualState.State.UNKNOWN;
        PartialPairRetry.Action retry = PartialPairRetry.Action.NONE;
        if (!green && !visualGestureInFlight && now - autoSecondClickAt >= 160L) {
            firstState = CardVisualState.read(pixels, width, height, autoFirstCardBounds, autoFirstTarget);
            secondState = CardVisualState.read(pixels, width, height, autoSecondCardBounds, autoSecondTarget);
            lastPixelStates = firstState + "/" + secondState;
            retry = partialPairRetry.observe(firstState, secondState, autoSecondClickAt, now);
        } else {
            partialPairRetry.clearObservation();
        }
        boolean faded = firstState == CardVisualState.State.FADED && secondState == CardVisualState.State.FADED;
        if (!faded) fadedPairConfirmation.reset();
        boolean mayHaveReplacement = firstState == CardVisualState.State.FADED || secondState == CardVisualState.State.FADED
                || firstState == CardVisualState.State.GREEN || secondState == CardVisualState.State.GREEN;
        if (!confirmed && !faded && !mayHaveReplacement && retry == PartialPairRetry.Action.NONE) {
            replacementPairConfirmation.reset(); return;
        }
        AccessibilityNodeInfo root = getCurrentScreenRoot();
        if (root == null) return;
        try {
            if (!DUOLINGO_PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return;
            AccessibilityWordExtractor.Extraction page = readPage(root, width, height);
            if (confirmReplacedPair(page, firstState, secondState, now)) return;
            if (width == screenWidth && height == screenHeight && page.isMatchingExercise()
                    && !page.isPauseConfirmationVisible() && !page.isNodeLimitReached()
                    && (confirmed || faded ? canConfirmGreenTargets(page, autoFirstCardBounds, autoSecondCardBounds, width)
                    : sameVisualTarget(page, autoFirstTarget, autoFirstCardBounds, width)
                    && sameVisualTarget(page, autoSecondTarget, autoSecondCardBounds, width))) {
                if (confirmed) completeAutoPair("live_green");
                else if (faded) confirmFadedPair(page, firstState, secondState, now);
                else retryPartialPair(root, page, retry);
            }
        } finally {
            root.recycle();
        }
    }

    private boolean confirmReplacedPair(AccessibilityWordExtractor.Extraction page,
                                        CardVisualState.State first, CardVisualState.State second, long now) {
        if (!autoClickBusy || !autoSecondClickIssued) return false;
        boolean complete = page.isMatchingExercise() && !page.isPauseConfirmationVisible()
                && !page.isNodeLimitReached() && page.getSelectedWords().isEmpty();
        if (!replacementPairConfirmation.observe(page.getWords(), autoFirstTarget, autoFirstCardBounds,
                page.getCardBounds(autoFirstTarget, screenWidth), autoSecondTarget, autoSecondCardBounds,
                page.getCardBounds(autoSecondTarget, screenWidth), page.isInactive(autoFirstTarget),
                page.isInactive(autoSecondTarget), first, second, autoPairStartedActive, complete, autoSecondClickAt, now)) return false;
        completeAutoPair("replacement");
        return true;
    }

    private void confirmFadedPair(AccessibilityWordExtractor.Extraction page,
                                  CardVisualState.State first, CardVisualState.State second, long now) {
        boolean otherActive = false;
        for (WordBox word : page.getWords()) {
            if (!sameVisibleTarget(word, autoFirstTarget, 4, 4)
                    && !sameVisibleTarget(word, autoSecondTarget, 4, 4) && page.hasActiveControl(word)) {
                otherActive = true;
                break;
            }
        }
        boolean completeBoard = page.isMatchingExercise() && !page.isPauseConfirmationVisible()
                && !page.isNodeLimitReached() && page.getSelectedWords().isEmpty()
                && canConfirmGreenTargets(page, autoFirstCardBounds, autoSecondCardBounds, screenWidth);
        if (fadedPairConfirmation.observe(first, second, autoPairStartedActive,
                page.hasActiveControl(autoFirstTarget), page.hasActiveControl(autoSecondTarget),
                otherActive || isFullySubmittedInactiveBoard(page), completeBoard, autoSecondClickAt, now)) {
            completeAutoPair("faded");
            if (overlay != null) overlay.showStatus("两张词卡已灰化并停用｜继续下一对", MatchOverlayView.STATUS_OK);
        }
    }

    /** Nodes can expose the missing click even when continuous capture is unavailable. */
    private void maybeRetrySelectedPartner(AccessibilityNodeInfo root, AccessibilityWordExtractor.Extraction page) {
        if (!autoSecondClickIssued || autoSecondClickAt == 0L || visualGestureInFlight
                || page.getSelectedWords().size() != 1 || page.isNodeLimitReached()
                || page.isInactive(autoFirstTarget) || page.isInactive(autoSecondTarget)) return;
        WordBox selected = page.getSelectedWords().get(0);
        boolean first = sameVisibleTarget(selected, autoFirstTarget, 4, 4);
        boolean second = sameVisibleTarget(selected, autoSecondTarget, 4, 4);
        if (!first && !second) { partialPairRetry.clearObservation(); return; }
        PartialPairRetry.Action retry = partialPairRetry.observe(
                first ? CardVisualState.State.SELECTED : CardVisualState.State.READY,
                second ? CardVisualState.State.SELECTED : CardVisualState.State.READY,
                autoSecondClickAt, SystemClock.uptimeMillis());
        if (retry != PartialPairRetry.Action.NONE) retryPartialPair(root, page, retry);
    }

    private void retryPartialPair(AccessibilityNodeInfo root, AccessibilityWordExtractor.Extraction page,
                                  PartialPairRetry.Action retry) {
        if (!autoClickBusy || !autoSecondClickIssued || visualGestureInFlight || freezeActive
                || !isAssistEnabled(this) || !isPairAutomationEnabled(this)
                || !page.isMatchingExercise() || page.isNodeLimitReached() || page.isPauseConfirmationVisible()
                || autoFirstCardBounds == null || autoSecondCardBounds == null
                || !sameVisualTarget(page, autoFirstTarget, autoFirstCardBounds, screenWidth)
                || !sameVisualTarget(page, autoSecondTarget, autoSecondCardBounds, screenWidth)) return;
        if (recoverUnexpectedSelection(root, page)) return;
        if (page.getSelectedWords().isEmpty() && autoPairConfirmation.hasSuccessEvidence(wordsWithoutConfirmedPairs(page.getWords()),
                page.getStreakCount(), page.getProgress())) return;
        WordBox target = retry == PartialPairRetry.Action.FIRST ? autoFirstTarget : autoSecondTarget;
        WordBox selectedPartner = retry == PartialPairRetry.Action.FIRST ? autoSecondTarget : autoFirstTarget;
        // Reject a newly selected unrelated card, or evidence that the proposed target is selected.
        if (page.getSelectedWords().size() > 1) return;
        for (WordBox selected : page.getSelectedWords()) {
            if (retry == PartialPairRetry.Action.RECHECK
                    || !sameVisibleTarget(selected, selectedPartner, 4, 4)) return;
        }
        if (retry == PartialPairRetry.Action.RECHECK) {
            failActiveAutoPair("点击后两张词卡仍未响应");
            return;
        }
        if (retry == PartialPairRetry.Action.NONE) return;
        boolean sent = Build.VERSION.SDK_INT >= 24
                ? performVerifiedCardTap(root, page, target, 40L) : performNodeClick(root, target);
        if (sent) {
            partialPairRetry.recordRetry(SystemClock.uptimeMillis());
            if (overlay != null) overlay.showStatus("只选中一张｜补点 " + target.getText(), MatchOverlayView.STATUS_WORKING);
        } else {
            failActiveAutoPair("未能发出搭档补点");
        }
    }

    private boolean isCurrentVisualRecovery(int generation, int width, int height) {
        long now = SystemClock.uptimeMillis();
        return generation == visualGeneration && !autoClickBusy && autoPairRecovery.isPending()
                && !freezeActive && !freezeRequestPending && !aiLookupHoldingPause
                && isAssistEnabled(this) && isPairAutomationEnabled(this) && isGreenConfirmationEnabled()
                && now >= userTakeoverUntil && !manualLearning.hasWork(now, MANUAL_LEARNING_WINDOW_MS)
                && screenWidth == width && screenHeight == height;
    }

    /** Reconcile a stalled node state with positive evidence that a known pair is still ready. */
    private void maybeRecoverVisiblePair(AccessibilityNodeInfo root, AccessibilityWordExtractor.Extraction page) {
        long now = SystemClock.uptimeMillis();
        if (Build.VERSION.SDK_INT < 30 || visualCaptureUnavailable || visualCaptureInFlight
                || !page.getSelectedWords().isEmpty()
                || !isCurrentVisualRecovery(visualGeneration, screenWidth, screenHeight)) return;
        List<MatchPair> candidates = new ArrayList<>();
        List<WordBox> cards = new ArrayList<>();
        // Include suppressed pairs: a stale completion record is one possible reason for the stall.
        List<MatchPair> known = new ArrayList<>(ExactPairSelector.allExact(
                matcher.match(page.withoutInactiveWords(page.getWords()), screenWidth)));
        // Prefer live copies of repeated words; grey copies must not consume their
        // partners in the one-to-one matcher. Keep the pixel-verified fallback
        // for genuinely stale accessibility controls.
        known.addAll(ExactPairSelector.allExact(matcher.match(page.getWords(), screenWidth)));
        for (MatchPair pair : known) {
            if (!visualPairRetry.canRetry(pair)) continue;
            WordBox first = page.getCardBounds(pair.getFirst(), screenWidth);
            WordBox second = page.getCardBounds(pair.getSecond(), screenWidth);
            if (first == null || second == null) continue;
            candidates.add(pair);
            cards.add(first);
            cards.add(second);
        }
        if (candidates.isEmpty()) return;
        if (!cards.equals(recoverySampleCards)) {
            recoverySampleCards = cards;
            recoverySamplesPreparedAt = now;
            if (overlay != null) overlay.setSamplingCards(cards);
            return;
        }
        // Allow the overlay to expose the background samples before a display capture.
        if (now - recoverySamplesPreparedAt < 48L || !screenshotCadence.canRequest(now)) return;
        final int generation = visualGeneration, width = screenWidth, height = screenHeight;
        final int windowId = root.getWindowId();
        final Rect captureBounds = new Rect(0, 0, width, height);
        if (Build.VERSION.SDK_INT >= 34) {
            AccessibilityWindowInfo window = root.getWindow();
            if (window == null) return;
            window.getBoundsInScreen(captureBounds);
            //noinspection deprecation
            window.recycle();
        }
        visualCaptureInFlight = true;
        screenshotCadence.requested(now);
        TakeScreenshotCallback callback = new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                Bitmap hardware = null;
                int ready = -1;
                try {
                    if (!isCurrentVisualRecovery(generation, width, height)
                            || SystemClock.uptimeMillis() - now > 1000L) return;
                    hardware = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                    if (hardware != null && hardware.getWidth() == captureBounds.width()
                            && hardware.getHeight() == captureBounds.height()) {
                        for (int i = 0; i < candidates.size(); i++) {
                            MatchPair pair = candidates.get(i);
                            if (GreenCardScreenshot.readState(hardware, cards.get(i * 2), pair.getFirst(),
                                    captureBounds.left, captureBounds.top) == CardVisualState.State.READY
                                    && GreenCardScreenshot.readState(hardware, cards.get(i * 2 + 1), pair.getSecond(),
                                    captureBounds.left, captureBounds.top) == CardVisualState.State.READY) {
                                ready = i;
                                break;
                            }
                        }
                    }
                } catch (RuntimeException ignored) {
                    // A failed or obscured capture never overrides node restrictions.
                } finally {
                    if (hardware != null) hardware.recycle();
                    result.getHardwareBuffer().close();
                    visualCaptureInFlight = false;
                }
                if (ready < 0 || !isCurrentVisualRecovery(generation, width, height)) return;
                AccessibilityNodeInfo live = getCurrentScreenRoot();
                if (live == null) return;
                try {
                    if (live.getWindowId() != windowId || !DUOLINGO_PACKAGE.contentEquals(
                            live.getPackageName() == null ? "" : live.getPackageName())) return;
                    AccessibilityWordExtractor.Extraction fresh = readPage(live, width, height);
                    MatchPair pair = candidates.get(ready);
                    if (!fresh.isMatchingExercise() || fresh.isNodeLimitReached() || fresh.isPauseConfirmationVisible()
                            || !fresh.getSelectedWords().isEmpty()
                            || !sameRecoveryCard(fresh, pair.getFirst(), cards.get(ready * 2), width)
                            || !sameRecoveryCard(fresh, pair.getSecond(), cards.get(ready * 2 + 1), width)) return;
                    // Never clear other pairs' failure limits or their success records.
                    if (!isCurrentVisualRecovery(generation, width, height)
                            || !visualPairRetry.authorize(pair, SystemClock.uptimeMillis())) return;
                    recentlyIssuedPairs.removeIf(issued -> issued.contains(pair.getFirst(), width, height)
                            || issued.contains(pair.getSecond(), width, height));
                    autoPairRecovery.resumeVisuallyReadyPair(pair, width, height);
                    autoRetryBlockedUntil = 0L;
                    boolean started = startAutoClickSequence(live, fresh, pair, false);
                    if (!started) visualPairRetry.clearActive();
                    if (started && overlay != null) {
                        overlay.showStatus("确认词卡仍可点｜正在补点 " + pair.getFirst().getText()
                                + " ↔ " + pair.getSecond().getText(), MatchOverlayView.STATUS_WORKING);
                    }
                } finally {
                    //noinspection deprecation
                    live.recycle();
                }
            }

            @Override public void onFailure(int errorCode) {
                visualCaptureInFlight = false;
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) screenshotCadence.throttled(SystemClock.uptimeMillis());
                if (errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) visualCaptureUnavailable = true;
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(windowId, getMainExecutor(), callback);
            else takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), callback);
        } catch (RuntimeException ignored) {
            visualCaptureInFlight = false;
            visualCaptureUnavailable = true;
        }
    }

    private boolean sameRecoveryCard(AccessibilityWordExtractor.Extraction page, WordBox text,
                                     WordBox card, int width) {
        return card.equals(page.getCardBounds(text, width)) && sameVisualTarget(page, text, card, width);
    }

    private void updatePairHighlights(AccessibilityWordExtractor.Extraction page) {
        if (overlay == null || !page.isMatchingExercise() || page.isPauseConfirmationVisible()) return;
        if (page.isNodeLimitReached()) return;
        List<WordBox> activeWords = page.withoutInactiveWords(page.getWords());
        if (highlightMatcherCache != matcher || highlightWidthCache != screenWidth
                || !highlightBoardCache.equals(activeWords)) {
            highlightMatchesCache = matcher.match(activeWords, screenWidth);
            highlightBoardCache = new ArrayList<>(activeWords);
            highlightMatcherCache = matcher;
            highlightWidthCache = screenWidth;
        }
        List<MatchPair> pairs = highlightMatchesCache;
        // Display follows the visible board, not the shrinking executable queue.
        overlay.updateHighlights(page.getWords(), isPairAutomationEnabled(this)
                ? ExactPairSelector.allExact(pairs) : pairs, !page.isNodeLimitReached());
    }

    /** Read actual success colors when successful cards linger in the node tree. */
    private void maybeConfirmGreenPair(AccessibilityNodeInfo root, AccessibilityWordExtractor.Extraction page) {
        showAutoConfirmationStatus();
        long now = SystemClock.uptimeMillis();
        if (!ConfirmationPollTiming.needsScreenshotBackup(
                FastCaptureService.isRunning(), lastFastFrameAt, autoSecondClickAt, now)) return;
        if (!isGreenConfirmationEnabled()
                || visualCaptureUnavailable || visualCaptureInFlight || !autoSecondClickIssued
                || autoSecondClickAt == 0 || now - autoSecondClickAt < 48L
                || !screenshotCadence.canRequest(now)
                || !page.isMatchingExercise() || page.isNodeLimitReached() || page.isPauseConfirmationVisible()) {
            return;
        }
        WordBox firstCard = autoFirstCardBounds;
        WordBox secondCard = autoSecondCardBounds;
        if (firstCard == null || secondCard == null) return;
        if (!canConfirmGreenTargets(page, firstCard, secondCard, screenWidth)
                && !ReplacementPairConfirmation.replaced(page.getWords(), autoFirstTarget, firstCard,
                        page.getCardBounds(autoFirstTarget, screenWidth))
                && !ReplacementPairConfirmation.replaced(page.getWords(), autoSecondTarget, secondCard,
                        page.getCardBounds(autoSecondTarget, screenWidth))) return;
        final List<DeferredPairConfirmation> capturedDeferred = new ArrayList<>(deferredPairs);
        final List<WordBox> capturedWords = new ArrayList<>(page.getWords());
        final List<WordBox> capturedCards = new ArrayList<>();
        for (WordBox word : capturedWords) capturedCards.add(page.getCardBounds(word, screenWidth));
        final boolean inspectSelection = !ConfirmationPollTiming.hasLiveFrames(
                FastCaptureService.isRunning(), lastFastFrameAt, now)
                && !capturedCards.contains(null) && capturedCards.size() <= 24;
        final int generation = visualGeneration;
        final int width = screenWidth;
        final int height = screenHeight;
        final int windowId = root.getWindowId();
        final Rect captureBounds = new Rect(0, 0, width, height);
        if (Build.VERSION.SDK_INT >= 34) {
            AccessibilityWindowInfo window = root.getWindow();
            if (window == null) return;
            window.getBoundsInScreen(captureBounds);
            //noinspection deprecation
            window.recycle();
        }
        visualCaptureInFlight = true;
        screenshotCadence.requested(now);
        TakeScreenshotCallback callback = new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                Bitmap hardware = null;
                boolean green = false;
                CardVisualState.State firstState = CardVisualState.State.UNKNOWN;
                CardVisualState.State secondState = CardVisualState.State.UNKNOWN;
                List<WordBox> screenshotSelected = new ArrayList<>();
                boolean selectionRead = false;
                List<CardVisualState.State[]> deferredStates = new ArrayList<>();
                PartialPairRetry.Action retry = PartialPairRetry.Action.NONE;
                try {
                    if (!isCurrentVisualPair(generation, width, height)
                            || SystemClock.uptimeMillis() - now > 1000L) return;
                    hardware = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                    if (hardware != null && hardware.getWidth() == captureBounds.width()
                            && hardware.getHeight() == captureBounds.height()) {
                        for (DeferredPairConfirmation entry : capturedDeferred) {
                            deferredStates.add(new CardVisualState.State[]{
                                    GreenCardScreenshot.readState(hardware, entry.firstCard, entry.pair.getFirst(), captureBounds.left, captureBounds.top),
                                    GreenCardScreenshot.readState(hardware, entry.secondCard, entry.pair.getSecond(), captureBounds.left, captureBounds.top)});
                        }
                        firstState = GreenCardScreenshot.readState(hardware, firstCard,
                                autoFirstTarget, captureBounds.left, captureBounds.top);
                        secondState = GreenCardScreenshot.readState(hardware, secondCard,
                                autoSecondTarget, captureBounds.left, captureBounds.top);
                        green = firstState == CardVisualState.State.GREEN && secondState == CardVisualState.State.GREEN;
                        if (!green && !visualGestureInFlight) {
                            retry = partialPairRetry.observe(firstState, secondState, autoSecondClickAt,
                                    SystemClock.uptimeMillis());
                            if (inspectSelection && SystemClock.uptimeMillis() - now <= 200L) {
                                for (int i = 0; i < capturedWords.size(); i++) {
                                    if (GreenCardScreenshot.readState(hardware, capturedCards.get(i), capturedWords.get(i),
                                            captureBounds.left, captureBounds.top) == CardVisualState.State.SELECTED) {
                                        screenshotSelected.add(capturedWords.get(i));
                                    }
                                }
                                selectionRead = true;
                            }
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Missing/unsupported image data never counts as success.
                } finally {
                    if (hardware != null) hardware.recycle();
                    result.getHardwareBuffer().close();
                    visualCaptureInFlight = false;
                }
                if (!isCurrentVisualPair(generation, width, height)) return;
                boolean faded = firstState == CardVisualState.State.FADED && secondState == CardVisualState.State.FADED;
                if (!faded) fadedPairConfirmation.reset();
                boolean mayHaveReplacement = firstState == CardVisualState.State.FADED || secondState == CardVisualState.State.FADED
                        || firstState == CardVisualState.State.GREEN || secondState == CardVisualState.State.GREEN;
                if (!green && !faded && !mayHaveReplacement && retry == PartialPairRetry.Action.NONE && !selectionRead && deferredStates.isEmpty()) return;
                AccessibilityNodeInfo live = getCurrentScreenRoot();
                if (live == null) return;
                try {
                    if (!isCurrentVisualPair(generation, width, height)
                            || live.getWindowId() != windowId
                            || !DUOLINGO_PACKAGE.contentEquals(live.getPackageName() == null ? "" : live.getPackageName())) return;
                    AccessibilityWordExtractor.Extraction fresh =
                            readPage(live, width, height);
                    for (int i = 0; i < deferredStates.size(); i++) {
                        confirmDeferredColors(fresh, capturedDeferred.get(i), deferredStates.get(i)[0], deferredStates.get(i)[1],
                                true, SystemClock.uptimeMillis());
                    }
                    if (selectionRead && fresh.isMatchingExercise() && !fresh.isNodeLimitReached()
                            && !fresh.isPauseConfirmationVisible()) {
                        visualBoardSelection.observeSelected(capturedWords, capturedCards, width, height,
                                screenshotSelected, SystemClock.uptimeMillis());
                        fresh = fresh.withVisualSelection(visualBoardSelection.selectedWords(SystemClock.uptimeMillis()));
                        if (recoverUnexpectedSelection(live, fresh)) return;
                    }
                    if (confirmReplacedPair(fresh, firstState, secondState, SystemClock.uptimeMillis())) return;
                    if (fresh.isMatchingExercise() && !fresh.isNodeLimitReached() && !fresh.isPauseConfirmationVisible()
                            && (green || faded ? canConfirmGreenTargets(fresh, firstCard, secondCard, width)
                            : sameVisualTarget(fresh, autoFirstTarget, firstCard, width)
                            && sameVisualTarget(fresh, autoSecondTarget, secondCard, width))) {
                        if (green) {
                            completeAutoPair("screenshot_green");
                            if (overlay != null) overlay.showStatus("两张词卡已变绿｜继续下一对", MatchOverlayView.STATUS_OK);
                        } else if (faded) {
                            confirmFadedPair(fresh, firstState, secondState, SystemClock.uptimeMillis());
                        } else {
                            retryPartialPair(live, fresh, retry);
                        }
                    }
                } finally {
                    //noinspection deprecation
                    live.recycle();
                }
            }

            @Override public void onFailure(int errorCode) {
                visualCaptureInFlight = false;
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) screenshotCadence.throttled(SystemClock.uptimeMillis());
                if (errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) {
                    visualCaptureUnavailable = true;
                    if (overlay != null && isCurrentVisualPair(generation, width, height)) {
                        overlay.showStatus("绿卡加速未授权｜请重新开启无障碍服务", MatchOverlayView.STATUS_WARNING);
                    }
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                takeScreenshotOfWindow(windowId, getMainExecutor(), callback);
            } else {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), callback);
            }
        } catch (RuntimeException ignored) {
            visualCaptureInFlight = false;
            visualCaptureUnavailable = true;
        }
    }

    private boolean sameVisualTarget(AccessibilityWordExtractor.Extraction page, WordBox target,
                                     WordBox card, int width) {
        return VisualConfirmationGeometry.matchesVisible(page.getWords(), target, card,
                page.getCardBounds(target, width));
    }

    private boolean canConfirmGreenTargets(AccessibilityWordExtractor.Extraction page,
                                           WordBox firstCard, WordBox secondCard, int width) {
        return VisualConfirmationGeometry.matches(page.getWords(), autoFirstTarget, firstCard,
                page.getCardBounds(autoFirstTarget, width))
                && VisualConfirmationGeometry.matches(page.getWords(), autoSecondTarget, secondCard,
                page.getCardBounds(autoSecondTarget, width));
    }

    private void startAutoConfirmPolling() {
        if (autoConfirmPollScheduled) {
            return;
        }
        autoConfirmPollScheduled = true;
        handler.postAtFrontOfQueue(autoConfirmPollRunnable);
    }

    private void cancelAutoClickSequence(boolean scheduleAfterCancel) {
        replacementPairConfirmation.reset();
        slowPairWasPaused = false;
        dispatchAcceptance.reset();
        fadedPairConfirmation.reset();
        autoPairStartedActive = false;
        lastConfirmationSource = "";
        activeSelectionGuard.reset();
        partialPairRetry.reset();
        fastFrames.reset();
        if (overlay != null) overlay.setSamplingCards(Collections.emptyList());
        visualPairRetry.clearActive();
        visualGestureInFlight = false;
        recoverySampleCards = Collections.emptyList();
        visualGeneration++;
        autoSecondClickAt = 0L;
        autoFirstCardBounds = null;
        autoSecondCardBounds = null;
        autoPairConfirmation.reset();
        handler.removeCallbacks(autoSecondClickRunnable);
        handler.removeCallbacks(autoSecondClickWakeRunnable);
        handler.removeCallbacks(autoPairTimeoutRunnable);
        handler.removeCallbacks(autoConfirmPollRunnable);
        autoConfirmPollScheduled = false;
        autoClickBusy = false;
        autoSecondClickIssued = false;
        autoBurstPair = false;
        autoBoardWordCount = 0;
        autoFirstTarget = null;
        autoSecondTarget = null;
        autoFirstClickAt = 0L;
        if (scheduleAfterCancel && isAssistEnabled(this)) {
            scheduleRefresh();
        }
    }

    private boolean maybeStartAiLookup(AccessibilityWordExtractor.Extraction page) {
        return maybeStartAiLookup(page, false);
    }

    private boolean maybeStartAiLookup(AccessibilityWordExtractor.Extraction page, boolean prefetch) {
        long now = SystemClock.uptimeMillis();
        if (!isPairAutomationEnabled(this) || !isAssistEnabled(this) || freezeActive
                || now < userTakeoverUntil || manualLearning.hasWork(now, MANUAL_LEARNING_WINDOW_MS)
                || !page.isMatchingExercise() || page.isNodeLimitReached() || page.isPauseConfirmationVisible()
                || !AiMatchSettings.isEnabled(this)) {
            aiFailureRecheck.reset();
            return false;
        }
        pruneIssuedPairs(page.getWords());
        List<WordBox> remaining = wordsWithoutConfirmedPairs(page.getWords());
        if (FinalPairResolver.hasOnlyPair(finalPairWords(page), screenWidth)) {
            aiFailureRecheck.reset();
            return false;
        }
        AiLookupPlan plan = planAiLookup(page);
        if (plan.hasLocalPairs()) {
            aiFailureRecheck.reset();
            if (!lastAiError.isEmpty() && now < learningNoticeUntil) {
                learningNotice = "本地已识别 " + plan.localPairCount + " 对｜继续自动配对";
                learningNoticeWarning = false;
            }
        }
        AiBoardRequest request = plan.request;
        if (!request.needsModel()) {
            aiFailureRecheck.reset();
            return false;
        }
        // Known but temporarily disabled cards go through UI recovery, never a blocking AI lookup.
        if (prefetch != plan.hasLocalPairs()) return false;
        List<String> left = request.left, right = request.right;
        String signature = aiBoardSignature(left, right);
        if (aiLookupInFlight) {
            if (!prefetch && !signature.equals(lastAiBoardSignature)) {
                // The old prefetch no longer answers the current board. Its callback is invalidated.
                cancelAiLookup();
            } else {
                if (!prefetch && !aiLookupHoldingPause
                        && beginFreezeLoad(Math.max(4, remaining.size()), true, signature)) {
                    aiLookupHoldingPause = true;
                    handler.removeCallbacks(finishFreezeRunnable);
                    activeFreezeLoadMs = 0L;
                }
                return true;
            }
        }
        if (signature.equals(lastAiBoardSignature) && now < aiLookupBlockedUntil) {
            if (!prefetch && !lastAiError.isEmpty()) {
                if (!aiFailureRecheck.isStable(page.getWords(), screenWidth, true, now)) {
                    startAnimationPolling();
                    if (overlay != null) overlay.showStatus("补词未成功｜正在重查当前词卡", MatchOverlayView.STATUS_WORKING);
                    return true;
                }
                if (!beginFreezeLoad(Math.max(4, remaining.size()), true, signature)) return false;
                handler.removeCallbacks(finishFreezeRunnable);
                ensureModeControl();
                if (modeControl != null) modeControl.showTakeoverPrompt(
                        "剩余 " + left.size() + "×" + right.size() + " 个候选尚未匹配；" + lastAiError);
                if (overlay != null) overlay.showStatus("剩余词尚未找到可靠配对｜已暂停，等待接管", MatchOverlayView.STATUS_WARNING);
                Toast.makeText(this, R.string.floating_takeover_status, Toast.LENGTH_LONG).show();
                return true;
            }
            return false;
        }
        AiMatchSettings.Settings settings = AiMatchSettings.load(this);
        if (!settings.isConfigured()) return false;
        if (!prefetch && !beginFreezeLoad(Math.max(4, remaining.size()), true, signature)) return false;
        aiFailureRecheck.reset();
        aiLookupHoldingPause = !prefetch;
        if (aiLookupHoldingPause) {
            handler.removeCallbacks(finishFreezeRunnable);
            activeFreezeLoadMs = 0L;
        }
        aiLookupInFlight = true;
        aiLookupStartedAt = now;
        tracePerformance(prefetch ? "ai_prefetch" : "ai_blocking");
        int generation = ++aiLookupGeneration;
        lastAiBoardSignature = signature;
        if (overlay != null) overlay.showStatus(
                (prefetch ? "后台补词中｜继续已知配对｜" : "大模型补词中｜正在暂停｜")
                        + left.size() + "×" + right.size() + " 个候选", MatchOverlayView.STATUS_WORKING);
        AiMatchClient.Call call = new AiMatchClient.Call();
        activeAiCall = call;
        // Retain the complete request-time board, including temporarily inactive known
        // partners. Unknown-only payloads omit the evidence needed to reject conflicts.
        final List<WordBox> requestedBoard = new ArrayList<>(page.getWords());
        final int requestedWidth = screenWidth;
        activeAiTask = aiExecutor.submit(() -> {
            AiMatchClient.Result result = aiMatchClient.match(settings, left, right, call);
            handler.post(() -> finishAiLookup(generation, signature, requestedBoard, requestedWidth, result));
        });
        return true;
    }

    private AiLookupPlan planAiLookup(AccessibilityWordExtractor.Extraction page) {
        List<WordBox> remaining = wordsWithoutConfirmedPairs(page.getWords());
        List<WordBox> active = new ArrayList<>();
        for (WordBox word : remaining) {
            if (!page.isInactive(word)) active.add(word);
        }
        // Use the same active-card matches as automatic clicking, while retaining full-board
        // knowledge for partners that are temporarily disabled or finishing their animation.
        List<MatchPair> activeMatches = highlightMatcherCache == matcher
                && highlightWidthCache == screenWidth && highlightBoardCache.equals(active)
                ? highlightMatchesCache : matcher.match(active, screenWidth);
        List<MatchPair> visibleMatches = page.getWords().equals(active)
                ? activeMatches : matcher.match(page.getWords(), screenWidth);
        return AiLookupPlan.from(visibleMatches, activeMatches, remaining, active, screenWidth);
    }
    private List<WordBox> wordsWithoutConfirmedPairs(List<WordBox> currentWords) {
        return wordsWithoutConfirmedPairs(currentWords, true);
    }
    private List<WordBox> selectedWordsWithoutConfirmedPairs(List<WordBox> selected) {
        return wordsWithoutConfirmedPairs(selected, false);
    }
    private List<WordBox> wordsWithoutConfirmedPairs(List<WordBox> currentWords, boolean excludeDeferred) {
        List<WordBox> output = new ArrayList<>();
        for (WordBox word : currentWords) {
            boolean recentlyIssued = excludeDeferred && isDeferredWord(word);
            for (IssuedPair issued : recentlyIssuedPairs) {
                if (issued.contains(word, screenWidth, screenHeight)) {
                    recentlyIssued = true;
                    break;
                }
            }
            if (!recentlyIssued) {
                output.add(word);
            }
        }
        return output;
    }

    private String aiBoardSignature(List<String> left, List<String> right) {
        List<String> normalizedLeft = new ArrayList<>();
        List<String> normalizedRight = new ArrayList<>();
        for (String word : left) {
            normalizedLeft.add(WordNormalizer.normalize(word));
        }
        for (String word : right) {
            normalizedRight.add(WordNormalizer.normalize(word));
        }
        Collections.sort(normalizedLeft);
        Collections.sort(normalizedRight);
        return "L:" + normalizedLeft + "|R:" + normalizedRight;
    }

    private void finishAiLookup(int generation, String signature, List<WordBox> requestedBoard,
                                int requestedWidth, AiMatchClient.Result result) {
        if (generation != aiLookupGeneration || !aiLookupInFlight) return;
        aiLookupInFlight = false;
        boolean heldPause = aiLookupHoldingPause;
        aiLookupHoldingPause = false;
        activeAiCall = null;
        activeAiTask = null;
        if (!isAssistEnabled(this)) return;
        long now = SystemClock.uptimeMillis();
        if (result.isSuccess()) {
            int added = 0, usable = 0, conflicts = 0;
            for (AiPairValidator.ValidatedPair pair : result.getPairs()) {
                if (AiVocabularyAdmission.conflicts(pair.getLeft(), pair.getRight(),
                        requestedBoard, requestedWidth, vocabulary)) {
                    conflicts++;
                    continue;
                }
                VocabularyStore.AddResult saved = vocabulary.addAiPair(pair.getLeft(), pair.getRight());
                if (saved == VocabularyStore.AddResult.ADDED) added++;
                if (saved == VocabularyStore.AddResult.ADDED || saved == VocabularyStore.AddResult.ALREADY_EXISTS) usable++;
            }
            matcher = new PairMatcher(vocabulary);
            aiLookupBlockedUntil = now + AI_SUCCESS_COOLDOWN_MS;
            learningNotice = "大模型补词成功｜可用 " + usable + " 对｜新增 " + added + " 对｜"
                    + (now - aiLookupStartedAt) + "ms";
            learningNoticeWarning = false;
            String failure = AiVocabularyAdmission.failureReason(added, usable, conflicts);
            if (!failure.isEmpty()) {
                android.util.Log.w("WordMatchPerf", "ai_no_progress added=" + added
                        + " existing=" + usable + " conflicts=" + conflicts);
                result = AiMatchClient.Result.error(failure);
            }
        }
        if (!result.isSuccess()) {
            aiLookupBlockedUntil = now + AI_ERROR_COOLDOWN_MS;
            aiFailureRecheck.reset();
            learningNotice = "补词未成功｜重新检查本地配对";
            learningNoticeWarning = true;
        }
        tracePerformance(result.isSuccess() ? "ai_success" : "ai_failure");
        lastAiError = result.isSuccess() ? "" : result.getError();
        lastAiBoardSignature = signature;
        learningNoticeUntil = now + LEARNING_NOTICE_MS;
        if (overlay != null) overlay.showStatus(learningNotice,
                result.isSuccess() ? MatchOverlayView.STATUS_OK : MatchOverlayView.STATUS_WARNING);
        if (!result.isSuccess()) {
            // A prefetch failure must not stop known pairs already being clicked.
            if (!heldPause) { scheduleRefresh(); return; }
            // A modal hides/disables card nodes. Re-read the unobscured board before deciding
            // that manual help is needed. Local pairs and the final pair retain priority.
            if (!isPairAutomationEnabled(this) || !matchingSessionConfirmed) return;
            if (modeControl != null) modeControl.setAutomatic(true);
            if (freezeActive) finishFreezeAndResume();
            else {
                startAnimationPolling();
                scheduleRefresh();
            }
            return;
        }
        if (heldPause && freezeActive) finishFreezeAndResume();
        else scheduleRefresh();
    }

    private void cancelAiLookup() {
        aiFailureRecheck.reset();
        aiLookupGeneration++;
        aiLookupInFlight = false;
        aiLookupHoldingPause = false;
        if (activeAiCall != null) activeAiCall.cancel();
        if (activeAiTask != null) activeAiTask.cancel(true);
        activeAiCall = null;
        activeAiTask = null;
    }
    private List<MatchPair> executableExactPairs(
            List<MatchPair> matches,
            AccessibilityWordExtractor.Extraction page
    ) {
        return autoPairRecovery.availablePairs(interactableExactPairs(matches, page), screenWidth, screenHeight);
    }

    private List<MatchPair> interactableExactPairs(
            List<MatchPair> matches,
            AccessibilityWordExtractor.Extraction page
    ) {
        if (!page.isNodeLimitReached() && page.isMatchingExercise() && !page.isPauseConfirmationVisible()) {
            pruneIssuedPairs(page.getWords());
        }
        List<MatchPair> exactPairs = ExactPairSelector.allExact(matches);
        List<MatchPair> executable = new ArrayList<>();
        for (MatchPair pair : exactPairs) {
            if (isDeferredWord(pair.getFirst()) || isDeferredWord(pair.getSecond())) continue;
            if (visualPairRetry.permits(pair, SystemClock.uptimeMillis())) {
                executable.add(pair);
                continue;
            }
            if (page.isInactive(pair.getFirst()) || page.isInactive(pair.getSecond())) {
                continue;
            }
            boolean alreadyIssued = false;
            for (IssuedPair issued : recentlyIssuedPairs) {
                if (issued.contains(pair.getFirst(), screenWidth, screenHeight)
                        || issued.contains(pair.getSecond(), screenWidth, screenHeight)) {
                    alreadyIssued = true;
                    break;
                }
            }
            if (!alreadyIssued) {
                executable.add(pair);
            }
        }
        return executable;
    }

    private void rememberIssuedPair(WordBox first, WordBox second) {
        if (first == null || second == null) {
            return;
        }
        recentlyIssuedPairs.add(new IssuedPair(
                first,
                second,
                SystemClock.uptimeMillis()
        ));
    }

    private void pruneIssuedPairs(List<WordBox> currentWords) {
        long now = SystemClock.uptimeMillis();
        for (int index = recentlyIssuedPairs.size() - 1; index >= 0; index--) {
            IssuedPair issued = recentlyIssuedPairs.get(index);
            int visibleWordCount = currentWords == null ? 0 : currentWords.size();
            boolean firstVisible = visibleWordCount > 0 && containsVisibleTarget(
                    currentWords,
                    issued.first,
                    screenWidth,
                    screenHeight
            );
            boolean secondVisible = visibleWordCount > 0 && containsVisibleTarget(
                    currentWords,
                    issued.second,
                    screenWidth,
                    screenHeight
            );
            if (IssuedPairRetentionPolicy.shouldRemove(
                    now - issued.createdAt,
                    ISSUED_PAIR_MAX_AGE_MS,
                    visibleWordCount,
                    firstVisible,
                    secondVisible
            )) {
                recentlyIssuedPairs.remove(index);
            }
        }
    }

    private static boolean containsVisibleTarget(
            List<WordBox> words,
            WordBox target,
            int screenWidth,
            int screenHeight
    ) {
        int maximumX = Math.max(32, Math.round(screenWidth * 0.04f));
        int maximumY = Math.max(32, Math.round(screenHeight * 0.04f));
        for (WordBox word : words) {
            if (sameVisibleTarget(word, target, maximumX, maximumY)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameVisibleTarget(
            WordBox first,
            WordBox second,
            int maximumX,
            int maximumY
    ) {
        return first.getNormalized().equals(second.getNormalized())
                && Math.abs(first.getCenterX() - second.getCenterX()) <= maximumX
                && Math.abs(first.getCenterY() - second.getCenterY()) <= maximumY;
    }

    private boolean performAutoCardClick(AccessibilityNodeInfo root, AccessibilityWordExtractor.Extraction page,
                                         WordBox target) {
        // A real enabled card's accessibility action avoids gesture injection and
        // its completion callback. Both routes retain fresh target / modal checks.
        if (!visualPairRetry.permits(target, SystemClock.uptimeMillis())
                && page.hasActiveControl(target) && performNodeClick(root, target)) {
            tracePerformance("click_node");
            return true;
        }
        if (Build.VERSION.SDK_INT >= 24 && page.getCardBounds(target, screenWidth) != null
                && (page.hasActiveControl(target) || visualPairRetry.permits(target, SystemClock.uptimeMillis()))) {
            tracePerformance("click_gesture");
            return performVerifiedCardTap(root, page, target);
        }
        return performNodeClick(root, target);
    }

    private boolean performVerifiedCardTap(AccessibilityNodeInfo root, WordBox target) {
        return performVerifiedCardTap(root,
                readPage(root, screenWidth, screenHeight), target);
    }

    private boolean performVerifiedCardTap(AccessibilityNodeInfo root, AccessibilityWordExtractor.Extraction page,
                                           WordBox target) {
        return performVerifiedCardTap(root, page, target,
                visualPairRetry.permits(target, SystemClock.uptimeMillis()) ? 40L : 16L);
    }

    private boolean performVerifiedCardTap(AccessibilityNodeInfo root, AccessibilityWordExtractor.Extraction page,
                                           WordBox target, long durationMs) {
        if (Build.VERSION.SDK_INT < 24 || visualGestureInFlight || !autoClickBusy
                || !isAssistEnabled(this) || !isPairAutomationEnabled(this) || freezeActive
                || !DUOLINGO_PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return false;
        WordBox card = page.getCardBounds(target, screenWidth);
        if (!page.isMatchingExercise() || page.isNodeLimitReached() || page.isPauseConfirmationVisible()
                || card == null || !sameVisualTarget(page, target, card, screenWidth)) return false;
        if (page.getSelectedWords().size() > 1) return false;
        for (WordBox selected : page.getSelectedWords()) {
            if (sameVisibleTarget(selected, target, 4, 4)
                    || (!sameVisibleTarget(selected, autoFirstTarget, 4, 4)
                    && !sameVisibleTarget(selected, autoSecondTarget, 4, 4))) return false;
        }
        long now = SystemClock.uptimeMillis();
        PendingProgrammaticClick pending = new PendingProgrammaticClick(
                target.getCenterX(), target.getCenterY(), target.getNormalized(), now);
        pendingProgrammaticClicks.add(pending);
        programmaticClickGuardUntil = Math.max(programmaticClickGuardUntil, now + PROGRAMMATIC_CLICK_GUARD_MS);
        Path path = new Path();
        path.moveTo(target.getCenterX(), target.getCenterY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, durationMs)).build();
        int generation = visualGeneration;
        visualGestureInFlight = true;
        boolean accepted;
        try {
            accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription description) {
                    if (generation == visualGeneration) {
                        visualGestureInFlight = false;
                        // The first touch has ended: inspect selection and send
                        // its partner immediately, without another timer tick.
                        if (autoClickBusy && !autoSecondClickIssued) {
                            handler.removeCallbacks(autoSecondClickWakeRunnable);
                            handler.removeCallbacks(autoSecondClickRunnable);
                            handler.postAtFrontOfQueue(autoSecondClickRunnable);
                        }
                    }
                }
                @Override public void onCancelled(GestureDescription description) {
                    if (generation == visualGeneration) {
                        visualGestureInFlight = false;
                        if (autoClickBusy) failActiveAutoPair("词卡触点点击被系统取消");
                    }
                }
            }, handler);
        } catch (RuntimeException ignored) {
            accepted = false;
        }
        if (!accepted) {
            visualGestureInFlight = false;
            pendingProgrammaticClicks.remove(pending);
        }
        return accepted;
    }

    private boolean performNodeClick(AccessibilityNodeInfo root, WordBox target) {
        if (root == null || target == null || target.getNormalized().isEmpty()) {
            return false;
        }
        if (visualPairRetry.permits(target, SystemClock.uptimeMillis())) {
            return performVerifiedCardTap(root, target);
        }
        long locateStarted = SystemClock.uptimeMillis();
        NodeCandidate candidate = new NodeCandidate();
        findMatchingNode(root, target, candidate);
        traceDuration("locate", locateStarted);
        if (candidate.pauseDialogVisible) {
            if (candidate.node != null) {
                //noinspection deprecation
                candidate.node.recycle();
            }
            return false;
        }
        AccessibilityNodeInfo current = candidate.node;
        candidate.node = null;
        for (int depth = 0; current != null && depth < MAX_CLICK_ANCESTORS; depth++) {
            boolean clicked = false;
            Rect bounds = new Rect();
            current.getBoundsInScreen(bounds);
            if (current.isClickable()
                    && current.isEnabled()
                    && current.isVisibleToUser()
                    && isSafeTileBounds(bounds, target)) {
                PendingProgrammaticClick pendingClick = new PendingProgrammaticClick(
                        bounds.centerX(),
                        bounds.centerY(),
                        target.getNormalized(),
                        SystemClock.uptimeMillis()
                );
                pendingProgrammaticClicks.add(pendingClick);
                programmaticClickGuardUntil = Math.max(
                        programmaticClickGuardUntil,
                        SystemClock.uptimeMillis() + PROGRAMMATIC_CLICK_GUARD_MS
                );
                clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!clicked) {
                    pendingProgrammaticClicks.remove(pendingClick);
                }
            }
            AccessibilityNodeInfo parent = clicked ? null : current.getParent();
            //noinspection deprecation
            current.recycle();
            if (clicked) {
                return true;
            }
            current = parent;
        }
        if (current != null) {
            //noinspection deprecation
            current.recycle();
        }
        return false;
    }

    private void findMatchingNode(
            AccessibilityNodeInfo root,
            WordBox target,
            NodeCandidate best
    ) {
        if (root == null) {
            return;
        }
        PauseConfirmationDetector pauseDetector = new PauseConfirmationDetector();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        try {
            while (!queue.isEmpty() && visited++ < MAX_CLICK_SEARCH_NODES) {
                AccessibilityNodeInfo node = queue.removeFirst();
                try {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    CharSequence text = node.getText();
                    CharSequence description = node.getContentDescription();
                    String nodeText = text == null ? "" : text.toString();
                    String nodeDescription = description == null ? "" : description.toString();
                    // Reuse the click-location scan to block dialogs that appeared
                    // after refresh, even when the background lesson title remains.
                    pauseDetector.observe(nodeText, bounds.left, bounds.top, bounds.right, bounds.bottom,
                            screenWidth, screenHeight);
                    pauseDetector.observe(nodeDescription, bounds.left, bounds.top, bounds.right, bounds.bottom,
                            screenWidth, screenHeight);
                    long deltaX = (long) bounds.centerX() - target.getCenterX();
                    long deltaY = (long) bounds.centerY() - target.getCenterY();
                    int maximumX = Math.max(32, Math.round(screenWidth * 0.04f));
                    int maximumY = Math.max(32, Math.round(screenHeight * 0.04f));
                    // The complete scan still checks every node for a modal. Only
                    // nearby text can be this card, so normalize those candidates.
                    if (Math.abs(deltaX) <= maximumX && Math.abs(deltaY) <= maximumY
                            && (target.getNormalized().equals(WordNormalizer.normalize(nodeText))
                            || target.getNormalized().equals(WordNormalizer.normalize(nodeDescription)))) {
                        long distance = deltaX * deltaX + deltaY * deltaY;
                        if (distance < best.distance) {
                            if (best.node != null) best.node.recycle();
                            best.node = AccessibilityNodeInfo.obtain(node);
                            best.distance = distance;
                        }
                    }
                    for (int index = 0; index < node.getChildCount(); index++) {
                        AccessibilityNodeInfo child = node.getChild(index);
                        if (child != null) {
                            queue.addLast(child);
                        }
                    }
                } finally {
                    if (node != root) {
                        //noinspection deprecation
                        node.recycle();
                    }
                }
            }
        } finally {
            while (!queue.isEmpty()) {
                //noinspection deprecation
                queue.removeFirst().recycle();
            }
        }
        best.pauseDialogVisible = pauseDetector.isVisible();
    }

    private boolean isSafeTileBounds(Rect bounds, WordBox target) {
        if (bounds.width() <= 0
                || bounds.height() <= 0
                || bounds.width() > screenWidth * 0.55f
                || bounds.height() > screenHeight * 0.18f) {
            return false;
        }
        return Math.abs(bounds.centerX() - target.getCenterX()) <= screenWidth * 0.09f
                && Math.abs(bounds.centerY() - target.getCenterY()) <= screenHeight * 0.06f;
    }

    private void hideOverlay() {
        autoPairRecovery.reset();
        confirmationPace.reset();
        visualPairRetry.reset();
        matchingSessionConfirmed = false;
        finalPairResolver.reset();
        if (windowManager == null) {
            return;
        }
        if (modeControl != null) {
            try {
                windowManager.removeView(modeControl);
            } catch (IllegalArgumentException ignored) {
                // The system may already have detached the accessibility overlay.
            }
            modeControl = null;
            modeControlParams = null;
        }
        if (overlay != null) {
            try {
                windowManager.removeView(overlay);
            } catch (IllegalArgumentException ignored) {
                // The system may already have detached the accessibility overlay.
            }
            overlay = null;
        }
    }

    private final class ModeControlDragListener implements View.OnTouchListener {
        private float downRawX;
        private float downRawY;
        private int startX;
        private int startY;
        private boolean moved;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (modeControlParams == null || windowManager == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = modeControlParams.x;
                    startY = modeControlParams.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float deltaX = event.getRawX() - downRawX;
                    float deltaY = event.getRawY() - downRawY;
                    moved = moved || Math.abs(deltaX) >= dp(4) || Math.abs(deltaY) >= dp(4);
                    if (!moved) {
                        return true;
                    }
                    modeControlParams.x = clamp(
                            startX - Math.round(deltaX),
                            0,
                            Math.max(0, screenWidth - dp(54))
                    );
                    modeControlParams.y = clamp(
                            startY + Math.round(deltaY),
                            0,
                            Math.max(0, screenHeight - dp(54))
                    );
                    try {
                        windowManager.updateViewLayout(modeControl, modeControlParams);
                    } catch (IllegalArgumentException ignored) {
                        // The overlay may have been closed while this gesture was active.
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        view.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        }
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private String summarizeWords(List<WordBox> words) {
        StringBuilder summary = new StringBuilder();
        int count = Math.min(3, words.size());
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                summary.append(" · ");
            }
            String text = words.get(index).getText();
            summary.append(text.length() > 10 ? text.substring(0, 10) + "…" : text);
        }
        return summary.toString();
    }

    private static final class NodeCandidate {
        private boolean pauseDialogVisible;
        private AccessibilityNodeInfo node;
        private long distance = Long.MAX_VALUE;
    }

    private static final class PendingProgrammaticClick {
        private final int centerX;
        private final int centerY;
        private final String normalized;
        private final long createdAt;

        private PendingProgrammaticClick(
                int centerX,
                int centerY,
                String normalized,
                long createdAt
        ) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.normalized = normalized;
            this.createdAt = createdAt;
        }
    }

    private static final class IssuedPair {
        private final WordBox first;
        private final WordBox second;
        private final long createdAt;

        private IssuedPair(WordBox first, WordBox second, long createdAt) {
            this.first = first;
            this.second = second;
            this.createdAt = createdAt;
        }

        private boolean contains(WordBox word, int screenWidth, int screenHeight) {
            int maximumX = Math.max(32, Math.round(screenWidth * 0.04f));
            int maximumY = Math.max(32, Math.round(screenHeight * 0.04f));
            return sameVisibleTarget(first, word, maximumX, maximumY)
                    || sameVisibleTarget(second, word, maximumX, maximumY);
        }
    }
}
