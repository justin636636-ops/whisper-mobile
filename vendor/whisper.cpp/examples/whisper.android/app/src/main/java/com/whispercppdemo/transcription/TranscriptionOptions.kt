package com.whispercppdemo.transcription

data class ChunkDurationOption(
    val seconds: Int,
    val label: String,
)

data class LanguageOption(
    val key: String,
    val label: String,
    val nativeLanguage: String?,
    val detectLanguage: Boolean,
    val normalizeToSimplified: Boolean,
)

val CHUNK_DURATION_OPTIONS = listOf(
    ChunkDurationOption(seconds = 10, label = "10 秒"),
    ChunkDurationOption(seconds = 15, label = "15 秒"),
    ChunkDurationOption(seconds = 20, label = "20 秒"),
    ChunkDurationOption(seconds = 30, label = "30 秒"),
)

val LANGUAGE_OPTIONS = listOf(
    LanguageOption(
        key = "zh",
        label = "中文优先",
        nativeLanguage = "zh",
        detectLanguage = false,
        normalizeToSimplified = true,
    ),
    LanguageOption(
        key = "auto",
        label = "自动识别",
        nativeLanguage = null,
        detectLanguage = true,
        normalizeToSimplified = true,
    ),
    LanguageOption(
        key = "en",
        label = "英文优先",
        nativeLanguage = "en",
        detectLanguage = false,
        normalizeToSimplified = false,
    ),
)

fun findChunkDurationOption(seconds: Int?): ChunkDurationOption {
    return CHUNK_DURATION_OPTIONS.firstOrNull { it.seconds == seconds }
        ?: CHUNK_DURATION_OPTIONS.last()
}

fun findLanguageOption(key: String?): LanguageOption {
    return LANGUAGE_OPTIONS.firstOrNull { it.key == key }
        ?: LANGUAGE_OPTIONS.first()
}
