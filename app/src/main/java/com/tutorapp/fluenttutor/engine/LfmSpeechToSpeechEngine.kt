package com.tutorapp.fluenttutor.engine

import android.os.Debug
import com.tutorapp.fluenttutor.model.MissionGoal
import com.tutorapp.fluenttutor.model.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 唯一的推論引擎：LFM3-Audio Speech-to-Speech。
 * 同一個模型一次輸出「口說回覆 + 結構化評分/目標判定」，由 [MissionStateMachine] 驗證並記錄，
 * 不再有第二個判定引擎。實際推論由 [LfmBackend] 提供。
 */
class LfmSpeechToSpeechEngine(
    private val backend: LfmBackend,
    private val memoryProbe: () -> Long = { defaultMemoryUsageMb() }
) {
    companion object {
        internal const val FALLBACK_REPLY = "Sorry, I didn't catch that. Could you say it again?"

        fun defaultMemoryUsageMb(): Long {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            return memInfo.totalPss / 1024L
        }
    }

    class S2SResult(
        /** 要顯示/念出的 NPC 回覆 */
        val replyText: String,
        /** 解析成功的結構化輸出；解析失敗為 null (該回合不計分、不改變任務狀態) */
        val output: ModelTurnOutput?,
        val audioBytes: ByteArray?,
        val audioStream: Flow<ByteArray>? = null,
        val latencyMs: Long,
        val realRamMb: Long
    )

    fun getRealMemoryUsageMb(): Long = memoryProbe()

    suspend fun process(
        pcmData: ByteArray?,
        scenario: Scenario,
        goals: List<MissionGoal>,
        history: List<Pair<String, String>>,
        manualText: String?
    ): S2SResult = withContext(Dispatchers.Default) {
        val startNano = System.nanoTime()

        val request = LfmRequest(
            systemPrompt = LfmPromptBuilder.build(scenario, goals),
            scenario = scenario,
            goals = goals,
            history = history,
            pcm16kMono = pcmData,
            manualText = manualText?.trim()?.takeIf { it.isNotEmpty() }
        )
        val raw = backend.generate(request)

        val output = ModelOutputParser.parse(raw.text)
        // output == null 代表模型沒有依協議輸出 verdict：該回合不計分、不改變任務狀態 (由 ViewModel 處理)
        val reply = (output?.reply ?: ModelOutputParser.replyOf(raw.text)).ifBlank { FALLBACK_REPLY }

        S2SResult(
            replyText = reply,
            output = output,
            audioBytes = raw.audioBytes,
            audioStream = raw.audioStream,
            latencyMs = (System.nanoTime() - startNano) / 1_000_000L,
            realRamMb = memoryProbe()
        )
    }

    fun synthesizeSpeech(text: String): ByteArray? = backend.synthesizeSpeech(text)

    fun synthesizeSpeechStream(text: String): Flow<ByteArray> = backend.synthesizeSpeechStream(text)

    fun release() = backend.release()
}
