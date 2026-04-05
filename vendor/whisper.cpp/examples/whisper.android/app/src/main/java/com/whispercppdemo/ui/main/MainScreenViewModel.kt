package com.whispercppdemo.ui.main

import android.app.ActivityManager
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.BatteryManager
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercpp.whisper.WhisperContext
import com.whispercppdemo.data.AppPreferences
import com.whispercppdemo.data.ResumeSnapshot
import com.whispercppdemo.recorder.Recorder
import com.whispercppdemo.service.TranscriptionService
import com.whispercppdemo.transcription.CHUNK_DURATION_OPTIONS
import com.whispercppdemo.transcription.LANGUAGE_OPTIONS
import com.whispercppdemo.transcription.findChunkDurationOption
import com.whispercppdemo.transcription.findLanguageOption
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "MainScreenViewModel"
private const val STALE_TRANSCRIPTION_TIMEOUT_MS = 15_000L

class MainScreenViewModel(private val application: Application) : ViewModel() {
    var activityLog by mutableStateOf("")
        private set
    var isDarkTheme by mutableStateOf(false)
        private set
    var isPaused by mutableStateOf(false)
        private set
    var isProcessing by mutableStateOf(false)
        private set
    var isRecording by mutableStateOf(false)
        private set
    var appMemoryBytes by mutableStateOf<Long?>(null)
        private set
    var appCpuUsagePercent by mutableStateOf<Float?>(null)
        private set
    var elapsedMs by mutableStateOf(0L)
        private set
    var estimatedRemainingMs by mutableStateOf<Long?>(null)
        private set
    var availableRamBytes by mutableStateOf<Long?>(null)
        private set
    var totalRamBytes by mutableStateOf<Long?>(null)
        private set
    var isProfessionalMonitorEnabled by mutableStateOf(false)
        private set
    var memoryPressureLabel by mutableStateOf("检测中")
        private set
    var modelFileSizeBytes by mutableStateOf<Long?>(null)
        private set
    var modelRuntimeMemoryBytes by mutableStateOf<Long?>(null)
        private set
    var progressFraction by mutableStateOf<Float?>(null)
        private set
    var selectedChunkDurationSeconds by mutableStateOf(findChunkDurationOption(null).seconds)
        private set
    var selectedAudioName by mutableStateOf<String?>(null)
        private set
    var selectedLanguageModeKey by mutableStateOf(findLanguageOption(null).key)
        private set
    var selectedModelName by mutableStateOf<String?>(null)
        private set
    var showTimestamps by mutableStateOf(true)
        private set
    var statusText by mutableStateOf("准备中")
        private set
    var temperatureCelsius by mutableStateOf<Float?>(null)
        private set
    var temperatureTrendLabel by mutableStateOf("检测中")
        private set
    var threadCount by mutableStateOf<Int?>(null)
        private set
    var transcriptText by mutableStateOf("")
        private set

    val availableModels = mutableStateListOf<String>()
    val availableChunkDurations = CHUNK_DURATION_OPTIONS
    val availableLanguageModes = LANGUAGE_OPTIONS

    val canPickAudio: Boolean
        get() = !isRecording

    val canRecord: Boolean
        get() = selectedModelName != null && !isProcessing

    val canStartSelectedFile: Boolean
        get() = selectedModelName != null &&
            selectedAudioFile != null &&
            !isProcessing &&
            !isRecording &&
            !canContinueSelectedFile

    val canRestartSelectedFile: Boolean
        get() = selectedModelName != null && selectedAudioFile != null && !isRecording

    val canContinueSelectedFile: Boolean
        get() = selectedModelName != null &&
            selectedAudioFile != null &&
            !isRecording &&
            !isProcessing &&
            (isPaused || AppPreferences.readResumeSnapshot(application).canResume)

    val canPauseSelectedFile: Boolean
        get() = isProcessing && !isRecording

    val displayedTranscriptText: String
        get() = if (showTimestamps) transcriptText else stripTranscriptTimestamps(transcriptText)

    val selectedChunkDurationLabel: String
        get() = findChunkDurationOption(selectedChunkDurationSeconds).label

    val selectedLanguageLabel: String
        get() = findLanguageOption(selectedLanguageModeKey).label

    private val prefs = AppPreferences.prefs(application)
    private val modelsPath = File(application.filesDir, "models")
    private val importsPath = File(application.filesDir, "imports")
    private val recordingsPath = File(application.filesDir, "recordings")
    private var idleNativeHeapBytes: Long? = null
    private var lastCpuSample: CpuSample? = null
    private var lastTemperatureSample: Float? = null
    private var performanceMonitorJob: Job? = null
    private var recorder = Recorder()
    private var recordedFile: File? = null
    private var selectedAudioFile: File? = null

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        viewModelScope.launch {
            refreshStateFromPreferences()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        startPerformanceMonitor()
        viewModelScope.launch {
            initialize()
        }
    }

    private suspend fun initialize() {
        modelsPath.mkdirs()
        importsPath.mkdirs()
        recordingsPath.mkdirs()
        appendLog("Whisper system info: ${WhisperContext.getSystemInfo()}")
        refreshModels()
        restoreModelSelection()
        restoreTranscriptionOptions()
        restoreSelectedAudio()
        isProfessionalMonitorEnabled = AppPreferences.isProfessionalMonitorEnabled(application)
        refreshStateFromPreferences()
        clearStaleTranscriptionIfNeeded()
        resumeInterruptedTranscriptionIfPossible()
        if (statusText.isBlank() || statusText == "准备中") {
            statusText = defaultStatusText()
        }
    }

    fun importModel(uri: Uri) = viewModelScope.launch {
        try {
            statusText = "正在导入模型..."
            val modelName = application.queryDisplayName(uri)
                ?: throw IllegalArgumentException("无法读取模型文件名")
            require(isSupportedModelFile(modelName)) {
                "只支持 medium / large-v3 模型，请导入 ggml-medium*.bin 或 ggml-large-v3*.bin"
            }

            val destination = File(modelsPath, modelName)
            withContext(Dispatchers.IO) {
                application.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法打开模型文件" }
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            appendLog("模型已导入：${destination.name}")
            refreshModels()
            selectModel(destination.name)
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            statusText = "模型导入失败"
            appendLog(e.localizedMessage ?: "模型导入失败")
        }
    }

    fun selectModel(name: String) {
        if (!availableModels.contains(name)) {
            statusText = "模型文件不存在"
            return
        }
        selectedModelName = name
        AppPreferences.setSelectedModelName(application, name)
        if (!isProcessing) {
            statusText = "模型已选定，可以开始转写"
        }
        appendLog("当前模型切换为 $name")
    }

    fun selectChunkDuration(seconds: Int) {
        selectedChunkDurationSeconds = findChunkDurationOption(seconds).seconds
        AppPreferences.setSelectedChunkDurationSeconds(application, selectedChunkDurationSeconds)
        if (!isProcessing) {
            statusText = defaultStatusText()
        }
    }

    fun selectLanguageMode(key: String) {
        selectedLanguageModeKey = findLanguageOption(key).key
        AppPreferences.setSelectedLanguageMode(application, selectedLanguageModeKey)
        if (!isProcessing) {
            statusText = defaultStatusText()
        }
    }

    fun importAudio(uri: Uri) = viewModelScope.launch {
        try {
            val displayName = application.queryDisplayName(uri) ?: "selected_audio.wav"
            val destination = withContext(Dispatchers.IO) {
                val target = uniqueFile(importsPath, displayName)
                application.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取录音文件" }
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
                target
            }
            selectedAudioFile = destination
            selectedAudioName = displayName
            AppPreferences.setSelectedAudio(application, destination.absolutePath, displayName)
            if (!isProcessing) {
                statusText = if (selectedModelName == null) {
                    "录音文件已就绪，请先选择模型"
                } else {
                    "录音文件已就绪，可以开始后台转换"
                }
            }
            appendLog("已选择录音文件：$displayName")
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            statusText = "录音文件导入失败"
            appendLog(e.localizedMessage ?: "录音文件导入失败")
        }
    }

    fun startSelectedAudioTranscription() {
        val audioFile = selectedAudioFile ?: run {
            statusText = "请先选择录音文件"
            return
        }
        val sourceName = selectedAudioName ?: audioFile.name
        enqueueTranscription(audioFile, sourceName, clearExistingLog = true)
    }

    fun restartSelectedAudioTranscription() {
        val audioFile = selectedAudioFile ?: run {
            statusText = "请先选择录音文件"
            return
        }
        val sourceName = selectedAudioName ?: audioFile.name
        transcriptText = ""
        AppPreferences.clearResumeSnapshot(application)
        AppPreferences.setPauseRequested(application, false)
        AppPreferences.updateTranscriptionState(
            context = application,
            isRunning = false,
            progressFraction = null,
            statusText = "已清空结果，准备重新开始",
            sourceName = sourceName,
            transcriptText = "",
            isPaused = false,
            elapsedMs = 0L,
            clearEstimatedRemaining = true,
        )
        appendLog("已清空当前结果，重新开始转写：$sourceName")
        enqueueTranscription(audioFile, sourceName, clearExistingLog = true)
    }

    fun continueSelectedAudioTranscription() {
        val resumeSnapshot = AppPreferences.readResumeSnapshot(application)
        if (!resumeSnapshot.canResume) {
            statusText = "当前没有可继续的转写任务"
            return
        }

        val modelPath = resumeSnapshot.modelPath ?: run {
            statusText = "恢复失败，缺少模型信息"
            return
        }
        val audioPath = resumeSnapshot.audioPath ?: run {
            statusText = "恢复失败，缺少音频信息"
            return
        }
        val modelFile = File(modelPath)
        val audioFile = File(audioPath)
        if (!modelFile.exists() || !audioFile.exists()) {
            AppPreferences.clearResumeSnapshot(application)
            statusText = "恢复失败，模型或音频文件不存在"
            return
        }

        val sourceName = resumeSnapshot.sourceName ?: audioFile.name
        selectedAudioFile = audioFile
        selectedAudioName = sourceName
        selectedModelName = modelFile.name
        selectedLanguageModeKey = resumeSnapshot.languageModeKey ?: selectedLanguageModeKey
        selectedChunkDurationSeconds = resumeSnapshot.chunkDurationSeconds.takeIf { it > 0 }
            ?: selectedChunkDurationSeconds
        AppPreferences.setSelectedModelName(application, modelFile.name)
        AppPreferences.setSelectedAudio(application, audioFile.absolutePath, sourceName)
        AppPreferences.setSelectedLanguageMode(application, selectedLanguageModeKey)
        AppPreferences.setSelectedChunkDurationSeconds(application, selectedChunkDurationSeconds)

        val requestId = AppPreferences.activeRequestId(application).takeIf { it != 0L }
            ?: System.currentTimeMillis().also { AppPreferences.setActiveRequestId(application, it) }
        val snapshot = AppPreferences.readSnapshot(application)
        AppPreferences.setPauseRequested(application, false, requestId)
        AppPreferences.updateTranscriptionState(
            context = application,
            isRunning = true,
            progressFraction = snapshot.progressFraction ?: 0.40f,
            statusText = if (snapshot.isPaused) "正在继续后台转换" else "正在从断点继续",
            sourceName = sourceName,
            transcriptText = snapshot.transcriptText,
            requestId = requestId,
            isPaused = false,
            elapsedMs = snapshot.elapsedMs,
            estimatedRemainingMs = snapshot.estimatedRemainingMs,
        )
        TranscriptionService.start(
            context = application,
            modelPath = modelPath,
            audioPath = audioPath,
            sourceName = sourceName,
            languageModeKey = selectedLanguageModeKey,
            chunkDurationSeconds = selectedChunkDurationSeconds,
            resumeRequested = true,
            requestId = requestId,
        )
        appendLog("继续后台转写：$sourceName")
    }

    fun pauseSelectedAudioTranscription() {
        val snapshot = AppPreferences.readSnapshot(application)
        val requestId = AppPreferences.activeRequestId(application)
        if (!snapshot.isRunning || requestId == 0L) {
            statusText = "当前没有进行中的转写任务"
            return
        }

        AppPreferences.setPauseRequested(application, true, requestId)
        AppPreferences.updateTranscriptionState(
            context = application,
            isRunning = true,
            progressFraction = snapshot.progressFraction,
            statusText = "已请求暂停，当前片段完成后暂停",
            sourceName = snapshot.sourceName,
            transcriptText = snapshot.transcriptText,
            requestId = requestId,
            isPaused = false,
            elapsedMs = snapshot.elapsedMs,
            estimatedRemainingMs = snapshot.estimatedRemainingMs,
        )
        appendLog("已请求暂停当前后台转写")
    }

    fun toggleTimestampVisibility() {
        showTimestamps = !showTimestamps
    }

    fun copyTranscriptToClipboard() {
        val text = displayedTranscriptText.trim()
        if (text.isBlank()) {
            statusText = "当前还没有可复制的转写内容"
            return
        }
        val clipboardManager = application.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("Whisper Transcript", text))
        statusText = "转写内容已复制"
        appendLog("已复制当前转写文本")
    }

    fun toggleTheme() {
        val next = !isDarkTheme
        isDarkTheme = next
        AppPreferences.setDarkThemeEnabled(application, next)
    }

    fun toggleProfessionalMonitor() {
        val next = !isProfessionalMonitorEnabled
        isProfessionalMonitorEnabled = next
        AppPreferences.setProfessionalMonitorEnabled(application, next)
    }

    fun toggleRecord() = viewModelScope.launch {
        try {
            if (isRecording) {
                recorder.stopRecording()
                isRecording = false
                statusText = "录音结束，正在加入后台转换"
                val file = recordedFile
                recordedFile = null
                if (file != null) {
                    selectedAudioFile = file
                    selectedAudioName = file.name
                    AppPreferences.setSelectedAudio(application, file.absolutePath, file.name)
                    enqueueTranscription(file, file.name, clearExistingLog = true)
                }
            } else {
                if (selectedModelName == null) {
                    statusText = "请先选择模型"
                    return@launch
                }
                val file = withContext(Dispatchers.IO) { tempRecordingFile() }
                recorder.startRecording(file) { error ->
                    viewModelScope.launch {
                        isRecording = false
                        statusText = "录音失败"
                        appendLog(error.localizedMessage ?: "录音失败")
                    }
                }
                recordedFile = file
                isRecording = true
                statusText = "正在录音，结束后会自动后台转写"
                appendLog("开始录音：${file.name}")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            isRecording = false
            statusText = "录音失败"
            appendLog(e.localizedMessage ?: "录音失败")
        }
    }

    override fun onCleared() {
        performanceMonitorJob?.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onCleared()
    }

    private fun startPerformanceMonitor() {
        performanceMonitorJob?.cancel()
        performanceMonitorJob = viewModelScope.launch {
            while (isActive) {
                refreshPerformanceStats()
                delay(if (isProcessing || isPaused) 1_000L else 2_500L)
            }
        }
    }

    private suspend fun refreshPerformanceStats() {
        val snapshot = withContext(Dispatchers.Default) {
            val activityManager =
                application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val systemMemoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            val processMemoryInfo = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val currentNativeHeapBytes = Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L)
            val cpuSample = CpuSample(
                processCpuMs = Process.getElapsedCpuTime().coerceAtLeast(0L),
                elapsedRealtimeMs = SystemClock.elapsedRealtime().coerceAtLeast(0L),
            )
            val selectedModelFile = selectedModelName
                ?.let { File(modelsPath, it) }
                ?.takeIf { it.exists() && it.isFile }
            val batteryStatus = application.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val temperatureTenths = batteryStatus
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
            val temperatureValue = temperatureTenths?.toFloat()?.div(10f)
            val threadCountValue = File("/proc/self/task").list()?.size

            val modelFileBytes = selectedModelFile?.length()?.coerceAtLeast(0L)
            val availableBytes = systemMemoryInfo.availMem.coerceAtLeast(0L)
            val totalBytes = systemMemoryInfo.totalMem.coerceAtLeast(0L)
            val appPssBytes = processMemoryInfo.totalPss.toLong().coerceAtLeast(0L) * 1024L
            val pressureRatio = if (totalBytes > 0L) {
                availableBytes.toDouble() / totalBytes.toDouble()
            } else {
                0.0
            }
            val pressureLabel = when {
                systemMemoryInfo.lowMemory || pressureRatio < 0.10 -> "紧张"
                pressureRatio < 0.20 -> "偏低"
                else -> "正常"
            }

            PerformanceSnapshot(
                appMemoryBytes = appPssBytes,
                availableRamBytes = availableBytes,
                totalRamBytes = totalBytes,
                memoryPressureLabel = pressureLabel,
                modelFileSizeBytes = modelFileBytes,
                nativeHeapBytes = currentNativeHeapBytes,
                cpuSample = cpuSample,
                temperatureCelsius = temperatureValue,
                threadCount = threadCountValue,
            )
        }

        if (!isProcessing && !isPaused) {
            idleNativeHeapBytes = snapshot.nativeHeapBytes
        }

        appMemoryBytes = snapshot.appMemoryBytes
        availableRamBytes = snapshot.availableRamBytes
        totalRamBytes = snapshot.totalRamBytes
        memoryPressureLabel = snapshot.memoryPressureLabel
        modelFileSizeBytes = snapshot.modelFileSizeBytes
        threadCount = snapshot.threadCount
        modelRuntimeMemoryBytes = if (isProcessing) {
            idleNativeHeapBytes
                ?.let { baseline -> (snapshot.nativeHeapBytes - baseline).coerceAtLeast(0L) }
        } else {
            0L
        }
        appCpuUsagePercent = lastCpuSample?.let { previous ->
            val wallDeltaMs = (snapshot.cpuSample.elapsedRealtimeMs - previous.elapsedRealtimeMs)
                .coerceAtLeast(1L)
            val cpuDeltaMs = (snapshot.cpuSample.processCpuMs - previous.processCpuMs)
                .coerceAtLeast(0L)
            val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            ((cpuDeltaMs.toFloat() / (wallDeltaMs.toFloat() * coreCount.toFloat())) * 100f)
                .coerceIn(0f, 999f)
        }
        lastCpuSample = snapshot.cpuSample
        temperatureCelsius = snapshot.temperatureCelsius
        temperatureTrendLabel = buildTemperatureTrend(snapshot.temperatureCelsius)
    }

    private fun enqueueTranscription(audioFile: File, sourceName: String, clearExistingLog: Boolean = false) {
        val modelName = selectedModelName ?: run {
            statusText = "请先选择模型"
            return
        }
        val modelFile = File(modelsPath, modelName)
        if (!modelFile.exists()) {
            statusText = "模型文件不存在"
            appendLog("找不到模型文件：$modelName")
            refreshModelsAsync()
            return
        }

        val requestId = System.currentTimeMillis()
        AppPreferences.setActiveRequestId(application, requestId)
        AppPreferences.setPauseRequested(application, false, requestId)
        if (clearExistingLog) {
            AppPreferences.clearLog(application)
            activityLog = ""
        }
        val languageMode = findLanguageOption(selectedLanguageModeKey)
        AppPreferences.updateTranscriptionState(
            context = application,
            isRunning = true,
            progressFraction = 0.02f,
            statusText = "已加入后台转换队列",
            sourceName = sourceName,
            transcriptText = transcriptText,
            requestId = requestId,
            isPaused = false,
            elapsedMs = 0L,
            clearEstimatedRemaining = true,
        )
        TranscriptionService.start(
            context = application,
            modelPath = modelFile.absolutePath,
            audioPath = audioFile.absolutePath,
            sourceName = sourceName,
            languageModeKey = languageMode.key,
            chunkDurationSeconds = selectedChunkDurationSeconds,
            requestId = requestId,
        )
        appendLog("已提交后台转写任务：$sourceName")
    }

    private fun refreshModelsAsync() {
        viewModelScope.launch {
            refreshModels()
            restoreModelSelection()
        }
    }

    private suspend fun refreshModels() = withContext(Dispatchers.Main) {
        val names = modelsPath.listFiles()
            ?.filter { it.isFile && isSupportedModelFile(it.name) }
            ?.sortedBy { modelSortKey(it.name) }
            ?.map { it.name }
            ?: emptyList()

        availableModels.clear()
        availableModels.addAll(names)
    }

    private fun restoreModelSelection() {
        val saved = AppPreferences.selectedModelName(application)
        selectedModelName = when {
            saved != null && availableModels.contains(saved) -> saved
            availableModels.isNotEmpty() -> availableModels.first()
            else -> null
        }
        AppPreferences.setSelectedModelName(application, selectedModelName)
    }

    private fun restoreTranscriptionOptions() {
        selectedChunkDurationSeconds = findChunkDurationOption(
            AppPreferences.selectedChunkDurationSeconds(application)
        ).seconds
        selectedLanguageModeKey = findLanguageOption(
            AppPreferences.selectedLanguageMode(application)
        ).key
        AppPreferences.setSelectedChunkDurationSeconds(application, selectedChunkDurationSeconds)
        AppPreferences.setSelectedLanguageMode(application, selectedLanguageModeKey)
    }

    private fun restoreSelectedAudio() {
        val savedPath = AppPreferences.selectedAudioPath(application)
        val savedName = AppPreferences.selectedAudioName(application)
        val restoredFile = savedPath?.let { File(it) }?.takeIf { it.exists() }

        selectedAudioFile = restoredFile
        selectedAudioName = restoredFile?.name ?: savedName

        if (restoredFile == null && (savedPath != null || savedName != null)) {
            AppPreferences.setSelectedAudio(application, null, null)
        }
    }

    private suspend fun refreshStateFromPreferences() = withContext(Dispatchers.Main) {
        val snapshot = AppPreferences.readSnapshot(application)
        isDarkTheme = AppPreferences.isDarkThemeEnabled(application)
        isProcessing = snapshot.isRunning
        isPaused = snapshot.isPaused
        elapsedMs = snapshot.elapsedMs
        estimatedRemainingMs = snapshot.estimatedRemainingMs
        progressFraction = snapshot.progressFraction
        transcriptText = snapshot.transcriptText
        activityLog = snapshot.activityLog

        statusText = when {
            snapshot.statusText.isNotBlank() -> snapshot.statusText
            else -> defaultStatusText()
        }

        if (snapshot.sourceName.isNotBlank()) {
            selectedAudioName = selectedAudioName ?: snapshot.sourceName
        }
    }

    private suspend fun clearStaleTranscriptionIfNeeded() = withContext(Dispatchers.Main) {
        val snapshot = AppPreferences.readSnapshot(application)
        if (!snapshot.isRunning) {
            return@withContext
        }

        val lastUpdatedAt = snapshot.lastUpdatedAt
        val isStale = lastUpdatedAt == null ||
            (System.currentTimeMillis() - lastUpdatedAt) > STALE_TRANSCRIPTION_TIMEOUT_MS

        if (!isStale) {
            return@withContext
        }

        val resumeSnapshot = AppPreferences.readResumeSnapshot(application)
        if (resumeSnapshot.canResume) {
            AppPreferences.updateTranscriptionState(
                context = application,
                isRunning = false,
                progressFraction = snapshot.progressFraction,
                statusText = "检测到中断，准备从断点继续",
                sourceName = snapshot.sourceName.ifBlank { selectedAudioName ?: "" },
                transcriptText = snapshot.transcriptText,
                isPaused = false,
                elapsedMs = snapshot.elapsedMs,
                clearEstimatedRemaining = true,
            )
            appendLog("检测到中断的后台转写，准备从断点继续。")
        } else {
            AppPreferences.updateTranscriptionState(
                context = application,
                isRunning = false,
                progressFraction = null,
                statusText = "上次后台转写已中断，请重新开始",
                sourceName = snapshot.sourceName.ifBlank { selectedAudioName ?: "" },
                transcriptText = snapshot.transcriptText,
                isPaused = false,
                elapsedMs = snapshot.elapsedMs,
                clearEstimatedRemaining = true,
            )
            appendLog("检测到过期的后台转写状态，已自动重置。")
        }
        refreshStateFromPreferences()
    }

    private suspend fun resumeInterruptedTranscriptionIfPossible() = withContext(Dispatchers.Main) {
        val resumeSnapshot = AppPreferences.readResumeSnapshot(application)
        val snapshot = AppPreferences.readSnapshot(application)
        if (!resumeSnapshot.canResume || snapshot.isRunning || snapshot.isPaused) {
            return@withContext
        }

        val modelPath = resumeSnapshot.modelPath ?: return@withContext
        val audioPath = resumeSnapshot.audioPath ?: return@withContext
        val sourceName = resumeSnapshot.sourceName ?: File(audioPath).name
        if (!File(modelPath).exists() || !File(audioPath).exists()) {
            AppPreferences.clearResumeSnapshot(application)
            return@withContext
        }

        selectedAudioFile = File(audioPath)
        selectedAudioName = sourceName
        selectedLanguageModeKey = resumeSnapshot.languageModeKey ?: selectedLanguageModeKey
        selectedChunkDurationSeconds = resumeSnapshot.chunkDurationSeconds.takeIf { it > 0 }
            ?: selectedChunkDurationSeconds
        statusText = "正在从第 ${resumeSnapshot.completedChunks + 1}/${resumeSnapshot.totalChunks} 段继续"
        AppPreferences.updateTranscriptionState(
            context = application,
            isRunning = true,
            progressFraction = snapshot.progressFraction ?: 0.40f,
            statusText = statusText,
            sourceName = sourceName,
            transcriptText = snapshot.transcriptText,
            isPaused = false,
            elapsedMs = snapshot.elapsedMs,
            estimatedRemainingMs = snapshot.estimatedRemainingMs,
        )
        TranscriptionService.start(
            context = application,
            modelPath = modelPath,
            audioPath = audioPath,
            sourceName = sourceName,
            languageModeKey = selectedLanguageModeKey,
            chunkDurationSeconds = selectedChunkDurationSeconds,
            resumeRequested = true,
            requestId = AppPreferences.activeRequestId(application).takeIf { it != 0L }
                ?: System.currentTimeMillis().also { AppPreferences.setActiveRequestId(application, it) },
        )
        appendLog("已自动从第 ${resumeSnapshot.completedChunks + 1}/${resumeSnapshot.totalChunks} 段继续。")
        refreshStateFromPreferences()
    }

    private fun defaultStatusText(): String {
        return when {
            selectedModelName == null -> "请先导入并选择 Whisper 模型"
            selectedAudioFile == null -> "可以直接录音，或先选择本地录音文件"
            else -> "录音文件已就绪，可以开始后台转换"
        }
    }

    private fun appendLog(message: String) {
        AppPreferences.appendLog(application, message)
        activityLog = AppPreferences.readSnapshot(application).activityLog
    }

    private fun buildTemperatureTrend(currentTemperature: Float?): String {
        if (currentTemperature == null) {
            lastTemperatureSample = null
            return "不可用"
        }

        val previous = lastTemperatureSample
        lastTemperatureSample = currentTemperature
        if (previous == null) {
            return "稳定"
        }

        val delta = currentTemperature - previous
        return when {
            delta >= 0.3f -> String.format(Locale.getDefault(), "升温中 (+%.1f°C)", delta)
            delta <= -0.3f -> String.format(Locale.getDefault(), "回落中 (%.1f°C)", delta)
            else -> "稳定"
        }
    }

    private fun tempRecordingFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(recordingsPath, "meeting_$timestamp.wav")
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainScreenViewModel(application)
            }
        }
    }
}

private data class PerformanceSnapshot(
    val appMemoryBytes: Long,
    val availableRamBytes: Long,
    val totalRamBytes: Long,
    val memoryPressureLabel: String,
    val modelFileSizeBytes: Long?,
    val nativeHeapBytes: Long,
    val cpuSample: CpuSample,
    val temperatureCelsius: Float?,
    val threadCount: Int?,
)

private data class CpuSample(
    val processCpuMs: Long,
    val elapsedRealtimeMs: Long,
)

private fun stripTranscriptTimestamps(text: String): String {
    return text.lineSequence()
        .joinToString("\n") { line ->
            line.replace(Regex("^\\[[^\\]]+\\]:\\s*"), "")
        }
        .trim()
}

private fun uniqueFile(parent: File, displayName: String): File {
    val cleanedName = displayName
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .ifBlank { "audio_file" }
    val dotIndex = cleanedName.lastIndexOf('.')
    val base = if (dotIndex >= 0) cleanedName.substring(0, dotIndex) else cleanedName
    val ext = if (dotIndex >= 0) cleanedName.substring(dotIndex) else ""

    var candidate = File(parent, cleanedName)
    var index = 1
    while (candidate.exists()) {
        candidate = File(parent, "${base}_$index$ext")
        index += 1
    }
    return candidate
}

private fun isSupportedModelFile(fileName: String): Boolean {
    val normalized = fileName.lowercase()
    if (!normalized.endsWith(".bin")) {
        return false
    }

    val isMedium = normalized.startsWith("ggml-medium")
    val isLargeV3 = normalized.startsWith("ggml-large-v3")
    val isEnglishOnly = normalized.contains(".en")
    val isTurbo = normalized.contains("turbo")

    return (isMedium || isLargeV3) && !isEnglishOnly && !isTurbo
}

private fun modelSortKey(name: String): String {
    return when {
        name.startsWith("ggml-medium-q5_0") -> "0_$name"
        name.startsWith("ggml-medium") -> "1_$name"
        name.startsWith("ggml-large-v3-q5_0") -> "2_$name"
        name.startsWith("ggml-large-v3") -> "3_$name"
        else -> "9_$name"
    }
}

private fun Context.queryDisplayName(uri: Uri): String? {
    return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    cursor.getString(index)
                } else {
                    null
                }
            } else {
                null
            }
        }
}
