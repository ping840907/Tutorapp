package com.tutorapp.fluenttutor.model

/**
 * NPC 視覺造型風格 (對應不同場景角色)
 */
enum class NpcAvatarStyle(
    val primaryColorHex: Long,
    val secondaryColorHex: Long,
    val hairColorHex: Long,
    val skinColorHex: Long,
    val roleBadge: String
) {
    BARISTA_SARAH(
        primaryColorHex = 0xFF2DD4BF,   // 咖啡廳青綠圍裙色
        secondaryColorHex = 0xFFF59E0B, // 暖琥珀色
        hairColorHex = 0xFF5C3A21,      // 暖棕髮色
        skinColorHex = 0xFFFFDFC4,
        roleBadge = "☕ NYC Barista"
    ),
    CONCIERGE_DAVID(
        primaryColorHex = 0xFF38BDF8,   // 禮賓部皇家藍
        secondaryColorHex = 0xFFEAB308, // 金色徽章
        hairColorHex = 0xFF1E293B,      // 深黑灰髮色
        skinColorHex = 0xFFF5D0B5,
        roleBadge = "🛎️ Hotel Concierge"
    ),
    AGENT_ELENA(
        primaryColorHex = 0xFF818CF8,   // 航空靛藍
        secondaryColorHex = 0xFFEC4899, // 領巾亮粉紅
        hairColorHex = 0xFF9A3412,      // 栗紅棕髮色
        skinColorHex = 0xFFFFE4C9,
        roleBadge = "✈️ Airline Agent"
    )
}

/**
 * NPC 即時互動行為狀態
 */
enum class NpcInteractionState(val statusLabel: String) {
    IDLE("Ready to chat"),
    LISTENING_IDLE("Listening..."),
    LISTENING_ACTIVE("Hearing you speak..."),
    THINKING("Thinking..."),
    SPEAKING("Speaking...")
}

/**
 * NPC 臉部表情與情緒狀態
 */
enum class NpcEmotion(val label: String, val emoji: String) {
    WELCOMING("Welcoming", "😊"),
    HAPPY_ENCOURAGING("Encouraging", "😄"),
    EXCITED_PROUD("Impressed!", "🌟"),
    CONFUSED_WARNING("Confused / Off-Topic", "🤔"),
    DISAPPOINTED_FAILED("Mission Failed", "😔")
}
