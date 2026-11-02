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
        val silentBuffer = ShortArray(100) { 0 }
        val silentRms = vad.calculateRms(silentBuffer, silentBuffer.size)
        assertEquals(0f, silentRms, 0.001f)

        val loudBuffer = ShortArray(100) { 2000 }
        val loudRms = vad.calculateRms(loudBuffer, loudBuffer.size)
        assertTrue("Loud RMS should be > 1000", loudRms > 1000f)
    }

    @Test
    fun testSpeechStartAndEndCycle() {
        val now = 10000L

        // 1. 靜音 Frame
        val res1 = vad.processFrame(rms = 50f, now = now)
        assertTrue(res1 is VoiceActivityDetector.VadResult.None)

        // 2. 檢測到高能量 (說話開始)
        val res2 = vad.processFrame(rms = 1200f, now = now + 10)
        assertTrue(res2 is VoiceActivityDetector.VadResult.SpeechStarted)

        // 3. 持續說話中
        vad.processFrame(rms = 1500f, now = now + 150)
        vad.processFrame(rms = 1300f, now = now + 400)

        // 4. 用戶停頓，靜音超過 500ms
        val resEnd = vad.processFrame(rms = 50f, now = now + 1000)
        assertTrue("Should trigger SpeechEnded after silence threshold", resEnd is VoiceActivityDetector.VadResult.SpeechEnded)
    }
}
