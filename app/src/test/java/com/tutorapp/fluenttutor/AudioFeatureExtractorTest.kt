package com.tutorapp.fluenttutor

import com.tutorapp.fluenttutor.engine.ondevice.AudioFeatureExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioFeatureExtractorTest {

    private lateinit var extractor: AudioFeatureExtractor

    @Before
    fun setUp() {
        extractor = AudioFeatureExtractor()
    }

    @Test
    fun testPcmToFloatArray() {
        // 模擬 16-bit PCM 正弦波樣本 (2 個 short = 4 bytes)
        val pcm = byteArrayOf(0x00, 0x00, 0x00, 0x40) // 0, 16384 (約 0.5)
        val floats = extractor.pcmToFloatArray(pcm)
        assertEquals(2, floats.size)
        assertEquals(0f, floats[0], 0.001f)
        assertEquals(0.5f, floats[1], 0.01f)
    }

    @Test
    fun testExtractMelSpectrogram() {
        // 模擬 1 秒 16kHz PCM (32000 bytes)
        val dummyPcm = ByteArray(32000) { (it % 128).toByte() }
        val melSpec = extractor.extractMelSpectrogram(dummyPcm)

        assertNotNull(melSpec)
        assertEquals(80, melSpec.size) // 80 mel bins
        assertTrue("Frames count should be > 0", melSpec[0].isNotEmpty())
    }
}
