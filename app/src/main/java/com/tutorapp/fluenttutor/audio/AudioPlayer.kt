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
 * 1. 標準 RIFF WAV Chunk 掃描解析器 (動態尋找 fmt 與 data chunk，解決非固定 44-byte 標頭導致的爆音)
 * 2. 播放期間即時計算音訊振幅 (RMS Amplitude)，驅動 NPC 動畫頭像的嘴型同步 (Lip-Sync)
 * 3. 支援 TTS 未就緒時的延遲佇列 (Pending Speech Queue)，修復首次進入畫面漏播招呼語的問題
 * 4. 完整的播放狀態回報，供半雙工防回音 (AEC) 閘控使用
 */
class AudioPlayer(
    private val context: Context,
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit,
    private val onPlaybackAmplitudeChanged: (amplitude: Float) -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AudioPlayer"

        /**
         * 解析 RIFF WAV 結構，精確定位 fmt 與 data 區塊
         */
        internal data class WavInfo(
            val sampleRate: Int,
            val channels: Int,
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
                return WavInfo(fallbackSampleRate, 1, 0, bytes.size)
            }

            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var sampleRate = fallbackSampleRate
            var channels = 1
            var dataOffset = 44
            var dataLength = bytes.size - 44

            var pos = 12 // 跳過 RIFF + size + WAVE
            while (pos + 8 <= bytes.size) {
                val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
                val chunkSize = bb.getInt(pos + 4)
                val chunkDataStart = pos + 8

                if (chunkSize < 0 || chunkDataStart > bytes.size) break

                when (chunkId) {
                    "fmt " -> {
                        if (chunkDataStart + 8 <= bytes.size) {
                            channels = bb.getShort(chunkDataStart + 2).toInt().coerceAtLeast(1)
                            sampleRate = bb.getInt(chunkDataStart + 4).coerceIn(8000, 96000)
                        }
                    }
                    "data" -> {
                        dataOffset = chunkDataStart
                        dataLength = minOf(chunkSize, bytes.size - chunkDataStart)
                        break
                    }
                }
                // WAV chunk 偶數位元組對齊
                pos = chunkDataStart + chunkSize + (chunkSize and 1)
            }

            return WavInfo(sampleRate, channels, dataOffset, dataLength)
        }
    }

    private var tts: TextToSpeech? = null
    @Volatile
    private var isTtsReady = false
    @Volatile
    private var pendingSpeechText: String? = null

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

            // 若初始化前已有排隊的招呼語，立即播報
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

    /**
     * 播放音訊數據 (自動檢測 WAV 標頭或純 PCM，並流式計算即時音量振幅驅動 NPC 嘴型)
     */
    suspend fun playAudioData(audioBytes: ByteArray, fallbackSampleRate: Int = 16000) = withContext(Dispatchers.IO) {
        if (audioBytes.isEmpty()) return@withContext
        stop()
        onPlaybackStateChanged(true)

        try {
            val wavInfo = parseWavHeader(audioBytes, fallbackSampleRate)
            val channelConfig = if (wavInfo.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBufSize = AudioTrack.getMinBufferSize(
                wavInfo.sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            )

            // 使用 MODE_STREAM 分塊寫入並同步計算每個音訊塊的真實 RMS 能量
            val streamChunkBytes = maxOf((wavInfo.sampleRate * 2 * wavInfo.channels) / 20, 1024) // 約 50ms 一塊
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

            audioTrack = track
            track.play()

            var offset = wavInfo.dataOffset
            val endOffset = wavInfo.dataOffset + wavInfo.dataLength

            while (offset < endOffset && isActive) {
                val toWrite = minOf(streamChunkBytes, endOffset - offset)
                val written = track.write(audioBytes, offset, toWrite)
                if (written <= 0) break

                // 計算此塊 PCM 的真實 RMS 振幅，驅動 NPC 嘴型動畫
                val chunkAmp = computePcmChunkAmplitude(audioBytes, offset, written)
                onPlaybackAmplitudeChanged(chunkAmp)

                offset += written
            }

            delay(80L)
        } catch (e: Exception) {
            Log.e(TAG, "Error during audio playback", e)
        } finally {
            onPlaybackAmplitudeChanged(0f)
            onPlaybackStateChanged(false)
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

    /**
     * 文字轉語音發音
     */
    fun speakText(text: String, utteranceId: String = System.currentTimeMillis().toString()) {
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
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioTrack = null
        onPlaybackStateChanged(false)
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
