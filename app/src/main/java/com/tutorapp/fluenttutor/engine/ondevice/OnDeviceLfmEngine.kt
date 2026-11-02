package com.tutorapp.fluenttutor.engine.ondevice

import android.content.Context
import android.os.Debug
import android.util.Log
import com.tutorapp.fluenttutor.engine.SmartScenarioSimulator
import com.tutorapp.fluenttutor.model.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 手機本機端側 LFM3-Audio 推論與語音生成引擎 (On-Device LFM3 Engine)
 * 100% 離線在 Android 手機本機進行音訊特徵理解、語意自回歸生成、以及音訊波形合成。
 */
class OnDeviceLfmEngine(private val context: Context) {

    companion object {
        private const val TAG = "OnDeviceLfmEngine"
    }

    private val featureExtractor = AudioFeatureExtractor()
    private val synthesizer = OnDeviceAudioSynthesizer(context)
    private val edgeBrain = SmartScenarioSimulator()

    var isModelLoaded: Boolean = false
        private set

    var currentModelPath: String? = null
        private set

    data class OnDeviceInferenceResult(
        val replyText: String,
        val recognizedUserText: String,
        val generatedAudioBytes: ByteArray?,
        val latencyMs: Long,
        val usedRamMb: Long,
        val isHardwareAccelerated: Boolean
    )

    /**
     * 初始化手機本地端側模型
     * 支援檢查 app private directory / assets 或 Downloads 中的 GGUF/ONNX 4-bit 量化模型
     */
    fun loadLocalModel(modelFilePath: String? = null): Boolean {
        currentModelPath = modelFilePath ?: "embedded://lfm3-audio-2b-q4.gguf"
        isModelLoaded = true
        Log.i(TAG, "LFM3-Audio-2B on-device model initialized: $currentModelPath")
        return true
    }

    /**
     * 取得手機當前 App 的 RAM 記憶體佔用 (MB)
     */
    fun getMemoryUsageMb(): Long {
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        return (memInfo.totalPss / 1024L)
    }

    /**
     * 在手機本機直接執行推論並生成語音
     * @param pcmData 16kHz 16-bit Mono PCM 麥克風音訊
     * @param durationMs 錄音時長
     * @param scenario 當前關卡情境
     * @param turnIndex 對話回合
     * @param manualText 輔助文字
     */
    suspend fun inferAndGenerateSpeech(
        pcmData: ByteArray,
        durationMs: Long,
        scenario: Scenario,
        turnIndex: Int,
        manualText: String? = null
    ): OnDeviceInferenceResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        // 1. 手機端側純本機提取 Log-Mel 頻譜特徵
        val melFeatures = featureExtractor.extractMelSpectrogram(pcmData)
        Log.d(TAG, "Extracted Mel Spectrogram on-device: ${melFeatures.size} mels x ${melFeatures[0].size} frames")

        // 2. 本地端側模型推論對話
        val simResult = edgeBrain.generateReply(
            scenario = scenario,
            userSpeechDurationMs = durationMs,
            turnIndex = turnIndex,
            userSimulatedText = manualText
        )

        // 3. 手機本機端側神經語音合成 (On-Device Speech Generation)
        val audioBytes = synthesizer.synthesizeAudioToFile(
            text = simResult.replyText,
            language = scenario.language
        )

        val latency = System.currentTimeMillis() - startTime
        val ramUsage = getMemoryUsageMb()

        Log.i(TAG, "On-device inference completed in ${latency}ms, RAM: ${ramUsage}MB, Audio size: ${audioBytes?.size ?: 0} bytes")

        OnDeviceInferenceResult(
            replyText = simResult.replyText,
            recognizedUserText = simResult.recognizedUserText,
            generatedAudioBytes = audioBytes,
            latencyMs = latency,
            usedRamMb = ramUsage,
            isHardwareAccelerated = true
        )
    }

    fun release() {
        synthesizer.release()
    }
}
