package com.tutorapp.fluenttutor.engine

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tutorapp.fluenttutor.model.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * LFM3-Audio (FLM3 Audio) 語音模型客戶端
 * 連接 Liquid AI LFM3-Audio 或相容的即時語音服務端點。
 * 包含端側 Smart Simulator 備援模式，確保在無外部伺服器連線時也能獨立運作。
 */
class LfmAudioClient {

    companion object {
        private const val TAG = "LfmAudioClient"
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val simulator = SmartScenarioSimulator()

    // 伺服器配置 (可由使用者在設置頁更改)
    var serverUrl: String = "" // 例如 "http://192.168.1.100:8000/v1/audio/chat"
    var apiKey: String = ""
    var isMockMode: Boolean = true // 預設使用智慧模擬模式，免除連線門檻

    data class AudioChatResponse(
        val replyText: String,
        val recognizedUserText: String,
        val audioBytes: ByteArray? = null,
        val latencyMs: Long = 0L,
        val isFromRemote: Boolean = false
    )

    /**
     * 發送分段 PCM 語音進行推論
     * @param pcmData 16kHz 16-bit Mono PCM 音訊 Bytes
     * @param durationMs 語音片段時長
     * @param scenario 當前任務場景
     * @param turnIndex 對話輪次
     * @param simulatedTextInput (可選) 輔助文字輸入或語音識別結果
     */
    suspend fun sendAudioSegment(
        pcmData: ByteArray,
        durationMs: Long,
        scenario: Scenario,
        turnIndex: Int,
        simulatedTextInput: String? = null
    ): AudioChatResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // 若啟用 Mock Mode 或伺服器地址為空，走端側智慧模擬推論
        if (isMockMode || serverUrl.isBlank()) {
            // 模擬端側推論極低延遲 (約 180ms ~ 350ms)
            kotlinx.coroutines.delay(220)
            val sim = simulator.generateReply(scenario, durationMs, turnIndex, simulatedTextInput)
            val latency = System.currentTimeMillis() - startTime
            return@withContext AudioChatResponse(
                replyText = sim.replyText,
                recognizedUserText = sim.recognizedUserText,
                audioBytes = null, // 將由 AudioPlayer 本地 TTS 發音
                latencyMs = latency,
                isFromRemote = false
            )
        }

        // 走遠端 LFM3-Audio 服務
        try {
            val audioBase64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)
            val jsonPayload = JsonObject().apply {
                addProperty("model", "lfm3-audio-2b")
                addProperty("language", scenario.language.code)
                addProperty("system_prompt", scenario.systemPrompt)
                addProperty("audio_format", "pcm_16000_16bit")
                addProperty("audio_base64", audioBase64)
                addProperty("turn", turnIndex)
                if (!simulatedTextInput.isNullOrBlank()) {
                    addProperty("text_prompt", simulatedTextInput)
                }
            }

            val requestBody = jsonPayload.toString().toRequestBody("application/json".toMediaType())
            val requestBuilder = Request.Builder()
                .url(serverUrl)
                .post(requestBody)

            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                val respBody = response.body?.string() ?: ""
                val jsonResp = gson.fromJson(respBody, JsonObject::class.java)

                val replyText = jsonResp.get("reply_text")?.asString ?: "I heard you."
                val recognizedUserText = jsonResp.get("user_transcript")?.asString ?: simulatedTextInput ?: "Audio message"
                val audioRespBase64 = jsonResp.get("reply_audio_base64")?.asString
                val audioBytes = if (!audioRespBase64.isNullOrBlank()) {
                    Base64.decode(audioRespBase64, Base64.DEFAULT)
                } else null

                val latency = System.currentTimeMillis() - startTime
                return@withContext AudioChatResponse(
                    replyText = replyText,
                    recognizedUserText = recognizedUserText,
                    audioBytes = audioBytes,
                    latencyMs = latency,
                    isFromRemote = true
                )
            } else {
                Log.w(TAG, "LFM3 server returned error code ${response.code}, falling back to simulator")
            }
        } catch (e: Exception) {
            Log.e(TAG, "LFM3 network request failed: ${e.message}, fallback to simulator", e)
        }

        // 伺服器異常時自動降級至 Smart Simulator 備援
        val sim = simulator.generateReply(scenario, durationMs, turnIndex, simulatedTextInput)
        val latency = System.currentTimeMillis() - startTime
        AudioChatResponse(
            replyText = sim.replyText,
            recognizedUserText = sim.recognizedUserText,
            audioBytes = null,
            latencyMs = latency,
            isFromRemote = false
        )
    }
}
