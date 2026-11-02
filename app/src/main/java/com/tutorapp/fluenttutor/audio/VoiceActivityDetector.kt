package com.tutorapp.fluenttutor.audio

import kotlin.math.sqrt

/**
 * 端側輕量化即時語音活動檢測器 (Voice Activity Detector - VAD)
 * 結合 RMS 能量門檻、短時動態自適應基線與靜音計時，實現低延遲自動斷句與分段切片。
 */
class VoiceActivityDetector(
    private val sampleRate: Int = 16000,
    private val silenceThresholdMs: Long = 700L,     // 判斷句子結束的靜音長度 (毫秒)
    private val minSpeechDurationMs: Long = 400L,    // 有效句子的最短時長 (避免雜音或咳嗽誤觸)
    private var baseEnergyThreshold: Float = 600f    // 能量門檻 (會根據環境底噪動態調整)
) {
    enum class VadState {
        SILENCE,
        SPEECH_DETECTED,
        IN_SPEECH
    }

    private var currentState: VadState = VadState.SILENCE
    private var speechStartTime: Long = 0L
    private var lastSpeechTime: Long = 0L
    private var noiseFloor: Float = 300f

    /**
     * 計算音訊 Frame 的 RMS (Root Mean Square) 能量
     */
    fun calculateRms(buffer: ShortArray, readSize: Int): Float {
        if (readSize <= 0) return 0f
        var sum = 0.0
        for (i in 0 until readSize) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        val rms = sqrt(sum / readSize).toFloat()
        // 動態平滑底噪
        if (currentState == VadState.SILENCE) {
            noiseFloor = 0.95f * noiseFloor + 0.05f * rms
            baseEnergyThreshold = (noiseFloor * 2.2f).coerceIn(400f, 2500f)
        }
        return rms
    }

    /**
     * 處理一個 Frame，返回是否應該觸發「句子結束切片 (Segment Complete)」
     * @param rms 當前 Frame 的 RMS 能量
     * @param now 當前時間戳
     * @return true 代表用戶說完一句話並停頓，可以立即截斷音訊送出
     */
    fun processFrame(rms: Float, now: Long = System.currentTimeMillis()): VadResult {
        val isVoice = rms > baseEnergyThreshold

        when (currentState) {
            VadState.SILENCE -> {
                if (isVoice) {
                    currentState = VadState.SPEECH_DETECTED
                    speechStartTime = now
                    lastSpeechTime = now
                    return VadResult.SpeechStarted
                }
            }
            VadState.SPEECH_DETECTED -> {
                if (isVoice) {
                    if (now - speechStartTime >= 100) { // 連續 100ms 語音確認進入說話
                        currentState = VadState.IN_SPEECH
                    }
                    lastSpeechTime = now
                } else {
                    // 誤觸閃爍，退回靜音
                    if (now - speechStartTime > 200) {
                        currentState = VadState.SILENCE
                    }
                }
            }
            VadState.IN_SPEECH -> {
                if (isVoice) {
                    lastSpeechTime = now
                } else {
                    val silenceDuration = now - lastSpeechTime
                    val totalDuration = now - speechStartTime
                    if (silenceDuration >= silenceThresholdMs) {
                        currentState = VadState.SILENCE
                        if (totalDuration >= minSpeechDurationMs) {
                            return VadResult.SpeechEnded(totalDuration)
                        }
                    }
                }
            }
        }
        return VadResult.None(isVoice, rms)
    }

    fun reset() {
        currentState = VadState.SILENCE
        speechStartTime = 0L
        lastSpeechTime = 0L
    }

    sealed class VadResult {
        object SpeechStarted : VadResult()
        data class SpeechEnded(val speechDurationMs: Long) : VadResult()
        data class None(val isVoice: Boolean, val rms: Float) : VadResult()
    }
}
