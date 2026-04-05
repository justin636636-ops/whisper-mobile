package com.whispercppdemo.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min

private const val TARGET_SAMPLE_RATE = 16_000
private const val CODEC_TIMEOUT_US = 10_000L
private const val MAX_IDLE_OUTPUT_POLLS = 600

fun decodeAudioFile(file: File, onProgress: ((Float) -> Unit)? = null): FloatArray {
    return if (file.extension.equals("wav", ignoreCase = true)) {
        decodeWaveFile(file).also {
            onProgress?.invoke(1f)
        }
    } else {
        decodeCompressedAudioFile(file, onProgress)
    }
}

fun decodeWaveFile(file: File): FloatArray {
    val bytes = file.readBytes()
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val channelCount = buffer.getShort(22).toInt().coerceAtLeast(1)
    val sampleRate = buffer.getInt(24).coerceAtLeast(1)
    buffer.position(44)
    val shortBuffer = buffer.asShortBuffer()
    val shortArray = ShortArray(shortBuffer.limit())
    shortBuffer.get(shortArray)
    val mono = FloatArray(shortArray.size / channelCount) { frameIndex ->
        var mixed = 0f
        for (channelIndex in 0 until channelCount) {
            mixed += shortArray[frameIndex * channelCount + channelIndex] / 32767.0f
        }
        (mixed / channelCount.toFloat()).coerceIn(-1f, 1f)
    }
    return resampleIfNeeded(mono, sampleRate)
}

fun encodeWaveFile(file: File, data: ShortArray) {
    file.outputStream().use {
        it.write(headerBytes(data.size * 2))
        val buffer = ByteBuffer.allocate(data.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(data)
        val bytes = ByteArray(buffer.limit())
        buffer.get(bytes)
        it.write(bytes)
    }
}

private fun decodeCompressedAudioFile(
    file: File,
    onProgress: ((Float) -> Unit)?,
): FloatArray {
    val extractor = MediaExtractor()
    extractor.setDataSource(file.absolutePath)

    val trackIndex = (0 until extractor.trackCount)
        .firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        }
        ?: error("未找到可解码的音频轨道")

    extractor.selectTrack(trackIndex)
    val inputFormat = extractor.getTrackFormat(trackIndex)
    val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
        ?: error("无法识别音频格式")
    val codec = MediaCodec.createDecoderByType(mimeType)
    codec.configure(inputFormat, null, null, 0)
    codec.start()

    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var outputDone = false
    var outputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var outputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
    val durationUs = inputFormat.safeLong(MediaFormat.KEY_DURATION)
    val sampleBuffer = FloatSampleBuffer()
    var maxQueuedPresentationUs = 0L
    var maxDecodedPresentationUs = 0L
    var idleOutputPolls = 0

    try {
        while (!outputDone) {
            if (!inputDone) {
                val inputBufferIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        ?: error("无法获取解码输入缓冲区")
                    inputBuffer.clear()
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize >= 0) {
                        val presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            sampleSize,
                            presentationTimeUs,
                            0
                        )
                        maxQueuedPresentationUs = maxOf(maxQueuedPresentationUs, presentationTimeUs)
                        durationUs?.let { duration ->
                            onProgress?.invoke((presentationTimeUs.toFloat() / duration.toFloat()).coerceIn(0f, 0.98f))
                        }
                        extractor.advance()
                    } else {
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    }
                }
            }

            when (val outputBufferIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    idleOutputPolls += 1
                    if (inputDone && idleOutputPolls >= MAX_IDLE_OUTPUT_POLLS) {
                        error("音频解码超时，请先把录音转成 WAV 再试")
                    }
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outputChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmEncoding = outputFormat.getInteger(
                        MediaFormat.KEY_PCM_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                }
                else -> {
                    if (outputBufferIndex >= 0) {
                        idleOutputPolls = 0
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                            ?: error("无法获取解码输出缓冲区")
                        if (info.size > 0) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            outputBuffer.get(chunk)
                            sampleBuffer.append(chunk, outputChannels, pcmEncoding)
                        }
                        if (info.presentationTimeUs >= 0) {
                            maxDecodedPresentationUs = maxOf(maxDecodedPresentationUs, info.presentationTimeUs)
                            durationUs?.let { duration ->
                                onProgress?.invoke((maxDecodedPresentationUs.toFloat() / duration.toFloat()).coerceIn(0f, 1f))
                            }
                        }

                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            }
        }
    } finally {
        codec.stop()
        codec.release()
        extractor.release()
    }

    if (sampleBuffer.isEmpty()) {
        error("当前音频文件没有解码出 PCM 数据，请改用 WAV 或重新导出录音")
    }

    onProgress?.invoke(1f)
    return resampleIfNeeded(sampleBuffer.toFloatArray(), outputSampleRate)
}

private fun resampleIfNeeded(monoSamples: FloatArray, sourceRate: Int): FloatArray {
    if (sourceRate == TARGET_SAMPLE_RATE || monoSamples.isEmpty()) {
        return monoSamples
    }

    val ratio = TARGET_SAMPLE_RATE.toDouble() / sourceRate.toDouble()
    val outputSize = maxOf(1, (monoSamples.size * ratio).toInt())
    return FloatArray(outputSize) { outputIndex ->
        val sourceIndex = outputIndex / ratio
        val left = floor(sourceIndex).toInt().coerceIn(0, monoSamples.lastIndex)
        val right = min(left + 1, monoSamples.lastIndex)
        val fraction = (sourceIndex - left).toFloat()
        monoSamples[left] + ((monoSamples[right] - monoSamples[left]) * fraction)
    }
}

private class FloatSampleBuffer {
    private var buffer = FloatArray(16_384)
    private var size = 0

    fun append(pcmBytes: ByteArray, channelCount: Int, pcmEncoding: Int) {
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> appendFloatPcm(pcmBytes, channelCount)
            else -> append16BitPcm(pcmBytes, channelCount)
        }
    }

    fun toFloatArray(): FloatArray {
        return buffer.copyOf(size)
    }

    fun isEmpty(): Boolean {
        return size == 0
    }

    private fun append16BitPcm(pcmBytes: ByteArray, channelCount: Int) {
        val shortBuffer = ByteBuffer.wrap(pcmBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val shortArray = ShortArray(shortBuffer.remaining())
        shortBuffer.get(shortArray)
        val frames = shortArray.size / channelCount.coerceAtLeast(1)
        ensureCapacity(size + frames)

        repeat(frames) { frameIndex ->
            var mixed = 0f
            for (channelIndex in 0 until channelCount) {
                mixed += shortArray[frameIndex * channelCount + channelIndex] / 32767.0f
            }
            buffer[size++] = (mixed / channelCount.toFloat()).coerceIn(-1f, 1f)
        }
    }

    private fun appendFloatPcm(pcmBytes: ByteArray, channelCount: Int) {
        val floatBuffer = ByteBuffer.wrap(pcmBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
        val floatArray = FloatArray(floatBuffer.remaining())
        floatBuffer.get(floatArray)
        val frames = floatArray.size / channelCount.coerceAtLeast(1)
        ensureCapacity(size + frames)

        repeat(frames) { frameIndex ->
            var mixed = 0f
            for (channelIndex in 0 until channelCount) {
                mixed += floatArray[frameIndex * channelCount + channelIndex]
            }
            buffer[size++] = (mixed / channelCount.toFloat()).coerceIn(-1f, 1f)
        }
    }

    private fun ensureCapacity(requiredSize: Int) {
        if (requiredSize <= buffer.size) {
            return
        }

        var newSize = buffer.size
        while (newSize < requiredSize) {
            newSize *= 2
        }
        buffer = buffer.copyOf(newSize)
    }
}

private fun MediaFormat.safeLong(key: String): Long? {
    return if (containsKey(key)) getLong(key) else null
}

private fun headerBytes(totalLength: Int): ByteArray {
    require(totalLength >= 44)
    ByteBuffer.allocate(44).apply {
        order(ByteOrder.LITTLE_ENDIAN)

        put('R'.code.toByte())
        put('I'.code.toByte())
        put('F'.code.toByte())
        put('F'.code.toByte())

        putInt(totalLength - 8)

        put('W'.code.toByte())
        put('A'.code.toByte())
        put('V'.code.toByte())
        put('E'.code.toByte())

        put('f'.code.toByte())
        put('m'.code.toByte())
        put('t'.code.toByte())
        put(' '.code.toByte())

        putInt(16)
        putShort(1.toShort())
        putShort(1.toShort())
        putInt(TARGET_SAMPLE_RATE)
        putInt(TARGET_SAMPLE_RATE * 2)
        putShort(2.toShort())
        putShort(16.toShort())

        put('d'.code.toByte())
        put('a'.code.toByte())
        put('t'.code.toByte())
        put('a'.code.toByte())

        putInt(totalLength - 44)
        position(0)
    }.also {
        val bytes = ByteArray(it.limit())
        it.get(bytes)
        return bytes
    }
}
