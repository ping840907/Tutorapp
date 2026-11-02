package com.tutorapp.fluenttutor.model

data class TurnEvaluation(
    val turnIndex: Int,
    val userInput: String,
    val isOffTopic: Boolean,
    val relevanceScore: Int,      // 0 ~ 100
    val fluencyScore: Int,        // 0 ~ 100
    val feedback: String,
    val completedGoalIds: List<String>
)

data class MissionFinalSummary(
    val status: MissionStatus,
    val totalTurns: Int,
    val offTopicStrikes: Int,
    val maxStrikes: Int,
    val completedGoalsCount: Int,
    val totalGoalsCount: Int,
    val overallScore: Int,        // 0 ~ 100
    val stars: Int,               // 0 ~ 3
    val summaryFeedback: String,
    val improvementTips: List<String>
)
