package com.tutorapp.fluenttutor

import com.tutorapp.fluenttutor.engine.MissionStateMachine
import com.tutorapp.fluenttutor.engine.ModelOutputParser
import com.tutorapp.fluenttutor.engine.ModelTurnOutput
import com.tutorapp.fluenttutor.model.MissionStatus
import com.tutorapp.fluenttutor.model.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MissionStateMachineTest {

    private lateinit var scenario: Scenario
    private lateinit var machine: MissionStateMachine

    @Before
    fun setUp() {
        scenario = Scenario.getPredefinedEnglishScenarios().first { it.id == "cafe_latte_en" }
        machine = MissionStateMachine(scenario)
    }

    private fun out(
        transcript: String = "hello",
        onTopic: Boolean = true,
        goals: List<String> = emptyList(),
        relevance: Int = 80,
        fluency: Int = 80,
        feedback: String = ""
    ) = ModelTurnOutput(transcript, "ok", onTopic, goals, relevance, fluency, feedback)

    @Test
    fun goalsCompleteInOrderAndMissionPasses() {
        var s = machine.initialState()
        s = machine.applyTurn(s, out(goals = listOf("goal_drink_oat")))
        assertTrue(s.goals.first { it.id == "goal_drink_oat" }.isCompleted)
        s = machine.applyTurn(s, out(goals = listOf("goal_temp_size")))
        s = machine.applyTurn(s, out(goals = listOf("goal_payment")))
        assertEquals(MissionStatus.PASSED, s.status)
        assertEquals(3, s.evaluations.size)
        assertNotNull(machine.buildSummary(s))
    }

    @Test
    fun outOfOrderGoalClaimIsRejected() {
        val s = machine.applyTurn(machine.initialState(), out(goals = listOf("goal_payment")))
        assertFalse(s.goals.first { it.id == "goal_payment" }.isCompleted)
    }

    @Test
    fun multipleGoalsInOneTurnAreAcceptedInOrder() {
        val s = machine.applyTurn(
            machine.initialState(),
            out(goals = listOf("goal_temp_size", "goal_drink_oat"))
        )
        assertTrue(s.goals.first { it.id == "goal_drink_oat" }.isCompleted)
        assertTrue(s.goals.first { it.id == "goal_temp_size" }.isCompleted)
        assertFalse(s.goals.first { it.id == "goal_payment" }.isCompleted)
    }

    @Test
    fun unknownGoalIdIsIgnored() {
        val s = machine.applyTurn(machine.initialState(), out(goals = listOf("goal_does_not_exist")))
        assertTrue(s.goals.none { it.isCompleted })
        assertEquals(1, s.evaluations.size)
    }

    @Test
    fun offTopicTurnGivesStrikeIgnoresGoalsAndCapsRelevance() {
        val s = machine.applyTurn(
            machine.initialState(),
            out(onTopic = false, goals = listOf("goal_drink_oat"), relevance = 95)
        )
        assertEquals(1, s.offTopicStrikes)
        assertTrue(s.goals.none { it.isCompleted })
        assertTrue(s.evaluations.last().isOffTopic)
        assertTrue(s.evaluations.last().relevanceScore <= 30)
    }

    @Test
    fun threeStrikesFailTheMission() {
        var s = machine.initialState()
        repeat(scenario.maxOffTopicStrikes) { s = machine.applyTurn(s, out(onTopic = false)) }
        assertEquals(MissionStatus.FAILED, s.status)
        assertEquals(0, machine.buildSummary(s)!!.stars)
    }

    @Test
    fun turnLimitFailsTheMission() {
        var s = machine.initialState()
        repeat(scenario.maxTurns) { s = machine.applyTurn(s, out()) }
        assertEquals(MissionStatus.FAILED, s.status)
    }

    @Test
    fun unintelligibleTurnIsNotCounted() {
        val before = machine.initialState()
        val after = machine.applyTurn(before, out(transcript = ""))
        assertEquals(before, after)
    }

    @Test
    fun scoresAreClampedAndFinishedMissionIgnoresFurtherTurns() {
        var s = machine.applyTurn(machine.initialState(), out(relevance = 500, fluency = -20))
        assertEquals(100, s.evaluations.last().relevanceScore)
        assertEquals(0, s.evaluations.last().fluencyScore)

        s = machine.applyTurn(s, out(goals = listOf("goal_drink_oat", "goal_temp_size", "goal_payment")))
        assertEquals(MissionStatus.PASSED, s.status)
        val frozen = machine.applyTurn(s, out(onTopic = false))
        assertEquals(s, frozen)
    }

    @Test
    fun parserReadsVerdictAndStripsItFromReply() {
        val raw = """Got it, one oat milk latte!
            <verdict>{"transcript":"I'd like an oat latte","off_topic":false,"goals":["goal_drink_oat"],"relevance":92,"fluency":81,"feedback":"Nice!"}</verdict>"""
        val o = ModelOutputParser.parse(raw)!!
        assertEquals("Got it, one oat milk latte!", o.reply)
        assertEquals("I'd like an oat latte", o.transcript)
        assertTrue(o.onTopic)
        assertEquals(listOf("goal_drink_oat"), o.completedGoalIds)
        assertEquals(92, o.relevance)
        assertEquals(81, o.fluency)
    }

    @Test
    fun parserRejectsMissingOrBrokenVerdict() {
        assertNull(ModelOutputParser.parse("Just a plain reply"))
        assertNull(ModelOutputParser.parse("Hi <verdict>{not json</verdict>"))
        assertEquals("Hi", ModelOutputParser.replyOf("Hi <verdict>{not json</verdict>"))
    }
}
