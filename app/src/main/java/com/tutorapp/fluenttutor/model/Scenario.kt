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
                    Ask for missing order details one at a time: drink, milk choice, size/temperature, then payment method.
                """.trimIndent(),
                initialGreeting = "Hi there! Welcome to Bean & Leaf. What can I get started for you today?",
                goals = listOf(
                    MissionGoal(
                        id = "goal_drink_oat",
                        title = "Order a latte with oat milk",
                        explanation = "Say you'd like a latte and ask for oat milk.",
                        criteria = "The learner orders a latte (or coffee) AND asks for oat milk. Not satisfied if they refuse or negate oat milk.",
                        orderIndex = 1
                    ),
                    MissionGoal(
                        id = "goal_temp_size",
                        title = "Specify temperature & size",
                        explanation = "State size (small/medium/large) and iced or hot.",
                        criteria = "The learner states BOTH the temperature (iced/hot) AND the size (small/medium/large or tall/grande/venti) of the drink.",
                        orderIndex = 2
                    ),
                    MissionGoal(
                        id = "goal_payment",
                        title = "Complete payment & thank barista",
                        explanation = "Say how you'll pay (card/cash/apple pay) and say thanks.",
                        criteria = "The learner says how they will pay (card, cash, Apple Pay, etc.) AND thanks the barista.",
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
                    Acknowledge the reservation, provide room key info (Room 704), and answer guest questions.
                    Breakfast is on the 2nd floor from 6:30 to 10:00 AM; the Wi-Fi network is HyattGuest with no password.
                """.trimIndent(),
                initialGreeting = "Good afternoon, welcome to Grand Hyatt. How may I assist you with your stay?",
                goals = listOf(
                    MissionGoal(
                        id = "goal_hotel_checkin",
                        title = "State reservation & check-in",
                        explanation = "Mention you'd like to check in and have a reservation.",
                        criteria = "The learner says they want to check in AND mentions a reservation/booking (or gives the name it is under).",
                        orderIndex = 1
                    ),
                    MissionGoal(
                        id = "goal_hotel_amenities",
                        title = "Ask about breakfast or Wi-Fi",
                        explanation = "Inquire about breakfast time or Wi-Fi login.",
                        criteria = "The learner asks about breakfast (time/place) OR about the Wi-Fi (network/password).",
                        orderIndex = 2
                    ),
                    MissionGoal(
                        id = "goal_hotel_key",
                        title = "Acknowledge room key & say thanks",
                        explanation = "Confirm the room key and express appreciation.",
                        criteria = "The learner acknowledges receiving the room key/room information AND thanks the concierge.",
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
                    Keep sentences concise, professional, and friendly. Seats available: 14A (window), 14C (aisle).
                    The flight departs from Gate B22 and boarding begins at 10:15 AM.
                """.trimIndent(),
                initialGreeting = "Good morning! Flying with Delta today? May I see your passport and ticket please?",
                goals = listOf(
                    MissionGoal(
                        id = "goal_flight_info",
                        title = "State destination & pass ticket",
                        explanation = "Say where you are flying to and offer your passport/ticket.",
                        criteria = "The learner says where they are flying to AND offers/presents their passport or ticket.",
                        orderIndex = 1
                    ),
                    MissionGoal(
                        id = "goal_seat_choice",
                        title = "Request window or aisle seat",
                        explanation = "Ask for either a window seat or an aisle seat.",
                        criteria = "The learner explicitly requests a window seat OR an aisle seat.",
                        orderIndex = 2
                    ),
                    MissionGoal(
                        id = "goal_gate_time",
                        title = "Confirm boarding gate & time",
                        explanation = "Ask what gate and what time boarding starts.",
                        criteria = "The learner asks which gate the flight leaves from AND/OR what time boarding starts.",
                        orderIndex = 3
                    )
                )
            )
        )
    }
}
