package com.tutorapp.fluenttutor.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 低延遲音訊錄製與即時分段切片引擎 (Streaming Audio Recorder with VAD)
 */
class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val onSegmentReady: (pcmData: ByteArray, durationMs: Long) -> Unit,
    private val onAmplitudeChanged: (amplitude: Float) -> Unit,
    private val onSpeechStatusChanged: (isSpeaking: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecorder"
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val vad = VoiceActivityDetector(sampleRate = sampleRate)

    private val audioBuffer = ByteArrayOutputStream()
    private var isRecording = false
    private var isAutoSegmentEnabled = true

    fun setAutoSegmentEnabled(enabled: Boolean) {
        this.isAutoSegmentEnabled = enabled
    }

    @SuppressLint("MissingPermission")
    fun startRecording(coroutineScope: CoroutineScope) {
        if (isRecording) return

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, 2048)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            vad.reset()
            audioBuffer.reset()

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                val shortBuffer = ShortArray(1024)
                val byteBuffer = ByteArray(2048)

                while (isActive && isRecording) {
                    val readCount = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    if (readCount > 0) {
                        // 轉換為 ShortArray 並計算 RMS
                        val rms = vad.calculateRms(shortBuffer, readCount)
                        val normalizedAmp = (rms / 3000f).coerceIn(0f, 1f)
                        onAmplitudeChanged(normalizedAmp)

                        // 寫入當前緩衝區 (轉換成 16-bit PCM Little Endian Bytes)
                        ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shortBuffer, 0, readCount)
                        val bytesLength = readCount * 2
                        synchronized(audioBuffer) {
                            audioBuffer.write(byteBuffer, 0, bytesLength)
                        }

                        // VAD 判斷斷句
                        if (isAutoSegmentEnabled) {
                            when (val vadRes = vad.processFrame(rms)) {
                                is VoiceActivityDetector.VadResult.SpeechStarted -> {
                                    onSpeechStatusChanged(true)
                                }
                                is VoiceActivityDetector.VadResult.SpeechEnded -> {
                                    onSpeechStatusChanged(false)
                                    // 觸發分段送出
                                    flushSegment(vadRes.speechDurationMs)
                                }
                                is VoiceActivityDetector.VadResult.None -> {
                                    // 保持狀態
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord", e)
            stopRecording()
        }
    }

    /**
     * 手動結束或強制觸發分段送出
     */
    fun flushSegment(estimatedDurationMs: Long = 0L) {
        val pcmBytes: ByteArray
        synchronized(audioBuffer) {
            pcmBytes = audioBuffer.toByteArray()
            audioBuffer.reset()
        }
        if (pcmBytes.isNotEmpty()) {
            val duration = if (estimatedDurationMs > 0) {
                estimatedDurationMs
            } else {
                (pcmBytes.size / (sampleRate * 2.0) * 1000).toLong()
            }
            // 只有長度大於 250ms 才發送，過濾瞬間雜訊
            if (duration >= 250) {
                onSegmentReady(pcmBytes, duration)
            }
        }
        vad.reset()
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audioRecord", e)
        }
        audioRecord = null
        onSpeechStatusChanged(false)
        onAmplitudeChanged(0f)
    }

    fun isRecording(): Boolean = isRecording
}
