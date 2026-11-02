package com.tutorapp.fluenttutor.engine

import com.tutorapp.fluenttutor.model.MissionGoal
import com.tutorapp.fluenttutor.model.Scenario

/** 送進 LFM3-Audio 的一輪請求 (音訊與手動文字二擇一) */
data class LfmRequest(
    val systemPrompt: String,
    val scenario: Scenario,
    val goals: List<MissionGoal>,
    val history: List<Pair<String, String>>,
    val pcm16kMono: ByteArray?,
    val manualText: String?
)

/**
 * 模型原始輸出：文字 (回覆 + <verdict> 區塊，見 [ModelOutputParser]) 與模型生成的語音。
 * audioBytes 可為 WAV 或純 PCM，null 時由 App 退回系統 TTS 念 replyText。
 */
class LfmRawResponse(
    val text: String,
    val audioBytes: ByteArray?
)

/**
 * LFM3-Audio-2B 端側推論後端。真正的推論執行期 (llama.cpp / ExecuTorch / QNN / LiteRT…) 實作此介面即可接入，
 * 其餘 App 邏輯 (提示詞、解析、狀態機、UI) 不需更動。
 */
interface LfmBackend {
    suspend fun generate(request: LfmRequest): LfmRawResponse
    fun synthesizeSpeech(text: String): ByteArray? = null
    fun release()
}
