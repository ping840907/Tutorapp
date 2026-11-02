package com.tutorapp.fluenttutor.engine

import com.tutorapp.fluenttutor.model.MissionFinalSummary
import com.tutorapp.fluenttutor.model.MissionGoal
import com.tutorapp.fluenttutor.model.MissionStatus
import com.tutorapp.fluenttutor.model.Scenario
import com.tutorapp.fluenttutor.model.TurnEvaluation

/** 單一任務 session 的完整狀態 (不可變，每回合產生新狀態) */
data class MissionState(
    val goals: List<MissionGoal>,
    val offTopicStrikes: Int = 0,
    val evaluations: List<TurnEvaluation> = emptyList(),
    val status: MissionStatus = MissionStatus.IN_PROGRESS
)

/**
 * 任務狀態機：只信任單一模型 (LFM3-Audio) 的結構化輸出，本身不做任何語意判斷。
 * 僅負責：
 * 1. 驗證模型輸出 (未知的 goal id 忽略、離題回合不記錄目標、強制目標順序、分數夾在 0..100)
 * 2. 記錄回合評分、離題 Strike 計數
 * 3. 推進 IN_PROGRESS / PASSED / FAILED 狀態並產生結算成績單
 */
class MissionStateMachine(private val scenario: Scenario) {

    fun initialState(): MissionState =
        MissionState(goals = scenario.goals.map { it.copy(isCompleted = false) })

    /**
     * 套用模型一輪輸出。模型沒聽清楚 (transcript 為空) 時狀態不變、不計入回合。
     */
    fun applyTurn(state: MissionState, output: ModelTurnOutput): MissionState {
        if (state.status != MissionStatus.IN_PROGRESS || output.isUnintelligible) return state

        val offTopic = !output.onTopic

        // 離題回合不記錄任何目標；其餘回合只接受「已知、未完成、且前置目標都完成」的 id (可同回合連續完成多個)
        val accepted = mutableListOf<String>()
        if (!offTopic) {
            val claimed = output.completedGoalIds.toSet()
            for (goal in state.goals.sortedBy { it.orderIndex }) {
                if (goal.isCompleted || goal.id !in claimed) continue
                val prerequisitesDone = state.goals
                    .filter { it.orderIndex < goal.orderIndex }
                    .all { it.isCompleted || it.id in accepted }
                if (prerequisitesDone) accepted.add(goal.id)
            }
        }
        val goals = state.goals.map { if (it.id in accepted) it.copy(isCompleted = true) else it }

        val relevance = output.relevance.coerceIn(0, 100).let { if (offTopic) it.coerceAtMost(30) else it }
        val fluency = output.fluency.coerceIn(0, 100)
        val feedback = output.feedback.ifBlank {
            when {
                offTopic -> "⚠️ That seemed off-topic! Please focus on your role in this scenario."
                accepted.isNotEmpty() -> "🎯 Excellent! You achieved a mission milestone!"
                else -> "👍 Good response! Keep going toward the remaining goal."
            }
        }

        val evaluation = TurnEvaluation(
            turnIndex = state.evaluations.size,
            userInput = output.transcript,
            isOffTopic = offTopic,
            relevanceScore = relevance,
            fluencyScore = fluency,
            feedback = feedback,
            completedGoalIds = accepted
        )

        val strikes = state.offTopicStrikes + if (offTopic) 1 else 0
        val evaluations = state.evaluations + evaluation

        val status = when {
            goals.all { it.isCompleted } -> MissionStatus.PASSED
            strikes >= scenario.maxOffTopicStrikes -> MissionStatus.FAILED
            evaluations.size >= scenario.maxTurns -> MissionStatus.FAILED
            else -> MissionStatus.IN_PROGRESS
        }

        return MissionState(goals, strikes, evaluations, status)
    }

    /** 任務結束時產生成績單；進行中回傳 null */
    fun buildSummary(state: MissionState): MissionFinalSummary? {
        if (state.status == MissionStatus.IN_PROGRESS) return null

        val evaluations = state.evaluations
        val avgFluency = if (evaluations.isNotEmpty()) evaluations.map { it.fluencyScore }.average().toInt() else 0
        val avgRelevance = if (evaluations.isNotEmpty()) evaluations.map { it.relevanceScore }.average().toInt() else 0

        val baseScore = (avgFluency * 0.4 + avgRelevance * 0.6).toInt()
        val penalty = state.offTopicStrikes * 15

        val finalScore = when (state.status) {
            MissionStatus.PASSED -> (baseScore - penalty).coerceIn(65, 100)
            MissionStatus.FAILED -> (baseScore - penalty - 25).coerceIn(10, 59)
            MissionStatus.IN_PROGRESS -> baseScore
        }

        val stars = when {
            state.status == MissionStatus.PASSED && finalScore >= 90 -> 3
            state.status == MissionStatus.PASSED && finalScore >= 75 -> 2
            state.status == MissionStatus.PASSED -> 1
            else -> 0
        }

        val strikeFail = state.offTopicStrikes >= scenario.maxOffTopicStrikes
        val summaryFeedback = when (state.status) {
            MissionStatus.PASSED -> "🎉 MISSION ACCOMPLISHED! You handled all scenario tasks fluently!"
            MissionStatus.FAILED ->
                if (strikeFail) "❌ MISSION FAILED: You deviated from the mission goal too many times (${state.offTopicStrikes} strikes)."
                else "❌ MISSION FAILED: Reached the maximum turn limit (${evaluations.size} turns) before fulfilling all required goals."
            MissionStatus.IN_PROGRESS -> "Mission in progress."
        }

        val tips = mutableListOf<String>()
        if (state.status == MissionStatus.PASSED) {
            tips.add("✨ Great job staying on task!")
            tips.add("💡 Ready to challenge another English scenario?")
        } else {
            tips.add("🎯 Always keep an eye on the checklist card at the top.")
            tips.add("🗣️ Give direct, relevant answers to the NPC's prompts.")
            tips.add("🔄 Tap Retry to attempt this mission again!")
        }
        evaluations.lastOrNull { it.feedback.isNotBlank() }?.let { tips.add("📝 Last note: ${it.feedback}") }

        return MissionFinalSummary(
            status = state.status,
            totalTurns = evaluations.size,
            offTopicStrikes = state.offTopicStrikes,
            maxStrikes = scenario.maxOffTopicStrikes,
            completedGoalsCount = state.goals.count { it.isCompleted },
            totalGoalsCount = state.goals.size,
            overallScore = finalScore,
            stars = stars,
            summaryFeedback = summaryFeedback,
            improvementTips = tips
        )
    }
}
