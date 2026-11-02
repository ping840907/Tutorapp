package com.tutorapp.fluenttutor.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/**
 * NPC 語音播放器。
 *
 * TTS 設定與 Verbead 的 TtsSpeaker 完全一致 (該實作已在實機驗證可正常發聲)：
 * Android TextToSpeech + STREAM_MUSIC + CONTENT_TYPE_SPEECH。
 * 不再有自製 vocoder / 神經 codec 路徑 —— 那條路徑是「沙沙雜音」的來源。
 *
 * 注意：TextToSpeech.speak() 不會把 PCM 交給 App，所以嘴型振幅是依播放狀態產生的模擬動畫，不是真實音量。
 */
class AudioPlayer(
    context: Context,
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit,
    private val onPlaybackAmplitudeChanged: (amplitude: Float) -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val UTTERANCE_PREFIX = "tutor_tts_"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default)
    private var amplitudeJob: Job? = null

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    @Volatile private var isReady = false
    @Volatile private var currentUtterance: String? = null
    private var pendingText: String? = null

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TextToSpeech init failed, status=$status")
            return
        }
        var result = engine.setLanguage(Locale.US)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            result = engine.setLanguage(Locale.ENGLISH)
        }
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "No English TTS voice data installed on this device")
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setPitch(1.0f)
        engine.setSpeechRate(1.0f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != currentUtterance) return
                mainHandler.post {
                    onPlaybackStateChanged(true)
                    startAmplitudeSimulation()
                }
            }

            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "TTS error for $utteranceId")
                finish(utteranceId)
            }
        })
        isReady = true
        Log.i(TAG, "TextToSpeech ready, voice=${engine.voice?.name}, locale=${engine.voice?.locale}")
        pendingText?.let { pendingText = null; speakText(it) }
    }

    private fun finish(utteranceId: String?) {
        if (utteranceId != currentUtterance) return // 已被新的 utterance 取代
        currentUtterance = null
        mainHandler.post {
            stopAmplitudeSimulation()
            onPlaybackStateChanged(false)
        }
    }

    /** 念出文字 (取代正在播放的內容)。 */
    fun speakText(text: String) {
        if (text.isBlank()) return
        if (!isReady) {
            pendingText = text
            return
        }
        val id = "$UTTERANCE_PREFIX${System.nanoTime()}"
        currentUtterance = id
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        if (r != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak() failed: $r")
            finish(id)
        }
    }

    fun stop() {
        pendingText = null
        val wasPlaying = currentUtterance != null
        currentUtterance = null
        runCatching { tts?.stop() }
        mainHandler.post {
            stopAmplitudeSimulation()
            if (wasPlaying) onPlaybackStateChanged(false)
        }
    }

    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        isReady = false
        scope.cancel()
    }

    private fun startAmplitudeSimulation() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            var phase = 0.0
            while (isActive) {
                phase += 0.4
                onPlaybackAmplitudeChanged((0.20f + 0.60f * abs(sin(phase)).toFloat()).coerceIn(0.08f, 0.90f))
                delay(60L)
            }
        }
    }

    private fun stopAmplitudeSimulation() {
        amplitudeJob?.cancel()
        amplitudeJob = null
        onPlaybackAmplitudeChanged(0f)
    }
}
