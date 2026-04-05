package com.whispercppdemo.ui.main

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.whispercppdemo.transcription.ChunkDurationOption
import com.whispercppdemo.transcription.LanguageOption
import java.util.Locale

@Composable
fun MainScreen(viewModel: MainScreenViewModel) {
    MainScreen(
        availableModels = viewModel.availableModels,
        activityLog = viewModel.activityLog,
        canPickAudio = viewModel.canPickAudio,
        canContinueSelectedFile = viewModel.canContinueSelectedFile,
        canPauseSelectedFile = viewModel.canPauseSelectedFile,
        canRestartSelectedFile = viewModel.canRestartSelectedFile,
        canRecord = viewModel.canRecord,
        canStartSelectedFile = viewModel.canStartSelectedFile,
        displayedTranscriptText = viewModel.displayedTranscriptText,
        appMemoryBytes = viewModel.appMemoryBytes,
        appCpuUsagePercent = viewModel.appCpuUsagePercent,
        availableChunkDurations = viewModel.availableChunkDurations,
        availableLanguageModes = viewModel.availableLanguageModes,
        availableRamBytes = viewModel.availableRamBytes,
        elapsedMs = viewModel.elapsedMs,
        estimatedRemainingMs = viewModel.estimatedRemainingMs,
        isDarkTheme = viewModel.isDarkTheme,
        isPaused = viewModel.isPaused,
        isProcessing = viewModel.isProcessing,
        isProfessionalMonitorEnabled = viewModel.isProfessionalMonitorEnabled,
        isRecording = viewModel.isRecording,
        memoryPressureLabel = viewModel.memoryPressureLabel,
        modelFileSizeBytes = viewModel.modelFileSizeBytes,
        modelRuntimeMemoryBytes = viewModel.modelRuntimeMemoryBytes,
        progressFraction = viewModel.progressFraction,
        selectedAudioName = viewModel.selectedAudioName,
        selectedChunkDurationLabel = viewModel.selectedChunkDurationLabel,
        selectedLanguageLabel = viewModel.selectedLanguageLabel,
        selectedModelName = viewModel.selectedModelName,
        showTimestamps = viewModel.showTimestamps,
        statusText = viewModel.statusText,
        temperatureCelsius = viewModel.temperatureCelsius,
        temperatureTrendLabel = viewModel.temperatureTrendLabel,
        threadCount = viewModel.threadCount,
        totalRamBytes = viewModel.totalRamBytes,
        onSelectChunkDuration = viewModel::selectChunkDuration,
        onSelectLanguageMode = viewModel::selectLanguageMode,
        onImportAudio = viewModel::importAudio,
        onImportModel = viewModel::importModel,
        onContinueSelectedAudio = viewModel::continueSelectedAudioTranscription,
        onCopyTranscript = viewModel::copyTranscriptToClipboard,
        onPauseSelectedAudio = viewModel::pauseSelectedAudioTranscription,
        onRestartSelectedAudio = viewModel::restartSelectedAudioTranscription,
        onSelectModel = viewModel::selectModel,
        onStartSelectedAudio = viewModel::startSelectedAudioTranscription,
        onToggleRecord = viewModel::toggleRecord,
        onToggleTimestampVisibility = viewModel::toggleTimestampVisibility,
        onToggleTheme = viewModel::toggleTheme,
        onToggleProfessionalMonitor = viewModel::toggleProfessionalMonitor,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    availableModels: List<String>,
    activityLog: String,
    canPickAudio: Boolean,
    canContinueSelectedFile: Boolean,
    canPauseSelectedFile: Boolean,
    canRestartSelectedFile: Boolean,
    canRecord: Boolean,
    canStartSelectedFile: Boolean,
    displayedTranscriptText: String,
    appMemoryBytes: Long?,
    appCpuUsagePercent: Float?,
    availableChunkDurations: List<ChunkDurationOption>,
    availableLanguageModes: List<LanguageOption>,
    availableRamBytes: Long?,
    elapsedMs: Long,
    estimatedRemainingMs: Long?,
    isDarkTheme: Boolean,
    isPaused: Boolean,
    isProcessing: Boolean,
    isProfessionalMonitorEnabled: Boolean,
    isRecording: Boolean,
    memoryPressureLabel: String,
    modelFileSizeBytes: Long?,
    modelRuntimeMemoryBytes: Long?,
    progressFraction: Float?,
    selectedAudioName: String?,
    selectedChunkDurationLabel: String,
    selectedLanguageLabel: String,
    selectedModelName: String?,
    showTimestamps: Boolean,
    statusText: String,
    temperatureCelsius: Float?,
    temperatureTrendLabel: String,
    threadCount: Int?,
    totalRamBytes: Long?,
    onSelectChunkDuration: (Int) -> Unit,
    onSelectLanguageMode: (String) -> Unit,
    onImportAudio: (Uri) -> Unit,
    onImportModel: (Uri) -> Unit,
    onContinueSelectedAudio: () -> Unit,
    onCopyTranscript: () -> Unit,
    onPauseSelectedAudio: () -> Unit,
    onRestartSelectedAudio: () -> Unit,
    onSelectModel: (String) -> Unit,
    onStartSelectedAudio: () -> Unit,
    onToggleRecord: () -> Unit,
    onToggleProfessionalMonitor: () -> Unit,
    onToggleTimestampVisibility: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                onImportModel(uri)
            }
        }
    )
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                onImportAudio(uri)
            }
        }
    )
    val background = remember(isDarkTheme) {
        if (isDarkTheme) {
            Brush.verticalGradient(
                colors = listOf(Color(0xFF020617), Color(0xFF0F172A), Color(0xFF134E4A))
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(Color(0xFFEFF6FF), Color(0xFFD9F1F4), Color(0xFFF8FAFC))
            )
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(text = "Whisper 会议转写") },
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isDarkTheme) Color(0xCC08101A) else Color(0xEAF4F7FB),
                    titleContentColor = if (isDarkTheme) Color(0xFFE2E8F0) else Color(0xFF102235),
                    actionIconContentColor = if (isDarkTheme) Color(0xFFB6E5DF) else Color(0xFF0F5C65),
                ),
                actions = {
                    TextButton(onClick = onToggleTheme) {
                        Text(if (isDarkTheme) "浅色" else "深色")
                    }
                }
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeroCard(
                isProcessing = isProcessing,
                progressFraction = progressFraction,
                selectedAudioName = selectedAudioName,
                selectedChunkDurationLabel = selectedChunkDurationLabel,
                selectedLanguageLabel = selectedLanguageLabel,
                selectedModelName = selectedModelName,
                statusText = statusText,
                elapsedMs = elapsedMs,
                estimatedRemainingMs = estimatedRemainingMs,
                isPaused = isPaused,
                isDarkTheme = isDarkTheme,
            )
            PerformanceCard(
                appMemoryBytes = appMemoryBytes,
                appCpuUsagePercent = appCpuUsagePercent,
                availableRamBytes = availableRamBytes,
                isDarkTheme = isDarkTheme,
                isProcessing = isProcessing,
                isProfessionalMonitorEnabled = isProfessionalMonitorEnabled,
                memoryPressureLabel = memoryPressureLabel,
                modelFileSizeBytes = modelFileSizeBytes,
                modelRuntimeMemoryBytes = modelRuntimeMemoryBytes,
                temperatureCelsius = temperatureCelsius,
                temperatureTrendLabel = temperatureTrendLabel,
                threadCount = threadCount,
                totalRamBytes = totalRamBytes,
                onToggleProfessionalMonitor = onToggleProfessionalMonitor,
            )
            ConfigCard(
                availableChunkDurations = availableChunkDurations,
                availableLanguageModes = availableLanguageModes,
                isDarkTheme = isDarkTheme,
                selectedChunkDurationLabel = selectedChunkDurationLabel,
                selectedLanguageLabel = selectedLanguageLabel,
                onSelectChunkDuration = onSelectChunkDuration,
                onSelectLanguageMode = onSelectLanguageMode,
            )
            ModelCard(
                availableModels = availableModels,
                selectedModelName = selectedModelName,
                isDarkTheme = isDarkTheme,
                onImportModelTapped = { modelPicker.launch(arrayOf("*/*")) },
                onSelectModel = onSelectModel,
            )
            AudioFileCard(
                canPickAudio = canPickAudio,
                canContinueSelectedFile = canContinueSelectedFile,
                canPauseSelectedFile = canPauseSelectedFile,
                canRestartSelectedFile = canRestartSelectedFile,
                canStartSelectedFile = canStartSelectedFile,
                isDarkTheme = isDarkTheme,
                selectedAudioName = selectedAudioName,
                onContinueSelectedAudio = onContinueSelectedAudio,
                onPauseSelectedAudio = onPauseSelectedAudio,
                onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                onRestartSelectedAudio = onRestartSelectedAudio,
                onStartSelectedAudio = onStartSelectedAudio,
            )
            ActionCard(
                canRecord = canRecord,
                isDarkTheme = isDarkTheme,
                isRecording = isRecording,
                onRecordTapped = onToggleRecord,
            )
            TranscriptCard(
                isDarkTheme = isDarkTheme,
                showTimestamps = showTimestamps,
                transcriptText = displayedTranscriptText,
                onCopyTranscript = onCopyTranscript,
                onToggleTimestampVisibility = onToggleTimestampVisibility,
            )
            LogCard(isDarkTheme = isDarkTheme, log = activityLog)
        }
    }
}

@Composable
private fun PerformanceCard(
    appMemoryBytes: Long?,
    appCpuUsagePercent: Float?,
    availableRamBytes: Long?,
    isDarkTheme: Boolean,
    isProcessing: Boolean,
    isProfessionalMonitorEnabled: Boolean,
    memoryPressureLabel: String,
    modelFileSizeBytes: Long?,
    modelRuntimeMemoryBytes: Long?,
    temperatureCelsius: Float?,
    temperatureTrendLabel: String,
    threadCount: Int?,
    totalRamBytes: Long?,
    onToggleProfessionalMonitor: () -> Unit,
) {
    val containerColor = if (isDarkTheme) Color(0xFF111827) else Color(0xFFF7FBFF)
    val titleColor = if (isDarkTheme) Color(0xFFE5E7EB) else Color(0xFF11243A)
    val bodyColor = if (isDarkTheme) Color(0xFF9CA3AF) else Color(0xFF4A6179)
    val emphasisColor = if (isDarkTheme) Color(0xFFB6E5DF) else Color(0xFF0F5C65)

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor.copy(alpha = 0.95f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "性能监控",
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onToggleProfessionalMonitor) {
                    Text(if (isProfessionalMonitorEnabled) "简洁模式" else "专业模式")
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "这里会实时显示模型相关内存和手机剩余内存，转写进行中刷新会更快。",
                color = bodyColor
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "模型文件大小：${modelFileSizeBytes?.let(::formatBytes) ?: "尚未选择"}",
                color = bodyColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "模型运行占用：${formatRuntimeModelMemory(modelRuntimeMemoryBytes, isProcessing)}",
                color = emphasisColor,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "当前 App 内存：${appMemoryBytes?.let(::formatBytes) ?: "检测中"}",
                color = bodyColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "本机剩余内存：${formatAvailableRam(availableRamBytes, totalRamBytes)}",
                color = bodyColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "内存状态：$memoryPressureLabel",
                color = if (memoryPressureLabel == "正常") bodyColor else emphasisColor,
                fontWeight = if (memoryPressureLabel == "正常") FontWeight.Normal else FontWeight.SemiBold
            )
            if (isProfessionalMonitorEnabled) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "App CPU：${formatCpuUsage(appCpuUsagePercent)}",
                    color = bodyColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "线程数：${threadCount?.toString() ?: "检测中"}",
                    color = bodyColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "设备温度：${formatTemperature(temperatureCelsius)}",
                    color = bodyColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "温度趋势：$temperatureTrendLabel",
                    color = if (temperatureTrendLabel == "稳定") bodyColor else emphasisColor,
                    fontWeight = if (temperatureTrendLabel == "稳定") FontWeight.Normal else FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    isProcessing: Boolean,
    progressFraction: Float?,
    selectedAudioName: String?,
    selectedChunkDurationLabel: String,
    selectedLanguageLabel: String,
    selectedModelName: String?,
    statusText: String,
    elapsedMs: Long,
    estimatedRemainingMs: Long?,
    isPaused: Boolean,
    isDarkTheme: Boolean,
) {
    val containerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF7FBFF)
    val titleColor = if (isDarkTheme) Color(0xFFE2E8F0) else Color(0xFF11243A)
    val bodyColor = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF4A6179)

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor.copy(alpha = 0.94f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "本地离线 Whisper",
                style = MaterialTheme.typography.titleLarge,
                color = titleColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "支持选择本地录音文件、后台转写和录音后自动转写，整个流程不走系统语音识别。",
                color = bodyColor
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "当前状态：$statusText",
                color = titleColor,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "当前模型：${selectedModelName ?: "尚未选择"}",
                color = bodyColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "分段时长：$selectedChunkDurationLabel",
                color = bodyColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "语言模式：$selectedLanguageLabel",
                color = bodyColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "当前音频：${selectedAudioName ?: "尚未选择本地录音文件"}",
                color = bodyColor
            )
            if ((isProcessing || isPaused) && progressFraction != null) {
                Spacer(modifier = Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = progressFraction.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "转换进度 ${(progressFraction * 100).toInt().coerceIn(0, 100)}%",
                    color = bodyColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "已耗时 ${formatDuration(elapsedMs)}",
                    color = bodyColor
                )
                Text(
                    text = "预计剩余 ${estimatedRemainingMs?.let(::formatDuration) ?: "计算中"}",
                    color = bodyColor
                )
            }
        }
    }
}

@Composable
private fun ConfigCard(
    availableChunkDurations: List<ChunkDurationOption>,
    availableLanguageModes: List<LanguageOption>,
    isDarkTheme: Boolean,
    selectedChunkDurationLabel: String,
    selectedLanguageLabel: String,
    onSelectChunkDuration: (Int) -> Unit,
    onSelectLanguageMode: (String) -> Unit,
) {
    val containerColor = if (isDarkTheme) Color(0xFF111827) else Color(0xFFF7FBFF)
    val titleColor = if (isDarkTheme) Color(0xFFE5E7EB) else Color(0xFF11243A)
    val bodyColor = if (isDarkTheme) Color(0xFF9CA3AF) else Color(0xFF4A6179)
    var durationExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor.copy(alpha = 0.95f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "转写设置",
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "长分段通常上下文更稳，短分段更灵敏。中文模型有时会吐繁体，这里默认会转成简体显示。",
                color = bodyColor
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { durationExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("分段：$selectedChunkDurationLabel")
                    }
                    DropdownMenu(
                        expanded = durationExpanded,
                        onDismissRequest = { durationExpanded = false },
                    ) {
                        availableChunkDurations.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    durationExpanded = false
                                    onSelectChunkDuration(option.seconds)
                                }
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { languageExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("语言：$selectedLanguageLabel")
                    }
                    DropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false },
                    ) {
                        availableLanguageModes.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    languageExpanded = false
                                    onSelectLanguageMode(option.key)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    availableModels: List<String>,
    selectedModelName: String?,
    isDarkTheme: Boolean,
    onImportModelTapped: () -> Unit,
    onSelectModel: (String) -> Unit,
) {
    val containerColor = if (isDarkTheme) Color(0xFF111827) else Color(0xFFF7FBFF)
    val titleColor = if (isDarkTheme) Color(0xFFE5E7EB) else Color(0xFF11243A)
    val bodyColor = if (isDarkTheme) Color(0xFF9CA3AF) else Color(0xFF4A6179)

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor.copy(alpha = 0.95f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "模型管理",
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "导入并选中 medium / large-v3 模型后，转写任务会在后台单独加载模型，空闲时不长期占内存。",
                color = bodyColor
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onImportModelTapped) {
                Text("导入本地模型文件")
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (availableModels.isEmpty()) {
                Text(
                    text = "还没有模型。请导入 ggml-medium*.bin 或 ggml-large-v3*.bin。",
                    color = bodyColor
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    availableModels.forEach { modelName ->
                        ModelChip(
                            name = modelName,
                            selected = modelName == selectedModelName,
                            isDarkTheme = isDarkTheme,
                            onClick = { onSelectModel(modelName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioFileCard(
    canPickAudio: Boolean,
    canContinueSelectedFile: Boolean,
    canPauseSelectedFile: Boolean,
    canRestartSelectedFile: Boolean,
    canStartSelectedFile: Boolean,
    isDarkTheme: Boolean,
    selectedAudioName: String?,
    onContinueSelectedAudio: () -> Unit,
    onPauseSelectedAudio: () -> Unit,
    onPickAudio: () -> Unit,
    onRestartSelectedAudio: () -> Unit,
    onStartSelectedAudio: () -> Unit,
) {
    val containerColor = if (isDarkTheme) Color(0xFF111827) else Color(0xFFF7FBFF)
    val titleColor = if (isDarkTheme) Color(0xFFE5E7EB) else Color(0xFF11243A)
    val bodyColor = if (isDarkTheme) Color(0xFF9CA3AF) else Color(0xFF4A6179)

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor.copy(alpha = 0.95f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "本地录音文件",
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "先选本地录音，再点开始。开始后即使切到后台，转写也会继续跑。",
                color = bodyColor
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "已选文件：${selectedAudioName ?: "尚未选择"}",
                color = bodyColor
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onPickAudio,
                enabled = canPickAudio,
            ) {
                Text("选择录音文件")
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartSelectedAudio,
                    enabled = canStartSelectedFile,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("开始后台转换")
                }
                OutlinedButton(
                    onClick = onRestartSelectedAudio,
                    enabled = canRestartSelectedFile,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("重新开始")
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onContinueSelectedAudio,
                    enabled = canContinueSelectedFile,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("继续")
                }
                OutlinedButton(
                    onClick = onPauseSelectedAudio,
                    enabled = canPauseSelectedFile,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("暂停")
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    canRecord: Boolean,
    isDarkTheme: Boolean,
    isRecording: Boolean,
    onRecordTapped: () -> Unit,
) {
    val containerColor = if (isDarkTheme) Color(0xFF111827) else Color(0xFFF7FBFF)
    val titleColor = if (isDarkTheme) Color(0xFFE5E7EB) else Color(0xFF11243A)
    val bodyColor = if (isDarkTheme) Color(0xFF9CA3AF) else Color(0xFF4A6179)

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor.copy(alpha = 0.95f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "会议录音",
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "手机上直接录音，结束后自动进入后台转写，不需要一直停留在前台页面。",
                color = bodyColor
            )
            Spacer(modifier = Modifier.height(14.dp))
            RecordButton(
                enabled = canRecord || isRecording,
                isRecording = isRecording,
                onClick = onRecordTapped
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val safeMs = durationMs.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

private fun formatBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L).toDouble()
    val gb = 1024.0 * 1024.0 * 1024.0
    val mb = 1024.0 * 1024.0
    return if (safeBytes >= gb) {
        String.format(Locale.getDefault(), "%.2f GB", safeBytes / gb)
    } else {
        String.format(Locale.getDefault(), "%.0f MB", safeBytes / mb)
    }
}

private fun formatRuntimeModelMemory(bytes: Long?, isProcessing: Boolean): String {
    return when {
        bytes == null && isProcessing -> "检测中"
        bytes == null -> "等待加载"
        bytes == 0L && !isProcessing -> "空闲中"
        else -> "约 ${formatBytes(bytes)}"
    }
}

private fun formatCpuUsage(value: Float?): String {
    return if (value == null) {
        "检测中"
    } else {
        String.format(Locale.getDefault(), "%.1f%%", value)
    }
}

private fun formatTemperature(value: Float?): String {
    return if (value == null) {
        "不可用"
    } else {
        String.format(Locale.getDefault(), "%.1f°C", value)
    }
}

private fun formatAvailableRam(availableRamBytes: Long?, totalRamBytes: Long?): String {
    return if (availableRamBytes == null || totalRamBytes == null || totalRamBytes <= 0L) {
        "检测中"
    } else {
        "${formatBytes(availableRamBytes)} / ${formatBytes(totalRamBytes)}"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptCard(
    isDarkTheme: Boolean,
    showTimestamps: Boolean,
    transcriptText: String,
    onCopyTranscript: () -> Unit,
    onToggleTimestampVisibility: () -> Unit,
) {
    val containerColor = if (isDarkTheme) Color(0xFF111827) else Color(0xFFF7FBFF)
    val titleColor = if (isDarkTheme) Color(0xFFE5E7EB) else Color(0xFF11243A)
    val bodyColor = if (isDarkTheme) Color(0xFF9CA3AF) else Color(0xFF4A6179)

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor.copy(alpha = 0.95f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "转写结果",
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (showTimestamps) {
                    "当前显示带时间戳的分段结果。"
                } else {
                    "当前显示纯文本结果，方便直接复制。"
                },
                color = bodyColor
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onToggleTimestampVisibility) {
                    Text(if (showTimestamps) "隐藏时间戳" else "显示时间戳")
                }
                Button(
                    onClick = onCopyTranscript,
                    enabled = transcriptText.isNotBlank(),
                ) {
                    Text("复制全部文本")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (isDarkTheme) Color(0xFF020617) else Color(0xFF0F172A),
                shape = MaterialTheme.shapes.large
            ) {
                SelectionContainer {
                    Text(
                        text = if (transcriptText.isBlank()) {
                            "转写内容会显示在这里。建议优先导入 ggml-medium-q5_0.bin 或 ggml-large-v3-q5_0.bin。"
                        } else {
                            transcriptText
                        },
                        color = Color.White,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LogCard(isDarkTheme: Boolean, log: String) {
    val containerColor = if (isDarkTheme) Color(0xFF111827) else Color(0xFFF7FBFF)
    val titleColor = if (isDarkTheme) Color(0xFFE5E7EB) else Color(0xFF11243A)
    val bodyColor = if (isDarkTheme) Color(0xFFCBD5E1) else Color(0xFF4A6179)

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor.copy(alpha = 0.95f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "运行日志",
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            SelectionContainer {
                Text(
                    text = if (log.isBlank()) "暂无日志" else log,
                    color = bodyColor
                )
            }
        }
    }
}

@Composable
private fun ModelChip(
    name: String,
    selected: Boolean,
    isDarkTheme: Boolean,
    onClick: () -> Unit,
) {
    val selectedColor = if (isDarkTheme) Color(0xFF115E59) else Color(0xFF0F766E)
    val normalColor = if (isDarkTheme) Color(0xFF1F2937) else Color(0xFFE8F1F8)
    val normalText = if (isDarkTheme) Color(0xFFE5E7EB) else Color(0xFF17324A)

    Surface(
        color = if (selected) selectedColor else normalColor,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = name,
                color = if (selected) Color.White else normalText,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun RecordButton(enabled: Boolean, isRecording: Boolean, onClick: () -> Unit) {
    val micPermissionState = rememberPermissionState(
        permission = android.Manifest.permission.RECORD_AUDIO,
        onPermissionResult = { granted ->
            if (granted) {
                onClick()
            }
        }
    )
    Button(
        onClick = {
            if (micPermissionState.status.isGranted) {
                onClick()
            } else {
                micPermissionState.launchPermissionRequest()
            }
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            if (isRecording) {
                "结束录音并后台转写"
            } else {
                "开始会议录音"
            }
        )
    }
}
