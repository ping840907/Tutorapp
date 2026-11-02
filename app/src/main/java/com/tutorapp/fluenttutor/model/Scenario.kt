package com.tutorapp.fluenttutor.model

enum class Language(val code: String, val displayName: String, val flag: String) {
    ENGLISH("en", "English", "🇺🇸"),
    JAPANESE("ja", "日本語", "🇯🇵")
}

data class Scenario(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val language: Language,
    val systemPrompt: String,
    val aiInitialGreeting: String,
    val aiVoiceName: String = "aura-asteria-en",
    val goals: List<MissionGoal>,
    val maxOffTopicStrikes: Int = 3,
    val maxTurns: Int = 10
) {
    companion object {
        fun getPredefinedScenarios(): List<Scenario> = listOf(
            // --- 英文場景 1：咖啡廳點餐 ---
            Scenario(
                id = "cafe_latte_en",
                title = "Coffee Shop Order",
                subtitle = "Order an oat milk latte",
                description = "You're at a bustling cafe in New York. Order an iced oat milk latte, confirm the size, and complete payment.",
                language = Language.ENGLISH,
                systemPrompt = """
                    You are Sarah, a cheerful barista at a modern New York coffee shop.
                    Keep responses natural, concise (1-2 sentences), and conversational.
                    Guide the customer to specify drink type (latte/coffee), milk choice (oat milk), and size/temperature.
                    Stay in character at all times.
                """.trimIndent(),
                aiInitialGreeting = "Hi there! Welcome to Bean & Leaf. What can I get started for you today?",
                goals = listOf(
                    MissionGoal(
                        id = "goal_drink",
                        title = "Order a latte with oat milk",
                        explanation = "Mention you want a latte and ask for oat milk.",
                        keywords = listOf("latte", "oat milk", "oat", "coffee")
                    ),
                    MissionGoal(
                        id = "goal_size_temp",
                        title = "Specify size and temperature",
                        explanation = "State size (small/medium/large) and iced or hot.",
                        keywords = listOf("iced", "hot", "medium", "large", "small", "tall", "grande")
                    ),
                    MissionGoal(
                        id = "goal_pay",
                        title = "Complete payment",
                        explanation = "Say how you'll pay (card, cash, apple pay) or say thanks.",
                        keywords = listOf("card", "cash", "pay", "apple pay", "thanks", "receipt", "here you go")
                    )
                )
            ),

            // --- 英文場景 2：飯店登機/退房與問路 ---
            Scenario(
                id = "hotel_checkin_en",
                title = "Hotel Front Desk",
                subtitle = "Check in and ask for breakfast info",
                description = "You arrived at your hotel. Check in with your reservation, ask about breakfast time and Wi-Fi password.",
                language = Language.ENGLISH,
                systemPrompt = """
                    You are David, the front desk concierge at Grand View Hotel.
                    Polite, helpful, and professional. Keep replies under 2 sentences.
                """.trimIndent(),
                aiInitialGreeting = "Good afternoon! Welcome to the Grand View Hotel. How may I assist you today?",
                goals = listOf(
                    MissionGoal(
                        id = "goal_reservation",
                        title = "State reservation name",
                        explanation = "Mention check-in and your name or reservation.",
                        keywords = listOf("check in", "reservation", "booking", "name", "stay")
                    ),
                    MissionGoal(
                        id = "goal_breakfast_wifi",
                        title = "Ask about breakfast or Wi-Fi",
                        explanation = "Inquire when breakfast is served or ask for Wi-Fi info.",
                        keywords = listOf("breakfast", "wifi", "wi-fi", "password", "time", "morning")
                    ),
                    MissionGoal(
                        id = "goal_key_card",
                        title = "Confirm room key and thank staff",
                        explanation = "Acknowledge the room key/details.",
                        keywords = listOf("key", "room", "thank", "thanks", "great", "appreciate")
                    )
                )
            ),

            // --- 日語場景 1：便利商店點餐與加熱便當 ---
            Scenario(
                id = "convenience_store_ja",
                title = "コンビニで買い物",
                subtitle = "お弁当の購入と温めのお願い",
                description = "日本のコンビニでレジに来ました。お弁当と飲み物を購入し、お弁当の温めとレジ袋の要否を伝えてください。",
                language = Language.JAPANESE,
                systemPrompt = """
                    あなたは日本のコンビニの店員「たなか」です。
                    親切で素早い接客をしてください。返答は自然な短文（1〜2文程度）で行います。
                    お弁当の温めやレジ袋が必要か尋ねてください。
                """.trimIndent(),
                aiInitialGreeting = "いらっしゃいませ！お弁当をお預かりします。こちら温めますか？",
                goals = listOf(
                    MissionGoal(
                        id = "goal_warm_bento",
                        title = "お弁当の温めを頼む",
                        explanation = "「温めてください」または「はい、お願いします」と伝える。",
                        keywords = listOf("温め", "あたため", "お願いします", "はい", "いいえ", "大丈夫")
                    ),
                    MissionGoal(
                        id = "goal_bag_spoon",
                        title = "レジ袋やスプーンの希望を伝える",
                        explanation = "レジ袋の有無や、お箸/スプーンについて答える。",
                        keywords = listOf("袋", "ふくろ", "いりません", "結構です", "お願いします", "箸", "はし", "スプーン")
                    ),
                    MissionGoal(
                        id = "goal_payment_ja",
                        title = "支払い方法を伝えて完了する",
                        explanation = "PayPay、カード、または現金で支払う旨を伝える。",
                        keywords = listOf("paypay", "カード", "現金", "電子マネー", "スイカ", "suica", "ありがとう")
                    )
                )
            ),

            // --- 日語場景 2：拉麵店點餐 ---
            Scenario(
                id = "ramen_shop_ja",
                title = "ラーメン屋での注文",
                subtitle = "麺の硬さと味の好みを伝える",
                description = "博多豚骨ラーメン店で、麺の硬さ（バリカタ・かため）やトッピングの好みを伝えて注文します。",
                language = Language.JAPANESE,
                systemPrompt = """
                    あなたは威勢の良いラーメン屋の店主です。
                    元気よく簡潔に応対してください。麺の硬さやお好みを尋ねます。
                """.trimIndent(),
                aiInitialGreeting = "へいらっしゃい！何にしましょう？食券お預かりします！麺の硬さはどうしますか？",
                goals = listOf(
                    MissionGoal(
                        id = "goal_ramen_type",
                        title = "ラーメンの種類を伝える",
                        explanation = "とんこつラーメンやトッピング（味玉、チャーシューなど）を注文する。",
                        keywords = listOf("ラーメン", "とんこつ", "チャーシュー", "味玉", "煮玉子", "これ")
                    ),
                    MissionGoal(
                        id = "goal_noodle_firmness",
                        title = "麺の硬さを指定する",
                        explanation = "「かため」「バリカタ」「ふつう」などを指定する。",
                        keywords = listOf("硬め", "かため", "バリカタ", "ふつう", "普通", "柔らかめ")
                    ),
                    MissionGoal(
                        id = "goal_extra_request",
                        title = "替え玉やお冷をお願いする",
                        explanation = "お水やお礼を伝えて席に着く。",
                        keywords = listOf("お水", "お冷", "お願いします", "ありがとう", "替え玉")
                    )
                )
            )
        )
    }
}
