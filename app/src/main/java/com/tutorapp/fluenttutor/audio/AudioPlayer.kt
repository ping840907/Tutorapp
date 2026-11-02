package com.tutorapp.fluenttutor.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.tutorapp.fluenttutor.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 混合音訊播放器：
 * 1. 支援 PCM 原始音訊流播放 (AudioTrack)
 * 2. 支援內建 Android TTS (支援英文 en-US / 日語 ja-JP)
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
    private var currentLanguage: Language = Language.ENGLISH

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            setupTtsLanguage(currentLanguage)
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
        } else {
            Log.e(TAG, "TTS initialization failed: $status")
        }
    }

    fun setLanguage(language: Language) {
        currentLanguage = language
        if (isTtsReady) {
            setupTtsLanguage(language)
        }
    }

    private fun setupTtsLanguage(language: Language) {
        val targetLocale = when (language) {
            Language.ENGLISH -> Locale.US
            Language.JAPANESE -> Locale.JAPAN
        }
        val result = tts?.setLanguage(targetLocale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "TTS language not supported for $targetLocale")
        }
    }

    /**
     * 以語音朗讀文字
     */
    fun speakText(text: String, utteranceId: String = System.currentTimeMillis().toString()) {
        if (!isTtsReady) {
            Log.w(TAG, "TTS not ready yet")
            return
        }
        tts?.stop()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * 播放 PCM 音訊 Bytes (16kHz 16bit Mono)
     */
    suspend fun playPcm(pcmBytes: ByteArray, sampleRate: Int = 16000) = withContext(Dispatchers.IO) {
        stop()
        onPlaybackStateChanged(true)
        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBufSize, pcmBytes.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.write(pcmBytes, 0, pcmBytes.size)
            audioTrack?.play()

            val durationMs = (pcmBytes.size / (sampleRate * 2.0) * 1000).toLong()
            kotlinx.coroutines.delay(durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing PCM audio", e)
        } finally {
            onPlaybackStateChanged(false)
        }
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
