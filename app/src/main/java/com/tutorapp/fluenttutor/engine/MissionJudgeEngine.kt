package com.tutorapp.fluenttutor.engine

import com.tutorapp.fluenttutor.model.Language
import com.tutorapp.fluenttutor.model.MissionFinalSummary
import com.tutorapp.fluenttutor.model.MissionGoal
import com.tutorapp.fluenttutor.model.MissionStatus
import com.tutorapp.fluenttutor.model.Scenario
import com.tutorapp.fluenttutor.model.TurnEvaluation

/**
 * 智慧任務目標與偏離判定小模型引擎 (Mission Judge & Evaluation Engine)
 * 負責即時檢驗語音對話內容：
 * 1. 子目標達成判定 (Sub-goal completion)
 * 2. 偏離情境檢測與警告 (Off-topic detection & strikes)
 * 3. 宣布過關 (Mission Passed) 或 宣告失敗 (Mission Failed)
 * 4. 多維度學習評分 (相關度、流暢度、星級評價)
 */
class MissionJudgeEngine {

    // 偏離情境的黑名單概念庫 (無關主題如遊戲、外星人、無意義字眼等)
    private val offTopicEnKeywords = listOf(
        "minecraft", "video game", "play station", "alien", "ufo", "politics", "president",
        "football match", "homework", "boring", "nonsense", "blablabla", "supermarket"
    )
    private val offTopicJaKeywords = listOf(
        "アニメ", "ゲーム", "宇宙人", "政治", "宿題", "つまらない", "関係ない", "なんだこれ"
    )

    /**
     * 評估單一輪次的對話輸入
     */
    fun evaluateTurn(
        userInput: String,
        currentGoals: List<MissionGoal>,
        scenario: Scenario,
        turnIndex: Int,
        audioDurationMs: Long
    ): TurnEvaluation {
        val cleanInput = userInput.trim().lowercase()

        // 1. 檢查是否偏離情境
        val isOffTopic = checkIsOffTopic(cleanInput, scenario)

        // 2. 檢查是否有目標達成
        val newlyCompletedGoalIds = mutableListOf<String>()
        if (!isOffTopic && cleanInput.isNotBlank()) {
            for (goal in currentGoals) {
                if (!goal.isCompleted) {
                    val isMatched = goal.keywords.any { keyword ->
                        cleanInput.contains(keyword.lowercase())
                    }
                    if (isMatched) {
                        newlyCompletedGoalIds.add(goal.id)
                    }
                }
            }
        }

        // 3. 計算發音與流暢度評分 (0 ~ 100)
        val wordCount = cleanInput.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val fluencyScore = calculateFluencyScore(wordCount, audioDurationMs)

        // 4. 計算任務相關度評分 (0 ~ 100)
        val relevanceScore = if (isOffTopic) {
            25
        } else if (newlyCompletedGoalIds.isNotEmpty()) {
            95
        } else {
            75
        }

        val feedback = when {
            isOffTopic -> {
                if (scenario.language == Language.ENGLISH) {
                    "⚠️ Notice: Your response seems unrelated to the mission! Please stay focused on the scenario."
                } else {
                    "⚠️ 警告：会話の内容がミッション目標から離れています。シナリオに集中してください。"
                }
            }
            newlyCompletedGoalIds.isNotEmpty() -> {
                if (scenario.language == Language.ENGLISH) {
                    "🎯 Great job! You successfully completed a mission goal!"
                } else {
                    "🎯 素晴らしい！目標をひとつ達成しました！"
                }
            }
            else -> {
                if (scenario.language == Language.ENGLISH) {
                    "👍 Good response, keep the conversation moving toward the remaining goals!"
                } else {
                    "👍 順調です！残りの目標に向けて会話を続けましょう。"
                }
            }
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
     * 判斷是否完全偏離情境
     */
    private fun checkIsOffTopic(cleanInput: String, scenario: Scenario): Boolean {
        if (cleanInput.isBlank()) return false

        val offList = if (scenario.language == Language.ENGLISH) offTopicEnKeywords else offTopicJaKeywords
        val hasOffTopicKeyword = offList.any { cleanInput.contains(it) }

        // 針對特定場景的偏離判定
        val isDomainUnrelated = when (scenario.id) {
            "cafe_latte_en" -> {
                // 如果在咖啡廳要求買機票或修車
                cleanInput.contains("flight ticket") || cleanInput.contains("car repair") || cleanInput.contains("hospital")
            }
            "convenience_store_ja" -> {
                // 如果在便利商店要求做手術或搭高鐵
                cleanInput.contains("新幹線") || cleanInput.contains("病院") || cleanInput.contains("手術")
            }
            else -> false
        }

        return hasOffTopicKeyword || isDomainUnrelated
    }

    /**
     * 依據字數與說話時長計算流暢度評分 (Words Per Minute / 自然語速區間)
     */
    private fun calculateFluencyScore(wordCount: Int, durationMs: Long): Int {
        if (durationMs <= 0) return 80
        val durationSec = durationMs / 1000.0
        val wordsPerSec = wordCount / durationSec.coerceAtLeast(0.5)

        // 正常英語口語大約每秒 2~3 個單詞 (120~180 WPM)
        return when {
            wordsPerSec in 1.5..3.5 -> 95
            wordsPerSec in 0.8..4.2 -> 85
            wordsPerSec < 0.8 -> 70
            else -> 75
        }
    }

    /**
     * 綜合判定當前任務狀態 (進行中 / 成功過關 / 任務失敗)
     */
    fun checkMissionStatus(
        goals: List<MissionGoal>,
        offTopicStrikes: Int,
        totalTurns: Int,
        scenario: Scenario
    ): MissionStatus {
        val allGoalsCompleted = goals.all { it.isCompleted }

        return when {
            allGoalsCompleted -> MissionStatus.PASSED
            offTopicStrikes >= scenario.maxOffTopicStrikes -> MissionStatus.FAILED
            totalTurns >= scenario.maxTurns -> MissionStatus.FAILED
            else -> MissionStatus.IN_PROGRESS
        }
    }

    /**
     * 計算最終任務總結報告 (評分、星星、反饋建議)
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
            MissionStatus.PASSED -> (baseScore - penalty).coerceIn(60, 100)
            MissionStatus.FAILED -> (baseScore - penalty - 30).coerceIn(10, 59)
            MissionStatus.IN_PROGRESS -> baseScore
        }

        val stars = when {
            status == MissionStatus.PASSED && finalScore >= 90 -> 3
            status == MissionStatus.PASSED && finalScore >= 75 -> 2
            status == MissionStatus.PASSED -> 1
            else -> 0
        }

        val isEnglish = scenario.language == Language.ENGLISH
        val summaryFeedback = when (status) {
            MissionStatus.PASSED -> {
                if (isEnglish) {
                    "🎉 Mission Passed! You communicated clearly and fulfilled all required goals with natural expressions!"
                } else {
                    "🎉 ミッション合格！目標をすべて達成し、自然な日本語でスムーズに会話できました！"
                }
            }
            MissionStatus.FAILED -> {
                if (offTopicStrikes >= scenario.maxOffTopicStrikes) {
                    if (isEnglish) {
                        "❌ Mission Failed: Conversation deviated from the scenario goal too many times ($offTopicStrikes strikes)."
                    } else {
                        "❌ ミッション失敗：目標から外れた発言が制限回数（$offTopicStrikes 回）を超えました。"
                    }
                } else {
                    if (isEnglish) {
                        "❌ Mission Failed: Turns exceeded limit before completing all goals."
                    } else {
                        "❌ ミッション失敗：制限ターン数以内にすべての目標を達成できませんでした。"
                    }
                }
            }
            MissionStatus.IN_PROGRESS -> "Mission in progress."
        }

        val tips = mutableListOf<String>()
        if (status == MissionStatus.PASSED) {
            if (isEnglish) {
                tips.add("✨ Excellent responsiveness and pronunciation timing!")
                tips.add("💡 Try varying your vocabulary in the next challenge.")
            } else {
                tips.add("✨ 的確な相槌とスムーズな受け答えができています！")
                tips.add("💡 次回は敬語表現や丁寧な言い回しにも挑戦してみましょう。")
            }
        } else {
            if (isEnglish) {
                tips.add("🎯 Focus directly on the goal checklist displayed at the top.")
                tips.add("🗣️ Speak clearly and keep your sentences relevant to the scenario role.")
                tips.add("🔄 Tap Retry to challenge this scenario again!")
            } else {
                tips.add("🎯 画面上部の目標リストを確認しながら発言してみましょう。")
                tips.add("🗣️ 状況に合った単語を意識して、はっきりと話しましょう。")
                tips.add("🔄 「もう一度挑戦」をタップしてリトライできます！")
            }
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
