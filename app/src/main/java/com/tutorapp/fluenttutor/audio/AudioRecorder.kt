package com.tutorapp.fluenttutor.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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
 * 低延遲即時串流音訊錄音器 (SM8850 硬體回音消除與半雙工保護)
 * 1. 線程安全保護：音訊緩衝區與 Pre-roll 操作均受同步鎖保護
 * 2. 支援 Short Utterances：最低語音時長降低為 200ms
 * 3. 解決緩衝區殘留洩漏：無效雜訊觸發時重設緩衝區
 * 4. 手動錄音設有容量保護上限 (30s)
 */
class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val onSegmentReady: (pcmData: ByteArray, durationMs: Long) -> Unit,
    private val onAmplitudeChanged: (amplitude: Float) -> Unit,
    private val onSpeechStatusChanged: (isSpeaking: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecorder"
        private const val PRE_ROLL_CAPACITY_BYTES = 16000 * 2 * 3 / 10 // 300ms 前導環形緩存
        private const val MAX_MANUAL_BUFFER_BYTES = 16000 * 2 * 30    // 手動錄音上限 30 秒 (約 960KB)
        private const val MIN_SEGMENT_DURATION_MS = 200L               // 最短有效語音時長 (支援 short utterances)
    }

    private val lock = Any()
    @Volatile
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var recordingJob: Job? = null
    private val vad = VoiceActivityDetector(sampleRate = sampleRate, minSpeechDurationMs = MIN_SEGMENT_DURATION_MS)

    private val speechBuffer = ByteArrayOutputStream()
    private val preRollBuffer = ByteArray(PRE_ROLL_CAPACITY_BYTES)
    private var preRollHead = 0
    private var preRollFilled = 0

    @Volatile
    private var isRecording = false
    @Volatile
    private var isAutoSegmentEnabled = true
    @Volatile
    private var isHalfDuplexMuted = false // 當 AI 播放語音時為 true，阻斷麥克風自激

    fun setAutoSegmentEnabled(enabled: Boolean) {
        this.isAutoSegmentEnabled = enabled
        synchronized(lock) {
            speechBuffer.reset()
            preRollHead = 0
            preRollFilled = 0
        }
        vad.reset()
        onSpeechStatusChanged(false)
    }

    /**
     * 半雙工閘控：當 AI 在講話時設置為 true，忽略麥克風輸入
     */
    fun setHalfDuplexMuted(muted: Boolean) {
        this.isHalfDuplexMuted = muted
        if (muted) {
            synchronized(lock) {
                speechBuffer.reset()
            }
            vad.reset()
            onSpeechStatusChanged(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(coroutineScope: CoroutineScope): Boolean {
        if (isRecording) return true

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, 2048)

        try {
            // 使用 VOICE_COMMUNICATION 啟用高通 SM8850 底層硬體回音消除 (AEC)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                record.release()
                return false
            }

            audioRecord = record

            // 嘗試綁定硬體 AEC 與噪聲消除
            val sessionId = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable() && sessionId != 0) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                Log.i(TAG, "Hardware AcousticEchoCanceler (AEC) enabled on session $sessionId")
            }
            if (NoiseSuppressor.isAvailable() && sessionId != 0) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                Log.i(TAG, "Hardware NoiseSuppressor enabled on session $sessionId")
            }

            record.startRecording()
            isRecording = true
            vad.reset()
            synchronized(lock) {
                speechBuffer.reset()
                preRollHead = 0
                preRollFilled = 0
            }

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                val shortBuffer = ShortArray(1024)
                val byteBuffer = ByteArray(2048)

                while (isActive && isRecording) {
                    val currentRecord = audioRecord ?: break
                    val readCount = currentRecord.read(shortBuffer, 0, shortBuffer.size)
                    if (readCount > 0) {
                        // 若處於半雙工靜音狀態 (AI 正在發言)，直接丟棄避免回音
                        if (isHalfDuplexMuted) {
                            onAmplitudeChanged(0.02f)
                            continue
                        }

                        // 計算 RMS 能量
                        val rms = vad.calculateRms(shortBuffer, readCount)
                        val normalizedAmp = (rms / 2800f).coerceIn(0f, 1f)
                        onAmplitudeChanged(normalizedAmp)

                        // 轉為 PCM bytes
                        ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shortBuffer, 0, readCount)
                        val bytesLength = readCount * 2

                        // 若關閉自動 VAD，則在錄音期間持續累積音訊 (受 MAX_MANUAL_BUFFER_BYTES 保護)
                        if (!isAutoSegmentEnabled) {
                            onSpeechStatusChanged(normalizedAmp > 0.15f)
                            val shouldFlush = synchronized(lock) {
                                speechBuffer.write(byteBuffer, 0, bytesLength)
                                speechBuffer.size() >= MAX_MANUAL_BUFFER_BYTES
                            }
                            if (shouldFlush) {
                                flushSegment()
                            }
                            continue
                        }

                        // 自動 VAD 斷句狀態推進
                        when (val vadRes = vad.processFrame(rms, readCount)) {
                            is VoiceActivityDetector.VadResult.SpeechStarted -> {
                                onSpeechStatusChanged(true)
                                // 將前導 300ms 環形緩存拷貝進當前語音段，確保字首音節不丟失
                                synchronized(lock) {
                                    speechBuffer.reset()
                                    if (preRollFilled > 0) {
                                        val tailLen = minOf(preRollFilled, PRE_ROLL_CAPACITY_BYTES - preRollHead)
                                        speechBuffer.write(preRollBuffer, preRollHead, tailLen)
                                        if (preRollFilled > tailLen) {
                                            speechBuffer.write(preRollBuffer, 0, preRollFilled - tailLen)
                                        }
                                    }
                                    speechBuffer.write(byteBuffer, 0, bytesLength)
                                }
                            }
                            is VoiceActivityDetector.VadResult.SpeechEnded -> {
                                onSpeechStatusChanged(false)
                                synchronized(lock) {
                                    speechBuffer.write(byteBuffer, 0, bytesLength)
                                }
                                flushSegment(vadRes.speechDurationMs)
                            }
                            is VoiceActivityDetector.VadResult.SpeechDiscarded -> {
                                onSpeechStatusChanged(false)
                                synchronized(lock) {
                                    speechBuffer.reset()
                                }
                            }
                            is VoiceActivityDetector.VadResult.None -> {
                                synchronized(lock) {
                                    if (vad.isInSpeech()) {
                                        // 處於說話中，持續累積
                                        speechBuffer.write(byteBuffer, 0, bytesLength)
                                    } else {
                                        // 靜音中，清空 speechBuffer，維護 300ms 環形緩存
                                        speechBuffer.reset()
                                        appendPreRoll(byteBuffer, bytesLength)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord", e)
            stopRecording(flushRemaining = false)
            return false
        }
    }

    private fun appendPreRoll(bytes: ByteArray, length: Int) {
        var srcPos = 0
        var remaining = length
        while (remaining > 0) {
            val toWrite = minOf(remaining, PRE_ROLL_CAPACITY_BYTES - preRollHead)
            System.arraycopy(bytes, srcPos, preRollBuffer, preRollHead, toWrite)
            preRollHead = (preRollHead + toWrite) % PRE_ROLL_CAPACITY_BYTES
            preRollFilled = minOf(preRollFilled + toWrite, PRE_ROLL_CAPACITY_BYTES)
            srcPos += toWrite
            remaining -= toWrite
        }
    }

    fun flushSegment(estimatedDurationMs: Long = 0L) {
        val pcmBytes: ByteArray
        synchronized(lock) {
            pcmBytes = speechBuffer.toByteArray()
            speechBuffer.reset()
            preRollFilled = 0
        }
        if (pcmBytes.isNotEmpty()) {
            val duration = if (estimatedDurationMs > 0) {
                estimatedDurationMs
            } else {
                (pcmBytes.size / (sampleRate * 2.0) * 1000).toLong()
            }
            if (duration >= MIN_SEGMENT_DURATION_MS) {
                onSegmentReady(pcmBytes, duration)
            }
        }
        vad.reset()
    }

    fun stopRecording(flushRemaining: Boolean = false) {
        val wasRecording = isRecording
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        if (wasRecording && flushRemaining) {
            flushSegment()
        } else {
            synchronized(lock) {
                speechBuffer.reset()
                preRollFilled = 0
            }
        }

        val recordToRelease = audioRecord
        audioRecord = null

        try {
            recordToRelease?.stop()
            recordToRelease?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }

        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
        } catch (e: Exception) {
            // ignore
        }
        echoCanceler = null
        noiseSuppressor = null
        onSpeechStatusChanged(false)
        onAmplitudeChanged(0f)
    }

    fun isRecording(): Boolean = isRecording
}
