package com.tutorapp.fluenttutor.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * LiquidCodec 神經聲學神經解碼器 (Neural Codec Decoder + Depth Decoder + iSTFT)
 * 將 LFM3-Audio 的 hidden_states (維度 2048) 轉換為 12 組音訊碼簿 (Codebooks)，
 * 再由 codec_decoder.onnx 生成 STFT 頻譜特徵，最後透過 iSTFT 合成原生 24kHz PCM 音訊。
 */
class LiquidCodecVocoder(
    private val env: OrtEnvironment,
    private val depthDecoderSession: OrtSession?,
    private val codecDecoderSession: OrtSession?
) {

    companion object {
        private const val TAG = "LiquidCodecVocoder"
        const val SAMPLE_RATE = 24000
        const val N_FFT = 1280
        const val HOP_LENGTH = 320     // 24kHz / 320 = 75 Hz STFT 幀率
        const val NUM_BINS = N_FFT / 2 + 1 // 641 頻率箱
        const val CODEBOOK_COUNT = 12
        const val SAMPLES_PER_CHUNK_250MS = 6000 // 250ms at 24kHz = 6000 samples = 12,000 bytes

        // Hann 逆變換合成窗
        private val SYNTHESIS_WINDOW = FloatArray(N_FFT) { i ->
            (0.5f * (1.0f - cos(2.0 * PI * i / N_FFT))).toFloat()
        }

        // 預先計算旋轉因子表 cos/sin 以加速 iSTFT
        private val COS_TABLE = Array(N_FFT) { n ->
            FloatArray(NUM_BINS) { k -> cos(2.0 * PI * k * n / N_FFT).toFloat() }
        }
        private val SIN_TABLE = Array(N_FFT) { n ->
            FloatArray(NUM_BINS) { k -> sin(2.0 * PI * k * n / N_FFT).toFloat() }
        }
    }

    val isAvailable: Boolean
        get() = depthDecoderSession != null && codecDecoderSession != null

    /**
     * 從 hidden_states 列表生成 24kHz PCM 音訊 (16-bit Mono Little Endian)
     */
    fun decodeHiddenStatesToPcm(hiddenStates: List<FloatArray>): ByteArray {
        if (!isAvailable || hiddenStates.isEmpty()) return ByteArray(0)
        val numFrames = hiddenStates.size
        val audioCodes = predictAudioCodes(hiddenStates) ?: return ByteArray(0)
        val stftFeatures = runCodecDecoder(audioCodes, numFrames) ?: return ByteArray(0)
        return performInverseStft(stftFeatures)
    }

    /**
     * 串流生成 250ms PCM 音訊塊
     */
    fun decodeHiddenStatesToStream(hiddenStates: List<FloatArray>): Flow<ByteArray> = flow {
        if (!isAvailable || hiddenStates.isEmpty()) return@flow
        val pcm = decodeHiddenStatesToPcm(hiddenStates)
        if (pcm.isEmpty()) return@flow

        val chunkSize = SAMPLES_PER_CHUNK_250MS * 2 // 12,000 bytes
        var offset = 0
        while (offset < pcm.size) {
            val end = min(offset + chunkSize, pcm.size)
            emit(pcm.copyOfRange(offset, end))
            offset = end
        }
    }.flowOn(Dispatchers.Default)

    /**
     * 使用 depth_decoder.onnx 預測 12 個 Codebook 的音訊碼
     */
    private fun predictAudioCodes(hiddenStates: List<FloatArray>): LongArray? {
        val session = depthDecoderSession ?: return null
        val numFrames = hiddenStates.size
        // 輸出陣列形狀 [1, 12, numFrames]
        val allCodes = LongArray(12 * numFrames)

        val codesBuf = LongBuffer.allocate(12)
        val maskedBuf = ByteBuffer.allocate(12)
        // 初始 masked 全部為 1 (true)
        for (i in 0 until 12) maskedBuf.put(1.toByte())

        for (f in 0 until numFrames) {
            val h = hiddenStates[f]
            codesBuf.clear()
            for (i in 0 until 12) codesBuf.put(0L)
            codesBuf.flip()

            maskedBuf.position(0)

            try {
                val hTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(1, 2048))
                val cTensor = OnnxTensor.createTensor(env, codesBuf, longArrayOf(1, 12))
                val mTensor = OnnxTensor.createTensor(env, maskedBuf, longArrayOf(1, 12), ai.onnxruntime.OnnxJavaType.BOOL)

                val inputs = mapOf(
                    "hidden_states" to hTensor,
                    "codes" to cTensor,
                    "masked" to mTensor
                )
                val outputs = session.run(inputs)
                val logitsTensor = outputs[0] as OnnxTensor
                val logitsBuf = logitsTensor.floatBuffer

                // logits 形狀: [1, 12, 2017]
                for (c in 0 until 12) {
                    var maxVal = Float.NEGATIVE_INFINITY
                    var bestIdx = 0L
                    for (k in 0 until 2017) {
                        val v = logitsBuf.get(c * 2017 + k)
                        if (v > maxVal) {
                            maxVal = v
                            bestIdx = k.toLong()
                        }
                    }
                    // allCodes 佈局 [1, 12, numFrames]: c * numFrames + f
                    allCodes[c * numFrames + f] = bestIdx
                }

                hTensor.close()
                cTensor.close()
                mTensor.close()
                outputs.close()
            } catch (e: Exception) {
                runCatching { Log.w(TAG, "Error in depth_decoder frame $f: ${e.message}") }
                return null
            }
        }

        return allCodes
    }

    /**
     * 使用 codec_decoder.onnx 將音訊碼解碼為 STFT 頻譜特徵
     */
    private fun runCodecDecoder(audioCodes: LongArray, numFrames: Int): Array<FloatArray>? {
        val session = codecDecoderSession ?: return null
        try {
            val inputShape = longArrayOf(1, 12, numFrames.toLong())
            val codesTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(audioCodes), inputShape)
            val outputs = session.run(mapOf("audio_codes" to codesTensor))
            val stftTensor = outputs[0] as OnnxTensor
            // 輸出形狀: [1, seq_len, 1282]
            val shape = stftTensor.info.shape
            val seqLen = shape[1].toInt()
            val featureDim = shape[2].toInt() // 1282

            val buf = stftTensor.floatBuffer
            val stftFeatures = Array(seqLen) { FloatArray(featureDim) }
            for (s in 0 until seqLen) {
                buf.get(stftFeatures[s])
            }

            codesTensor.close()
            outputs.close()
            return stftFeatures
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error in codec_decoder: ${e.message}", e) }
            return null
        }
    }

    /**
     * 執行反向短時傅立葉變換 (iSTFT) 重構 24kHz PCM 音訊
     */
    private fun performInverseStft(stftFeatures: Array<FloatArray>): ByteArray {
        val seqLen = stftFeatures.size
        if (seqLen == 0) return ByteArray(0)

        val totalSamples = (seqLen - 1) * HOP_LENGTH + N_FFT
        val floatAudio = FloatArray(totalSamples)

        val timeFrame = FloatArray(N_FFT)

        for (m in 0 until seqLen) {
            val frame = stftFeatures[m]
            // 前 641 為實部，後 641 為虛部
            val realOffset = 0
            val imagOffset = NUM_BINS

            // 計算 1280 點對稱 IFFT
            for (n in 0 until N_FFT) {
                val cosRow = COS_TABLE[n]
                val sinRow = SIN_TABLE[n]

                var sum = frame[realOffset] + (if (n % 2 == 1) -frame[realOffset + NUM_BINS - 1] else frame[realOffset + NUM_BINS - 1])
                for (k in 1 until NUM_BINS - 1) {
                    val r = frame[realOffset + k]
                    val im = frame[imagOffset + k]
                    sum += 2.0f * (r * cosRow[k] - im * sinRow[k])
                }
                timeFrame[n] = (sum / N_FFT) * SYNTHESIS_WINDOW[n]
            }

            // 重疊相加 (Overlap-Add)
            val startIdx = m * HOP_LENGTH
            for (n in 0 until N_FFT) {
                floatAudio[startIdx + n] += timeFrame[n]
            }
        }

        // 轉為 16-bit PCM 位元組
        val pcmBytes = ByteArray(totalSamples * 2)
        val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)

        // 標準化因子 (Hann 窗在 hop=320 時之重疊權重約為 1.5)
        val normFactor = 1.0f / 1.5f
        for (i in 0 until totalSamples) {
            val sample = (floatAudio[i] * normFactor).coerceIn(-1.0f, 1.0f)
            val shortVal = (sample * 32767.0f).toInt().toShort()
            bb.putShort(shortVal)
        }

        return pcmBytes
    }
}
