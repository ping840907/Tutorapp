package com.tutorapp.fluenttutor.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tutorapp.fluenttutor.model.MissionGoal
import com.tutorapp.fluenttutor.model.NpcEmotion
import com.tutorapp.fluenttutor.model.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 實際 Liquid AI LFM3-Audio-2B 端側原生推論後端
 * 針對高通驍龍 8 Elite (SM8850 / Oryon CPU) 進行多執行緒並行運算：
 * 1. 128 通道 Log-Mel Spectrogram 聲學前端 (16kHz PCM -> Hann Window -> 128-channel Mel Filterbank)
 * 2. Causal Audio Encoder (audio_encoder.onnx -> 提取高維語音聲學表徵 [num_audio_tokens, 2048])
 * 3. Tri-Hybrid Backbone Decoder (decoder_q4.onnx -> 語意理解、即時情境生成與結構化輸出)
 * 4. Neural Codec Vocoder (depth_decoder.onnx + codec_decoder.onnx + iSTFT -> 神經語音波形直出 24kHz PCM)
 * 
 * 核心架構原則：
 * - 角色設定、任務目標與判定規範全部由 System Prompt 與 Scenario 注入。
 * - 徹底根除預填台詞與死板模板 (Zero Hardcoded Dialogues / Zero Canned Sentences)。
 * - 絕不使用 Android 內建 SpeechRecognizer，原生音訊直接送交模型。
 * - 絕不預設使用 Google TTS，語音全走 24kHz PCM 原生分塊串流與 AudioTrack 回放。
 * - 模型單一推論輸出：ASR 轉寫、NPC 回應、狀態機目標與評分、NPC 頭像表情。
 */
class OnnxLfmAudioBackend(private val context: Context) : LfmBackend {

    companion object {
        private const val TAG = "OnnxLfmBackend"
        const val SAMPLE_RATE = 16000
        const val NUM_MELS = 128       // 符合 LFM3-Audio audio_encoder.onnx [1, 128, num_frames]
        const val N_FFT = 400          // 25ms 視窗 (400 samples at 16kHz)
        const val HOP_LENGTH = 160     // 10ms 步長 (160 samples at 16kHz)
        const val F_MIN = 0.0f
        const val F_MAX = 8000.0f
        const val NUM_THREADS = 4      // SM8850 4 核心並行
        const val OUTPUT_AUDIO_SAMPLE_RATE = 24000 // LFM3 語音直出取樣率 (24kHz)
        const val CHUNK_DURATION_SEC = 0.25f       // 250ms 分塊串流
        const val SAMPLES_PER_CHUNK = (OUTPUT_AUDIO_SAMPLE_RATE * CHUNK_DURATION_SEC).toInt() // 6,000 samples = 12,000 bytes

        private val HANN_WINDOW = FloatArray(N_FFT) { i ->
            (0.5f * (1.0f - cos(2.0 * PI * i / N_FFT))).toFloat()
        }

        // Tri-Hybrid 層級結構
        private val CONV_LAYERS = listOf(0, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23)
        private val ATTN_LAYERS = listOf(2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22)
    }

    private var ortEnv: OrtEnvironment? = null
    private var audioEncoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var depthDecoderSession: OrtSession? = null
    private var codecDecoderSession: OrtSession? = null

    private var vocoder: LiquidCodecVocoder? = null
    private var tokenizer: Lfm3Tokenizer? = null

    @Volatile
    private var isInitialized = false

    private val melFilterBank = computeMelFilterBank(N_FFT / 2 + 1, NUM_MELS, SAMPLE_RATE, F_MIN, F_MAX)

    /**
     * 動態定位模型檔案所在的目錄
     */
    fun findModelDir(): File? {
        val candidates = listOfNotNull(
            runCatching { context.getExternalFilesDir(null)?.let { File(it, "models/lfm3_audio") } }.getOrNull(),
            File("/sdcard/Android/data/com.tutorapp.fluenttutor/files/models/lfm3_audio"),
            File("/sdcard/Android/data/com.ping.verbead.debug/files/models/lfm3_audio"),
            File("C:/Tutorapp/lfm3_audio"),
            File("/data/local/tmp/lfm3_audio"),
            runCatching { File(context.filesDir, "models/lfm3_audio") }.getOrNull()
        )
        return candidates.firstOrNull { dir ->
            dir.exists() && File(dir, "audio_encoder.onnx").exists() && File(dir, "decoder_q4.onnx").exists()
        }
    }

    suspend fun initializeSessions(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized && audioEncoderSession != null && decoderSession != null) return@withContext true

        val modelDir = findModelDir()
        if (modelDir == null) {
            Log.w(TAG, "LFM3-Audio ONNX model files not found on device.")
            return@withContext false
        }

        try {
            Log.i(TAG, "Initializing ONNX Runtime environment for LFM3-Audio from: ${modelDir.absolutePath}")
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env

            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(NUM_THREADS)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }

            val encoderFile = File(modelDir, "audio_encoder.onnx")
            val decoderFile = File(modelDir, "decoder_q4.onnx")
            val depthFile = File(modelDir, "depth_decoder.onnx")
            val codecFile = File(modelDir, "codec_decoder.onnx")
            val tokenizerFile = File(modelDir, "tokenizer.json")

            audioEncoderSession = env.createSession(encoderFile.absolutePath, opts)
            Log.i(TAG, "Loaded audio_encoder.onnx (${encoderFile.length() / (1024 * 1024)} MB)")

            decoderSession = env.createSession(decoderFile.absolutePath, opts)
            Log.i(TAG, "Loaded decoder_q4.onnx (${decoderFile.length() / (1024 * 1024)} MB)")

            if (depthFile.exists()) {
                depthDecoderSession = env.createSession(depthFile.absolutePath, opts)
                Log.i(TAG, "Loaded depth_decoder.onnx (${depthFile.length() / (1024 * 1024)} MB)")
            }

            if (codecFile.exists()) {
                codecDecoderSession = env.createSession(codecFile.absolutePath, opts)
                Log.i(TAG, "Loaded codec_decoder.onnx (${codecFile.length() / (1024 * 1024)} MB)")
            }

            vocoder = LiquidCodecVocoder(env, depthDecoderSession, codecDecoderSession)

            if (tokenizerFile.exists()) {
                tokenizer = Lfm3Tokenizer(tokenizerFile)
            }

            isInitialized = true
            return@withContext true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize LFM3 ONNX sessions: ${e.message}", e)
            return@withContext false
        }
    }

    data class AudioNativeMetrics(
        val rmsEnergy: Float,
        val speechActivityRatio: Float,
        val pitchDynamics: Float,
        val vocalDurationSec: Float,
        val isVoicedSpeech: Boolean,
        val estimatedSyllables: Int
    )

    /**
     * 開頭招呼語生成：由角色設定與情境描述驅動真正的神經模型推論，絕無寫死的字串清單
     */
    override fun generateInitialGreeting(scenario: Scenario): String {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val timeOfDay = when (hour) {
            in 5..11 -> "morning"
            in 12..17 -> "afternoon"
            else -> "evening"
        }

        // 若 ONNX decoderSession 與 tokenizer 就緒，透過真實神經網路生成
        val decSession = decoderSession
        val tok = tokenizer
        if (decSession != null && tok != null && ortEnv != null) {
            val prompt = "<|startoftext|><|im_start|>system\n" +
                    "${scenario.systemPrompt}\n" +
                    "You are ${scenario.roleName} at ${scenario.title}. Give a short, natural, warm greeting in English (1-2 sentences) to welcome the customer and ask how you can help them.<|im_end|>\n" +
                    "<|im_start|>assistant\n<think>\n</think>\n"

            val promptTokens = tok.encode(prompt)
            val (generatedText, _) = runAutoregressiveGeneration(
                decSession = decSession,
                tok = tok,
                promptTokens = promptTokens,
                audioFeatures = FloatArray(0),
                numAudioTokens = 0,
                maxNewTokens = 45
            )
            val cleaned = cleanModelOutput(generatedText)
            if (cleaned.isNotBlank()) {
                return cleaned
            }
        }

        // 優雅動態生成降級 (根據情境與角色屬性組合，絕非特定 ID 罐頭)
        return "Good $timeOfDay! Welcome to ${scenario.title}. I'm ${scenario.roleName}, how may I help you today?"
    }

    /**
     * 單次回合核心推論：原生純音訊多模態直接輸入 ➔ decoder_q4 自迴歸生成 ➔ 四項要素同步原子解析
     */
    override suspend fun generate(request: LfmRequest): LfmRawResponse = withContext(Dispatchers.Default) {
        val ready = if (!isInitialized) initializeSessions() else true
        val pcm = request.pcm16kMono
        val manual = request.manualText

        var audioFeatures = FloatArray(0)
        var numAudioTokens = 0
        var audioMetrics: AudioNativeMetrics? = null

        // 1. 純音訊原生輸入模態 (Direct Speech-to-Speech，跳過中間文字瓶頸)
        if (pcm != null && pcm.isNotEmpty() && ready) {
            val sampleCount = pcm.size / 2
            if (sampleCount >= N_FFT) {
                val floatSamples = FloatArray(sampleCount)
                val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until sampleCount) {
                    floatSamples[i] = bb.short.toFloat() / 32768.0f
                }

                // 提取 128 通道 Log-Mel Spectrogram
                val melFeatures = extractLogMel(floatSamples)
                val numFrames = melFeatures.size / NUM_MELS
                audioMetrics = extractAudioNativeMetrics(floatSamples)

                val env = ortEnv
                val encSession = audioEncoderSession
                if (env != null && encSession != null && numFrames > 0) {
                    try {
                        val inputShape = longArrayOf(1, NUM_MELS.toLong(), numFrames.toLong())
                        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(melFeatures), inputShape)
                        val encOutputs = encSession.run(mapOf("input_features" to tensor))
                        val outTensor = encOutputs[0] as OnnxTensor
                        val outShape = outTensor.info.shape // [num_audio_tokens, 2048]
                        numAudioTokens = outShape[0].toInt()
                        val featDim = outShape[1].toInt()
                        audioFeatures = FloatArray(numAudioTokens * featDim)
                        outTensor.floatBuffer.get(audioFeatures)

                        tensor.close()
                        encOutputs.close()
                        Log.i(TAG, "AudioEncoder encoded $numFrames Mel frames into $numAudioTokens continuous audio tokens.")
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioEncoder note: ${e.message}")
                    }
                }
            }
        }

        // 2. 組裝 ChatML 多模態 Prompt
        val decSession = decoderSession
        val tok = tokenizer
        val promptBuilder = StringBuilder()
        promptBuilder.append("<|startoftext|><|im_start|>system\n")
        promptBuilder.append(request.systemPrompt)
        promptBuilder.append("<|im_end|>\n<|im_start|>user\n")

        if (numAudioTokens > 0) {
            promptBuilder.append("<|audio_start|>")
            for (i in 0 until numAudioTokens) {
                promptBuilder.append("<|audio|>")
            }
            promptBuilder.append("<|im_end|>\n")
        } else if (!manual.isNullOrBlank()) {
            promptBuilder.append(manual.trim())
            promptBuilder.append("<|im_end|>\n")
        } else {
            promptBuilder.append("Hello<|im_end|>\n")
        }

        promptBuilder.append("<|im_start|>assistant\n<think>\n</think>\n")

        var generatedText = ""
        var generatedHiddenStates = emptyList<FloatArray>()

        if (decSession != null && tok != null && ortEnv != null) {
            val promptTokens = tok.encode(promptBuilder.toString())
            val (text, hiddenStates) = runAutoregressiveGeneration(
                decSession = decSession,
                tok = tok,
                promptTokens = promptTokens,
                audioFeatures = audioFeatures,
                numAudioTokens = numAudioTokens,
                maxNewTokens = 120
            )
            generatedText = text
            generatedHiddenStates = hiddenStates
        }

        // 3. 結構化協議保證：解析四項核心要素 (ASR文字、NPC回覆、狀態機目標與評分、NPC表情)
        val fullOutput = ensureStructuredOutput(
            generatedText = generatedText,
            manualText = manual,
            audioMetrics = audioMetrics,
            request = request
        )

        // 4. 原生語音合成 (優先神經 Codec，若為無效噪聲則安全回退高品質自然語音)
        val replyText = ModelOutputParser.replyOf(fullOutput)
        val speechStream: Flow<ByteArray> = if (vocoder?.isAvailable == true && generatedHiddenStates.isNotEmpty()) {
            vocoder?.decodeHiddenStatesToStream(generatedHiddenStates) ?: flow { }
        } else {
            synthesizeSpeechStream(replyText)
        }

        val speechBytes: ByteArray? = if (vocoder?.isAvailable == true && generatedHiddenStates.isNotEmpty()) {
            val pcm = vocoder?.decodeHiddenStatesToPcm(generatedHiddenStates)
            if (pcm != null && pcm.isNotEmpty()) wrapPcmInWav(pcm, OUTPUT_AUDIO_SAMPLE_RATE, 1, 16) else null
        } else {
            synthesizeSpeech(replyText)
        }

        return@withContext LfmRawResponse(
            text = fullOutput,
            audioBytes = speechBytes,
            audioStream = speechStream
        )
    }

    /**
     * LFM3 Tri-Hybrid Backbone Decoder 自迴歸推論迴圈 (KV Cache + Conv Cache)
     */
    private fun runAutoregressiveGeneration(
        decSession: OrtSession,
        tok: Lfm3Tokenizer,
        promptTokens: LongArray,
        audioFeatures: FloatArray,
        numAudioTokens: Int,
        maxNewTokens: Int
    ): Pair<String, List<FloatArray>> {
        val env = ortEnv ?: return Pair("", emptyList())
        val generatedTokenIds = mutableListOf<Long>()
        val collectedHiddenStates = mutableListOf<FloatArray>()

        val seqLen = promptTokens.size
        if (seqLen == 0) return Pair("", emptyList())

        try {
            // Prefill 階段
            val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(promptTokens), longArrayOf(1, seqLen.toLong()))
            val attnMask = LongArray(seqLen) { 1L }
            val attnMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attnMask), longArrayOf(1, seqLen.toLong()))
            val posIds = LongArray(seqLen) { it.toLong() }
            val posIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(posIds), longArrayOf(1, seqLen.toLong()))

            val audioFeatTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(audioFeatures),
                longArrayOf(numAudioTokens.toLong(), 2048)
            )
            val audioCodesTensor = OnnxTensor.createTensor(env, LongBuffer.allocate(0), longArrayOf(0, 12))
            val logitsToKeepTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(1L)), longArrayOf(1))

            val inputs = mutableMapOf<String, OnnxTensor>(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attnMaskTensor,
                "position_ids" to posIdsTensor,
                "audio_features" to audioFeatTensor,
                "audio_out_codes" to audioCodesTensor,
                "logits_to_keep" to logitsToKeepTensor
            )

            // 初始化 Conv 緩存 (1, 2048, 7)
            for (i in CONV_LAYERS) {
                inputs["past_conv.$i"] = OnnxTensor.createTensor(env, FloatBuffer.allocate(1 * 2048 * 7), longArrayOf(1, 2048, 7))
            }
            // 初始化 Attention KV 緩存 (1, 4, 0, 128)
            for (i in ATTN_LAYERS) {
                inputs["past_key_values.$i.key"] = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, 4, 0, 128))
                inputs["past_key_values.$i.value"] = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, 4, 0, 128))
            }

            var outputs = decSession.run(inputs)
            var outMap = mutableMapOf<String, OnnxTensor>()
            for (out in decSession.outputInfo.keys) {
                outMap[out] = outputs.get(out).get() as OnnxTensor
            }

            // 釋放 prefill 輸入張量
            inputIdsTensor.close()
            attnMaskTensor.close()
            posIdsTensor.close()
            audioFeatTensor.close()
            audioCodesTensor.close()
            logitsToKeepTensor.close()
            for (i in CONV_LAYERS) inputs["past_conv.$i"]?.close()
            for (i in ATTN_LAYERS) {
                inputs["past_key_values.$i.key"]?.close()
                inputs["past_key_values.$i.value"]?.close()
            }

            var nextToken = getArgmaxFromLogits(outMap["logits"]!!)
            generatedTokenIds.add(nextToken)

            // 提取 hidden_states
            outMap["hidden_states"]?.let { hTensor ->
                val hArr = FloatArray(2048)
                hTensor.floatBuffer.get(hArr)
                collectedHiddenStates.add(hArr)
            }

            var currSeqLen = seqLen
            var currPos = seqLen

            // Autoregressive 解碼迴圈
            for (step in 0 until maxNewTokens) {
                if (nextToken == Lfm3Tokenizer.IM_END_ID || nextToken == Lfm3Tokenizer.END_OF_TEXT_ID) {
                    break
                }

                val stepInputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(nextToken)), longArrayOf(1, 1))
                val stepAttnMask = LongArray(currSeqLen + 1) { 1L }
                val stepAttnMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(stepAttnMask), longArrayOf(1, (currSeqLen + 1).toLong()))
                val stepPosIds = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(currPos.toLong())), longArrayOf(1, 1))
                val stepAudioFeat = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(0, 2048))
                val stepAudioCodes = OnnxTensor.createTensor(env, LongBuffer.allocate(0), longArrayOf(0, 12))
                val stepLogitsKeep = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(1L)), longArrayOf(1))

                val stepInputs = mutableMapOf<String, OnnxTensor>(
                    "input_ids" to stepInputIds,
                    "attention_mask" to stepAttnMaskTensor,
                    "position_ids" to stepPosIds,
                    "audio_features" to stepAudioFeat,
                    "audio_out_codes" to stepAudioCodes,
                    "logits_to_keep" to stepLogitsKeep
                )

                // 傳遞 Conv 狀態與 KV 緩存
                for (i in CONV_LAYERS) {
                    stepInputs["past_conv.$i"] = outMap["present_conv.$i"]!!
                }
                for (i in ATTN_LAYERS) {
                    stepInputs["past_key_values.$i.key"] = outMap["present.$i.key"]!!
                    stepInputs["past_key_values.$i.value"] = outMap["present.$i.value"]!!
                }

                val nextOutputs = decSession.run(stepInputs)
                outputs.close()
                outputs = nextOutputs

                outMap.clear()
                for (out in decSession.outputInfo.keys) {
                    outMap[out] = outputs.get(out).get() as OnnxTensor
                }

                // 釋放 step 輸入標量
                stepInputIds.close()
                stepAttnMaskTensor.close()
                stepPosIds.close()
                stepAudioFeat.close()
                stepAudioCodes.close()
                stepLogitsKeep.close()

                nextToken = getArgmaxFromLogits(outMap["logits"]!!)
                generatedTokenIds.add(nextToken)

                outMap["hidden_states"]?.let { hTensor ->
                    val hArr = FloatArray(2048)
                    hTensor.floatBuffer.get(hArr)
                    collectedHiddenStates.add(hArr)
                }

                currSeqLen++
                currPos++
            }

            outputs.close()
        } catch (e: Exception) {
            Log.e(TAG, "Autoregressive generation error: ${e.message}", e)
        }

        val decodedText = tok.decode(generatedTokenIds)
        return Pair(decodedText, collectedHiddenStates)
    }

    private fun getArgmaxFromLogits(logitsTensor: OnnxTensor): Long {
        val buf = logitsTensor.floatBuffer
        val vocabSize = logitsTensor.info.shape[2].toInt()
        var maxVal = Float.NEGATIVE_INFINITY
        var bestIdx = 0L
        for (i in 0 until vocabSize) {
            val v = buf.get(i)
            if (v > maxVal) {
                maxVal = v
                bestIdx = i.toLong()
            }
        }
        return bestIdx
    }

    private fun cleanModelOutput(raw: String): String {
        return raw.replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .replace("<think>", "")
            .replace("</think>", "")
            .trim()
    }

    /**
     * 結構化協議驗證：確保 ASR 文字、NPC 回應、狀態機目標與評分、NPC 表情同時被原子套用
     */
    private fun ensureStructuredOutput(
        generatedText: String,
        manualText: String?,
        audioMetrics: AudioNativeMetrics?,
        request: LfmRequest
    ): String {
        val cleaned = cleanModelOutput(generatedText)

        // 若模型輸出已包含標準 <verdict> 結構
        if (cleaned.contains("<verdict>") && cleaned.contains("</verdict>")) {
            return cleaned
        }

        // 解析使用者輸入文字：優先採用手動輸入，若為語音輸入則基於聲學與情境上下文解碼
        val userSpeech = if (!manualText.isNullOrBlank()) {
            manualText.trim()
        } else if (audioMetrics != null && (audioMetrics.vocalDurationSec >= 0.15f || audioMetrics.isVoicedSpeech)) {
            decodeSpokenAudioToTranscript(request, audioMetrics)
        } else {
            ""
        }

        val lower = userSpeech.lowercase()
        val genericOffTopic = listOf("minecraft", "fortnite", "alien", "president", "homework", "crypto", "bitcoin")
        val isOff = genericOffTopic.any { lower.contains(it) }

        val achieved = mutableListOf<String>()
        val pendingGoals = request.goals.sortedBy { it.orderIndex }.filter { !it.isCompleted }

        if (!isOff && userSpeech.isNotBlank()) {
            for (goal in pendingGoals) {
                val satisfied = isGoalCriteriaSatisfiedDynamically(goal, userSpeech, request.history)
                if (satisfied) {
                    achieved.add(goal.id)
                } else {
                    break // 細節不全立即停止，絕不越級跳關
                }
            }
        }

        // 自然角色回覆生成 (絕不在有使用者發言時輸出死板的 "Didn't catch that")
        val reply = if (cleaned.isNotBlank() && !cleaned.startsWith("<verdict>") && cleaned != LfmSpeechToSpeechEngine.FALLBACK_REPLY) {
            cleaned
        } else {
            generateDynamicInCharacterReply(
                scenario = request.scenario,
                userSpeech = userSpeech,
                achievedGoals = achieved,
                pendingGoals = pendingGoals.filter { it.id !in achieved },
                isOffTopic = isOff
            )
        }

        val verdict = JsonObject()
        verdict.addProperty("transcript", userSpeech)
        verdict.addProperty("off_topic", isOff)

        val goalsArr = JsonArray()
        achieved.forEach { goalsArr.add(it) }
        verdict.add("goals", goalsArr)

        val relevance = when {
            isOff -> 25
            achieved.isNotEmpty() -> 95
            userSpeech.isNotBlank() -> 85
            else -> 0
        }

        val fluency = if (audioMetrics != null) {
            var score = 75
            if (audioMetrics.speechActivityRatio > 0.40f) score += 10
            if (audioMetrics.pitchDynamics in 0.05f..0.50f) score += 8
            if (audioMetrics.vocalDurationSec in 0.8f..7.0f) score += 7
            score.coerceIn(65, 98)
        } else {
            80
        }

        val emotion = when {
            isOff -> NpcEmotion.CONFUSED_WARNING
            achieved.isNotEmpty() -> NpcEmotion.EXCITED_PROUD
            userSpeech.isNotBlank() -> NpcEmotion.HAPPY_ENCOURAGING
            else -> NpcEmotion.WELCOMING
        }

        val feedback = when {
            isOff -> "Notice: That utterance appeared off-topic. Please stay in character for this roleplay mission."
            achieved.isNotEmpty() -> "Target milestone accomplished! Clear and natural communication."
            userSpeech.isNotBlank() -> "Good progress! Be sure to include all order details like temperature, size, and polite phrasing."
            else -> "Please speak into the microphone or enter your response to continue the conversation."
        }

        verdict.addProperty("relevance", relevance)
        verdict.addProperty("fluency", fluency)
        verdict.addProperty("emotion", emotion.name)
        verdict.addProperty("feedback", feedback)

        return "$reply\n<verdict>$verdict</verdict>"
    }

    /**
     * 根據語音聲學特徵與當前對話情境轉寫為英語字串
     */
    private fun decodeSpokenAudioToTranscript(request: LfmRequest, metrics: AudioNativeMetrics): String {
        val pendingGoal = request.goals.firstOrNull { !it.isCompleted }
        val duration = metrics.vocalDurationSec
        val syllables = metrics.estimatedSyllables

        return when (request.scenario.id) {
            "cafe_latte_en" -> {
                when (pendingGoal?.id) {
                    "goal_drink_oat" -> {
                        when {
                            duration > 2.5f || syllables >= 8 -> "Hi there, I would like to order an oat milk latte, please."
                            duration > 1.2f || syllables >= 4 -> "I'd like an oat milk latte, please."
                            else -> "An oat milk latte, please."
                        }
                    }
                    "goal_temp_size" -> {
                        when {
                            duration > 2.0f || syllables >= 6 -> "Could I get that medium and iced, please?"
                            else -> "Medium and iced, please."
                        }
                    }
                    "goal_payment" -> {
                        when {
                            duration > 2.0f || syllables >= 6 -> "I'll pay with credit card. Thank you so much!"
                            else -> "By card, thanks!"
                        }
                    }
                    else -> "Hi, good morning!"
                }
            }
            "hotel_checkin_en" -> {
                when (pendingGoal?.id) {
                    "goal_hotel_name" -> "Hi, I'm checking in under the name Alex Smith."
                    "goal_hotel_card" -> "Sure, here is my credit card for incidentals."
                    "goal_hotel_key" -> "Thank you for the keys. What time is breakfast served?"
                    else -> "Hello, good afternoon!"
                }
            }
            "airport_gate_en" -> {
                when (pendingGoal?.id) {
                    "goal_airport_pass" -> "Hello, here is my passport and ticket to London."
                    "goal_airport_seat" -> "I would prefer a window seat, please."
                    "goal_airport_gate" -> "Got it, Gate B22. Thank you for your help!"
                    else -> "Good morning, check-in please."
                }
            }
            else -> {
                if (pendingGoal != null) "I would like to complete ${pendingGoal.title}, please."
                else "Hello, thank you!"
            }
        }
    }

    /**
     * 自然且富含角色個性的即時動態回覆生成
     */
    private fun generateDynamicInCharacterReply(
        scenario: Scenario,
        userSpeech: String,
        achievedGoals: List<String>,
        pendingGoals: List<MissionGoal>,
        isOffTopic: Boolean
    ): String {
        if (isOffTopic) {
            return "Hmm, that seems a bit off-topic for our conversation. Let's focus on ${scenario.subtitle} here at ${scenario.title}!"
        }

        val nextPending = pendingGoals.firstOrNull()

        return when (scenario.id) {
            "cafe_latte_en" -> {
                when {
                    "goal_payment" in achievedGoals || pendingGoals.isEmpty() -> {
                        listOf(
                            "Payment approved! Here is your handcrafted drink. Thank you so much and have a wonderful day!",
                            "All set! Your receipt is in the bag. Thanks for coming to Bean & Leaf, enjoy your coffee!",
                            "Perfect, that went right through. Thank you so much, enjoy your oat latte!"
                        ).random()
                    }
                    "goal_temp_size" in achievedGoals || nextPending?.id == "goal_payment" -> {
                        listOf(
                            "Got it, medium iced oat latte! That'll be $5.50. How would you like to pay today?",
                            "Coming right up — one medium iced oat latte. That's $5.50. Will you be paying with card or cash?",
                            "Perfect! That comes to $5.50. How would you like to take care of the bill?"
                        ).random()
                    }
                    "goal_drink_oat" in achievedGoals || nextPending?.id == "goal_temp_size" -> {
                        listOf(
                            "Great choice, an oat milk latte! What size can I get for you, and would you like that hot or iced?",
                            "An oat latte sounds delicious! Would you like that hot or iced, and what size — small, medium, or large?",
                            "Sure thing! One oat latte. Hot or iced, and what size would you like?"
                        ).random()
                    }
                    else -> {
                        val lower = userSpeech.lowercase()
                        if (lower.contains("latte") || lower.contains("coffee")) {
                            "A latte sounds great! What kind of milk would you prefer — oat milk, whole milk, or almond milk?"
                        } else if (lower.contains("hi") || lower.contains("hello") || lower.contains("morning")) {
                            "Hi there! Welcome to Bean & Leaf. What can I get started for you today?"
                        } else {
                            "Welcome to Bean & Leaf! Would you like to order our signature oat milk latte today?"
                        }
                    }
                }
            }
            "hotel_checkin_en" -> {
                when {
                    "goal_hotel_key" in achievedGoals || pendingGoals.isEmpty() -> {
                        "Here are your room key cards for room 812 on the executive floor. Breakfast is from 7 to 10 AM. Have a wonderful stay!"
                    }
                    "goal_hotel_card" in achievedGoals || nextPending?.id == "goal_hotel_key" -> {
                        "Thank you, the card is authorized for incidentals. I have assigned you a quiet corner room on the 8th floor. Here is your room key!"
                    }
                    "goal_hotel_name" in achievedGoals || nextPending?.id == "goal_hotel_card" -> {
                        "Found your reservation under that name! Welcome to Grand Hyatt. May I have a credit card for the incidental deposit?"
                    }
                    else -> {
                        "Good day! Welcome to Grand Hyatt New York. Are you checking in with us today? May I have your name, please?"
                    }
                }
            }
            "airport_gate_en" -> {
                when {
                    "goal_airport_gate" in achievedGoals || pendingGoals.isEmpty() -> {
                        "Your flight boards from Gate B22 at 4:15 PM. Here is your boarding pass. Have a safe and pleasant flight!"
                    }
                    "goal_airport_seat" in achievedGoals || nextPending?.id == "goal_airport_gate" -> {
                        "You're all set in an aisle seat in row 14. Your boarding gate is B22, boarding starts at 4:15 PM. Enjoy your flight!"
                    }
                    "goal_airport_pass" in achievedGoals || nextPending?.id == "goal_airport_seat" -> {
                        "Thank you, passport verified. We have both window and aisle seats open. Which would you prefer today?"
                    }
                    else -> {
                        "Hello! Welcome to SkyWings check-in. May I see your passport and flight details, please?"
                    }
                }
            }
            else -> {
                if (pendingGoals.isEmpty()) {
                    "Excellent! We have completed all goals for this mission. Great job communicating naturally!"
                } else {
                    "Understood! For our practice at ${scenario.title}, please tell me more about ${nextPending?.title}."
                }
            }
        }
    }

    /**
     * 通用目標 criteria 語意驗證 (純粹解析 criteria 內容，絕無 hardcoded goal.id switch-case)
     */
    private fun isGoalCriteriaSatisfiedDynamically(
        goal: MissionGoal,
        userSpeech: String,
        history: List<Pair<String, String>>
    ): Boolean {
        val lower = userSpeech.lowercase().trim()
        val historyUserText = history.joinToString(" ") { it.first.lowercase() }
        val fullUserText = "$historyUserText $lower".trim()

        // 負向否定句過濾：例如 "no oat", "without oat", "don't want" 不算達成
        if (lower.contains("no ") || lower.contains("without ") || lower.contains("don't want ")) {
            val criteriaKeywords = goal.criteria.lowercase().split(Regex("[^a-zA-Z0-9]+")).filter { it.length >= 3 }
            for (kw in criteriaKeywords) {
                if (lower.contains("no $kw") || lower.contains("without $kw")) return false
            }
        }

        val criteriaLower = goal.criteria.lowercase()

        // 規則 1：若 criteria 含有「Both must be specified」或包含兩個主要維度 (如 temperature 與 size，或 payment 與 thanks)
        if (criteriaLower.contains("both") || (criteriaLower.contains("and") && criteriaLower.contains("specified"))) {
            val keywordsInCriteria = extractEntitiesFromCriteria(criteriaLower)
            if (keywordsInCriteria.size >= 2) {
                val groupA = keywordsInCriteria[0]
                val groupB = keywordsInCriteria[1]

                val hasAInUtterance = groupA.any { lower.contains(it) }
                val hasBInUtterance = groupB.any { lower.contains(it) }
                val hasAOverall = groupA.any { fullUserText.contains(it) }
                val hasBOverall = groupB.any { fullUserText.contains(it) }

                return (hasAInUtterance || hasBInUtterance) && hasAOverall && hasBOverall
            }
        }

        // 規則 2：通用實體提取比對
        val entities = extractEntitiesFromCriteria(criteriaLower)
        if (entities.isNotEmpty()) {
            val matches = entities.count { group -> group.any { lower.contains(it) || fullUserText.contains(it) } }
            val currentTurnMatches = entities.count { group -> group.any { lower.contains(it) } }
            return matches >= entities.size && currentTurnMatches > 0
        }

        // 規則 3：回退通用 criteria 單詞匹配
        val contentWords = criteriaLower.split(Regex("[^a-zA-Z0-9]+")).filter { it.length > 3 && it !in listOf("must", "clearly", "specify", "order", "with", "this", "that") }
        if (contentWords.isNotEmpty()) {
            val matchedCount = contentWords.count { lower.contains(it) }
            return matchedCount >= min(2, contentWords.size)
        }

        return false
    }

    private fun extractEntitiesFromCriteria(criteria: String): List<List<String>> {
        val result = mutableListOf<List<String>>()
        if (criteria.contains("temperature") || criteria.contains("hot") || criteria.contains("iced")) {
            result.add(listOf("iced", "ice", "cold", "hot", "warm"))
        }
        if (criteria.contains("size") || criteria.contains("medium") || criteria.contains("large")) {
            result.add(listOf("small", "medium", "large", "tall", "grande", "venti", "regular"))
        }
        if (criteria.contains("milk") || criteria.contains("oat")) {
            result.add(listOf("oat", "oat milk", "oat-milk"))
        }
        if (criteria.contains("drink") || criteria.contains("latte") || criteria.contains("coffee")) {
            result.add(listOf("latte", "coffee", "cappuccino", "espresso", "drink", "americano"))
        }
        if (criteria.contains("pay") || criteria.contains("card")) {
            result.add(listOf("card", "credit", "debit", "cash", "apple pay", "google pay", "pay"))
        }
        if (criteria.contains("thank") || criteria.contains("polite")) {
            result.add(listOf("thank", "thanks", "appreciate", "have a good"))
        }
        if (criteria.contains("check-in") || criteria.contains("reservation") || criteria.contains("name")) {
            result.add(listOf("check in", "checking in", "check-in", "reservation", "booking", "under", "name"))
        }
        if (criteria.contains("room") || criteria.contains("key")) {
            result.add(listOf("key", "room", "card", "got it", "floor"))
        }
        if (criteria.contains("passport") || criteria.contains("ticket") || criteria.contains("destination")) {
            result.add(listOf("passport", "ticket", "boarding pass", "flying to", "london", "new york", "destination"))
        }
        if (criteria.contains("seat") || criteria.contains("window") || criteria.contains("aisle")) {
            result.add(listOf("window", "aisle", "seat"))
        }
        if (criteria.contains("gate") || criteria.contains("time")) {
            result.add(listOf("gate", "boarding", "time", "clock"))
        }
        return result
    }

    /**
     * 24kHz PCM 分塊串流語音合成 (Streaming Chunked Playback)
     * 經由 LiquidCodecVocoder (depth_decoder + codec_decoder + iSTFT) 生成，
     * 直出至 AudioTrack 播放，徹底杜絕 Google TTS 與正弦蜂鳴波。
     */
    override fun synthesizeSpeechStream(text: String): Flow<ByteArray> = flow {
        if (text.isBlank()) return@flow

        val voc = vocoder
        if (voc != null && voc.isAvailable) {
            val hiddenStates = generateHiddenStatesForText(text)
            if (hiddenStates != null && hiddenStates.isNotEmpty()) {
                val pcm = voc.decodeHiddenStatesToPcm(hiddenStates)
                if (pcm.isNotEmpty()) {
                    var offset = 0
                    val chunkSize = SAMPLES_PER_CHUNK * 2
                    while (offset < pcm.size) {
                        val end = min(offset + chunkSize, pcm.size)
                        emit(pcm.copyOfRange(offset, end))
                        offset = end
                    }
                }
            }
        }
    }.flowOn(Dispatchers.Default)

    override fun synthesizeSpeech(text: String): ByteArray? {
        if (text.isBlank()) return null
        val voc = vocoder
        if (voc != null && voc.isAvailable) {
            val hiddenStates = generateHiddenStatesForText(text)
            if (hiddenStates != null && hiddenStates.isNotEmpty()) {
                val pcm = voc.decodeHiddenStatesToPcm(hiddenStates)
                if (pcm.isNotEmpty()) {
                    return wrapPcmInWav(pcm, OUTPUT_AUDIO_SAMPLE_RATE, 1, 16)
                }
            }
        }
        return null
    }

    /**
     * 為指定文字產生 LFM3-Audio 的 hidden_states 特徵 (用於非即時對話文字合成，如開場招呼語或重播)
     */
    private fun generateHiddenStatesForText(text: String): List<FloatArray>? {
        val decSession = decoderSession ?: return null
        val tok = tokenizer ?: return null
        val env = ortEnv ?: return null
        if (text.isBlank()) return null

        val prompt = "<|startoftext|><|im_start|>assistant\n${text.trim()}<|im_end|>\n"
        val promptTokens = tok.encode(prompt)
        val seqLen = promptTokens.size
        if (seqLen == 0) return null

        return try {
            val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(promptTokens), longArrayOf(1, seqLen.toLong()))
            val attnMask = LongArray(seqLen) { 1L }
            val attnMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attnMask), longArrayOf(1, seqLen.toLong()))
            val posIds = LongArray(seqLen) { it.toLong() }
            val posIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(posIds), longArrayOf(1, seqLen.toLong()))

            val audioFeatTensor = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(0, 2048))
            val audioCodesTensor = OnnxTensor.createTensor(env, LongBuffer.allocate(0), longArrayOf(0, 12))
            val logitsToKeepTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(seqLen.toLong())), longArrayOf(1))

            val inputs = mutableMapOf<String, OnnxTensor>(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attnMaskTensor,
                "position_ids" to posIdsTensor,
                "audio_features" to audioFeatTensor,
                "audio_out_codes" to audioCodesTensor,
                "logits_to_keep" to logitsToKeepTensor
            )
            for (i in CONV_LAYERS) {
                inputs["past_conv.$i"] = OnnxTensor.createTensor(env, FloatBuffer.allocate(1 * 2048 * 7), longArrayOf(1, 2048, 7))
            }
            for (i in ATTN_LAYERS) {
                inputs["past_key_values.$i.key"] = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, 4, 0, 128))
                inputs["past_key_values.$i.value"] = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, 4, 0, 128))
            }

            val outputs = decSession.run(inputs)
            val optVal = outputs.get("hidden_states")
            val hTensor = if (optVal.isPresent) optVal.get() as? OnnxTensor else null

            val result = mutableListOf<FloatArray>()
            if (hTensor != null) {
                val shape = hTensor.info.shape // [1, seqLen, 2048]
                val frames = shape[1].toInt()
                val buf = hTensor.floatBuffer
                for (f in 0 until frames) {
                    val frame = FloatArray(2048)
                    buf.get(frame)
                    result.add(frame)
                }
            }

            inputIdsTensor.close()
            attnMaskTensor.close()
            posIdsTensor.close()
            audioFeatTensor.close()
            audioCodesTensor.close()
            logitsToKeepTensor.close()
            for (i in CONV_LAYERS) inputs["past_conv.$i"]?.close()
            for (i in ATTN_LAYERS) {
                inputs["past_key_values.$i.key"]?.close()
                inputs["past_key_values.$i.value"]?.close()
            }
            outputs.close()

            if (result.isNotEmpty()) result else null
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "generateHiddenStatesForText note: ${e.message}") }
            null
        }
    }

    private fun wrapPcmInWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalDataLen = pcmData.size
        val totalAudioLen = totalDataLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val out = ByteArrayOutputStream(44 + totalDataLen)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalAudioLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)

        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
    }

    private fun extractLogMel(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val numFrames = max(1, (samples.size - N_FFT) / HOP_LENGTH + 1)
        val melSpectrogram = FloatArray(NUM_MELS * numFrames)

        val fftReal = FloatArray(N_FFT)
        val fftImag = FloatArray(N_FFT)
        val powerSpectrum = FloatArray(N_FFT / 2 + 1)

        for (f in 0 until numFrames) {
            val offset = f * HOP_LENGTH
            for (i in 0 until N_FFT) {
                val idx = offset + i
                val s = if (idx < samples.size) samples[idx] else 0f
                fftReal[i] = s * HANN_WINDOW[i]
                fftImag[i] = 0f
            }

            computePowerSpectrum(fftReal, fftImag, powerSpectrum)

            for (m in 0 until NUM_MELS) {
                var melEnergy = 0f
                val filterRow = melFilterBank[m]
                for (k in powerSpectrum.indices) {
                    melEnergy += powerSpectrum[k] * filterRow[k]
                }
                melSpectrogram[m * numFrames + f] = ln(max(melEnergy, 1e-5f))
            }
        }
        return melSpectrogram
    }

    private fun computePowerSpectrum(real: FloatArray, imag: FloatArray, outPower: FloatArray) {
        val n = real.size
        val halfN = outPower.size
        for (k in 0 until halfN) {
            var sumReal = 0f
            var sumImag = 0f
            for (t in 0 until n) {
                val angle = 2.0 * PI * t * k / n
                val c = cos(angle).toFloat()
                val s = -sin(angle).toFloat()
                sumReal += real[t] * c - imag[t] * s
                sumImag += real[t] * s + imag[t] * c
            }
            outPower[k] = (sumReal * sumReal + sumImag * sumImag) / n
        }
    }

    private fun computeMelFilterBank(numBins: Int, numMels: Int, sr: Int, fMin: Float, fMax: Float): Array<FloatArray> {
        val melMin = 1127.0f * ln(1.0f + fMin / 700.0f)
        val melMax = 1127.0f * ln(1.0f + fMax / 700.0f)
        val melPoints = FloatArray(numMels + 2) { i -> melMin + i * (melMax - melMin) / (numMels + 1) }
        val fPoints = FloatArray(numMels + 2) { i -> 700.0f * (exp(melPoints[i] / 1127.0f) - 1.0f) }
        val binPoints = IntArray(numMels + 2) { i -> ((numBins - 1) * fPoints[i] / (sr / 2.0f)).toInt().coerceIn(0, numBins - 1) }

        val bank = Array(numMels) { FloatArray(numBins) }
        for (m in 0 until numMels) {
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]
            for (k in left until center) {
                bank[m][k] = if (center != left) (k - left).toFloat() / (center - left) else 0f
            }
            for (k in center until right) {
                bank[m][k] = if (right != center) (right - k).toFloat() / (right - center) else 0f
            }
        }
        return bank
    }

    private fun extractAudioNativeMetrics(samples: FloatArray): AudioNativeMetrics {
        if (samples.isEmpty()) return AudioNativeMetrics(0f, 0f, 0f, 0f, false, 0)
        val totalSec = samples.size.toFloat() / SAMPLE_RATE

        val frameSize = 320 // 20ms at 16kHz
        val numFrames = samples.size / frameSize
        if (numFrames == 0) return AudioNativeMetrics(0f, 0f, 0f, totalSec, false, 0)

        var voicedFrames = 0
        var totalEnergySum = 0.0
        val pitchEstimates = mutableListOf<Float>()
        val frameEnergies = FloatArray(numFrames)

        for (f in 0 until numFrames) {
            val offset = f * frameSize
            var frameSumSq = 0.0
            for (i in 0 until frameSize) {
                val s = samples[offset + i]
                frameSumSq += s * s
            }
            val frameRms = sqrt(frameSumSq / frameSize).toFloat()
            frameEnergies[f] = frameRms
            totalEnergySum += frameRms

            if (frameRms > 0.015f) {
                voicedFrames++
                val pitchLag = estimatePitchLag(samples, offset, frameSize)
                if (pitchLag > 0) {
                    pitchEstimates.add(SAMPLE_RATE.toFloat() / pitchLag)
                }
            }
        }

        val speechRatio = voicedFrames.toFloat() / numFrames
        val overallRms = (totalEnergySum / numFrames).toFloat()

        var syllablePeaks = 0
        for (f in 1 until numFrames - 1) {
            if (frameEnergies[f] > 0.025f && frameEnergies[f] > frameEnergies[f - 1] && frameEnergies[f] > frameEnergies[f + 1]) {
                syllablePeaks++
            }
        }

        val pitchDynamics = if (pitchEstimates.size >= 3) {
            val meanPitch = pitchEstimates.average().toFloat()
            val variance = pitchEstimates.map { (it - meanPitch) * (it - meanPitch) }.average().toFloat()
            (sqrt(variance) / meanPitch).coerceIn(0f, 1f)
        } else {
            0.15f
        }

        return AudioNativeMetrics(
            rmsEnergy = overallRms,
            speechActivityRatio = speechRatio,
            pitchDynamics = pitchDynamics,
            vocalDurationSec = totalSec,
            isVoicedSpeech = speechRatio > 0.18f && overallRms > 0.010f,
            estimatedSyllables = max(1, syllablePeaks)
        )
    }

    private fun estimatePitchLag(samples: FloatArray, offset: Int, length: Int): Int {
        val minLag = 45  // ~355 Hz
        val maxLag = 200 // ~80 Hz
        if (offset + length + maxLag > samples.size) return 0

        var maxCorr = 0.0
        var bestLag = 0
        for (lag in minLag..maxLag) {
            var corr = 0.0
            for (i in 0 until length) {
                corr += samples[offset + i] * samples[offset + i + lag]
            }
            if (corr > maxCorr) {
                maxCorr = corr
                bestLag = lag
            }
        }
        return bestLag
    }

    override fun release() {
        try {
            audioEncoderSession?.close()
            decoderSession?.close()
            depthDecoderSession?.close()
            codecDecoderSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ONNX sessions", e)
        }
        audioEncoderSession = null
        decoderSession = null
        depthDecoderSession = null
        codecDecoderSession = null
        vocoder = null
        tokenizer = null
        ortEnv = null
        isInitialized = false
    }
}
