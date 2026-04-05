package com.whispercppdemo.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TranscriptionSnapshot(
    val isRunning: Boolean,
    val isPaused: Boolean,
    val progressFraction: Float?,
    val statusText: String,
    val sourceName: String,
    val transcriptText: String,
    val activityLog: String,
    val lastUpdatedAt: Long?,
    val elapsedMs: Long,
    val estimatedRemainingMs: Long?,
)

data class ResumeSnapshot(
    val modelPath: String?,
    val audioPath: String?,
    val sourceName: String?,
    val languageModeKey: String?,
    val chunkDurationSeconds: Int,
    val completedChunks: Int,
    val totalChunks: Int,
    val totalSamples: Int,
    val chunkSizeSamples: Int,
) {
    val canResume: Boolean
        get() = !modelPath.isNullOrBlank() &&
            !audioPath.isNullOrBlank() &&
            !languageModeKey.isNullOrBlank() &&
            chunkDurationSeconds > 0 &&
            completedChunks in 0 until totalChunks &&
            totalSamples > 0 &&
            chunkSizeSamples > 0
}

object AppPreferences {
    private const val PREFS_NAME = "whisper_cpp_demo_prefs"
    private const val MAX_LOG_LINES = 48
    private const val MAX_LOG_CHARS = 4_000
    private const val KEY_DARK_THEME = "dark_theme"
    private const val KEY_SELECTED_MODEL = "selected_model"
    private const val KEY_SELECTED_AUDIO_PATH = "selected_audio_path"
    private const val KEY_SELECTED_AUDIO_NAME = "selected_audio_name"
    private const val KEY_SELECTED_LANGUAGE = "selected_language"
    private const val KEY_SELECTED_CHUNK_DURATION = "selected_chunk_duration"
    private const val KEY_ACTIVE_REQUEST_ID = "active_request_id"
    private const val KEY_IS_RUNNING = "transcription_running"
    private const val KEY_IS_PAUSED = "transcription_paused"
    private const val KEY_PAUSE_REQUESTED = "transcription_pause_requested"
    private const val KEY_PROGRESS = "transcription_progress"
    private const val KEY_STATUS = "transcription_status"
    private const val KEY_SOURCE_NAME = "transcription_source_name"
    private const val KEY_TRANSCRIPT = "transcription_transcript"
    private const val KEY_ACTIVITY_LOG = "activity_log"
    private const val KEY_LAST_UPDATED_AT = "transcription_last_updated_at"
    private const val KEY_ELAPSED_MS = "transcription_elapsed_ms"
    private const val KEY_ESTIMATED_REMAINING_MS = "transcription_estimated_remaining_ms"
    private const val KEY_RESUME_MODEL_PATH = "resume_model_path"
    private const val KEY_RESUME_AUDIO_PATH = "resume_audio_path"
    private const val KEY_RESUME_SOURCE_NAME = "resume_source_name"
    private const val KEY_RESUME_LANGUAGE = "resume_language"
    private const val KEY_RESUME_CHUNK_DURATION = "resume_chunk_duration"
    private const val KEY_RESUME_COMPLETED_CHUNKS = "resume_completed_chunks"
    private const val KEY_RESUME_TOTAL_CHUNKS = "resume_total_chunks"
    private const val KEY_RESUME_TOTAL_SAMPLES = "resume_total_samples"
    private const val KEY_RESUME_CHUNK_SIZE = "resume_chunk_size"

    fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isDarkThemeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DARK_THEME, false)
    }

    fun setDarkThemeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK_THEME, enabled).apply()
    }

    fun selectedModelName(context: Context): String? {
        return prefs(context).getString(KEY_SELECTED_MODEL, null)
    }

    fun setSelectedModelName(context: Context, modelName: String?) {
        prefs(context).edit().putString(KEY_SELECTED_MODEL, modelName).apply()
    }

    fun selectedAudioPath(context: Context): String? {
        return prefs(context).getString(KEY_SELECTED_AUDIO_PATH, null)
    }

    fun selectedAudioName(context: Context): String? {
        return prefs(context).getString(KEY_SELECTED_AUDIO_NAME, null)
    }

    fun setSelectedAudio(context: Context, audioPath: String?, audioName: String?) {
        prefs(context).edit()
            .putString(KEY_SELECTED_AUDIO_PATH, audioPath)
            .putString(KEY_SELECTED_AUDIO_NAME, audioName)
            .apply()
    }

    fun selectedLanguageMode(context: Context): String? {
        return prefs(context).getString(KEY_SELECTED_LANGUAGE, null)
    }

    fun setSelectedLanguageMode(context: Context, languageMode: String) {
        prefs(context).edit().putString(KEY_SELECTED_LANGUAGE, languageMode).apply()
    }

    fun selectedChunkDurationSeconds(context: Context): Int? {
        val prefs = prefs(context)
        return if (prefs.contains(KEY_SELECTED_CHUNK_DURATION)) {
            prefs.getInt(KEY_SELECTED_CHUNK_DURATION, 0)
        } else {
            null
        }
    }

    fun setSelectedChunkDurationSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_SELECTED_CHUNK_DURATION, seconds).apply()
    }

    fun activeRequestId(context: Context): Long {
        return prefs(context).getLong(KEY_ACTIVE_REQUEST_ID, 0L)
    }

    fun setActiveRequestId(context: Context, requestId: Long) {
        prefs(context).edit().putLong(KEY_ACTIVE_REQUEST_ID, requestId).apply()
    }

    fun isPauseRequested(context: Context, requestId: Long? = null): Boolean {
        if (requestId != null && activeRequestId(context) != requestId) {
            return false
        }
        return prefs(context).getBoolean(KEY_PAUSE_REQUESTED, false)
    }

    fun setPauseRequested(context: Context, requested: Boolean, requestId: Long? = null) {
        if (requestId != null && activeRequestId(context) != requestId) {
            return
        }
        prefs(context).edit().putBoolean(KEY_PAUSE_REQUESTED, requested).apply()
    }

    fun readSnapshot(context: Context): TranscriptionSnapshot {
        val prefs = prefs(context)
        val progress = if (prefs.contains(KEY_PROGRESS)) {
            prefs.getFloat(KEY_PROGRESS, 0f)
        } else {
            null
        }
        val estimatedRemaining = if (prefs.contains(KEY_ESTIMATED_REMAINING_MS)) {
            prefs.getLong(KEY_ESTIMATED_REMAINING_MS, 0L)
        } else {
            null
        }

        return TranscriptionSnapshot(
            isRunning = prefs.getBoolean(KEY_IS_RUNNING, false),
            isPaused = prefs.getBoolean(KEY_IS_PAUSED, false),
            progressFraction = progress,
            statusText = prefs.getString(KEY_STATUS, "") ?: "",
            sourceName = prefs.getString(KEY_SOURCE_NAME, "") ?: "",
            transcriptText = prefs.getString(KEY_TRANSCRIPT, "") ?: "",
            activityLog = prefs.getString(KEY_ACTIVITY_LOG, "") ?: "",
            lastUpdatedAt = if (prefs.contains(KEY_LAST_UPDATED_AT)) {
                prefs.getLong(KEY_LAST_UPDATED_AT, 0L)
            } else {
                null
            },
            elapsedMs = prefs.getLong(KEY_ELAPSED_MS, 0L),
            estimatedRemainingMs = estimatedRemaining,
        )
    }

    fun readResumeSnapshot(context: Context): ResumeSnapshot {
        val prefs = prefs(context)
        return ResumeSnapshot(
            modelPath = prefs.getString(KEY_RESUME_MODEL_PATH, null),
            audioPath = prefs.getString(KEY_RESUME_AUDIO_PATH, null),
            sourceName = prefs.getString(KEY_RESUME_SOURCE_NAME, null),
            languageModeKey = prefs.getString(KEY_RESUME_LANGUAGE, null),
            chunkDurationSeconds = prefs.getInt(KEY_RESUME_CHUNK_DURATION, 0),
            completedChunks = prefs.getInt(KEY_RESUME_COMPLETED_CHUNKS, 0),
            totalChunks = prefs.getInt(KEY_RESUME_TOTAL_CHUNKS, 0),
            totalSamples = prefs.getInt(KEY_RESUME_TOTAL_SAMPLES, 0),
            chunkSizeSamples = prefs.getInt(KEY_RESUME_CHUNK_SIZE, 0),
        )
    }

    fun updateTranscriptionState(
        context: Context,
        isRunning: Boolean,
        progressFraction: Float?,
        statusText: String,
        sourceName: String? = null,
        transcriptText: String? = null,
        requestId: Long? = null,
        isPaused: Boolean? = null,
        elapsedMs: Long? = null,
        estimatedRemainingMs: Long? = null,
        clearEstimatedRemaining: Boolean = false,
    ) {
        if (requestId != null && activeRequestId(context) != requestId) {
            return
        }

        val editor = prefs(context).edit()
            .putBoolean(KEY_IS_RUNNING, isRunning)
            .putString(KEY_STATUS, statusText)
            .putLong(KEY_LAST_UPDATED_AT, System.currentTimeMillis())

        if (isPaused != null) {
            editor.putBoolean(KEY_IS_PAUSED, isPaused)
        }

        if (progressFraction == null) {
            editor.remove(KEY_PROGRESS)
        } else {
            editor.putFloat(KEY_PROGRESS, progressFraction.coerceIn(0f, 1f))
        }

        if (elapsedMs != null) {
            editor.putLong(KEY_ELAPSED_MS, elapsedMs.coerceAtLeast(0L))
        }

        if (estimatedRemainingMs != null) {
            editor.putLong(KEY_ESTIMATED_REMAINING_MS, estimatedRemainingMs.coerceAtLeast(0L))
        } else if (clearEstimatedRemaining) {
            editor.remove(KEY_ESTIMATED_REMAINING_MS)
        }

        if (sourceName != null) {
            editor.putString(KEY_SOURCE_NAME, sourceName)
        }

        if (transcriptText != null) {
            editor.putString(KEY_TRANSCRIPT, transcriptText)
        }

        editor.apply()
    }

    fun saveResumeSnapshot(
        context: Context,
        modelPath: String,
        audioPath: String,
        sourceName: String,
        languageModeKey: String,
        chunkDurationSeconds: Int,
        completedChunks: Int,
        totalChunks: Int,
        totalSamples: Int,
        chunkSizeSamples: Int,
    ) {
        prefs(context).edit()
            .putString(KEY_RESUME_MODEL_PATH, modelPath)
            .putString(KEY_RESUME_AUDIO_PATH, audioPath)
            .putString(KEY_RESUME_SOURCE_NAME, sourceName)
            .putString(KEY_RESUME_LANGUAGE, languageModeKey)
            .putInt(KEY_RESUME_CHUNK_DURATION, chunkDurationSeconds)
            .putInt(KEY_RESUME_COMPLETED_CHUNKS, completedChunks)
            .putInt(KEY_RESUME_TOTAL_CHUNKS, totalChunks)
            .putInt(KEY_RESUME_TOTAL_SAMPLES, totalSamples)
            .putInt(KEY_RESUME_CHUNK_SIZE, chunkSizeSamples)
            .apply()
    }

    fun clearResumeSnapshot(context: Context) {
        prefs(context).edit()
            .remove(KEY_RESUME_MODEL_PATH)
            .remove(KEY_RESUME_AUDIO_PATH)
            .remove(KEY_RESUME_SOURCE_NAME)
            .remove(KEY_RESUME_LANGUAGE)
            .remove(KEY_RESUME_CHUNK_DURATION)
            .remove(KEY_RESUME_COMPLETED_CHUNKS)
            .remove(KEY_RESUME_TOTAL_CHUNKS)
            .remove(KEY_RESUME_TOTAL_SAMPLES)
            .remove(KEY_RESUME_CHUNK_SIZE)
            .putBoolean(KEY_PAUSE_REQUESTED, false)
            .apply()
    }

    fun appendLog(context: Context, message: String) {
        val prefs = prefs(context)
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$timestamp] $message"
        val current = prefs.getString(KEY_ACTIVITY_LOG, "") ?: ""
        val updated = buildList {
            addAll(current.lineSequence().filter { it.isNotBlank() }.toList())
            add(line)
        }
            .takeLast(MAX_LOG_LINES)
            .joinToString("\n")
            .takeLast(MAX_LOG_CHARS)
        prefs.edit().putString(KEY_ACTIVITY_LOG, updated).apply()
    }

    fun clearLog(context: Context) {
        prefs(context).edit().remove(KEY_ACTIVITY_LOG).apply()
    }
}
