package com.tutorapp.fluenttutor

import com.tutorapp.fluenttutor.engine.MissionJudgeEngine
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
        cafeScenario = Scenario.getPredefinedEnglishScenarios().first { it.id == "cafe_latte_en" }
    }

    @Test
    fun testSequentialGoalCompletion() {
        // 第一輪：點燕麥奶拿鐵 (符合 orderIndex = 1)
        val turn1 = judgeEngine.evaluateTurn(
            userInput = "Hi! I would like to order an oat milk latte please.",
            currentGoals = cafeScenario.goals,
            scenario = cafeScenario,
            turnIndex = 0,
            audioDurationMs = 2100L
        )
        assertFalse("Should not be off-topic", turn1.isOffTopic)
        assertTrue("Goal 1 should be completed", turn1.completedGoalIds.contains("goal_drink_oat"))
        assertFalse("Goal 2 should not be completed prematurely", turn1.completedGoalIds.contains("goal_temp_size"))

        val updatedGoals1 = cafeScenario.goals.map {
            if (it.id == "goal_drink_oat") it.copy(isCompleted = true) else it
        }

        // 第二輪：指定冰的與中杯 (符合 orderIndex = 2)
        val turn2 = judgeEngine.evaluateTurn(
            userInput = "Please make it iced and medium.",
            currentGoals = updatedGoals1,
            scenario = cafeScenario,
            turnIndex = 1,
            audioDurationMs = 1500L
        )
        assertTrue("Goal 2 should be completed", turn2.completedGoalIds.contains("goal_temp_size"))

        val updatedGoals2 = updatedGoals1.map {
            if (it.id == "goal_temp_size") it.copy(isCompleted = true) else it
        }

        // 第三輪：完成刷卡付款 (符合 orderIndex = 3)
        val turn3 = judgeEngine.evaluateTurn(
            userInput = "I will pay with my credit card, thank you!",
            currentGoals = updatedGoals2,
            scenario = cafeScenario,
            turnIndex = 2,
            audioDurationMs = 1800L
        )
        assertTrue("Goal 3 should be completed", turn3.completedGoalIds.contains("goal_payment"))

        val finalGoals = updatedGoals2.map {
            if (it.id == "goal_payment") it.copy(isCompleted = true) else it
        }

        // 判定過關
        val finalStatus = judgeEngine.checkMissionStatus(
            goals = finalGoals,
            offTopicStrikes = 0,
            totalTurns = 3,
            scenario = cafeScenario
        )
        assertEquals(MissionStatus.PASSED, finalStatus)
    }

    @Test
    fun testNegativeExclusionPreventsFalsePositive() {
        // 使用者說「不要燕麥奶」或「不要拿鐵」，負向排除應該生效，不觸發目標達成
        val eval = judgeEngine.evaluateTurn(
            userInput = "I don't want oat milk, give me regular tea instead.",
            currentGoals = cafeScenario.goals,
            scenario = cafeScenario,
            turnIndex = 0,
            audioDurationMs = 2200L
        )
        assertFalse("Should NOT complete oat latte goal when user says don't want oat", eval.completedGoalIds.contains("goal_drink_oat"))
    }

    @Test
    fun testOffTopicDetectionAndFailure() {
        val offTopicEval = judgeEngine.evaluateTurn(
            userInput = "I love playing Minecraft and video games on my PlayStation all night!",
            currentGoals = cafeScenario.goals,
            scenario = cafeScenario,
            turnIndex = 0,
            audioDurationMs = 2500L
        )
        assertTrue("Should detect off-topic speech", offTopicEval.isOffTopic)
        assertEquals(20, offTopicEval.relevanceScore)

        // 累積偏離 3 次觸發任務失敗
        val failedStatus = judgeEngine.checkMissionStatus(
            goals = cafeScenario.goals,
            offTopicStrikes = 3,
            totalTurns = 3,
            scenario = cafeScenario
        )
        assertEquals(MissionStatus.FAILED, failedStatus)
    }

    @Test
    fun testWordBoundaryPreventsSubstringMatch() {
        // "photo" 不應該觸發 "hot"
        val eval = judgeEngine.evaluateTurn(
            userInput = "Can I show you a photo from my trip?",
            currentGoals = cafeScenario.goals,
            scenario = cafeScenario,
            turnIndex = 0,
            audioDurationMs = 1800L
        )
        assertFalse("Word 'photo' must not match 'hot'", eval.completedGoalIds.contains("goal_temp_size"))
    }
}
