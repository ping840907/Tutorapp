package com.tutorapp.fluenttutor.model

data class Scenario(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val roleName: String,
    val avatarStyle: NpcAvatarStyle = NpcAvatarStyle.BARISTA_SARAH,
    val systemPrompt: String,
    val initialGreeting: String,
    val goals: List<MissionGoal>,
    val maxOffTopicStrikes: Int = 3,
    val maxTurns: Int = 10
) {
    companion object {
        fun getPredefinedEnglishScenarios(): List<Scenario> = listOf(
            // --- 場景 1：紐約咖啡廳點餐 ---
            Scenario(
                id = "cafe_latte_en",
                title = "Coffee Shop Order",
                subtitle = "Order an iced oat milk latte",
                description = "You're at Bean & Leaf in New York. Order an oat milk latte, select iced/medium, and pay by card.",
                roleName = "Sarah (Barista)",
                avatarStyle = NpcAvatarStyle.BARISTA_SARAH,
                systemPrompt = """
                    You are Sarah, a warm and efficient barista at Bean & Leaf cafe in Manhattan.
                    Speak naturally in concise spoken English (1-2 sentences).
                    Ask for missing order details sequentially: drink, milk choice, size/temperature, and payment method.
                """.trimIndent(),
                initialGreeting = "Hi there! Welcome to Bean & Leaf. What can I get started for you today?",
                goals = listOf(
                    MissionGoal(
                        id = "goal_drink_oat",
                        title = "Order a latte with oat milk",
                        explanation = "Say you'd like a latte and ask for oat milk.",
                        requiredPatterns = listOf(
                            Regex("\\b(latte|coffee)\\b", RegexOption.IGNORE_CASE),
                            Regex("\\b(oat\\s*milk|oat)\\b", RegexOption.IGNORE_CASE)
                        ),
                        negativeExclusions = listOf(
                            Regex("\\b(no|without|don't\\s*want|not)\\s+(oat|latte)\\b", RegexOption.IGNORE_CASE)
                        ),
                        orderIndex = 1
                    ),
                    MissionGoal(
                        id = "goal_temp_size",
                        title = "Specify temperature & size",
                        explanation = "State size (small/medium/large) and iced or hot.",
                        requiredPatterns = listOf(
                            Regex("\\b(iced|hot|cold)\\b", RegexOption.IGNORE_CASE),
                            Regex("\\b(small|medium|large|tall|grande|venti)\\b", RegexOption.IGNORE_CASE)
                        ),
                        orderIndex = 2
                    ),
                    MissionGoal(
                        id = "goal_payment",
                        title = "Complete payment & thank barista",
                        explanation = "Say how you'll pay (card/cash/apple pay) and say thanks.",
                        requiredPatterns = listOf(
                            Regex("\\b(card|cash|apple\\s*pay|pay|credit)\\b", RegexOption.IGNORE_CASE),
                            Regex("\\b(thanks|thank\\s*you|here\\s*you\\s*go)\\b", RegexOption.IGNORE_CASE)
                        ),
                        orderIndex = 3
                    )
                )
            ),

            // --- 場景 2：飯店前台入住辦理 ---
            Scenario(
                id = "hotel_checkin_en",
                title = "Hotel Front Desk",
                subtitle = "Check in and ask for breakfast & Wi-Fi",
                description = "Check in with your reservation, ask about breakfast time and Wi-Fi password at Grand Hyatt.",
                roleName = "David (Concierge)",
                avatarStyle = NpcAvatarStyle.CONCIERGE_DAVID,
                systemPrompt = """
                    You are David, the concierge at Grand Hyatt. Polite, clear, and professional.
                    Acknowledge the reservation, provide room key info, and answer guest questions.
                """.trimIndent(),
                initialGreeting = "Good afternoon, welcome to Grand Hyatt. How may I assist you with your stay?",
                goals = listOf(
                    MissionGoal(
                        id = "goal_hotel_checkin",
                        title = "State reservation & check-in",
                        explanation = "Mention you'd like to check in and have a reservation.",
                        requiredPatterns = listOf(
                            Regex("\\b(check\\s*in|checking\\s*in)\\b", RegexOption.IGNORE_CASE),
                            Regex("\\b(reservation|booking|name)\\b", RegexOption.IGNORE_CASE)
                        ),
                        orderIndex = 1
                    ),
                    MissionGoal(
                        id = "goal_hotel_amenities",
                        title = "Ask about breakfast & Wi-Fi",
                        explanation = "Inquire about breakfast time or Wi-Fi login.",
                        requiredPatterns = listOf(
                            Regex("\\b(breakfast|morning\\s*meal)\\b", RegexOption.IGNORE_CASE),
                            Regex("\\b(wi-?fi|internet|password)\\b", RegexOption.IGNORE_CASE)
                        ),
                        orderIndex = 2
                    ),
                    MissionGoal(
                        id = "goal_hotel_key",
                        title = "Acknowledge room key & say thanks",
                        explanation = "Confirm the room key and express appreciation.",
                        requiredPatterns = listOf(
                            Regex("\\b(key|room|elevator)\\b", RegexOption.IGNORE_CASE),
                            Regex("\\b(thank|thanks|appreciate|great)\\b", RegexOption.IGNORE_CASE)
                        ),
                        orderIndex = 3
                    )
                )
            ),

            // --- 場景 3：機場櫃檯登機手續 ---
            Scenario(
                id = "airport_checkin_en",
                title = "Airport Boarding Gate",
                subtitle = "Check in luggage & request seat preference",
                description = "At JFK terminal. Check in for your flight, state seat preference (window/aisle), and ask for gate number.",
                roleName = "Elena (Airline Agent)",
                avatarStyle = NpcAvatarStyle.AGENT_ELENA,
                systemPrompt = """
                    You are Elena, a customer service agent for Delta Air Lines at JFK airport.
                    Keep sentences concise, professional, and friendly.
                """.trimIndent(),
                initialGreeting = "Good morning! Flying with Delta today? May I see your passport and ticket please?",
                goals = listOf(
                    MissionGoal(
                        id = "goal_flight_info",
                        title = "State destination & pass ticket",
                        explanation = "Say where you are flying to and offer your passport/ticket.",
                        requiredPatterns = listOf(
                            Regex("\\b(flight|flying|ticket|passport|here\\s*is)\\b", RegexOption.IGNORE_CASE)
                        ),
                        orderIndex = 1
                    ),
                    MissionGoal(
                        id = "goal_seat_choice",
                        title = "Request window or aisle seat",
                        explanation = "Ask for either a window seat or an aisle seat.",
                        requiredPatterns = listOf(
                            Regex("\\b(window|aisle|seat)\\b", RegexOption.IGNORE_CASE)
                        ),
                        orderIndex = 2
                    ),
                    MissionGoal(
                        id = "goal_gate_time",
                        title = "Confirm boarding gate & time",
                        explanation = "Ask what gate and what time boarding starts.",
                        requiredPatterns = listOf(
                            Regex("\\b(gate|boarding|time)\\b", RegexOption.IGNORE_CASE)
                        ),
                        orderIndex = 3
                    )
                )
            )
        )
    }
}
