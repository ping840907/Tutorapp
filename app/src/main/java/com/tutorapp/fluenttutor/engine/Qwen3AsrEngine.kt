package com.tutorapp.fluenttutor.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 離線語音辨識：sherpa-onnx + Qwen3-ASR (int8)。實作與 Verbead 的 Qwen3AsrEngine 相同。
 * 使用者說的話只會經由這裡轉成文字；辨識不出來就回空字串，不會有任何預設台詞。
 */
class Qwen3AsrEngine(private val context: Context) {

    companion object {
        private const val TAG = "Qwen3AsrEngine"
    }

    @Volatile private var recognizer: OfflineRecognizer? = null
    private val loadMutex = Mutex()

    fun isLoaded() = recognizer != null

    suspend fun load() = loadMutex.withLock {
        if (isLoaded()) return@withLock
        withContext(Dispatchers.IO) {
            val convFrontend = ModelStore.asrConvFrontend(context)
            val encoder = ModelStore.asrEncoder(context)
            val decoder = ModelStore.asrDecoder(context)
            val tokenizer = ModelStore.asrTokenizerDir(context)
            val missing = listOf(convFrontend, encoder, decoder).filter { !it.isFile }.map { it.absolutePath } +
                (if (tokenizer.isDirectory) emptyList() else listOf(tokenizer.absolutePath + "/"))
            if (missing.isNotEmpty()) {
                throw ModelNotReadyException("ASR model files missing:\n" + missing.joinToString("\n"))
            }

            try {
                System.loadLibrary("onnxruntime")
            } catch (t: Throwable) {
                Log.w(TAG, "Preload onnxruntime: ${t.message}")
            }

            var lastError: Throwable? = null
            for (provider in ModelStore.ASR_PROVIDER_PRIORITY) {
                try {
                    recognizer = OfflineRecognizer(
                        config = OfflineRecognizerConfig(
                            featConfig = FeatureConfig(sampleRate = ModelStore.ASR_SAMPLE_RATE, featureDim = 80),
                            modelConfig = OfflineModelConfig(
                                qwen3Asr = OfflineQwen3AsrModelConfig(
                                    convFrontend = convFrontend.absolutePath,
                                    encoder = encoder.absolutePath,
                                    decoder = decoder.absolutePath,
                                    tokenizer = tokenizer.absolutePath,
                                    hotwords = "",
                                ),
                                numThreads = ModelStore.ASR_THREADS,
                                provider = provider,
                            ),
                        )
                    )
                    Log.i(TAG, "Loaded Qwen3-ASR, provider=$provider")
                    return@withContext
                } catch (ex: Throwable) {
                    Log.w(TAG, "Provider '$provider' failed: ${ex.message}", ex)
                    lastError = ex
                }
            }
            throw ModelNotReadyException("Failed to load Qwen3-ASR: ${lastError?.message}", lastError)
        }
    }

    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.IO) {
        val r = recognizer ?: throw ModelNotReadyException("ASR not loaded")
        if (samples.isEmpty()) return@withContext ""
        val t0 = System.currentTimeMillis()
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, ModelStore.ASR_SAMPLE_RATE)
            r.decode(stream)
            val text = r.getResult(stream).text.trim()
            Log.i(TAG, "Transcribed ${samples.size / 16000f}s in ${System.currentTimeMillis() - t0}ms: \"$text\"")
            text
        } finally {
            runCatching { stream.release() }
        }
    }

    fun release() {
        recognizer?.release()
        recognizer = null
    }
}
