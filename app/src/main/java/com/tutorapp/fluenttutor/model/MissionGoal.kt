package com.tutorapp.fluenttutor.model

/**
 * 任務目標定義。目標是否達成由 LFM3-Audio 模型依 [criteria] 判斷並輸出，
 * 再由 MissionStateMachine 驗證與記錄；App 端不再使用任何關鍵字/正規表達式判定。
 *
 * @param id 目標唯一標識 (模型輸出的 goals 陣列必須使用此 id)
 * @param title 目標標題 (顯示於 UI)
 * @param explanation 給使用者看的提示
 * @param criteria 給模型看的達成條件描述 (寫入 system prompt)
 * @param orderIndex 階段順序索引 (狀態機強制順序：前面的目標未完成，後面的不會被記錄)
 * @param isCompleted 目標是否已達成
 */
data class MissionGoal(
    val id: String,
    val title: String,
    val explanation: String,
    val criteria: String,
    val orderIndex: Int = 0,
    val isCompleted: Boolean = false
)

enum class MissionStatus {
    IN_PROGRESS,
    PASSED,
    FAILED
}
