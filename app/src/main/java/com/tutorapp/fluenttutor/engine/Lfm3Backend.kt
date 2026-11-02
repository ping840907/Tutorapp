package com.tutorapp.fluenttutor.engine

import android.content.Context
import android.util.Log
import com.tutorapp.fluenttutor.model.Scenario
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 全 LFM3-Audio-2B (GGUF) 後端，沒有任何其他模型或系統語音服務：
 *  語音 → [Lfm3GgufRunner.transcribe] 得到使用者文字
 *  文字 → [Lfm3GgufRunner.chat] 由 LFM3 生成 NPC 回覆 + <verdict>
 *  回覆 → [Lfm3GgufRunner.synthesize] 由 LFM3 合成語音
 * 任何一步失敗都丟出例外，由 UI 顯示錯誤；沒有罐頭對白。
 */
class Lfm3Backend(private val context: Context) : LfmBackend {

    companion object {
        private const val TAG = "Lfm3Backend"
    }

    override suspend fun generate(request: LfmRequest): LfmRawResponse {
        val userText = request.manualText?.trim()?.takeIf { it.isNotEmpty() } ?: transcribe(request.pcm16kMono)
        // 沒辨識出任何內容：不呼叫生成、不編造回覆
        if (userText.isBlank()) return LfmRawResponse(userTranscript = "", text = "")

        val system = buildString {
            append(request.systemPrompt)
            val recent = request.history.takeLast(6)
            if (recent.isNotEmpty()) {
                append("\n\nConversation so far:\n")
                for ((u, a) in recent) append("Learner: ").append(u).append("\nYou: ").append(a).append('\n')
            }
        }
        val raw = Lfm3GgufRunner.chat(context, system, userText)
        Log.i(TAG, "user=\"$userText\" -> raw=\"$raw\"")
        return LfmRawResponse(userTranscript = userText, text = raw)
    }

    override suspend fun generateGreeting(scenario: Scenario): String {
        val system = scenario.systemPrompt + "\n\nStay in character. Speak 1-2 short natural sentences. " +
            "Output only the words you say aloud: no quotes, no stage directions, no notes."
        val raw = Lfm3GgufRunner.chat(context, system, "(A customer has just walked up to you. Greet them and open the conversation.)")
        return LfmTextCleaner.spokenOnly(raw)
    }

    override suspend fun synthesizeSpeech(text: String, language: String): FloatArray =
        Lfm3GgufRunner.synthesize(context, text, language)

    private suspend fun transcribe(pcm: ByteArray?): String {
        if (pcm == null || pcm.size < 2) return ""
        val n = pcm.size / 2
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(n) { bb.short / 32768f }
        return Lfm3GgufRunner.transcribe(context, samples)
    }

    override fun release() {}
}

/** 清掉 LLM 輸出中不該被念出來的標記 */
object LfmTextCleaner {
    private val VERDICT_OPEN = Regex("<verdict>.*", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val THINK = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun spokenOnly(raw: String): String =
        raw.replace(THINK, "").replace(VERDICT_OPEN, "").trim().trim('"').trim()
}
