package com.tutorapp.fluenttutor.engine.ondevice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.tutorapp.fluenttutor.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID

/**
 * 手機端側純本機語音合成生成器 (On-Device Local Audio Synthesizer)
 * 100% 在手機晶片 (ARM64) 上運作，無需聯網即可生成高質量語音音訊。
 */
class OnDeviceAudioSynthesizer(
    private val context: Context,
    private val onReady: () -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "OnDeviceAudioSynthesizer"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            onReady()
            Log.i(TAG, "On-device speech synthesizer initialized successfully.")
        } else {
            Log.e(TAG, "Failed to initialize on-device speech engine: $status")
        }
    }

    fun isReady(): Boolean = isInitialized

    /**
     * 在手機本地端直接將文字合成並生成 WAV / PCM 音訊檔案與 Byte 數據 (離線音訊生成)
     * @param text 要生成的語音文本
     * @param language 語言 (English / Japanese)
     * @return 合成出的音訊 Byte 數組 (WAV / PCM)
     */
    suspend fun synthesizeAudioToFile(
        text: String,
        language: Language
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not ready for on-device synthesis")
            return@withContext null
        }

        val targetLocale = when (language) {
            Language.ENGLISH -> Locale.US
            Language.JAPANESE -> Locale.JAPAN
        }
        tts?.language = targetLocale

        val tempAudioFile = File(context.cacheDir, "ondevice_synth_${UUID.randomUUID()}.wav")
        val utteranceId = "synth_${System.currentTimeMillis()}"

        var completed = false
        val listener = object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId) completed = true
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) completed = true
            }
        }
        tts?.setOnUtteranceProgressListener(listener)

        val result = tts?.synthesizeToFile(text, null, tempAudioFile, utteranceId)
        if (result == TextToSpeech.SUCCESS) {
            // 等待本地生成完成 (通常只需 50ms ~ 200ms)
            var waitCount = 0
            while (!completed && waitCount < 30) {
                kotlinx.coroutines.delay(50)
                waitCount++
            }

            if (tempAudioFile.exists() && tempAudioFile.length() > 0) {
                val bytes = tempAudioFile.readBytes()
                tempAudioFile.delete()
                Log.i(TAG, "On-device speech generated: ${bytes.size} bytes locally")
                return@withContext bytes
            }
        }

        return@withContext null
    }

    fun release() {
        tts?.shutdown()
        tts = null
    }
}
