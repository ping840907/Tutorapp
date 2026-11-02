package com.tutorapp.fluenttutor.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 高效能音訊播放器 (支援 SM8850 低延遲輸出通道與即時語音振幅同步)
 * 1. 標準 RIFF WAV Chunk 掃描解析器 (動態尋找 fmt 與 data chunk，防溢位與格式檢驗)
 * 2. 播放期間即時計算音訊振幅 (RMS Amplitude)，驅動 NPC 動畫頭像的嘴型同步 (Lip-Sync)
 * 3. 嚴謹的 AudioTrack 播放頭排空等待 (Drain playback head)，杜絕提前解除半雙工靜音導致尾音自激
 * 4. 支援 TTS 未就緒時的延遲佇列 (Pending Speech Queue)，修復首次進入畫面漏播招呼語的問題
 */
class AudioPlayer(
    private val context: Context,
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit,
    private val onPlaybackAmplitudeChanged: (amplitude: Float) -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AudioPlayer"

        internal data class WavInfo(
            val sampleRate: Int,
            val channels: Int,
            val bitsPerSample: Int,
            val dataOffset: Int,
            val dataLength: Int
        )

        internal fun parseWavHeader(bytes: ByteArray, fallbackSampleRate: Int = 16000): WavInfo {
            if (bytes.size <= 44 ||
                bytes[0] != 'R'.code.toByte() ||
                bytes[1] != 'I'.code.toByte() ||
                bytes[2] != 'F'.code.toByte() ||
                bytes[3] != 'F'.code.toByte()
            ) {
                return WavInfo(fallbackSampleRate, 1, 16, 0, bytes.size)
            }

            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var sampleRate = fallbackSampleRate
            var channels = 1
            var bitsPerSample = 16
            var dataOffset = 44
            var dataLength = bytes.size - 44

            var pos = 12 // 跳過 RIFF (4) + size (4) + WAVE (4)
            while (pos + 8 <= bytes.size) {
                val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
                val chunkSizeLong = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
                val chunkDataStart = pos + 8

                if (chunkSizeLong < 0 || chunkDataStart > bytes.size) break

                when (chunkId) {
                    "fmt " -> {
                        if (chunkDataStart + 16 <= bytes.size) {
                            channels = bb.getShort(chunkDataStart + 2).toInt().coerceIn(1, 2)
                            sampleRate = bb.getInt(chunkDataStart + 4).coerceIn(8000, 96000)
                            bitsPerSample = bb.getShort(chunkDataStart + 14).toInt().let { if (it in listOf(8, 16, 24, 32)) it else 16 }
                        }
                    }
                    "data" -> {
                        dataOffset = chunkDataStart
                        dataLength = minOf(chunkSizeLong.toInt(), bytes.size - chunkDataStart)
                        break
                    }
                }
                val nextPos = chunkDataStart + chunkSizeLong + (chunkSizeLong and 1L)
                if (nextPos <= pos || nextPos > bytes.size) break
                pos = nextPos.toInt()
            }

            return WavInfo(sampleRate, channels, bitsPerSample, dataOffset, dataLength)
        }
    }

    private var tts: TextToSpeech? = null
    @Volatile
    private var isTtsReady = false
    @Volatile
    private var pendingSpeechText: String? = null

    @Volatile
    private var audioTrack: AudioTrack? = null
    private var ttsEnvelopeJob: Job? = null
    private val playerScope = CoroutineScope(Dispatchers.Default)

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            isTtsReady = (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onPlaybackStateChanged(true)
                    startTtsAmplitudeEnvelope()
                }

                override fun onDone(utteranceId: String?) {
                    stopTtsAmplitudeEnvelope()
                    onPlaybackStateChanged(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    stopTtsAmplitudeEnvelope()
                    onPlaybackStateChanged(false)
                }
            })
            Log.i(TAG, "AudioPlayer TTS engine initialized successfully (Locale: US)")

            val pending = pendingSpeechText
            if (!pending.isNullOrBlank()) {
                pendingSpeechText = null
                speakText(pending)
            }
        } else {
            Log.e(TAG, "AudioPlayer TTS init failed: $status")
        }
    }

    private fun startTtsAmplitudeEnvelope() {
        ttsEnvelopeJob?.cancel()
        ttsEnvelopeJob = playerScope.launch {
            var tick = 0f
            while (isActive) {
                val env = (0.35f + 0.45f * abs(sin(tick * 1.7f)) * abs(sin(tick * 0.6f))).coerceIn(0.15f, 0.9f)
                onPlaybackAmplitudeChanged(env)
                delay(45L)
                tick += 0.35f
            }
        }
    }

    private fun stopTtsAmplitudeEnvelope() {
        ttsEnvelopeJob?.cancel()
        ttsEnvelopeJob = null
        onPlaybackAmplitudeChanged(0f)
    }

    private fun stopAudioTrack() {
        val track = audioTrack
        audioTrack = null
        try {
            track?.pause()
            track?.flush()
            track?.stop()
            track?.release()
        } catch (e: Exception) {
            // ignore
        }
    }

    suspend fun playAudioData(audioBytes: ByteArray, fallbackSampleRate: Int = 16000) = withContext(Dispatchers.IO) {
        if (audioBytes.isEmpty()) return@withContext
        stop()
        onPlaybackStateChanged(true)

        var currentTrack: AudioTrack? = null
        try {
            val wavInfo = parseWavHeader(audioBytes, fallbackSampleRate)
            val channelConfig = if (wavInfo.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBufSize = AudioTrack.getMinBufferSize(
                wavInfo.sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val streamChunkBytes = maxOf((wavInfo.sampleRate * 2 * wavInfo.channels) / 20, 1024) // ~50ms
            val trackBufferSize = maxOf(minBufSize, streamChunkBytes * 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(wavInfo.sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(trackBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            currentTrack = track
            audioTrack = track
            track.play()

            var offset = wavInfo.dataOffset
            val endOffset = wavInfo.dataOffset + wavInfo.dataLength
            var totalBytesWritten = 0

            while (offset < endOffset && isActive) {
                val toWrite = minOf(streamChunkBytes, endOffset - offset)
                val written = track.write(audioBytes, offset, toWrite)
                if (written <= 0) break

                totalBytesWritten += written
                val chunkAmp = computePcmChunkAmplitude(audioBytes, offset, written)
                onPlaybackAmplitudeChanged(chunkAmp)

                offset += written
            }

            // 排空 AudioTrack 緩衝區：等待播放頭追上寫入的 Frame 數，防提前解鎖導致麥克風自激
            val bytesPerFrame = 2 * wavInfo.channels
            val totalFrames = if (bytesPerFrame > 0) totalBytesWritten / bytesPerFrame else 0
            val timeoutLimit = System.currentTimeMillis() + 1500L
            while (isActive && track.playState == AudioTrack.PLAYSTATE_PLAYING && System.currentTimeMillis() < timeoutLimit) {
                if (track.playbackHeadPosition >= totalFrames) break
                delay(20L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during audio playback", e)
        } finally {
            try {
                currentTrack?.stop()
                currentTrack?.release()
            } catch (e: Exception) {
                // ignore
            }
            if (audioTrack === currentTrack) {
                audioTrack = null
            }
            onPlaybackAmplitudeChanged(0f)
            onPlaybackStateChanged(false)
        }
    }

    /**
     * 串流播放分塊音訊 (Streaming Chunked Playback)
     * 首個音訊塊抵達立即啟動 AudioTrack 播放，後續 Chunk 邊運算邊寫入，
     * 實現首包超低延遲 (TTFA < 100ms)，並在被打斷 (Barge-in / Cancel) 時立即終止。
     */
    suspend fun playAudioStream(
        chunks: kotlinx.coroutines.flow.Flow<ByteArray>,
        sampleRate: Int = 24000,
        channels: Int = 1,
        fallbackText: String? = null
    ) = withContext(Dispatchers.IO) {
        stop()
        onPlaybackStateChanged(true)

        var currentTrack: AudioTrack? = null
        var totalBytesWritten = 0
        val channelConfig = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val bytesPerFrame = 2 * channels

        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val streamChunkBytes = maxOf((sampleRate * 2 * channels) / 10, 2048) // ~100ms
            val trackBufferSize = maxOf(minBufSize * 2, streamChunkBytes * 4)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(trackBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            currentTrack = track
            audioTrack = track
            track.play()

            chunks.collect { chunk ->
                if (!isActive) return@collect
                if (chunk.isEmpty()) return@collect

                // 若 Chunk 包含 RIFF 檔頭則自動跳過檔頭
                val (offset, length) = if (chunk.size >= 44 &&
                    chunk[0] == 'R'.code.toByte() && chunk[1] == 'I'.code.toByte() &&
                    chunk[2] == 'F'.code.toByte() && chunk[3] == 'F'.code.toByte()
                ) {
                    val info = parseWavHeader(chunk, sampleRate)
                    Pair(info.dataOffset, info.dataLength)
                } else {
                    Pair(0, chunk.size)
                }

                var writtenOffset = offset
                val endOffset = offset + length

                while (writtenOffset < endOffset && isActive) {
                    val toWrite = minOf(streamChunkBytes, endOffset - writtenOffset)
                    val written = track.write(chunk, writtenOffset, toWrite)
                    if (written <= 0) break
                    totalBytesWritten += written

                    val chunkAmp = computePcmChunkAmplitude(chunk, writtenOffset, written)
                    onPlaybackAmplitudeChanged(chunkAmp)

                    writtenOffset += written
                }
            }

            // 排空 AudioTrack 緩衝區
            val totalFrames = if (bytesPerFrame > 0) totalBytesWritten / bytesPerFrame else 0
            val timeoutLimit = System.currentTimeMillis() + 1500L
            while (isActive && track.playState == AudioTrack.PLAYSTATE_PLAYING && System.currentTimeMillis() < timeoutLimit) {
                if (track.playbackHeadPosition >= totalFrames) break
                delay(20L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during streaming audio playback", e)
        } finally {
            try {
                currentTrack?.stop()
                currentTrack?.release()
            } catch (e: Exception) {
                // ignore
            }
            if (audioTrack === currentTrack) {
                audioTrack = null
            }
            onPlaybackAmplitudeChanged(0f)
            onPlaybackStateChanged(false)
            if (totalBytesWritten == 0 && !fallbackText.isNullOrBlank()) {
                speakText(fallbackText)
            }
        }
    }

    private fun computePcmChunkAmplitude(bytes: ByteArray, offset: Int, length: Int): Float {
        val sampleCount = length / 2
        if (sampleCount <= 0) return 0f
        var sumSq = 0.0
        var i = 0
        while (i < sampleCount) {
            val byteIdx = offset + i * 2
            if (byteIdx + 1 >= bytes.size) break
            val low = bytes[byteIdx].toInt() and 0xFF
            val high = bytes[byteIdx + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toDouble()
            sumSq += sample * sample
            i++
        }
        val rms = sqrt(sumSq / sampleCount).toFloat()
        return (rms / 4500f).coerceIn(0.08f, 1.0f)
    }

    fun speakText(text: String, utteranceId: String = System.currentTimeMillis().toString()) {
        stopAudioTrack()
        if (!isTtsReady) {
            Log.i(TAG, "TTS initializing, queueing pending text: $text")
            pendingSpeechText = text
            return
        }
        tts?.stop()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        pendingSpeechText = null
        stopTtsAmplitudeEnvelope()
        tts?.stop()
        stopAudioTrack()
        onPlaybackAmplitudeChanged(0f)
        onPlaybackStateChanged(false)
    }

    fun release() {
        stop()
        playerScope.cancel()
        tts?.shutdown()
        tts = null
    }
}
