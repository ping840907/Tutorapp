package com.tutorapp.fluenttutor

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

class LfmSpeechToSpeechEngineTest {

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

    /** 假後端：userTranscript 模擬 ASR/手動輸入的結果；text 是 LLM 原始輸出 */
    private class FakeBackend(private val userTranscript: String, private val text: String) : LfmBackend {
        var lastRequest: LfmRequest? = null
        var generateCalls = 0
        override suspend fun generate(request: LfmRequest): LfmRawResponse {
            generateCalls++
            lastRequest = request
            return LfmRawResponse(userTranscript, text)
        }
        override suspend fun generateGreeting(scenario: Scenario) = "unused"
        override fun release() {}
    }

    private val verdict = "<verdict>{\"off_topic\":false,\"goals\":[\"goal_drink_oat\"],\"relevance\":90,\"fluency\":85,\"emotion\":\"EXCITED_PROUD\",\"feedback\":\"Nice\"}</verdict>"

    @Test
    fun engineParsesVerdictAndBuildsPromptWithGoals() = runBlocking {
        val scenario = Scenario.getPredefinedEnglishScenarios().first()
        val backend = FakeBackend("an oat latte", "Got it!\n$verdict")
        val engine = LfmSpeechToSpeechEngine(backend) { 123L }
        val result = engine.process(null, scenario, scenario.goals, emptyList(), "an oat latte")

        assertEquals("Got it!", result.replyText)
        assertNotNull(result.output)
        assertEquals(listOf("goal_drink_oat"), result.output!!.completedGoalIds)
        assertEquals(NpcEmotion.EXCITED_PROUD, result.output!!.emotion)
        assertEquals(123L, result.realRamMb)
        val prompt = backend.lastRequest!!.systemPrompt
        assertTrue(prompt.contains("[goal_drink_oat]"))
        assertTrue(prompt.contains("<verdict>"))
    }

    @Test
    fun transcriptComesFromAsrNotFromModel() = runBlocking {
        val scenario = Scenario.getPredefinedEnglishScenarios().first()
        // 模型即使在 verdict 裡偷塞一句 transcript，也必須被 ASR 結果覆蓋
        val sneaky = "Sure.\n<verdict>{\"transcript\":\"I'd like an oat milk latte, please.\",\"off_topic\":false,\"goals\":[],\"relevance\":50,\"fluency\":50}</verdict>"
        val engine = LfmSpeechToSpeechEngine(FakeBackend("what time is it", sneaky)) { 0L }
        val result = engine.process(null, scenario, scenario.goals, emptyList(), null)
        assertEquals("what time is it", result.userTranscript)
        assertEquals("what time is it", result.output!!.transcript)
    }

    @Test
    fun nothingRecognizedYieldsNoReplyAndNoOutput() = runBlocking {
        val scenario = Scenario.getPredefinedEnglishScenarios().first()
        val engine = LfmSpeechToSpeechEngine(FakeBackend("", "")) { 0L }
        val result = engine.process(ByteArray(3200), scenario, scenario.goals, emptyList(), null)
        assertEquals("", result.userTranscript)
        assertEquals("", result.replyText) // 不得有任何預設對白
        assertNull(result.output)
    }

    @Test
    fun engineKeepsReplyWhenModelOmitsVerdict() = runBlocking {
        val scenario = Scenario.getPredefinedEnglishScenarios().first()
        val engine = LfmSpeechToSpeechEngine(FakeBackend("hi", "Just chatting")) { 0L }
        val result = engine.process(null, scenario, scenario.goals, emptyList(), "hi")
        assertNull(result.output)
        assertEquals("Just chatting", result.replyText)
    }

    @Test
    fun truncatedVerdictIsNeverSpoken() = runBlocking {
        val scenario = Scenario.getPredefinedEnglishScenarios().first()
        val engine = LfmSpeechToSpeechEngine(FakeBackend("hi", "Welcome!\n<verdict>{\"off_topic\":fal")) { 0L }
        val result = engine.process(null, scenario, scenario.goals, emptyList(), "hi")
        assertNull(result.output)
        assertEquals("Welcome!", result.replyText)
    }
}
