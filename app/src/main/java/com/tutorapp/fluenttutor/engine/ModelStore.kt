package com.tutorapp.fluenttutor.engine

import android.content.Context
import java.io.File

/**
 * LFM3-Audio-2B GGUF 模型位置 (與 Verbead 相同，可直接複製過來)：
 *
 *   /sdcard/Android/data/com.tutorapp.fluenttutor/files/models/lfm3_audio/LFM3-Audio-2B-Q4_0.gguf
 *   /sdcard/Android/data/com.tutorapp.fluenttutor/files/models/lfm3_audio/mmproj-LFM3-Audio-2B-Q8_0.gguf
 *
 * 下載來源：https://huggingface.co/LiquidAI/LFM3-Audio-2B-GGUF
 */
object ModelStore {
    const val GGUF_MODEL = "LFM3-Audio-2B-Q4_0.gguf"
    const val GGUF_MMPROJ = "mmproj-LFM3-Audio-2B-Q8_0.gguf"
    const val TTS_SAMPLE_RATE = 24_000

    fun lfm3Dir(context: Context): File =
        File(context.getExternalFilesDir("models") ?: File(context.filesDir, "models"), "lfm3_audio")

    fun modelFile(context: Context) = File(lfm3Dir(context), GGUF_MODEL)
    fun mmprojFile(context: Context) = File(lfm3Dir(context), GGUF_MMPROJ)

    /** 缺少的檔案 (完整路徑)；空表示模型齊全 */
    fun missingFiles(context: Context): List<String> =
        listOf(modelFile(context), mmprojFile(context)).filter { !it.isFile || it.length() < 1024L }.map { it.absolutePath }
}

/** 模型缺失、執行檔缺失或推論失敗。訊息會直接顯示給使用者，絕不以罐頭對白代替。 */
class ModelNotReadyException(message: String, cause: Throwable? = null) : Exception(message, cause)
