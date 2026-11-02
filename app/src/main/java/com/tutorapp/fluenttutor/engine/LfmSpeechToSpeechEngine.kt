package com.tutorapp.fluenttutor.engine

import android.os.Debug
import com.tutorapp.fluenttutor.model.MissionGoal
import com.tutorapp.fluenttutor.model.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 對話引擎：LFM3 ASR (使用者說了什麼) → LFM3 對話 (NPC 回覆 + 結構化評分/目標判定)，
 * 由 [MissionStateMachine] 驗證並記錄。回覆語音由 LFM3 TTS 合成。
 */
class LfmSpeechToSpeechEngine(
    private val backend: LfmBackend,
    private val memoryProbe: () -> Long = { defaultMemoryUsageMb() }
) {
    companion object {
        fun defaultMemoryUsageMb(): Long {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            return memInfo.totalPss / 1024L
        }
    }

    class S2SResult(
        /** 使用者這輪的內容 (ASR 結果或手動輸入)；空字串＝沒聽到 */
        val userTranscript: String,
        /** NPC 回覆；沒聽到使用者時為空字串 (不會有任何預設對白) */
        val replyText: String,
        /** 解析成功的結構化輸出；失敗為 null (該回合不計分、不改變任務狀態) */
        val output: ModelTurnOutput?,
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

        // 使用者的話一律以 ASR/手動輸入為準，不採信 LLM 自己寫的 transcript
        val output = ModelOutputParser.parse(raw.text)?.copy(transcript = raw.userTranscript)
        val reply = output?.reply ?: LfmTextCleaner.spokenOnly(raw.text)

        S2SResult(
            userTranscript = raw.userTranscript,
            replyText = reply,
            output = output,
            latencyMs = (System.nanoTime() - startNano) / 1_000_000L,
            realRamMb = memoryProbe()
        )
    }

    suspend fun generateGreeting(scenario: Scenario): String = backend.generateGreeting(scenario)

    suspend fun synthesizeSpeech(text: String, language: String): FloatArray = backend.synthesizeSpeech(text, language)

    fun release() = backend.release()
}
