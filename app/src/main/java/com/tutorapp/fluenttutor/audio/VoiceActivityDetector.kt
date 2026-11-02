package com.tutorapp.fluenttutor.audio

import kotlin.math.sqrt

/**
 * 健壯型端側即時語音活動檢測器 (Robust Adaptive VAD)
 * 1. 取樣點精確計時 (Sample-Accurate Timing，免疫系統時間抖動與 GC 延遲)
 * 2. 自適應動態噪聲基線跟蹤 (Adaptive Noise Floor Tracking)
 * 3. 雙門檻滯後比較 (Hysteresis Thresholding 避免喘氣/環境突發脈衝誤觸)
 */
class VoiceActivityDetector(
    private val sampleRate: Int = 16000,
    private val silenceThresholdMs: Long = 650L,   // 斷句判定靜音長度
    private val minSpeechDurationMs: Long = 400L,  // 有效語音最短長度
    private var baseEnergyThreshold: Float = 550f
) {
    enum class VadState {
        SILENCE,
        SPEECH_DETECTED,
        IN_SPEECH
    }

    private var currentState: VadState = VadState.SILENCE
    private var noiseFloor: Float = 250f
    private var processedSamplesTotal: Long = 0L
    private var speechStartSample: Long = 0L
    private var lastSpeechSample: Long = 0L

    /**
     * 計算音訊 Frame 的 RMS 能量
     */
    fun calculateRms(buffer: ShortArray, readSize: Int): Float {
        if (readSize <= 0) return 0f
        var sum = 0.0
        for (i in 0 until readSize) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        val rms = sqrt(sum / readSize).toFloat()

        // 僅在靜音時緩慢跟隨背景噪聲
        if (currentState == VadState.SILENCE) {
            noiseFloor = 0.96f * noiseFloor + 0.04f * rms
            baseEnergyThreshold = (noiseFloor * 2.2f).coerceIn(400f, 2200f)
        }
        return rms
    }

    /**
     * 處理音訊 Frame
     * @param rms 當前 Frame 的能量
     * @param frameSamplesCount 當前 Frame 包含的音訊取樣點數
     */
    fun processFrame(rms: Float, frameSamplesCount: Int): VadResult {
        processedSamplesTotal += frameSamplesCount
        val speechEnergyThreshold = baseEnergyThreshold
        val silenceEnergyThreshold = baseEnergyThreshold * 0.7f // 滯後低門檻

        val isAboveSpeech = rms > speechEnergyThreshold
        val isAboveSilence = rms > silenceEnergyThreshold

        val currentMs = (processedSamplesTotal * 1000L) / sampleRate

        when (currentState) {
            VadState.SILENCE -> {
                if (isAboveSpeech) {
                    currentState = VadState.SPEECH_DETECTED
                    speechStartSample = processedSamplesTotal
                    lastSpeechSample = processedSamplesTotal
                    return VadResult.SpeechStarted
                }
            }
            VadState.SPEECH_DETECTED -> {
                if (isAboveSilence) {
                    val speechDurationMs = ((processedSamplesTotal - speechStartSample) * 1000L) / sampleRate
                    if (speechDurationMs >= 100L) { // 連續 100ms 確認說話
                        currentState = VadState.IN_SPEECH
                    }
                    lastSpeechSample = processedSamplesTotal
                } else {
                    val elapsedMs = ((processedSamplesTotal - speechStartSample) * 1000L) / sampleRate
                    if (elapsedMs > 180L) {
                        currentState = VadState.SILENCE
                    }
                }
            }
            VadState.IN_SPEECH -> {
                if (isAboveSilence) {
                    lastSpeechSample = processedSamplesTotal
                } else {
                    val silenceDurationMs = ((processedSamplesTotal - lastSpeechSample) * 1000L) / sampleRate
                    val totalDurationMs = ((processedSamplesTotal - speechStartSample) * 1000L) / sampleRate
                    if (silenceDurationMs >= silenceThresholdMs) {
                        currentState = VadState.SILENCE
                        if (totalDurationMs >= minSpeechDurationMs) {
                            return VadResult.SpeechEnded(totalDurationMs)
                        }
                    }
                }
            }
        }
        return VadResult.None(isVoice = isAboveSilence, rms = rms)
    }

    fun reset() {
        currentState = VadState.SILENCE
        processedSamplesTotal = 0L
        speechStartSample = 0L
        lastSpeechSample = 0L
    }

    sealed class VadResult {
        object SpeechStarted : VadResult()
        data class SpeechEnded(val speechDurationMs: Long) : VadResult()
        data class None(val isVoice: Boolean, val rms: Float) : VadResult()
    }
}
