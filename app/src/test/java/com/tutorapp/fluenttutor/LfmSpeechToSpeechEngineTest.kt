package com.tutorapp.fluenttutor

import com.tutorapp.fluenttutor.audio.AudioPlayer
import com.tutorapp.fluenttutor.engine.LfmBackend
import com.tutorapp.fluenttutor.engine.LfmRawResponse
import com.tutorapp.fluenttutor.engine.LfmRequest
import com.tutorapp.fluenttutor.engine.LfmSpeechToSpeechEngine
import kotlinx.coroutines.runBlocking
import com.tutorapp.fluenttutor.model.NpcAvatarStyle
import com.tutorapp.fluenttutor.model.NpcEmotion
import com.tutorapp.fluenttutor.model.NpcInteractionState
import com.tutorapp.fluenttutor.model.Scenario
import com.tutorapp.fluenttutor.ui.viewmodel.TutorUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LfmSpeechToSpeechEngineTest {

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

    private class FakeBackend(private val text: String) : LfmBackend {
        var lastRequest: LfmRequest? = null
        override suspend fun generate(request: LfmRequest): LfmRawResponse {
            lastRequest = request
            return LfmRawResponse(text, null)
        }
        override fun release() {}
    }

    @Test
    fun engineParsesVerdictAndBuildsPromptWithGoals() = runBlocking {
        val scenario = Scenario.getPredefinedEnglishScenarios().first()
        val backend = FakeBackend(
            "Got it!\n<verdict>{\"transcript\":\"an oat latte\",\"off_topic\":false,\"goals\":[\"goal_drink_oat\"],\"relevance\":90,\"fluency\":85,\"feedback\":\"\"}</verdict>"
        )
        val engine = LfmSpeechToSpeechEngine(backend) { 123L }
        val result = engine.process(null, scenario, scenario.goals, emptyList(), "an oat latte")

        assertEquals("Got it!", result.replyText)
        assertNotNull(result.output)
        assertEquals(listOf("goal_drink_oat"), result.output!!.completedGoalIds)
        assertEquals(123L, result.realRamMb)
        val prompt = backend.lastRequest!!.systemPrompt
        assertTrue(prompt.contains("[goal_drink_oat]"))
        assertTrue(prompt.contains("<verdict>"))
    }

    @Test
    fun engineFallsBackWhenModelOmitsVerdict() = runBlocking {
        val scenario = Scenario.getPredefinedEnglishScenarios().first()
        val engine = LfmSpeechToSpeechEngine(FakeBackend("Just chatting")) { 0L }
        val result = engine.process(null, scenario, scenario.goals, emptyList(), "hi")
        assertNull(result.output)
        assertEquals("Just chatting", result.replyText)
    }

    @Test
    fun engineParsesEmotionAndOutputsSynchronously() = runBlocking {
        val scenario = Scenario.getPredefinedEnglishScenarios().first()
        val backend = FakeBackend(
            "Certainly!\n<verdict>{\"transcript\":\"an oat latte\",\"off_topic\":false,\"goals\":[\"goal_drink_oat\"],\"relevance\":95,\"fluency\":90,\"emotion\":\"EXCITED_PROUD\",\"feedback\":\"Great choice\"}</verdict>"
        )
        val engine = LfmSpeechToSpeechEngine(backend) { 0L }
        val result = engine.process(null, scenario, scenario.goals, emptyList(), "an oat latte")

        assertNotNull(result.output)
        assertEquals("Certainly!", result.replyText)
        assertEquals(listOf("goal_drink_oat"), result.output!!.completedGoalIds)
        assertEquals(95, result.output!!.relevance)
        assertEquals(90, result.output!!.fluency)
        assertEquals(NpcEmotion.EXCITED_PROUD, result.output!!.emotion)
    }

    @Test
    fun testSynthesizeSpeechStreamEmitsChunks() = runBlocking {
        val chunkList = mutableListOf<ByteArray>()
        val fakeStreamingBackend = object : LfmBackend {
            override suspend fun generate(request: LfmRequest): LfmRawResponse = LfmRawResponse("ok")
            override fun synthesizeSpeechStream(text: String) = kotlinx.coroutines.flow.flow {
                // 模擬 250ms chunks (6000 samples = 12000 bytes)
                repeat(4) {
                    emit(ByteArray(12000))
                }
            }
            override fun release() {}
        }
        val engine = LfmSpeechToSpeechEngine(fakeStreamingBackend)
        engine.synthesizeSpeechStream("Hello this is a test").collect { chunk ->
            chunkList.add(chunk)
        }

        assertEquals(4, chunkList.size)
        assertEquals(12000, chunkList[0].size)
    }

    @Test
    fun testDynamicGreetingGeneration() {
        val scenario = Scenario.getPredefinedEnglishScenarios().first()
        val fakeBackend = object : LfmBackend {
            override suspend fun generate(request: LfmRequest): LfmRawResponse = LfmRawResponse("ok")
            override fun generateInitialGreeting(scenario: Scenario): String {
                return "Good morning! Welcome to Bean & Leaf. What can I get handcrafted for you today?"
            }
            override fun release() {}
        }
        val engine = LfmSpeechToSpeechEngine(fakeBackend)
        val greeting = engine.generateGreeting(scenario)
        assertTrue(greeting.isNotBlank())
        assertTrue(greeting.contains("Bean & Leaf"))
    }

    @Test
    fun testSpeechSynthesisNullWhenNoCodecGenerated() {
        val fakeBackend = object : LfmBackend {
            override suspend fun generate(request: LfmRequest): LfmRawResponse = LfmRawResponse("ok")
            override fun synthesizeSpeech(text: String): ByteArray? = null
            override fun release() {}
        }
        val engine = LfmSpeechToSpeechEngine(fakeBackend)
        assertNull(engine.synthesizeSpeech("Any speech text"))
    }

    @Test
    fun testNativeSpeechOutputProtocolAllFourComponentsSynchronous() = runBlocking {
        val scenario = Scenario.getPredefinedEnglishScenarios().first()
        val backend = FakeBackend(
            "An iced oat latte, wonderful choice!\n<verdict>{\"transcript\":\"I'd like an iced oat latte, please.\",\"off_topic\":false,\"goals\":[\"goal_drink_oat\"],\"relevance\":96,\"fluency\":92,\"emotion\":\"EXCITED_PROUD\",\"feedback\":\"Natural intonation and clear polite phrasing!\"}</verdict>"
        )
        val engine = LfmSpeechToSpeechEngine(backend) { 512L }
        val result = engine.process(null, scenario, scenario.goals, emptyList(), "I'd like an iced oat latte, please.")

        // 1. NPC 回應
        assertEquals("An iced oat latte, wonderful choice!", result.replyText)
        assertNotNull(result.output)

        // 2. ASR 轉寫文字
        assertEquals("I'd like an iced oat latte, please.", result.output!!.transcript)

        // 3. 狀態機達成與分數
        assertEquals(listOf("goal_drink_oat"), result.output!!.completedGoalIds)
        assertEquals(96, result.output!!.relevance)
        assertEquals(92, result.output!!.fluency)
        assertTrue(result.output!!.onTopic)

        // 4. NPC 頭像表情
        assertEquals(NpcEmotion.EXCITED_PROUD, result.output!!.emotion)
    }

    @Test
    fun testSynthesizeSpeechStreamProduces250msPcmChunks() = runBlocking {
        val chunks = mutableListOf<ByteArray>()
        val backend = object : LfmBackend {
            override suspend fun generate(request: LfmRequest): LfmRawResponse = LfmRawResponse("ok")
            override fun synthesizeSpeechStream(text: String) = kotlinx.coroutines.flow.flow {
                // 250ms at 24kHz 16-bit mono = 6,000 samples = 12,000 bytes
                val chunkSize = (24000 * 0.25 * 2).toInt()
                repeat(3) {
                    emit(ByteArray(chunkSize))
                }
            }
            override fun release() {}
        }
        val engine = LfmSpeechToSpeechEngine(backend)
        engine.synthesizeSpeechStream("Good morning, welcome to our store!").collect { chunk ->
            chunks.add(chunk)
        }

        assertEquals(3, chunks.size)
        assertEquals(12000, chunks[0].size)
    }
}
