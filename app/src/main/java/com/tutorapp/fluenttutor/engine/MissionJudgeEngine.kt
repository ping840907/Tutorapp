package com.tutorapp.fluenttutor.engine

import com.tutorapp.fluenttutor.model.MissionFinalSummary
import com.tutorapp.fluenttutor.model.MissionGoal
import com.tutorapp.fluenttutor.model.MissionStatus
import com.tutorapp.fluenttutor.model.Scenario
import com.tutorapp.fluenttutor.model.TurnEvaluation

/**
 * 獨立副判定引擎 (Judge & Anti-Hallucination Secondary Engine)
 * 專為防止 2B Speech-to-Speech (S2S) 主模型產生任務判定幻覺 (Hallucination) 而設計。
 * 具備：
 * 1. 意圖邊界識別 (Regex Boundary Matching，防止 "photo" 誤判成 "hot")
 * 2. 負向否定句過濾 (Negative Exclusions，防止 "I don't want oat milk" 誤判為達成)
 * 3. 階段順序依賴性約束 (Sequential Dependency，防止一句話灌水同時完成全部目標)
 * 4. 嚴密偏離情境審查 (Off-Topic Semantic Auditor，累計 3 次 Strikes 判定任務失敗)
 */
class MissionJudgeEngine {

    companion object {
        // 明顯偏離英語對話情境的離題詞組庫
        private val UNRELATED_TOPICS = listOf(
            Regex("\\b(minecraft|fortnite|playstation|xbox|video\\s*game|gameplay)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(alien|ufo|extraterrestrial|space\\s*ship|mars)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(election|president|congress|politics|political|senate)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(homework|math\\s*test|chemistry|exam|algebra)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(blablabla|nonsense|whatever|who\\s*cares|shut\\s*up)\\b", RegexOption.IGNORE_CASE)
        )
    }

    /**
     * 獨立評估單輪語音輸入
     */
    fun evaluateTurn(
        userInput: String,
        currentGoals: List<MissionGoal>,
        scenario: Scenario,
        turnIndex: Int,
        audioDurationMs: Long
    ): TurnEvaluation {
        val cleanInput = userInput.trim()

        // 1. 離題審查 (Off-Topic Audit)
        val isOffTopic = checkOffTopic(cleanInput, scenario)

        // 2. 目標達成嚴密檢驗 (防作弊與順序約束)
        val newlyCompletedGoalIds = mutableListOf<String>()

        if (!isOffTopic && cleanInput.isNotBlank()) {
            val completedOrderIndexMax = currentGoals
                .filter { it.isCompleted }
                .maxOfOrNull { it.orderIndex } ?: 0

            for (goal in currentGoals) {
                if (!goal.isCompleted) {
                    // 順序約束：當前階段只能達成 orderIndex <= completedOrderIndexMax + 1 的目標
                    if (goal.orderIndex <= completedOrderIndexMax + 1) {
                        val isExcluded = goal.negativeExclusions.any { it.containsMatchIn(cleanInput) }
                        if (!isExcluded) {
                            val isAllPatternsMatched = goal.requiredPatterns.all { pattern ->
                                pattern.containsMatchIn(cleanInput)
                            }
                            if (isAllPatternsMatched) {
                                newlyCompletedGoalIds.add(goal.id)
                                // 單輪最多只允許推進一個新階段目標，防止一句話作弊全打勾
                                break
                            }
                        }
                    }
                }
            }
        }

        // 3. 計算發音與語速流暢度評分 (Words Per Minute / 依音訊時長)
        val wordCount = cleanInput.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val fluencyScore = calculateFluencyScore(wordCount, audioDurationMs)

        // 4. 計算任務相關度得分
        val relevanceScore = when {
            isOffTopic -> 20
            newlyCompletedGoalIds.isNotEmpty() -> 95
            cleanInput.length >= 8 -> 80
            else -> 60
        }

        val feedback = when {
            isOffTopic -> "⚠️ Notice: That seemed completely off-topic! Please focus on your current role in this scenario."
            newlyCompletedGoalIds.isNotEmpty() -> "🎯 Excellent! You successfully achieved a mission milestone!"
            else -> "👍 Good response! Continue natural dialogue toward the remaining goal."
        }

        return TurnEvaluation(
            turnIndex = turnIndex,
            userInput = userInput,
            isOffTopic = isOffTopic,
            relevanceScore = relevanceScore,
            fluencyScore = fluencyScore,
            feedback = feedback,
            completedGoalIds = newlyCompletedGoalIds
        )
    }

    /**
     * 檢查是否完全偏離情境
     */
    private fun checkOffTopic(input: String, scenario: Scenario): Boolean {
        if (input.isBlank()) return false

        // 通用離題庫比對
        if (UNRELATED_TOPICS.any { it.containsMatchIn(input) }) {
            return true
        }

        // 場景特定領域排斥判定
        return when (scenario.id) {
            "cafe_latte_en" -> {
                // 在咖啡廳談論修車、買高鐵票、看病
                val cafeIrrelevant = Regex("\\b(car\\s*repair|mechanic|flight\\s*ticket|hospital|surgery)\\b", RegexOption.IGNORE_CASE)
                cafeIrrelevant.containsMatchIn(input)
            }
            "hotel_checkin_en" -> {
                val hotelIrrelevant = Regex("\\b(dentist|tractor|football\\s*stadium|roller\\s*coaster)\\b", RegexOption.IGNORE_CASE)
                hotelIrrelevant.containsMatchIn(input)
            }
            "airport_checkin_en" -> {
                val airportIrrelevant = Regex("\\b(haircut|swimming\\s*pool|camping\\s*tent)\\b", RegexOption.IGNORE_CASE)
                airportIrrelevant.containsMatchIn(input)
            }
            else -> false
        }
    }

    private fun calculateFluencyScore(wordCount: Int, durationMs: Long): Int {
        if (durationMs <= 0 || wordCount == 0) return 80
        val durationSec = durationMs / 1000.0
        val wps = wordCount / durationSec.coerceAtLeast(0.4)

        // 英語自然口語速度約 2.0 ~ 3.2 words/sec (120~190 WPM)
        return when {
            wps in 1.8..3.5 -> 95
            wps in 1.0..4.5 -> 85
            wps < 1.0 -> 72
            else -> 75
        }
    }

    /**
     * 綜合判定過關與失敗狀態
     */
    fun checkMissionStatus(
        goals: List<MissionGoal>,
        offTopicStrikes: Int,
        totalTurns: Int,
        scenario: Scenario
    ): MissionStatus {
        val allCompleted = goals.all { it.isCompleted }

        return when {
            allCompleted -> MissionStatus.PASSED
            offTopicStrikes >= scenario.maxOffTopicStrikes -> MissionStatus.FAILED
            totalTurns >= scenario.maxTurns -> MissionStatus.FAILED
            else -> MissionStatus.IN_PROGRESS
        }
    }

    /**
     * 結算最終成績單
     */
    fun generateFinalSummary(
        status: MissionStatus,
        goals: List<MissionGoal>,
        evaluations: List<TurnEvaluation>,
        offTopicStrikes: Int,
        scenario: Scenario
    ): MissionFinalSummary {
        val completedCount = goals.count { it.isCompleted }
        val totalGoals = goals.size

        val avgFluency = if (evaluations.isNotEmpty()) evaluations.map { it.fluencyScore }.average().toInt() else 80
        val avgRelevance = if (evaluations.isNotEmpty()) evaluations.map { it.relevanceScore }.average().toInt() else 80

        val baseScore = (avgFluency * 0.4 + avgRelevance * 0.6).toInt()
        val penalty = offTopicStrikes * 15

        val finalScore = when (status) {
            MissionStatus.PASSED -> (baseScore - penalty).coerceIn(65, 100)
            MissionStatus.FAILED -> (baseScore - penalty - 25).coerceIn(10, 59)
            MissionStatus.IN_PROGRESS -> baseScore
        }

        val stars = when {
            status == MissionStatus.PASSED && finalScore >= 90 -> 3
            status == MissionStatus.PASSED && finalScore >= 75 -> 2
            status == MissionStatus.PASSED -> 1
            else -> 0
        }

        val summaryFeedback = when (status) {
            MissionStatus.PASSED -> {
                "🎉 MISSION ACCOMPLISHED! You handled all scenario tasks fluently and communicated naturally like a native speaker!"
            }
            MissionStatus.FAILED -> {
                if (offTopicStrikes >= scenario.maxOffTopicStrikes) {
                    "❌ MISSION FAILED: You deviated from the mission goal too many times ($offTopicStrikes strikes)."
                } else {
                    "❌ MISSION FAILED: Reached the maximum turn limit (${evaluations.size} turns) before fulfilling all required goals."
                }
            }
            MissionStatus.IN_PROGRESS -> "Mission in progress."
        }

        val tips = mutableListOf<String>()
        if (status == MissionStatus.PASSED) {
            tips.add("✨ Excellent cadence and clear acoustic rhythm!")
            tips.add("💡 Ready to challenge another English scenario?")
        } else {
            tips.add("🎯 Always keep an eye on the checklist card at the top.")
            tips.add("🗣️ Give direct, relevant answers to the AI's prompts.")
            tips.add("🔄 Tap Retry to attempt this mission again!")
        }

        return MissionFinalSummary(
            status = status,
            totalTurns = evaluations.size,
            offTopicStrikes = offTopicStrikes,
            maxStrikes = scenario.maxOffTopicStrikes,
            completedGoalsCount = completedCount,
            totalGoalsCount = totalGoals,
            overallScore = finalScore,
            stars = stars,
            summaryFeedback = summaryFeedback,
            improvementTips = tips
        )
    }
}
