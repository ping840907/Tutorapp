package com.tutorapp.fluenttutor.engine

import android.content.Context
import android.util.Log
import com.tutorapp.fluenttutor.model.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 真實端側推論後端 (實作方式參考 Verbead)：
 *  - 語音 → [Qwen3AsrEngine] (sherpa-onnx) 辨識成文字；
 *  - 文字 → llama.cpp 載入 Qwen3.5 GGUF 生成 NPC 回覆與 <verdict>。
 * 任何一步失敗都會丟出例外，不會有罐頭對白、也不會呼叫 Google/系統語音辨識。
 */
class LlamaChatBackend(private val context: Context) : LfmBackend {

    companion object {
        private const val TAG = "LlamaChatBackend"
        private val STOP_SEQUENCES = listOf("<|im_end|>", "<|endoftext|>", "</s>")
    }

    private val asr = Qwen3AsrEngine(context)
    private val loadMutex = Mutex()
    @Volatile private var llm: LlamaModel? = null

    private suspend fun ensureLlm(): LlamaModel = loadMutex.withLock {
        llm?.takeIf { it.isLoaded }?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            val file = ModelStore.llmFile(context)
                ?: throw ModelNotReadyException(
                    "LLM model missing. Put a Qwen3.5 GGUF in:\n${ModelStore.llmDir(context).absolutePath}/\n" +
                        "(e.g. ${ModelStore.LLM_FILE_CANDIDATES.first()})"
                )
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
            Log.i(TAG, "Loading ${file.name} (${file.length() / (1024 * 1024)} MB), threads=$threads")
            val model = try {
                LlamaModel.load(file.absolutePath) {
                    gpuLayers = 99
                    contextSize = 4096
                    batchSize = 512
                    this.threads = threads
                    threadsBatch = threads
                    useMmap = true
                    useMlock = false
                    temperature = 0.4f
                    topP = 0.9f
                    topK = 40
                    repeatPenalty = 1.1f
                    maxTokens = 320
                    stopSequences = STOP_SEQUENCES
                }
            } catch (gpuErr: Throwable) {
                Log.w(TAG, "GPU load failed, falling back to CPU: ${gpuErr.message}", gpuErr)
                try {
                    LlamaModel.load(file.absolutePath) {
                        gpuLayers = 0
                        contextSize = 4096
                        batchSize = 512
                        this.threads = threads
                        threadsBatch = threads
                        useMmap = true
                        useMlock = false
                        temperature = 0.4f
                        topP = 0.9f
                        topK = 40
                        repeatPenalty = 1.1f
                        maxTokens = 320
                        stopSequences = STOP_SEQUENCES
                    }
                } catch (e: Throwable) {
                    throw ModelNotReadyException("Failed to load LLM: ${e.message}", e)
                }
            }
            llm = model
            model
        }
    }

    private suspend fun runLlm(prompt: String): String = withContext(Dispatchers.Default) {
        val model = ensureLlm()
        val sb = StringBuilder()
        model.generateStream(prompt).collect { token -> sb.append(token) }
        cleanOutput(sb.toString())
    }

    override suspend fun generate(request: LfmRequest): LfmRawResponse {
        // 1. 使用者輸入：手動文字原樣使用；語音一律走 ASR
        val userText = request.manualText?.trim()?.takeIf { it.isNotEmpty() }
            ?: transcribe(request.pcm16kMono)

        // 沒辨識出任何內容：不呼叫 LLM、不編造回覆，由呼叫端提示使用者
        if (userText.isBlank()) return LfmRawResponse(userTranscript = "", text = "")

        // 2. LLM 生成 NPC 回覆 + verdict
        val prompt = buildPrompt(request.systemPrompt, request.history, userText)
        val raw = runLlm(prompt)
        Log.i(TAG, "user=\"$userText\" -> raw=\"$raw\"")
        return LfmRawResponse(userTranscript = userText, text = raw)
    }

    override suspend fun generateGreeting(scenario: Scenario): String {
        val system = scenario.systemPrompt + "\n\nStay in character. Speak 1-2 short natural sentences. " +
            "Output only the words you say aloud: no quotes, no stage directions, no notes."
        val prompt = chatMl(system, emptyList(), "(A customer has just walked up to you. Greet them and open the conversation.)")
        return LfmTextCleaner.spokenOnly(runLlm(prompt))
    }

    private suspend fun transcribe(pcm: ByteArray?): String {
        if (pcm == null || pcm.size < 2) return ""
        if (!asr.isLoaded()) asr.load()
        val n = pcm.size / 2
        val samples = FloatArray(n)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) samples[i] = bb.short / 32768f
        return asr.transcribe(samples)
    }

    private fun buildPrompt(system: String, history: List<Pair<String, String>>, userText: String) =
        chatMl(system, history.takeLast(6), userText)

    /** Qwen ChatML；assistant 開頭預填空 think 區塊以關閉思考 (同 Verbead) */
    private fun chatMl(system: String, history: List<Pair<String, String>>, userText: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        for ((u, a) in history) {
            sb.append("<|im_start|>user\n").append(u).append("<|im_end|>\n")
            sb.append("<|im_start|>assistant\n").append(a).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n").append(userText).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n<think>\n\n</think>\n")
        return sb.toString()
    }

    private fun cleanOutput(text: String): String =
        text.replace("<|im_end|>", "").replace("<|endoftext|>", "").replace("<|im_start|>", "").replace("</s>", "").trim()

    override fun release() {
        asr.release()
        runCatching { llm?.cancelGeneration(); llm?.close() }
        llm = null
    }
}

/** 清掉 LLM 輸出中不該被念出來的標記 */
object LfmTextCleaner {
    private val VERDICT_OPEN = Regex("<verdict>.*", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val THINK = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun spokenOnly(raw: String): String =
        raw.replace(THINK, "").replace(VERDICT_OPEN, "").trim().trim('"').trim()
}
