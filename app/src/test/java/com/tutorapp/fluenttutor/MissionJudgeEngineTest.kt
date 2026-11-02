package com.tutorapp.fluenttutor

import com.tutorapp.fluenttutor.engine.MissionJudgeEngine
import com.tutorapp.fluenttutor.model.MissionGoal
import com.tutorapp.fluenttutor.model.MissionStatus
import com.tutorapp.fluenttutor.model.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MissionJudgeEngineTest {

    private lateinit var judgeEngine: MissionJudgeEngine
    private lateinit var cafeScenario: Scenario

    @Before
    fun setUp() {
        judgeEngine = MissionJudgeEngine()
        cafeScenario = Scenario.getPredefinedScenarios().first { it.id == "cafe_latte_en" }
    }

    @Test
    fun testGoalCompletionWhenUserMentionsDrink() {
        val userInput = "Hello, could I get an iced latte with oat milk please?"
        val eval = judgeEngine.evaluateTurn(
            userInput = userInput,
            currentGoals = cafeScenario.goals,
            scenario = cafeScenario,
            turnIndex = 0,
            audioDurationMs = 2000L
        )

        assertFalse(eval.isOffTopic)
        assertTrue("Should complete drink goal", eval.completedGoalIds.contains("goal_drink"))
        assertTrue("Should complete size/temp goal", eval.completedGoalIds.contains("goal_size_temp"))
    }

    @Test
    fun testOffTopicDetection() {
        val userInput = "I love playing Minecraft and video games all night!"
        val eval = judgeEngine.evaluateTurn(
            userInput = userInput,
            currentGoals = cafeScenario.goals,
            scenario = cafeScenario,
            turnIndex = 0,
            audioDurationMs = 2000L
        )

        assertTrue("Should detect off-topic speech", eval.isOffTopic)
        assertEquals(25, eval.relevanceScore)
    }

    @Test
    fun testMissionPassedWhenAllGoalsCompleted() {
        val completedGoals = cafeScenario.goals.map { it.copy(isCompleted = true) }
        val status = judgeEngine.checkMissionStatus(
            goals = completedGoals,
            offTopicStrikes = 0,
            totalTurns = 3,
            scenario = cafeScenario
        )

        assertEquals(MissionStatus.PASSED, status)
    }

    @Test
    fun testMissionFailedWhenStrikesExceedMax() {
        val incompleteGoals = cafeScenario.goals
        val status = judgeEngine.checkMissionStatus(
            goals = incompleteGoals,
            offTopicStrikes = 3,
            totalTurns = 3,
            scenario = cafeScenario
        )

        assertEquals(MissionStatus.FAILED, status)
    }

    @Test
    fun testFinalSummaryScoreCalculation() {
        val completedGoals = cafeScenario.goals.map { it.copy(isCompleted = true) }
        val evals = listOf(
            judgeEngine.evaluateTurn("I want an oat milk latte", cafeScenario.goals, cafeScenario, 0, 1500L),
            judgeEngine.evaluateTurn("Iced and medium please", cafeScenario.goals, cafeScenario, 1, 1200L),
            judgeEngine.evaluateTurn("I'll pay with credit card, thanks!", cafeScenario.goals, cafeScenario, 2, 1800L)
        )

        val summary = judgeEngine.generateFinalSummary(
            status = MissionStatus.PASSED,
            goals = completedGoals,
            evaluations = evals,
            offTopicStrikes = 0,
            scenario = cafeScenario
        )

        assertEquals(MissionStatus.PASSED, summary.status)
        assertTrue("Passed score should be >= 60", summary.overallScore >= 60)
        assertTrue("Should earn at least 1 star", summary.stars >= 1)
        assertEquals(3, summary.completedGoalsCount)
    }
}
