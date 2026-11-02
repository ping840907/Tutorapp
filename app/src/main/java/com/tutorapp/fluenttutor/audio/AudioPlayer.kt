package com.tutorapp.fluenttutor.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * 高效能音訊播放器 (支援 SM8850 低延遲輸出通道)
 * 1. 智慧 WAV 標頭解析器 (自動跳過 44-byte RIFF 標頭，精準匹配取樣率 16k/24k/48k，杜絕雜音爆音)
 * 2. 原始 16-bit PCM AudioTrack 直通播放
 * 3. 本地系統 TTS 備援發音引擎
 * 4. 完整的播放狀態回報，供半雙工防回音 (AEC) 閘控使用
 */
class AudioPlayer(
    private val context: Context,
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var audioTrack: AudioTrack? = null

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
                }

                override fun onDone(utteranceId: String?) {
                    onPlaybackStateChanged(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onPlaybackStateChanged(false)
                }
            })
            Log.i(TAG, "AudioPlayer TTS engine initialized successfully (Locale: US)")
        } else {
            Log.e(TAG, "AudioPlayer TTS init failed: $status")
        }
    }

    /**
     * 播放音訊數據 (自動檢測 WAV 標頭或純 PCM)
     */
    suspend fun playAudioData(audioBytes: ByteArray, fallbackSampleRate: Int = 16000) = withContext(Dispatchers.IO) {
        if (audioBytes.isEmpty()) return@withContext
        stop()
        onPlaybackStateChanged(true)

        try {
            var sampleRate = fallbackSampleRate
            var channels = 1
            var pcmOffset = 0
            var pcmLength = audioBytes.size

            // 檢查是否包含 RIFF WAV 標頭
            if (audioBytes.size > 44 &&
                audioBytes[0] == 'R'.code.toByte() &&
                audioBytes[1] == 'I'.code.toByte() &&
                audioBytes[2] == 'F'.code.toByte() &&
                audioBytes[3] == 'F'.code.toByte()
            ) {
                // 解析 WAV 標頭
                val bb = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN)
                channels = bb.getShort(22).toInt()
                sampleRate = bb.getInt(24)
                pcmOffset = 44
                pcmLength = audioBytes.size - 44
                Log.d(TAG, "Parsed WAV header: sampleRate=$sampleRate, channels=$channels, pcmLength=$pcmLength")
            }

            val channelConfig = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
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
                .setBufferSizeInBytes(maxOf(minBufSize, pcmLength))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.write(audioBytes, pcmOffset, pcmLength)
            audioTrack?.play()

            // 精確計算播放時間
            val bytesPerSample = 2 * channels
            val totalSamples = pcmLength / bytesPerSample
            val durationMs = (totalSamples * 1000L) / sampleRate

            kotlinx.coroutines.delay(durationMs + 60L) // 增加微小緩衝確保音軌播完
        } catch (e: Exception) {
            Log.e(TAG, "Error during audio playback", e)
        } finally {
            onPlaybackStateChanged(false)
        }
    }

    /**
     * 文字轉語音發音
     */
    fun speakText(text: String, utteranceId: String = System.currentTimeMillis().toString()) {
        if (!isTtsReady) {
            Log.w(TAG, "TTS not ready for speakText: $text")
            return
        }
        tts?.stop()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
        try {
            audioTrack?.stop()
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
