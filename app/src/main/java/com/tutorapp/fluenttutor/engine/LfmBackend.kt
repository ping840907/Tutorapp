package com.tutorapp.fluenttutor.engine

import com.tutorapp.fluenttutor.model.MissionGoal
import com.tutorapp.fluenttutor.model.Scenario

/** 一輪請求 (音訊與手動文字二擇一) */
data class LfmRequest(
    val systemPrompt: String,
    val scenario: Scenario,
    val goals: List<MissionGoal>,
    val history: List<Pair<String, String>>,
    val pcm16kMono: ByteArray?,
    val manualText: String?
)

/**
 * 後端的一輪輸出。
 * @param userTranscript 使用者這輪實際說/打的內容 (語音由 ASR 辨識得到；空字串＝沒辨識出任何內容)
 * @param text 模型原始輸出 (回覆 + <verdict> 區塊)。沒辨識出內容時為空字串，此時不會呼叫 LLM。
 */
class LfmRawResponse(
    val userTranscript: String,
    val text: String
)

/**
 * 推論後端：ASR → LLM。實作失敗時丟出 [ModelNotReadyException] 或其他例外，
 * 呼叫端必須把錯誤顯示給使用者，不得以預設對白頂替。
 */
interface LfmBackend {
    suspend fun generate(request: LfmRequest): LfmRawResponse
    suspend fun generateGreeting(scenario: Scenario): String
    fun release()
}
