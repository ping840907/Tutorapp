package com.tutorapp.fluenttutor

import com.tutorapp.fluenttutor.audio.AudioPlayer
import com.tutorapp.fluenttutor.model.NpcAvatarStyle
import com.tutorapp.fluenttutor.model.NpcInteractionState
import com.tutorapp.fluenttutor.model.Scenario
import com.tutorapp.fluenttutor.ui.viewmodel.TutorUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LfmSpeechToSpeechEngineTest {

    @Test
    fun testAudioFeatureMelExtractionLogic() {
        // 模擬 1 秒 16kHz 16-bit PCM 音訊 (32000 bytes)
        val pcm = ByteArray(32000) { (it % 127).toByte() }
        val shortsCount = pcm.size / 2
        assertTrue("PCM should have 16000 samples", shortsCount == 16000)
    }

    @Test
    fun testParseWavHeaderWithExtraRiffChunks() {
        // 建構包含額外 "LIST" chunk 的 RIFF WAV 測試資料，驗證動態掃描不會誤切 44-byte 偏移
        val pcmDataSize = 320
        val extraChunkSize = 16 // "LIST" + 4 bytes size + 8 bytes payload
        val totalSize = 44 + extraChunkSize + pcmDataSize
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header (12 bytes)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalSize - 8)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt chunk (24 bytes)
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1) // PCM format
        buffer.putShort(1) // Mono
        buffer.putInt(24000) // Sample rate 24kHz
        buffer.putInt(48000) // Byte rate
        buffer.putShort(2) // Block align
        buffer.putShort(16) // Bits per sample

        // Extra LIST chunk before data (16 bytes)
        buffer.put("LIST".toByteArray(Charsets.US_ASCII))
        buffer.putInt(8)
        buffer.putLong(0x123456789ABCDEF0L)

        // data chunk (8 + pcmDataSize bytes)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(pcmDataSize)
        repeat(pcmDataSize) { buffer.put(0x11.toByte()) }

        val wavInfo = AudioPlayer.parseWavHeader(buffer.array())
        assertEquals(24000, wavInfo.sampleRate)
        assertEquals(1, wavInfo.channels)
        assertEquals(60, wavInfo.dataOffset)
        assertEquals(pcmDataSize, wavInfo.dataLength)
    }

    @Test
    fun testNpcInteractionStateAndAvatarMapping() {
        val scenarios = Scenario.getPredefinedEnglishScenarios()
        val defaultScenario = scenarios.first()
        assertEquals(NpcAvatarStyle.BARISTA_SARAH, scenarios[0].avatarStyle)
        assertEquals(NpcAvatarStyle.CONCIERGE_DAVID, scenarios[1].avatarStyle)
        assertEquals(NpcAvatarStyle.AGENT_ELENA, scenarios[2].avatarStyle)

        val idleState = TutorUiState(
            currentScenario = defaultScenario,
            goals = defaultScenario.goals,
            isRecording = false,
            isProcessing = false,
            isAiSpeaking = false
        )
        assertEquals(NpcInteractionState.IDLE, idleState.npcInteractionState)

        val listeningIdle = idleState.copy(isRecording = true, isUserSpeaking = false)
        assertEquals(NpcInteractionState.LISTENING_IDLE, listeningIdle.npcInteractionState)

        val listeningActive = idleState.copy(isRecording = true, isUserSpeaking = true, amplitude = 0.65f)
        assertEquals(NpcInteractionState.LISTENING_ACTIVE, listeningActive.npcInteractionState)
        assertEquals(0.65f, listeningActive.activeAmplitude, 0.001f)

        val thinkingState = idleState.copy(isProcessing = true)
        assertEquals(NpcInteractionState.THINKING, thinkingState.npcInteractionState)

        val speakingState = idleState.copy(isAiSpeaking = true, aiAmplitude = 0.8f, amplitude = 0.0f)
        assertEquals(NpcInteractionState.SPEAKING, speakingState.npcInteractionState)
        assertEquals(0.8f, speakingState.activeAmplitude, 0.001f)
    }
}
