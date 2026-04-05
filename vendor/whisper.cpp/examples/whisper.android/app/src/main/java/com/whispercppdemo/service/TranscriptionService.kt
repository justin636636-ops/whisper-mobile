package com.whispercppdemo.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.icu.text.Transliterator
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.whispercpp.whisper.WhisperContext
import com.whispercppdemo.MainActivity
import com.whispercppdemo.R
import com.whispercppdemo.data.AppPreferences
import com.whispercppdemo.data.ResumeSnapshot
import com.whispercppdemo.media.decodeAudioFile
import com.whispercppdemo.transcription.findLanguageOption
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CHANNEL_ID = "whisper_transcription_channel"
private const val NOTIFICATION_ID = 4107
private const val SAMPLE_RATE = 16_000
private const val OVERLAP_TRIM_BUFFER_MS = 150L
private const val SETUP_READY_PROGRESS = 0.18f
private const val TRANSCRIPTION_PROGRESS_RANGE = 0.77f

class TranscriptionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    @Volatile
    private var activeRequestId: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH)
        val audioPath = intent?.getStringExtra(EXTRA_AUDIO_PATH)
        val sourceName = intent?.getStringExtra(EXTRA_SOURCE_NAME)
        val languageModeKey = intent?.getStringExtra(EXTRA_LANGUAGE_MODE) ?: "zh"
        val chunkDurationSeconds = intent?.getIntExtra(EXTRA_CHUNK_DURATION_SECONDS, 30)?.takeIf { it > 0 } ?: 30
        val resumeRequested = intent?.getBooleanExtra(EXTRA_RESUME, false) == true
        val requestId = intent?.getLongExtra(EXTRA_REQUEST_ID, 0L)?.takeIf { it != 0L } ?: System.currentTimeMillis()

        if (modelPath.isNullOrBlank() || audioPath.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                sourceName = sourceName ?: File(audioPath).name,
                statusText = if (resumeRequested) "正在恢复后台转换" else "已进入后台转换队列",
                progressPercent = 2,
            )
        )

        activeRequestId = requestId
        activeJob?.cancel()
        activeJob = serviceScope.launch {
            runTranscription(
                audioPath = audioPath,
                modelPath = modelPath,
                sourceName = sourceName ?: File(audioPath).name,
                languageModeKey = languageModeKey,
                chunkDurationSeconds = chunkDurationSeconds,
                resumeRequested = resumeRequested,
                requestId = requestId,
            )
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runTranscription(
        audioPath: String,
        modelPath: String,
        sourceName: String,
        languageModeKey: String,
        chunkDurationSeconds: Int,
        resumeRequested: Boolean,
        requestId: Long,
    ) {
        val modelFile = File(modelPath)
        val audioFile = File(audioPath)
        val languageOption = findLanguageOption(languageModeKey)
        val chunkSizeSamples = (SAMPLE_RATE * chunkDurationSeconds).coerceAtLeast(SAMPLE_RATE * 10)
        val chunkOverlapSamples = (SAMPLE_RATE * overlapSecondsForChunk(chunkDurationSeconds))
            .coerceAtMost(chunkSizeSamples / 3)
            .coerceAtLeast(SAMPLE_RATE * 2)
        val decodedCacheDir = File(applicationContext.cacheDir, "decoded-audio")
        var whisperContext: WhisperContext? = null
        val snapshotAtRunStart = AppPreferences.readSnapshot(applicationContext)
        var baseElapsedMs = if (resumeRequested) snapshotAtRunStart.elapsedMs else 0L
        val runStartedAtMs = System.currentTimeMillis()

        if (!modelFile.exists() || !audioFile.exists()) {
            failTask("文件不存在，无法开始转写", sourceName, requestId)
            return
        }

        try {
            updateProgressState(
                sourceName = sourceName,
                statusText = if (resumeRequested) "正在恢复后台转换" else "已进入后台转换队列",
                progressFraction = 0.03f,
                requestId = requestId,
                baseElapsedMs = baseElapsedMs,
                runStartedAtMs = runStartedAtMs,
                allowEstimate = false,
            )
            AppPreferences.appendLog(applicationContext, "开始处理 $sourceName")

            updateProgressState(
                sourceName,
                "正在加载模型 ${modelFile.name}",
                0.08f,
                requestId,
                baseElapsedMs,
                runStartedAtMs,
                false,
            )
            whisperContext = withContext(Dispatchers.IO) {
                WhisperContext.createContextFromFile(modelFile.absolutePath)
            }
            AppPreferences.appendLog(applicationContext, "模型 ${modelFile.name} 已加载")

            updateProgressState(
                sourceName,
                "正在解码音频文件",
                0.12f,
                requestId,
                baseElapsedMs,
                runStartedAtMs,
                false,
            )
            val decodedAudio = withContext(Dispatchers.IO) {
                decodeAudioFile(audioFile, decodedCacheDir) { decodeFraction ->
                    val mappedProgress = 0.12f + (0.06f * decodeFraction.coerceIn(0f, 1f))
                    updateProgressState(
                        sourceName,
                        "正在解码音频文件",
                        mappedProgress,
                        requestId,
                        baseElapsedMs,
                        runStartedAtMs,
                        false,
                    )
                }
            }
            val audioSamples = decodedAudio.samples
            val audioDurationMs = (audioSamples.size * 1000L / SAMPLE_RATE.toLong()).coerceAtLeast(1_000L)
            AppPreferences.appendLog(
                applicationContext,
                "音频解码完成，时长约 ${audioDurationMs / 1000}s"
            )
            AppPreferences.appendLog(
                applicationContext,
                if (decodedAudio.cacheHit) "命中音频解码缓存，直接复用上次结果" else "已更新音频解码缓存"
            )

            val totalChunks = ceil(audioSamples.size / chunkSizeSamples.toDouble()).toInt().coerceAtLeast(1)
            val resumeSnapshot = AppPreferences.readResumeSnapshot(applicationContext)
            val shouldResume = resumeRequested && resumeSnapshot.matches(
                modelPath = modelPath,
                audioPath = audioPath,
                languageModeKey = languageOption.key,
                chunkDurationSeconds = chunkDurationSeconds,
                totalChunks = totalChunks,
                totalSamples = audioSamples.size,
                chunkSizeSamples = chunkSizeSamples,
            )
            var completedChunks = if (shouldResume) resumeSnapshot.completedChunks else 0
            if (!shouldResume) {
                baseElapsedMs = 0L
            }
            var accumulatedTranscript = if (shouldResume) {
                AppPreferences.readSnapshot(applicationContext).transcriptText
            } else {
                ""
            }

            AppPreferences.saveResumeSnapshot(
                context = applicationContext,
                modelPath = modelPath,
                audioPath = audioPath,
                sourceName = sourceName,
                languageModeKey = languageOption.key,
                chunkDurationSeconds = chunkDurationSeconds,
                completedChunks = completedChunks,
                totalChunks = totalChunks,
                totalSamples = audioSamples.size,
                chunkSizeSamples = chunkSizeSamples,
            )
            if (!shouldResume) {
                AppPreferences.updateTranscriptionState(
                    context = applicationContext,
                    isRunning = true,
                    progressFraction = SETUP_READY_PROGRESS,
                    statusText = "准备分段转写 1/$totalChunks",
                    sourceName = sourceName,
                    transcriptText = "",
                    requestId = requestId,
                    isPaused = false,
                    elapsedMs = currentElapsedMs(baseElapsedMs, runStartedAtMs),
                    clearEstimatedRemaining = true,
                )
            } else {
                AppPreferences.appendLog(
                    applicationContext,
                    "继续从第 ${completedChunks + 1}/$totalChunks 段开始转写"
                )
            }

            val initialProgress = transcriptionProgress(completedChunks, totalChunks)
            if (maybePauseTranscription(
                    sourceName = sourceName,
                    requestId = requestId,
                    progressFraction = initialProgress,
                    transcriptText = accumulatedTranscript,
                    baseElapsedMs = baseElapsedMs,
                    runStartedAtMs = runStartedAtMs,
                )
            ) {
                return
            }

            while (completedChunks < totalChunks) {
                val logicalChunkStart = completedChunks * chunkSizeSamples
                val chunkStart = maxOf(0, logicalChunkStart - chunkOverlapSamples)
                val chunkEnd = minOf(logicalChunkStart + chunkSizeSamples, audioSamples.size)
                val chunkIndex = completedChunks + 1
                val chunkData = audioSamples.copyOfRange(chunkStart, chunkEnd)

                updateProgressState(
                    sourceName = sourceName,
                    statusText = "正在转写 第 $chunkIndex/$totalChunks 段",
                    progressFraction = transcriptionProgress(chunkIndex - 1, totalChunks),
                    requestId = requestId,
                    baseElapsedMs = baseElapsedMs,
                    runStartedAtMs = runStartedAtMs,
                    allowEstimate = completedChunks > 0,
                )

                val chunkTranscript = whisperContext.transcribeData(
                    data = chunkData,
                    language = languageOption.nativeLanguage,
                    detectLanguage = languageOption.detectLanguage,
                )
                    .let { offsetTranscriptTimestamps(it, chunkStart) }
                    .let { trimTranscriptBeforeBoundary(it, logicalChunkStart) }
                    .let {
                        if (languageOption.normalizeToSimplified) simplifyChineseText(it) else it
                    }
                    .trim()

                accumulatedTranscript = mergeChunkTranscript(accumulatedTranscript, chunkTranscript)
                completedChunks = chunkIndex

                AppPreferences.saveResumeSnapshot(
                    context = applicationContext,
                    modelPath = modelPath,
                    audioPath = audioPath,
                    sourceName = sourceName,
                    languageModeKey = languageOption.key,
                    chunkDurationSeconds = chunkDurationSeconds,
                    completedChunks = completedChunks,
                    totalChunks = totalChunks,
                    totalSamples = audioSamples.size,
                    chunkSizeSamples = chunkSizeSamples,
                )
                AppPreferences.updateTranscriptionState(
                    context = applicationContext,
                    isRunning = true,
                    progressFraction = transcriptionProgress(completedChunks, totalChunks),
                    statusText = "已完成第 $completedChunks/$totalChunks 段",
                    sourceName = sourceName,
                    transcriptText = accumulatedTranscript,
                    requestId = requestId,
                    isPaused = false,
                    elapsedMs = currentElapsedMs(baseElapsedMs, runStartedAtMs),
                    estimatedRemainingMs = estimateRemainingMs(
                        transcriptionProgress(completedChunks, totalChunks),
                        currentElapsedMs(baseElapsedMs, runStartedAtMs),
                    ),
                )
                AppPreferences.appendLog(
                    applicationContext,
                    "已完成第 $completedChunks/$totalChunks 段"
                )
                if (maybePauseTranscription(
                        sourceName = sourceName,
                        requestId = requestId,
                        progressFraction = transcriptionProgress(completedChunks, totalChunks),
                        transcriptText = accumulatedTranscript,
                        baseElapsedMs = baseElapsedMs,
                        runStartedAtMs = runStartedAtMs,
                    )
                ) {
                    return
                }
            }

            val finalElapsedMs = currentElapsedMs(baseElapsedMs, runStartedAtMs)
            AppPreferences.appendLog(applicationContext, "转写完成，共 $totalChunks 段")
            AppPreferences.clearResumeSnapshot(applicationContext)
            AppPreferences.updateTranscriptionState(
                context = applicationContext,
                isRunning = false,
                progressFraction = 1.0f,
                statusText = "转写完成",
                sourceName = sourceName,
                transcriptText = accumulatedTranscript,
                requestId = requestId,
                isPaused = false,
                elapsedMs = finalElapsedMs,
                clearEstimatedRemaining = true,
            )
            updateNotification(sourceName, "转写完成", 100)
        } catch (_: CancellationException) {
            if (requestId == AppPreferences.activeRequestId(applicationContext)) {
                AppPreferences.appendLog(applicationContext, "当前转写任务已取消")
            }
        } catch (error: Exception) {
            val resumeSnapshot = AppPreferences.readResumeSnapshot(applicationContext)
            val checkpointLabel = if (resumeSnapshot.totalChunks > 0) {
                "，已保存到第 ${resumeSnapshot.completedChunks}/${resumeSnapshot.totalChunks} 段"
            } else {
                ""
            }
            AppPreferences.appendLog(
                applicationContext,
                "转写中断：${error.localizedMessage ?: error.javaClass.simpleName}$checkpointLabel"
            )
            AppPreferences.updateTranscriptionState(
                context = applicationContext,
                isRunning = false,
                progressFraction = AppPreferences.readSnapshot(applicationContext).progressFraction,
                statusText = "转写中断，下次可继续",
                sourceName = sourceName,
                transcriptText = AppPreferences.readSnapshot(applicationContext).transcriptText,
                requestId = requestId,
                isPaused = false,
                elapsedMs = currentElapsedMs(baseElapsedMs, runStartedAtMs),
                clearEstimatedRemaining = true,
            )
            updateNotification(sourceName, "转写中断，可继续", 0)
        } finally {
            whisperContext?.release()
            if (requestId == activeRequestId) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun failTask(statusText: String, sourceName: String, requestId: Long) {
        AppPreferences.updateTranscriptionState(
            context = applicationContext,
            isRunning = false,
            progressFraction = null,
            statusText = statusText,
            sourceName = sourceName,
            requestId = requestId,
            isPaused = false,
            elapsedMs = 0L,
            clearEstimatedRemaining = true,
        )
        AppPreferences.appendLog(applicationContext, statusText)
        AppPreferences.clearResumeSnapshot(applicationContext)
        if (requestId == activeRequestId) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun updateProgressState(
        sourceName: String,
        statusText: String,
        progressFraction: Float,
        requestId: Long,
        baseElapsedMs: Long,
        runStartedAtMs: Long,
        allowEstimate: Boolean,
    ) {
        val elapsedMs = currentElapsedMs(baseElapsedMs, runStartedAtMs)
        AppPreferences.updateTranscriptionState(
            context = applicationContext,
            isRunning = true,
            progressFraction = progressFraction,
            statusText = statusText,
            sourceName = sourceName,
            requestId = requestId,
            isPaused = false,
            elapsedMs = elapsedMs,
            estimatedRemainingMs = if (allowEstimate) estimateRemainingMs(progressFraction, elapsedMs) else null,
            clearEstimatedRemaining = !allowEstimate,
        )
        updateNotification(sourceName, statusText, (progressFraction * 100).toInt())
    }

    private fun maybePauseTranscription(
        sourceName: String,
        requestId: Long,
        progressFraction: Float,
        transcriptText: String,
        baseElapsedMs: Long,
        runStartedAtMs: Long,
    ): Boolean {
        if (!AppPreferences.isPauseRequested(applicationContext, requestId)) {
            return false
        }

        val elapsedMs = currentElapsedMs(baseElapsedMs, runStartedAtMs)
        AppPreferences.setPauseRequested(applicationContext, false, requestId)
        AppPreferences.updateTranscriptionState(
            context = applicationContext,
            isRunning = false,
            progressFraction = progressFraction,
            statusText = "已暂停，可继续",
            sourceName = sourceName,
            transcriptText = transcriptText,
            requestId = requestId,
            isPaused = true,
            elapsedMs = elapsedMs,
            estimatedRemainingMs = estimateRemainingMs(progressFraction, elapsedMs),
        )
        AppPreferences.appendLog(applicationContext, "后台转写已暂停")
        updateNotification(sourceName, "已暂停，可继续", (progressFraction * 100).toInt())
        return true
    }

    private fun updateNotification(
        sourceName: String,
        statusText: String,
        progressPercent: Int,
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(sourceName, statusText, progressPercent.coerceIn(0, 100))
        )
    }

    private fun buildNotification(
        sourceName: String,
        statusText: String,
        progressPercent: Int,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Whisper 正在后台转写")
        .setContentText("$sourceName - $statusText")
        .setStyle(NotificationCompat.BigTextStyle().bigText("$sourceName - $statusText"))
        .setOnlyAlertOnce(true)
        .setOngoing(progressPercent in 0..99)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .setProgress(100, progressPercent, false)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Whisper 后台转写",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示离线语音转写的后台进度"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val EXTRA_MODEL_PATH = "model_path"
        private const val EXTRA_AUDIO_PATH = "audio_path"
        private const val EXTRA_SOURCE_NAME = "source_name"
        private const val EXTRA_LANGUAGE_MODE = "language_mode"
        private const val EXTRA_CHUNK_DURATION_SECONDS = "chunk_duration_seconds"
        private const val EXTRA_RESUME = "resume"
        private const val EXTRA_REQUEST_ID = "request_id"

        fun createIntent(
            context: Context,
            modelPath: String,
            audioPath: String,
            sourceName: String,
            languageModeKey: String,
            chunkDurationSeconds: Int,
            resumeRequested: Boolean,
            requestId: Long,
        ): Intent {
            return Intent(context, TranscriptionService::class.java).apply {
                putExtra(EXTRA_MODEL_PATH, modelPath)
                putExtra(EXTRA_AUDIO_PATH, audioPath)
                putExtra(EXTRA_SOURCE_NAME, sourceName)
                putExtra(EXTRA_LANGUAGE_MODE, languageModeKey)
                putExtra(EXTRA_CHUNK_DURATION_SECONDS, chunkDurationSeconds)
                putExtra(EXTRA_RESUME, resumeRequested)
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
        }

        fun start(
            context: Context,
            modelPath: String,
            audioPath: String,
            sourceName: String,
            languageModeKey: String,
            chunkDurationSeconds: Int,
            resumeRequested: Boolean = false,
            requestId: Long,
        ) {
            ContextCompat.startForegroundService(
                context,
                createIntent(
                    context,
                    modelPath,
                    audioPath,
                    sourceName,
                    languageModeKey,
                    chunkDurationSeconds,
                    resumeRequested,
                    requestId,
                ),
            )
        }

        private fun transcriptionProgress(completedChunks: Int, totalChunks: Int): Float {
            val normalized = if (totalChunks <= 0) 0f else completedChunks.toFloat() / totalChunks.toFloat()
            return SETUP_READY_PROGRESS + (TRANSCRIPTION_PROGRESS_RANGE * normalized.coerceIn(0f, 1f))
        }

        private fun currentElapsedMs(baseElapsedMs: Long, runStartedAtMs: Long): Long {
            return baseElapsedMs + (System.currentTimeMillis() - runStartedAtMs).coerceAtLeast(0L)
        }

        private fun estimateRemainingMs(progressFraction: Float, elapsedMs: Long): Long? {
            val safeProgress = progressFraction.coerceIn(0f, 1f)
            if (safeProgress <= (SETUP_READY_PROGRESS + 0.03f) || elapsedMs <= 0L) {
                return null
            }
            val totalEstimateMs = (elapsedMs / safeProgress).toLong()
            return (totalEstimateMs - elapsedMs).coerceAtLeast(0L)
        }

        private fun overlapSecondsForChunk(chunkDurationSeconds: Int): Int {
            return when {
                chunkDurationSeconds <= 10 -> 2
                chunkDurationSeconds <= 15 -> 3
                chunkDurationSeconds <= 20 -> 4
                else -> 5
            }
        }

        private fun simplifyChineseText(text: String): String {
            return try {
                Transliterator.getInstance("Traditional-Simplified").transliterate(text)
            } catch (_: Exception) {
                text
            }
        }

        private fun offsetTranscriptTimestamps(transcript: String, chunkStartSample: Int): String {
            if (transcript.isBlank()) {
                return transcript
            }
            val offsetMs = (chunkStartSample * 1000L) / SAMPLE_RATE
            return transcript.lineSequence()
                .filter { it.isNotBlank() }
                .joinToString("\n") { line ->
                    if (!line.startsWith("[")) {
                        return@joinToString line
                    }
                    val separator = "]: "
                    val endBracket = line.indexOf(separator)
                    if (endBracket < 0) {
                        return@joinToString line
                    }
                    val header = line.substring(1, endBracket)
                    val text = line.substring(endBracket + separator.length)
                    val parts = header.split(" --> ")
                    if (parts.size != 2) {
                        return@joinToString line
                    }
                    "[${shiftTimestamp(parts[0], offsetMs)} --> ${shiftTimestamp(parts[1], offsetMs)}]: $text"
                }
        }

        private fun trimTranscriptBeforeBoundary(transcript: String, logicalChunkStartSample: Int): String {
            if (transcript.isBlank() || logicalChunkStartSample <= 0) {
                return transcript
            }

            val boundaryMs = logicalChunkStartSample * 1000L / SAMPLE_RATE
            return parseTranscriptLines(transcript)
                .filter { line ->
                    line.endMs == null || line.endMs > (boundaryMs + OVERLAP_TRIM_BUFFER_MS)
                }
                .joinToString("\n") { it.render() }
        }

        private fun mergeChunkTranscript(existing: String, incoming: String): String {
            if (incoming.isBlank()) {
                return existing
            }
            if (existing.isBlank()) {
                return incoming.trim()
            }

            val merged = parseTranscriptLines(existing).toMutableList()
            val incomingLines = parseTranscriptLines(incoming).toMutableList()
            if (incomingLines.isEmpty()) {
                return existing.trim()
            }

            if (merged.isNotEmpty() && incomingLines.isNotEmpty()) {
                val overlapCount = findOverlapCount(merged, incomingLines)
                if (overlapCount > 0) {
                    repeat(overlapCount) {
                        incomingLines.removeAt(0)
                    }
                } else if (shouldReplaceTail(merged.last(), incomingLines.first())) {
                    merged.removeAt(merged.lastIndex)
                } else {
                    mergeBoundaryLines(merged.last(), incomingLines.first())?.let { mergedLine ->
                        merged[merged.lastIndex] = mergedLine
                        incomingLines.removeAt(0)
                    }
                }
            }

            val combined = merged + incomingLines
            return combined
                .map { it.render() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .trim()
        }

        private fun findOverlapCount(
            existingLines: List<TranscriptLine>,
            incomingLines: List<TranscriptLine>,
        ): Int {
            val maxWindow = minOf(existingLines.size, incomingLines.size, 4)
            for (windowSize in maxWindow downTo 1) {
                val existingTail = existingLines.takeLast(windowSize)
                val incomingHead = incomingLines.take(windowSize)
                if (existingTail.indices.all { index ->
                        linesAreEquivalent(existingTail[index], incomingHead[index])
                    }
                ) {
                    return windowSize
                }
            }
            return 0
        }

        private fun shouldReplaceTail(existing: TranscriptLine, incoming: TranscriptLine): Boolean {
            if (existing.normalizedText.isBlank() || incoming.normalizedText.isBlank()) {
                return false
            }
            if (!timestampsLikelyOverlap(existing, incoming)) {
                return false
            }
            val similarity = normalizedSimilarity(existing.normalizedText, incoming.normalizedText)
            return incoming.normalizedText.length > existing.normalizedText.length &&
                (incoming.normalizedText.contains(existing.normalizedText) || similarity >= 0.82f)
        }

        private fun linesAreEquivalent(first: TranscriptLine, second: TranscriptLine): Boolean {
            if (first.normalizedText.isBlank() || second.normalizedText.isBlank()) {
                return false
            }
            if (!timestampsLikelyOverlap(first, second)) {
                return false
            }
            return first.normalizedText == second.normalizedText ||
                first.normalizedText.contains(second.normalizedText) ||
                second.normalizedText.contains(first.normalizedText) ||
                normalizedSimilarity(first.normalizedText, second.normalizedText) >= 0.84f
        }

        private fun mergeBoundaryLines(
            existing: TranscriptLine,
            incoming: TranscriptLine,
        ): TranscriptLine? {
            if (existing.normalizedText.isBlank() || incoming.normalizedText.isBlank()) {
                return null
            }
            if (!timestampsLikelyOverlap(existing, incoming)) {
                return null
            }

            val overlapLen = findNormalizedBoundaryOverlap(existing.normalizedText, incoming.normalizedText)
            val minLen = minOf(existing.normalizedText.length, incoming.normalizedText.length)
            val minRequiredOverlap = when {
                minLen >= 24 -> 8
                minLen >= 16 -> 6
                minLen >= 10 -> 4
                else -> 3
            }
            if (overlapLen < minRequiredOverlap && overlapLen * 2 < minLen) {
                return null
            }

            val remainingIncomingText = trimTextByNormalizedPrefix(incoming.text, overlapLen).trimStart()
            if (remainingIncomingText.isBlank()) {
                return existing.withText(
                    text = existing.text,
                    endMs = maxOf(existing.endMs ?: 0L, incoming.endMs ?: 0L).takeIf { it > 0L } ?: existing.endMs,
                )
            }

            val mergedText = buildString {
                append(existing.text.trimEnd())
                if (shouldInsertSpaceBetween(existing.text, remainingIncomingText)) {
                    append(' ')
                }
                append(remainingIncomingText)
            }

            val mergedEndMs = maxOf(existing.endMs ?: 0L, incoming.endMs ?: 0L).takeIf { it > 0L }
            return existing.withText(
                text = mergedText,
                endMs = mergedEndMs ?: existing.endMs,
            )
        }

        private fun timestampsLikelyOverlap(first: TranscriptLine, second: TranscriptLine): Boolean {
            val firstStart = first.startMs ?: return true
            val firstEnd = first.endMs ?: return true
            val secondStart = second.startMs ?: return true
            val secondEnd = second.endMs ?: return true
            val overlapMs = minOf(firstEnd, secondEnd) - maxOf(firstStart, secondStart)
            return overlapMs >= -400L
        }

        private fun shiftTimestamp(original: String, offsetMs: Long): String {
            val pieces = original.split(":", ".")
            if (pieces.size != 4) {
                return original
            }
            val hour = pieces[0].toLongOrNull() ?: return original
            val minute = pieces[1].toLongOrNull() ?: return original
            val second = pieces[2].toLongOrNull() ?: return original
            val millis = pieces[3].toLongOrNull() ?: return original
            val totalMs = (((hour * 60 + minute) * 60) + second) * 1000 + millis + offsetMs
            val newHour = totalMs / 3_600_000
            val newMinute = (totalMs % 3_600_000) / 60_000
            val newSecond = (totalMs % 60_000) / 1_000
            val newMillis = totalMs % 1_000
            return String.format("%02d:%02d:%02d.%03d", newHour, newMinute, newSecond, newMillis)
        }

        private fun formatTimestampMs(totalMs: Long): String {
            val safeMs = totalMs.coerceAtLeast(0L)
            val newHour = safeMs / 3_600_000
            val newMinute = (safeMs % 3_600_000) / 60_000
            val newSecond = (safeMs % 60_000) / 1_000
            val newMillis = safeMs % 1_000
            return String.format("%02d:%02d:%02d.%03d", newHour, newMinute, newSecond, newMillis)
        }

        private fun parseTranscriptLines(transcript: String): List<TranscriptLine> {
            return transcript.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { line ->
                    val separator = "]: "
                    val endBracket = line.indexOf(separator)
                    if (!line.startsWith("[") || endBracket < 0) {
                        TranscriptLine(
                            raw = line,
                            startMs = null,
                            endMs = null,
                            text = line,
                            hasTimestamps = false,
                        )
                    } else {
                        val header = line.substring(1, endBracket)
                        val text = line.substring(endBracket + separator.length)
                        val parts = header.split(" --> ")
                        val startMs = parts.getOrNull(0)?.let(::parseTimestampMs)
                        val endMs = parts.getOrNull(1)?.let(::parseTimestampMs)
                        TranscriptLine(
                            raw = line,
                            startMs = startMs,
                            endMs = endMs,
                            text = text,
                            hasTimestamps = startMs != null && endMs != null,
                        )
                    }
                }
                .toList()
        }

        private fun parseTimestampMs(timestamp: String): Long? {
            val pieces = timestamp.split(":", ".")
            if (pieces.size != 4) {
                return null
            }
            val hour = pieces[0].toLongOrNull() ?: return null
            val minute = pieces[1].toLongOrNull() ?: return null
            val second = pieces[2].toLongOrNull() ?: return null
            val millis = pieces[3].toLongOrNull() ?: return null
            return (((hour * 60 + minute) * 60) + second) * 1000L + millis
        }

        private fun normalizedSimilarity(first: String, second: String): Float {
            if (first.isBlank() || second.isBlank()) {
                return 0f
            }
            if (first == second) {
                return 1f
            }
            val distance = levenshteinDistance(first, second)
            val maxLength = maxOf(first.length, second.length).coerceAtLeast(1)
            return 1f - (distance.toFloat() / maxLength.toFloat())
        }

        private fun levenshteinDistance(first: String, second: String): Int {
            if (first == second) {
                return 0
            }
            if (first.isEmpty()) {
                return second.length
            }
            if (second.isEmpty()) {
                return first.length
            }

            val previous = IntArray(second.length + 1) { it }
            val current = IntArray(second.length + 1)
            for (i in first.indices) {
                current[0] = i + 1
                for (j in second.indices) {
                    val substitutionCost = if (first[i] == second[j]) 0 else 1
                    current[j + 1] = minOf(
                        current[j] + 1,
                        previous[j + 1] + 1,
                        previous[j] + substitutionCost,
                    )
                }
                for (index in previous.indices) {
                    previous[index] = current[index]
                }
            }
            return previous[second.length]
        }

        private fun findNormalizedBoundaryOverlap(existing: String, incoming: String): Int {
            val maxOverlap = minOf(existing.length, incoming.length)
            for (length in maxOverlap downTo 1) {
                if (existing.regionMatches(existing.length - length, incoming, 0, length, ignoreCase = false)) {
                    return length
                }
            }
            return 0
        }

        private fun trimTextByNormalizedPrefix(text: String, normalizedPrefixLength: Int): String {
            if (normalizedPrefixLength <= 0) {
                return text
            }

            var matched = 0
            var index = 0
            while (index < text.length && matched < normalizedPrefixLength) {
                val normalizedChar = normalizeCharForMatch(text[index])
                if (normalizedChar != null) {
                    matched += 1
                }
                index += 1
            }
            while (index < text.length && text[index].isWhitespace()) {
                index += 1
            }
            return text.substring(index.coerceAtMost(text.length))
        }

        private fun shouldInsertSpaceBetween(left: String, right: String): Boolean {
            if (left.isBlank() || right.isBlank()) {
                return false
            }
            val leftLast = left.last()
            val rightFirst = right.first()
            return leftLast.isLetterOrDigit() && rightFirst.isLetterOrDigit() &&
                leftLast.code < 128 && rightFirst.code < 128
        }

        private fun normalizeCharForMatch(char: Char): Char? {
            return when {
                char.isWhitespace() -> null
                char in listOf(',', '.', ';', ':', '!', '?', '，', '。', '！', '？', '、') -> null
                else -> char.lowercaseChar()
            }
        }
    }
}

private data class TranscriptLine(
    val raw: String,
    val startMs: Long?,
    val endMs: Long?,
    val text: String,
    val hasTimestamps: Boolean,
) {
    val normalizedText: String = text
        .lowercase()
        .replace(Regex("\\s+"), "")
        .replace(Regex("[,.;:!?，。！？、]"), "")

    fun render(): String {
        return if (hasTimestamps && startMs != null && endMs != null) {
            "[${formatTimestampForRender(startMs)} --> ${formatTimestampForRender(endMs)}]: $text"
        } else {
            raw
        }
    }

    fun withText(text: String, endMs: Long?): TranscriptLine {
        return copy(
            text = text,
            endMs = endMs,
        )
    }
}

private fun formatTimestampForRender(totalMs: Long): String {
    val safeMs = totalMs.coerceAtLeast(0L)
    val newHour = safeMs / 3_600_000
    val newMinute = (safeMs % 3_600_000) / 60_000
    val newSecond = (safeMs % 60_000) / 1_000
    val newMillis = safeMs % 1_000
    return String.format("%02d:%02d:%02d.%03d", newHour, newMinute, newSecond, newMillis)
}

private fun ResumeSnapshot.matches(
    modelPath: String,
    audioPath: String,
    languageModeKey: String,
    chunkDurationSeconds: Int,
    totalChunks: Int,
    totalSamples: Int,
    chunkSizeSamples: Int,
): Boolean {
    return canResume &&
        this.modelPath == modelPath &&
        this.audioPath == audioPath &&
        this.languageModeKey == languageModeKey &&
        this.chunkDurationSeconds == chunkDurationSeconds &&
        this.totalChunks == totalChunks &&
        this.totalSamples == totalSamples &&
        this.chunkSizeSamples == chunkSizeSamples &&
        completedChunks < totalChunks
}
