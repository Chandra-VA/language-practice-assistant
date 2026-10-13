package com.wordmatch.assist;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.wordmatch.assist.accessibility.DuolingoNodeService;
import com.wordmatch.assist.sentence.SentenceStore;
import com.wordmatch.assist.exercise.*;
import java.util.EnumSet;
import com.wordmatch.assist.sentence.SentenceMemory;
import com.wordmatch.assist.ai.AiMatchClient;
import com.wordmatch.assist.ai.AiMatchSettings;
import com.wordmatch.assist.ai.AiProvider;
import com.wordmatch.assist.capture.FastCaptureService;
import com.wordmatch.assist.matching.VocabularyStore;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public final class MainActivity extends Activity {
    public static final String ACTION_START_AUTOMATIC = "com.wordmatch.assist.START_AUTOMATIC";
    private static final int REQUEST_FAST_CAPTURE = 105;
    private static final int REQUEST_NOTIFICATIONS = 102;
    private static final int REQUEST_IMPORT = 103;
    private static final int PAIR_SOURCE_LEARNED = 0;
    private static final int PAIR_SOURCE_MANUAL = 1;
    private static final int PAIR_SOURCE_AI = 2;

    private TextView statusText;
    private TextView vocabularyText;
    private TextView learnedVocabularyText;
    private Button learnedVocabularyButton;
    private Button manualVocabularyButton;
    private Button aiVocabularyButton;
    private TextView aiSettingsSummary;
    private Switch aiMatchSwitch;
    private boolean syncingAiSwitch;
    private boolean waitingForAccessibilityPermission;
    private AssistMode pendingAssistMode = AssistMode.AUTOMATIC;
    private boolean waitingForCapturePermission;
    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            waitingForCapturePermission = savedInstanceState.getBoolean("waitingForCapture");
            waitingForAccessibilityPermission = savedInstanceState.getBoolean("waitingForAccessibility");
            pendingAssistMode = savedInstanceState.getBoolean("pendingAutoMode", true)
                    ? AssistMode.AUTOMATIC : AssistMode.LEARNING;
        }
        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        statusText = findViewById(R.id.statusText);
        vocabularyText = findViewById(R.id.vocabularyText);
        learnedVocabularyText = findViewById(R.id.learnedVocabularyText);
        learnedVocabularyButton = findViewById(R.id.learnedVocabularyButton);
        manualVocabularyButton = findViewById(R.id.manualVocabularyButton);
        aiVocabularyButton = findViewById(R.id.aiVocabularyButton);
        aiSettingsSummary = findViewById(R.id.aiSettingsSummary);
        learnedVocabularyButton.setOnClickListener(ignored -> showLearnedVocabulary());
        manualVocabularyButton.setOnClickListener(ignored -> showManualVocabulary());
        aiVocabularyButton.setOnClickListener(ignored -> showAiVocabulary());
        findViewById(R.id.addVocabularyPairButton)
                .setOnClickListener(ignored -> showAddPairDialog());
        findViewById(R.id.sentenceMemoryButton).setOnClickListener(ignored -> showSentenceMemory());
        findViewById(R.id.exerciseTypesButton).setOnClickListener(ignored -> showExerciseTypes());
        findViewById(R.id.exerciseMemoryButton).setOnClickListener(ignored -> showExerciseMemory());
        updateVocabularyCount();
        findViewById(R.id.learningModeButton)
                .setOnClickListener(ignored -> beginNodeSetup(AssistMode.LEARNING));
        findViewById(R.id.autoModeButton)
                .setOnClickListener(ignored -> beginNodeSetup(AssistMode.AUTOMATIC));
        aiMatchSwitch = findViewById(R.id.aiMatchSwitch);
        aiMatchSwitch.setChecked(AiMatchSettings.isEnabled(this));
        aiMatchSwitch.setOnCheckedChangeListener((ignored, checked) -> {
            if (syncingAiSwitch) {
                return;
            }
            AiMatchSettings.Settings settings = AiMatchSettings.load(this);
            if (checked && !settings.isConfigured()) {
                syncingAiSwitch = true;
                aiMatchSwitch.setChecked(false);
                syncingAiSwitch = false;
                AiMatchSettings.setEnabled(this, false);
                Toast.makeText(this, R.string.ai_configure_first, Toast.LENGTH_LONG).show();
                showAiSettingsDialog();
                return;
            }
            AiMatchSettings.setEnabled(this, checked);
            DuolingoNodeService.onAiSettingsChanged();
            updateAiSettingsSummary();
            Toast.makeText(
                    this,
                    checked ? R.string.ai_enabled : R.string.ai_disabled,
                    Toast.LENGTH_LONG
            ).show();
        });
        findViewById(R.id.aiSettingsButton)
                .setOnClickListener(ignored -> showAiSettingsDialog());
        updateAiSettingsSummary();
        findViewById(R.id.stopButton).setOnClickListener(ignored -> stopAssist());
        findViewById(R.id.importButton).setOnClickListener(ignored -> importVocabulary());
        if (savedInstanceState == null) handleModeIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleModeIntent(intent);
    }

    private void handleModeIntent(Intent intent) {
        if (intent != null && ACTION_START_AUTOMATIC.equals(intent.getAction())
                && DuolingoNodeService.consumeAutomaticStartRequest()) {
            intent.setAction(null);
            beginNodeSetup(AssistMode.AUTOMATIC);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        updateVocabularyCount();
        updateAiSettingsSummary();
        if (waitingForAccessibilityPermission
                && DuolingoNodeService.isServiceEnabled(this)) {
            waitingForAccessibilityPermission = false;
            continueModeSetup();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("waitingForCapture", waitingForCapturePermission);
        state.putBoolean("waitingForAccessibility", waitingForAccessibilityPermission);
        state.putBoolean("pendingAutoMode", pendingAssistMode == AssistMode.AUTOMATIC);
        super.onSaveInstanceState(state);
    }

    private void beginNodeSetup(AssistMode mode) {
        if (waitingForCapturePermission) return;
        pendingAssistMode = mode;
        if (!DuolingoNodeService.isServiceEnabled(this)) {
            waitingForAccessibilityPermission = true;
            Toast.makeText(
                    this,
                    "请开启“多邻国节点极速高亮”；若开关被禁用，请先在应用信息中允许受限设置",
                    Toast.LENGTH_LONG
            ).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        continueModeSetup();
    }

    private void continueModeSetup() {
        if (pendingAssistMode == AssistMode.AUTOMATIC && Build.VERSION.SDK_INT >= 30
                && !FastCaptureService.isRunning()) {
            if (!waitingForCapturePermission) {
                waitingForCapturePermission = true;
                ensureNotificationPermissionThenCapture();
            }
            return;
        }
        activateNodeMode(pendingAssistMode);
    }

    private void activateNodeMode(AssistMode mode) {
        if (mode == AssistMode.LEARNING) stopService(new Intent(this, FastCaptureService.class));
        DuolingoNodeService.setAutoModeEnabled(this, mode.isAutoClickEnabled());
        DuolingoNodeService.setFreezeModeEnabled(this, mode.isFreezeEnabled());
        DuolingoNodeService.setAssistEnabled(this, true);
        statusText.setText(mode == AssistMode.LEARNING
                ? R.string.status_learning_running
                : R.string.status_auto_running);
        Toast.makeText(
                this,
                mode == AssistMode.LEARNING
                        ? R.string.learning_mode_started
                        : R.string.auto_mode_started,
                Toast.LENGTH_LONG
        ).show();
        new Handler(Looper.getMainLooper()).postDelayed(this::openDuolingo, 120L);
    }

    private void ensureNotificationPermissionThenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS
            );
            return;
        }
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        if (!waitingForCapturePermission) return;
        Intent capture = Build.VERSION.SDK_INT >= 34
                ? projectionManager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                : projectionManager.createScreenCaptureIntent();
        startActivityForResult(capture, REQUEST_FAST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FAST_CAPTURE) {
            if (!waitingForCapturePermission) return;
            waitingForCapturePermission = false;
            // Screen sharing accelerates the same automatic mode. Denial must
            // still start node-based matching, including optional AI answers.
            activateNodeMode(AssistMode.AUTOMATIC);
            if (resultCode != RESULT_OK || data == null) {
                Toast.makeText(this, R.string.auto_capture_skipped, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent service = FastCaptureService.startIntent(this, resultCode, data);
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                else startService(service);
                statusText.setText(R.string.status_auto_accelerated);
            } catch (RuntimeException unavailable) {
                Toast.makeText(this, R.string.auto_capture_skipped, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                saveImportedVocabulary(uri);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            requestScreenCapture();
        }
    }

    private void stopAssist() {
        waitingForCapturePermission = false;
        waitingForAccessibilityPermission = false;
        stopService(new Intent(this, FastCaptureService.class));
        DuolingoNodeService.setAssistEnabled(this, false);
        statusText.setText(R.string.status_ready);
        Toast.makeText(this, "辅助已停止", Toast.LENGTH_SHORT).show();
    }

    private void importVocabulary() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    private void saveImportedVocabulary(Uri uri) {
        try {
            InputStream stream = getContentResolver().openInputStream(uri);
            if (stream == null) {
                throw new IOException("无法打开文件");
            }
            VocabularyStore.replaceUserVocabulary(this, stream);
            VocabularyStore store = new VocabularyStore(this);
            DuolingoNodeService.reloadVocabulary();
            Toast.makeText(
                    this,
                    "导入成功，共加载 " + store.size() + " 组词",
                    Toast.LENGTH_LONG
            ).show();
            updateVocabularyCount();
        } catch (IOException error) {
            Toast.makeText(this, "导入失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openDuolingo() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.duolingo");
        if (launchIntent == null) {
            launchIntent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage("com.duolingo");
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launchIntent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "未找到包名为 com.duolingo 的多邻国 App", Toast.LENGTH_LONG).show();
        }
    }

    private void updateStatus() {
        if (FastCaptureService.isRunning() && DuolingoNodeService.isAssistEnabled(this)
                && DuolingoNodeService.isAutoModeEnabled(this)) {
            statusText.setText(R.string.status_auto_accelerated);
            return;
        }
        if (DuolingoNodeService.isAssistEnabled(this)
                && DuolingoNodeService.isServiceEnabled(this)) {
            statusText.setText(DuolingoNodeService.isAutoModeEnabled(this)
                    ? R.string.status_auto_running
                    : R.string.status_learning_running);
        } else if (!DuolingoNodeService.isServiceEnabled(this)) {
            statusText.setText(R.string.node_permission_missing);
        } else {
            statusText.setText(R.string.status_ready);
        }
    }

    private void updateVocabularyCount() {
        if (vocabularyText == null) {
            return;
        }
        ((Button) findViewById(R.id.sentenceMemoryButton)).setText(getString(R.string.sentence_memory_count,
                new SentenceStore(this).read().entries().size()));
        VocabularyStore store = new VocabularyStore(this);
        vocabularyText.setText(getString(R.string.vocabulary_count, store.size()));
        List<VocabularyStore.VocabularyPair> learned = store.getLearnedPairsNewestFirst();
        List<VocabularyStore.VocabularyPair> manual = store.getManualPairsNewestFirst();
        List<VocabularyStore.VocabularyPair> ai = store.getAiPairsNewestFirst();
        learnedVocabularyButton.setText(getString(
                R.string.view_learned_vocabulary,
                learned.size()
        ));
        manualVocabularyButton.setText(getString(
                R.string.view_manual_vocabulary,
                manual.size()
        ));
        aiVocabularyButton.setText(getString(
                R.string.view_ai_vocabulary,
                ai.size()
        ));
        if (learned.isEmpty()) {
            learnedVocabularyText.setText(R.string.learned_vocabulary_empty_summary);
            return;
        }
        StringBuilder preview = new StringBuilder();
        int previewCount = Math.min(3, learned.size());
        for (int index = 0; index < previewCount; index++) {
            if (index > 0) {
                preview.append(" · ");
            }
            preview.append(learned.get(index).getDisplayText());
        }
        learnedVocabularyText.setText(getString(
                R.string.learned_vocabulary_summary,
                learned.size(),
                preview.toString()
        ));
    }

    private void showExerciseTypes() {
        ExerciseType[] types = ExerciseType.values();
        String[] labels = new String[types.length]; boolean[] checked = new boolean[types.length];
        EnumSet<ExerciseType> enabled = EnumSet.noneOf(ExerciseType.class);
        for (int i = 0; i < types.length; i++) {
            labels[i] = types[i].label; checked[i] = ExerciseSettings.enabled(this, types[i]);
            if (checked[i]) enabled.add(types[i]);
        }
        new AlertDialog.Builder(this).setTitle("选择自动处理的题型")
                .setMultiChoiceItems(labels, checked, (dialog, index, value) -> {
                    if (value) enabled.add(types[index]); else enabled.remove(types[index]);
                }).setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                    ExerciseSettings.save(this, enabled); DuolingoNodeService.onExerciseSettingsChanged();
                    Toast.makeText(this, "已启用 " + enabled.size() + " 类；未勾选的题目等待手动操作", Toast.LENGTH_LONG).show();
                }).show();
    }

    private void showExerciseMemory() {
        SentenceStore store = new SentenceStore(this, true);
        List<SentenceMemory.Entry> entries = store.read().entries();
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("已学会的其他题目（" + entries.size() + "）").setNegativeButton("关闭", null);
        if (entries.isEmpty()) { dialog.setMessage("选择、填空、阅读、对话和文字输入题在提交并显示答对后保存。配对与翻译组句仍保存在原词库中。").show(); return; }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = entries.get(i).display().replaceAll("\\n#[0-9a-f]{64}", "");
        dialog.setItems(labels, (d, i) -> new AlertDialog.Builder(this).setTitle("删除这条记忆？").setMessage(labels[i])
                .setNegativeButton("取消", null).setPositiveButton("删除", (a, b) -> {
                    store.remove(entries.get(i).prompt, entries.get(i).words); DuolingoNodeService.onExerciseSettingsChanged(); showExerciseMemory();
                }).show()).show();
    }

    private void showSentenceMemory() {
        SentenceStore store = new SentenceStore(this);
        List<SentenceMemory.Entry> entries = store.read().entries();
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.sentence_memory_count, entries.size())).setNegativeButton(R.string.close, null);
        if (entries.isEmpty()) { dialog.setMessage(R.string.sentence_memory_empty).show(); return; }
        CharSequence[] labels = new CharSequence[entries.size()];
        for (int i = 0; i < entries.size(); i++) labels[i] = entries.get(i).display();
        dialog.setItems(labels, (ignored, index) -> {
            SentenceMemory.Entry entry = entries.get(index);
            new AlertDialog.Builder(this).setTitle(R.string.sentence_memory_delete).setMessage(entry.display())
                    .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.delete, (d, w) -> {
                        boolean deleted = store.remove(entry.prompt, entry.words);
                        updateVocabularyCount();
                        Toast.makeText(this, deleted ? R.string.pair_deleted : R.string.pair_delete_failed, Toast.LENGTH_SHORT).show();
                    }).show();
        }).show();
    }

    private void showLearnedVocabulary() {
        VocabularyStore store = new VocabularyStore(this);
        showPairList(
                store,
                store.getLearnedPairsNewestFirst(),
                PAIR_SOURCE_LEARNED,
                R.string.learned_vocabulary_title,
                R.string.learned_vocabulary_empty_detail
        );
    }

    private void showManualVocabulary() {
        VocabularyStore store = new VocabularyStore(this);
        showPairList(
                store,
                store.getManualPairsNewestFirst(),
                PAIR_SOURCE_MANUAL,
                R.string.manual_vocabulary_title,
                R.string.manual_vocabulary_empty_detail
        );
    }

    private void showAiVocabulary() {
        VocabularyStore store = new VocabularyStore(this);
        showPairList(
                store,
                store.getAiPairsNewestFirst(),
                PAIR_SOURCE_AI,
                R.string.ai_vocabulary_title,
                R.string.ai_vocabulary_empty_detail
        );
    }

    private void showPairList(
            VocabularyStore store,
            List<VocabularyStore.VocabularyPair> pairs,
            int source,
            int titleResource,
            int emptyResource
    ) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(getString(titleResource, pairs.size()))
                .setNegativeButton(R.string.close, null);
        if (pairs.isEmpty()) {
            builder.setMessage(emptyResource).show();
            return;
        }
        CharSequence[] items = new CharSequence[pairs.size()];
        for (int index = 0; index < pairs.size(); index++) {
            items[index] = (index + 1) + ". " + pairs.get(index).getDisplayText();
        }
        builder.setItems(items, (dialog, which) -> confirmDeletePair(
                store,
                pairs.get(which),
                source
        )).show();
    }

    private void confirmDeletePair(
            VocabularyStore store,
            VocabularyStore.VocabularyPair pair,
            int source
    ) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_pair_title)
                .setMessage(pair.getDisplayText())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    boolean removed;
                    if (source == PAIR_SOURCE_LEARNED) {
                        removed = store.removeLearnedPair(pair);
                    } else if (source == PAIR_SOURCE_AI) {
                        removed = store.removeAiPair(pair);
                    } else {
                        removed = store.removeManualPair(pair);
                    }
                    if (removed) {
                        DuolingoNodeService.reloadVocabulary();
                        updateVocabularyCount();
                        Toast.makeText(this, R.string.pair_deleted, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.pair_delete_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void updateAiSettingsSummary() {
        if (aiSettingsSummary == null) {
            return;
        }
        AiMatchSettings.Settings settings = AiMatchSettings.load(this);
        if (aiMatchSwitch != null) {
            syncingAiSwitch = true;
            aiMatchSwitch.setChecked(settings.isEnabled());
            syncingAiSwitch = false;
        }
        if (!settings.isConfigured()) {
            aiSettingsSummary.setText(R.string.ai_settings_not_configured);
            return;
        }
        String host = Uri.parse(settings.getEndpoint()).getHost();
        if (host == null || host.trim().isEmpty()) {
            host = settings.getEndpoint();
        }
        aiSettingsSummary.setText(getString(
                R.string.ai_settings_summary,
                settings.getProvider().getDisplayName(),
                host,
                settings.getModel(),
                settings.getTimeoutMs(),
                getString(settings.hasApiKey() ? R.string.ai_key_saved : R.string.ai_key_missing)
        ));
    }

    private void showAiSettingsDialog() {
        AiMatchSettings.Settings settings = AiMatchSettings.load(this);
        int padding = Math.round(22f * getResources().getDisplayMetrics().density);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(padding, 0, padding, 0);

        TextView explanation = new TextView(this);
        explanation.setText(R.string.ai_settings_explanation);
        explanation.setTextSize(13f);
        form.addView(explanation, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView providerLabel = new TextView(this);
        providerLabel.setText(R.string.ai_provider_label);
        providerLabel.setTextSize(13f);
        LinearLayout.LayoutParams providerLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        providerLabelParams.topMargin = Math.round(10f * getResources().getDisplayMetrics().density);
        form.addView(providerLabel, providerLabelParams);

        AiProvider[] providers = AiProvider.values();
        String[] providerNames = new String[providers.length];
        int selectedProvider = 0;
        for (int index = 0; index < providers.length; index++) {
            providerNames[index] = providers[index].getDisplayName();
            if (providers[index] == settings.getProvider()) {
                selectedProvider = index;
            }
        }
        Spinner providerInput = new Spinner(this);
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                providerNames
        );
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerInput.setAdapter(providerAdapter);
        providerInput.setSelection(selectedProvider);
        form.addView(providerInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        EditText endpointInput = new EditText(this);
        endpointInput.setHint(R.string.ai_endpoint_hint);
        endpointInput.setSingleLine(true);
        endpointInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
        );
        endpointInput.setText(settings.getEndpoint());
        form.addView(endpointInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        EditText modelInput = new EditText(this);
        modelInput.setHint(R.string.ai_model_hint);
        modelInput.setSingleLine(true);
        modelInput.setInputType(InputType.TYPE_CLASS_TEXT);
        modelInput.setText(settings.getModel());
        form.addView(modelInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        EditText keyInput = new EditText(this);
        keyInput.setHint(R.string.ai_api_key_hint);
        keyInput.setSingleLine(true);
        keyInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        form.addView(keyInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        EditText timeoutInput = new EditText(this);
        timeoutInput.setHint(R.string.ai_timeout_hint);
        timeoutInput.setSingleLine(true);
        timeoutInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        timeoutInput.setText(String.valueOf(settings.getTimeoutMs()));
        form.addView(timeoutInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        Button testButton = new Button(this);
        testButton.setText(R.string.ai_test_connection);
        testButton.setAllCaps(false);
        form.addView(testButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView testStatus = new TextView(this);
        testStatus.setTextSize(13f);
        form.addView(testStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        final int[] previousProviderIndex = {selectedProvider};
        providerInput.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == previousProviderIndex[0]) {
                    return;
                }
                AiProvider selected = providers[position];
                if (selected != AiProvider.COMPATIBLE) {
                    endpointInput.setText(selected.getDefaultEndpoint());
                    modelInput.setText(selected.getDefaultModel());
                }
                previousProviderIndex[0] = position;
                testStatus.setText("");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the current provider.
            }
        });

        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(form);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.ai_settings_title)
                .setView(formScroll)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.ai_clear_key, null)
                .setPositiveButton(R.string.ai_save, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(button -> {
                AiMatchSettings.clearApiKey(this);
                AiMatchSettings.setEnabled(this, false);
                keyInput.setText("");
                updateAiSettingsSummary();
                DuolingoNodeService.onAiSettingsChanged();
                Toast.makeText(this, R.string.ai_key_cleared, Toast.LENGTH_SHORT).show();
            });
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(button -> {
                int timeoutMs;
                try {
                    timeoutMs = Integer.parseInt(timeoutInput.getText().toString().trim());
                } catch (NumberFormatException error) {
                    Toast.makeText(this, R.string.ai_settings_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                boolean saved = AiMatchSettings.save(
                        this,
                        providers[providerInput.getSelectedItemPosition()].getId(),
                        endpointInput.getText().toString(),
                        modelInput.getText().toString(),
                        keyInput.getText().toString(),
                        timeoutMs
                );
                if (!saved) {
                    Toast.makeText(this, R.string.ai_settings_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                updateAiSettingsSummary();
                DuolingoNodeService.onAiSettingsChanged();
                Toast.makeText(this, R.string.ai_settings_saved, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
            testButton.setOnClickListener(button -> {
                int timeoutMs;
                try {
                    timeoutMs = Integer.parseInt(timeoutInput.getText().toString().trim());
                } catch (NumberFormatException error) {
                    testStatus.setText(R.string.ai_settings_invalid);
                    return;
                }
                String enteredKey = keyInput.getText().toString().trim();
                AiMatchSettings.Settings savedSettings = AiMatchSettings.load(this);
                AiProvider provider = providers[providerInput.getSelectedItemPosition()];
                String candidateEndpoint = provider.normalizeEndpoint(endpointInput.getText().toString());
                if (enteredKey.isEmpty() && savedSettings.hasApiKey()
                        && !com.wordmatch.assist.ai.AiTransportPolicy.canReuseKey(
                                savedSettings.getEndpoint(), candidateEndpoint)) {
                    testStatus.setText(R.string.ai_endpoint_key_changed);
                    return;
                }
                String effectiveKey = enteredKey.isEmpty()
                        ? savedSettings.getApiKey()
                        : enteredKey;
                AiMatchSettings.Settings candidate = AiMatchSettings.createTransient(
                        provider.getId(),
                        endpointInput.getText().toString(),
                        modelInput.getText().toString(),
                        effectiveKey,
                        timeoutMs
                );
                if (!candidate.isConfigured()) {
                    testStatus.setText(R.string.ai_settings_invalid);
                    return;
                }
                testButton.setEnabled(false);
                testStatus.setText(R.string.ai_testing);
                new Thread(() -> {
                    long started = android.os.SystemClock.elapsedRealtime();
                    AiMatchClient.Result result = new AiMatchClient().match(
                            candidate,
                            java.util.Arrays.asList("苹果", "车费"),
                            java.util.Arrays.asList("fare", "apple")
                    );
                    long elapsed = android.os.SystemClock.elapsedRealtime() - started;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        testButton.setEnabled(true);
                        testStatus.setText(result.isSuccess()
                                ? getString(R.string.ai_test_success) + "｜" + result.getPairs().size() + "/2 对｜" + elapsed + "ms"
                                : getString(R.string.ai_test_failed, result.getError()));
                    });
                }, "ai-connection-test").start();
            });
        });
        dialog.show();
    }

    private void showAddPairDialog() {
        int padding = Math.round(22f * getResources().getDisplayMetrics().density);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(padding, 0, padding, 0);

        EditText firstInput = new EditText(this);
        firstInput.setHint(R.string.first_word_hint);
        firstInput.setSingleLine(true);
        firstInput.setInputType(InputType.TYPE_CLASS_TEXT);
        form.addView(firstInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        EditText secondInput = new EditText(this);
        secondInput.setHint(R.string.second_word_hint);
        secondInput.setSingleLine(true);
        secondInput.setInputType(InputType.TYPE_CLASS_TEXT);
        form.addView(secondInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.add_pair_title)
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.add, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    VocabularyStore store = new VocabularyStore(this);
                    VocabularyStore.AddResult result = store.addManualPair(
                            firstInput.getText().toString(),
                            secondInput.getText().toString()
                    );
                    if (result == VocabularyStore.AddResult.ADDED) {
                        DuolingoNodeService.reloadVocabulary();
                        updateVocabularyCount();
                        Toast.makeText(this, R.string.pair_added, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } else if (result == VocabularyStore.AddResult.ALREADY_EXISTS) {
                        Toast.makeText(this, R.string.pair_already_exists, Toast.LENGTH_SHORT).show();
                    } else if (result == VocabularyStore.AddResult.INVALID) {
                        Toast.makeText(this, R.string.pair_invalid, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.pair_add_failed, Toast.LENGTH_SHORT).show();
                    }
                }));
        dialog.show();
    }
}
