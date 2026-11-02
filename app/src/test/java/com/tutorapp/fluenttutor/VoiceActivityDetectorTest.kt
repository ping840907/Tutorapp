package com.tutorapp.fluenttutor

import com.tutorapp.fluenttutor.audio.VoiceActivityDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceActivityDetectorTest {

    private lateinit var vad: VoiceActivityDetector

    @Before
    fun setUp() {
        vad = VoiceActivityDetector(silenceThresholdMs = 500L, minSpeechDurationMs = 300L)
    }

    @Test
    fun testRmsCalculation() {
        val silentBuffer = ShortArray(160) { 0 }
        val silentRms = vad.calculateRms(silentBuffer, silentBuffer.size)
        assertEquals(0f, silentRms, 0.001f)

        val loudBuffer = ShortArray(160) { 2000 }
        val loudRms = vad.calculateRms(loudBuffer, loudBuffer.size)
        assertTrue("Loud RMS should be > 1000", loudRms > 1000f)
    }

    @Test
    fun testSampleAccurateSpeechCycle() {
        // 1. 靜音 Frame (160 samples = 10ms at 16kHz)
        val res1 = vad.processFrame(rms = 50f, frameSamplesCount = 160)
        assertTrue(res1 is VoiceActivityDetector.VadResult.None)

        // 2. 高能量語音開始 (連續 150ms 語音)
        val resStart = vad.processFrame(rms = 1200f, frameSamplesCount = 160)
        assertTrue(resStart is VoiceActivityDetector.VadResult.SpeechStarted)

        // 3. 持續說話中 (累計 400ms)
        for (i in 0 until 25) {
            vad.processFrame(rms = 1400f, frameSamplesCount = 160)
        }

        // 4. 靜音開始並超過 500ms (50 frames * 10ms = 500ms)
        var endedResult: VoiceActivityDetector.VadResult? = null
        for (i in 0 until 55) {
            val res = vad.processFrame(rms = 50f, frameSamplesCount = 160)
            if (res is VoiceActivityDetector.VadResult.SpeechEnded) {
                endedResult = res
                break
            }
        }

        assertTrue("Should detect SpeechEnded with sample accuracy", endedResult is VoiceActivityDetector.VadResult.SpeechEnded)
    }
}
