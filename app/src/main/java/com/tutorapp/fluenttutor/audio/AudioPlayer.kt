package com.tutorapp.fluenttutor.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * 播放 LFM3 TTS 產生的 24 kHz 單聲道 float PCM (AudioTrack 設定同 Verbead 的 PcmPlayer，已實機驗證)。
 * 這裡沒有 TextToSpeech：沒有 PCM 就不會有聲音，由呼叫端顯示錯誤。
 * 嘴型振幅是播放中實際 PCM 的 RMS。
 */
class AudioPlayer(
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit,
    private val onPlaybackAmplitudeChanged: (amplitude: Float) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioPlayer"
        private const val CHUNK_MS = 40
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var track: AudioTrack? = null

    fun play(samples: FloatArray, sampleRate: Int = 24_000) {
        stop()
        if (samples.isEmpty()) return
        val myJob = scope.launch {
            val self = coroutineContext[Job]
            val t = try {
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 4 / 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Throwable) {
                Log.e(TAG, "AudioTrack create failed: ${e.message}", e)
                return@launch
            }
            track = t
            try {
                onPlaybackStateChanged(true)
                t.play()
                val chunk = sampleRate * CHUNK_MS / 1000
                var pos = 0
                while (pos < samples.size && isActive) {
                    val n = minOf(chunk, samples.size - pos)
                    var sum = 0.0
                    for (i in pos until pos + n) sum += samples[i] * samples[i]
                    onPlaybackAmplitudeChanged((sqrt(sum / n).toFloat() * 4f).coerceIn(0.05f, 1f))
                    val w = t.write(samples, pos, n, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) { Log.w(TAG, "write error $w"); break }
                    pos += w
                }
                // 等待排空，避免提前解除半雙工靜音造成尾音自激
                val deadline = System.currentTimeMillis() + (samples.size * 1000L / sampleRate) + 500
                while (isActive && t.playbackHeadPosition < samples.size && System.currentTimeMillis() < deadline) delay(20)
            } catch (e: Throwable) {
                Log.w(TAG, "playback error: ${e.message}")
            } finally {
                releaseTrack(t)
                // 已被新的 play() 取代時，不要蓋掉新播放的狀態
                if (job === self || job == null) {
                    if (track === t) track = null
                    onPlaybackAmplitudeChanged(0f)
                    onPlaybackStateChanged(false)
                }
            }
        }
        job = myJob
    }

    private fun releaseTrack(t: AudioTrack) {
        runCatching { t.pause(); t.flush(); t.stop() }
        runCatching { t.release() }
    }

    fun stop() {
        val j = job
        job = null
        j?.cancel()
        track?.let { releaseTrack(it) }
        track = null
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
