package com.tutorapp.fluenttutor.model

/**
 * 任務目標定義
 * @param id 唯一標識符
 * @param title 目標名稱
 * @param explanation 達成提示
 * @param requiredPatterns 必備意圖的正則模式清單 (符合全詞邊界)
 * @param negativeExclusions 負向排除詞 (若出現否定句則不計達成，例如 "no oat milk")
 * @param orderIndex 階段順序索引 (確保循序漸進，避免一次灌水過關)
 * @param isCompleted 當前是否已達成
 */
data class MissionGoal(
    val id: String,
    val title: String,
    val explanation: String,
    val requiredPatterns: List<Regex>,
    val negativeExclusions: List<Regex> = emptyList(),
    val orderIndex: Int = 0,
    val isCompleted: Boolean = false
)

enum class MissionStatus {
    IN_PROGRESS,
    PASSED,
    FAILED
}
