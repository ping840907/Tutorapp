package com.tutorapp.fluenttutor.engine

import android.content.Context
import java.io.File

/**
 * 端側模型檔案位置 (與 Verbead 相同的目錄結構，可直接把 Verbead 已下載的模型複製過來)：
 *
 *   <externalFilesDir>/models/qwen3_asr/{conv_frontend.onnx, encoder.int8.onnx, decoder.int8.onnx, tokenizer/}
 *   <externalFilesDir>/models/slm/Qwen3.5-2B-Q4_K_M.gguf   (或 0.8B / 4B / 9B)
 *
 * 即 /sdcard/Android/data/com.tutorapp.fluenttutor/files/models/...
 */
object ModelStore {
    const val ASR_SAMPLE_RATE = 16_000
    val ASR_PROVIDER_PRIORITY = listOf("nnapi", "cpu")
    const val ASR_THREADS = 4

    /** 依偏好順序嘗試的 GGUF 檔名 (找到第一個存在的) */
    val LLM_FILE_CANDIDATES = listOf(
        "Qwen3.5-2B-Q4_K_M.gguf",
        "Qwen3.5-4B-Q4_K_M.gguf",
        "Qwen3.5-0.8B-Q4_K_M.gguf",
        "Qwen3.5-9B-Q4_K_M.gguf"
    )

    private fun modelsDir(context: Context): File =
        context.getExternalFilesDir("models") ?: File(context.filesDir, "models")

    fun asrDir(context: Context) = File(modelsDir(context), "qwen3_asr")
    fun asrConvFrontend(context: Context) = File(asrDir(context), "conv_frontend.onnx")
    fun asrEncoder(context: Context) = File(asrDir(context), "encoder.int8.onnx")
    fun asrDecoder(context: Context) = File(asrDir(context), "decoder.int8.onnx")
    fun asrTokenizerDir(context: Context) = File(asrDir(context), "tokenizer")

    fun llmDir(context: Context) = File(modelsDir(context), "slm")

    fun llmFile(context: Context): File? =
        LLM_FILE_CANDIDATES.map { File(llmDir(context), it) }.firstOrNull { it.isFile && it.length() > 0L }

    /** 缺少的檔案清單；空表示模型齊全 */
    fun missingFiles(context: Context): List<String> = buildList {
        for (f in listOf(asrConvFrontend(context), asrEncoder(context), asrDecoder(context))) {
            if (!f.isFile) add(f.absolutePath)
        }
        if (!asrTokenizerDir(context).isDirectory) add(asrTokenizerDir(context).absolutePath + "/")
        if (llmFile(context) == null) add(llmDir(context).absolutePath + "/<" + LLM_FILE_CANDIDATES.first() + ">")
    }
}

/** 模型缺失或載入失敗。訊息會直接顯示給使用者，絕不以罐頭對白代替。 */
class ModelNotReadyException(message: String, cause: Throwable? = null) : Exception(message, cause)
