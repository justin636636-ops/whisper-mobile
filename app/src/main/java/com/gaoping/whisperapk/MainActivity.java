package com.gaoping.whisperapk;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 1001;
    private static final long RESTART_DELAY_MS = 450L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private TextView engineText;
    private TextView transcriptText;
    private Button startStopButton;
    private Button clearButton;
    private Button copyButton;
    private Button saveButton;
    private CheckBox continuousCheck;
    private CheckBox mixedLanguageCheck;

    private SpeechRecognizer speechRecognizer;
    private boolean sessionActive;
    private boolean recognizerBusy;
    private boolean manualStopRequested;
    private boolean resumeAfterPermission;
    private String committedTranscript = "";
    private String partialTranscript = "";

    private final Runnable restartRunnable = new Runnable() {
        @Override
        public void run() {
            beginListeningCycle();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        engineText = findViewById(R.id.engine_text);
        transcriptText = findViewById(R.id.transcript_text);
        startStopButton = findViewById(R.id.start_stop_button);
        clearButton = findViewById(R.id.clear_button);
        copyButton = findViewById(R.id.copy_button);
        saveButton = findViewById(R.id.save_button);
        continuousCheck = findViewById(R.id.continuous_check);
        mixedLanguageCheck = findViewById(R.id.mixed_language_check);

        startStopButton.setOnClickListener(view -> toggleRecognition());
        clearButton.setOnClickListener(view -> clearTranscript());
        copyButton.setOnClickListener(view -> copyTranscript());
        saveButton.setOnClickListener(view -> saveTranscript());

        continuousCheck.setChecked(true);
        mixedLanguageCheck.setChecked(true);

        updateEngineStatus();
        updateStatus(getString(R.string.status_idle));
        updateTranscriptView();
        updateButtons();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(restartRunnable);
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    private void toggleRecognition() {
        if (sessionActive || recognizerBusy) {
            stopRecognition();
        } else {
            startRecognition();
        }
    }

    private void startRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateStatus(getString(R.string.status_recognizer_missing));
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            resumeAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }

        sessionActive = true;
        manualStopRequested = false;
        updateButtons();
        beginListeningCycle();
    }

    private void stopRecognition() {
        sessionActive = false;
        manualStopRequested = true;
        mainHandler.removeCallbacks(restartRunnable);

        if (speechRecognizer != null && recognizerBusy) {
            speechRecognizer.stopListening();
            updateStatus(getString(R.string.status_stopping));
        } else {
            recognizerBusy = false;
            updateStatus(getString(R.string.status_stopped));
        }

        updateButtons();
    }

    private void beginListeningCycle() {
        mainHandler.removeCallbacks(restartRunnable);
        if (!sessionActive || recognizerBusy) {
            return;
        }

        ensureSpeechRecognizer();
        if (speechRecognizer == null) {
            updateStatus(getString(R.string.status_recognizer_missing));
            sessionActive = false;
            updateButtons();
            return;
        }

        partialTranscript = "";
        updateTranscriptView();

        try {
            speechRecognizer.startListening(createRecognizerIntent());
            recognizerBusy = true;
            updateStatus(getString(R.string.status_listening));
            updateButtons();
        } catch (Exception exception) {
            recognizerBusy = false;
            sessionActive = false;
            updateStatus(getString(R.string.status_start_failed, exception.getMessage()));
            updateButtons();
        }
    }

    private void ensureSpeechRecognizer() {
        if (speechRecognizer != null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        } else {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        }

        if (speechRecognizer != null) {
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    updateStatus(getString(R.string.status_ready));
                }

                @Override
                public void onBeginningOfSpeech() {
                    updateStatus(getString(R.string.status_speaking));
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                }

                @Override
                public void onEndOfSpeech() {
                    updateStatus(getString(R.string.status_finishing));
                }

                @Override
                public void onError(int error) {
                    recognizerBusy = false;
                    partialTranscript = "";
                    updateTranscriptView();

                    if (manualStopRequested) {
                        manualStopRequested = false;
                        updateStatus(getString(R.string.status_stopped));
                        updateButtons();
                        return;
                    }

                    if (sessionActive && continuousCheck.isChecked() && shouldRetry(error)) {
                        updateStatus(getString(R.string.status_retrying, errorToText(error)));
                        mainHandler.postDelayed(restartRunnable, RESTART_DELAY_MS);
                    } else {
                        sessionActive = false;
                        updateStatus(getString(R.string.status_error, errorToText(error)));
                        updateButtons();
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    recognizerBusy = false;
                    partialTranscript = "";
                    appendBestMatch(results);
                    updateTranscriptView();

                    if (sessionActive && continuousCheck.isChecked()) {
                        updateStatus(getString(R.string.status_cycle_done));
                        mainHandler.postDelayed(restartRunnable, RESTART_DELAY_MS);
                    } else {
                        sessionActive = false;
                        updateStatus(getString(R.string.status_done));
                        updateButtons();
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    partialTranscript = extractBestMatch(partialResults);
                    updateTranscriptView();
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                }
            });
        }
    }

    private Intent createRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10_000L);

        if (!mixedLanguageCheck.isChecked()) {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN");
        }

        return intent;
    }

    private void appendBestMatch(Bundle results) {
        String bestMatch = extractBestMatch(results);
        if (TextUtils.isEmpty(bestMatch)) {
            return;
        }

        if (TextUtils.isEmpty(committedTranscript)) {
            committedTranscript = bestMatch.trim();
        } else {
            committedTranscript = committedTranscript + "\n" + bestMatch.trim();
        }
    }

    private String extractBestMatch(Bundle results) {
        if (results == null) {
            return "";
        }

        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) {
            return "";
        }

        return matches.get(0);
    }

    private boolean shouldRetry(int error) {
        return error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_CLIENT
                || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED;
    }

    private String errorToText(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return getString(R.string.error_audio);
            case SpeechRecognizer.ERROR_CLIENT:
                return getString(R.string.error_client);
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return getString(R.string.error_permission);
            case SpeechRecognizer.ERROR_NETWORK:
                return getString(R.string.error_network);
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return getString(R.string.error_network_timeout);
            case SpeechRecognizer.ERROR_NO_MATCH:
                return getString(R.string.error_no_match);
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return getString(R.string.error_busy);
            case SpeechRecognizer.ERROR_SERVER:
                return getString(R.string.error_server);
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return getString(R.string.error_timeout);
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                return getString(R.string.error_language_not_supported);
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return getString(R.string.error_language_unavailable);
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return getString(R.string.error_server_disconnected);
            default:
                return getString(R.string.error_unknown, error);
        }
    }

    private void clearTranscript() {
        committedTranscript = "";
        partialTranscript = "";
        updateTranscriptView();
        updateStatus(getString(R.string.status_cleared));
        updateButtons();
    }

    private void copyTranscript() {
        if (TextUtils.isEmpty(committedTranscript)) {
            showToast(getString(R.string.toast_empty));
            return;
        }

        ClipboardManager clipboardManager =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipboardManager.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.app_name), committedTranscript));
            showToast(getString(R.string.toast_copied));
        }
    }

    private void saveTranscript() {
        if (TextUtils.isEmpty(committedTranscript)) {
            showToast(getString(R.string.toast_empty));
            return;
        }

        File baseDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (baseDir == null) {
            showToast(getString(R.string.toast_save_failed));
            return;
        }

        if (!baseDir.exists() && !baseDir.mkdirs()) {
            showToast(getString(R.string.toast_save_failed));
            return;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File outputFile = new File(baseDir, "meeting_transcript_" + timestamp + ".txt");

        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            outputStream.write(committedTranscript.getBytes(StandardCharsets.UTF_8));
            showToast(getString(R.string.toast_saved, outputFile.getAbsolutePath()));
        } catch (Exception exception) {
            showToast(getString(R.string.toast_save_error, exception.getMessage()));
        }
    }

    private void updateTranscriptView() {
        StringBuilder builder = new StringBuilder();
        if (!TextUtils.isEmpty(committedTranscript)) {
            builder.append(committedTranscript.trim());
        }

        if (!TextUtils.isEmpty(partialTranscript)) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(getString(R.string.partial_prefix)).append(partialTranscript.trim());
        }

        if (builder.length() == 0) {
            transcriptText.setText(R.string.transcript_placeholder);
        } else {
            transcriptText.setText(builder.toString());
        }
    }

    private void updateButtons() {
        startStopButton.setText(sessionActive || recognizerBusy
                ? R.string.action_stop
                : R.string.action_start);

        boolean hasTranscript = !TextUtils.isEmpty(committedTranscript);
        copyButton.setEnabled(hasTranscript);
        saveButton.setEnabled(hasTranscript);
        clearButton.setEnabled(hasTranscript || !TextUtils.isEmpty(partialTranscript));
    }

    private void updateStatus(String status) {
        statusText.setText(status);
    }

    private void updateEngineStatus() {
        boolean onDeviceReady = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(this);

        if (onDeviceReady) {
            engineText.setText(R.string.engine_on_device_ready);
        } else {
            engineText.setText(R.string.engine_system_offline);
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && resumeAfterPermission) {
            resumeAfterPermission = false;
            startRecognition();
        } else {
            resumeAfterPermission = false;
            updateStatus(getString(R.string.status_permission_denied));
        }
    }
}
