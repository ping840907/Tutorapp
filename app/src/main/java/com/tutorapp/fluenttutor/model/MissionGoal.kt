package com.tutorapp.fluenttutor.model

/**
 * 任務目標定義
 * @param id 目標識別符
 * @param title 目標描述 (如 "點一杯燕麥奶拿鐵")
 * @param explanation 輔助說明
 * @param keywords 語意關鍵詞集合
 * @param isCompleted 是否已達成
 */
data class MissionGoal(
    val id: String,
    val title: String,
    val explanation: String,
    val keywords: List<String>,
    val isCompleted: Boolean = false
)

enum class MissionStatus {
    IN_PROGRESS, // 進行中
    PASSED,      // 恭喜過關 (所有核心目標達成)
    FAILED       // 任務失敗 (完全偏離主題或超出容忍次數)
}
