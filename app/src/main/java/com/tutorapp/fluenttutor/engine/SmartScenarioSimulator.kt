package com.tutorapp.fluenttutor.engine

import com.tutorapp.fluenttutor.model.Language
import com.tutorapp.fluenttutor.model.Scenario

/**
 * 端側智慧多輪情境模擬器 (Edge Smart Scenario Simulator)
 * 當未連接外部 LFM3 伺服器時，提供真實流暢的 AI 角色扮演回應與語音教學互動。
 */
class SmartScenarioSimulator {

    data class SimResponse(
        val replyText: String,
        val recognizedUserText: String
    )

    fun generateReply(
        scenario: Scenario,
        userSpeechDurationMs: Long,
        turnIndex: Int,
        userSimulatedText: String? = null
    ): SimResponse {
        val userInput = userSimulatedText?.lowercase() ?: ""

        return when (scenario.language) {
            Language.ENGLISH -> handleEnglish(scenario, userInput, turnIndex)
            Language.JAPANESE -> handleJapanese(scenario, userInput, turnIndex)
        }
    }

    private fun handleEnglish(scenario: Scenario, input: String, turn: Int): SimResponse {
        return when (scenario.id) {
            "cafe_latte_en" -> {
                when {
                    input.contains("latte") && input.contains("oat") -> {
                        SimResponse(
                            replyText = "Awesome! An oat milk latte. What size would you like, and do you want that hot or iced?",
                            recognizedUserText = if (input.isNotBlank()) input else "I'd like an oat milk latte please."
                        )
                    }
                    input.contains("latte") || input.contains("coffee") -> {
                        SimResponse(
                            replyText = "Sure thing! What kind of milk would you prefer? We have whole, skim, almond, and oat milk.",
                            recognizedUserText = if (input.isNotBlank()) input else "Can I get a latte please?"
                        )
                    }
                    input.contains("iced") || input.contains("hot") || input.contains("medium") || input.contains("large") || input.contains("small") -> {
                        SimResponse(
                            replyText = "Got it! That'll be $5.25. Will you be paying with card or cash?",
                            recognizedUserText = if (input.isNotBlank()) input else "Medium and iced please."
                        )
                    }
                    input.contains("card") || input.contains("cash") || input.contains("pay") || input.contains("apple") || input.contains("here") -> {
                        SimResponse(
                            replyText = "Payment received! Your drink will be ready at the pickup counter in just a minute. Have a wonderful day!",
                            recognizedUserText = if (input.isNotBlank()) input else "I'll pay with credit card."
                        )
                    }
                    input.contains("weather") || input.contains("game") || input.contains("movie") || input.contains("alien") -> {
                        // 偏離話題
                        SimResponse(
                            replyText = "Haha, that's interesting, but we have a bit of a queue! Could you please let me know your order first?",
                            recognizedUserText = input
                        )
                    }
                    else -> {
                        if (turn == 0) {
                            SimResponse(
                                replyText = "Welcome! What coffee or tea drink can I get ready for you?",
                                recognizedUserText = if (input.isNotBlank()) input else "Hello!"
                            )
                        } else {
                            SimResponse(
                                replyText = "Sounds good! Anything else to go with that, or ready to pay?",
                                recognizedUserText = if (input.isNotBlank()) input else "That sounds great."
                            )
                        }
                    }
                }
            }
            "hotel_checkin_en" -> {
                when {
                    input.contains("check in") || input.contains("reservation") || input.contains("booking") -> {
                        SimResponse(
                            replyText = "Certainly! I've located your reservation. Here is your keycard for Room 402. Do you have any questions about our amenities?",
                            recognizedUserText = if (input.isNotBlank()) input else "Hi, I have a reservation to check in."
                        )
                    }
                    input.contains("breakfast") || input.contains("wifi") || input.contains("wi-fi") -> {
                        SimResponse(
                            replyText = "Breakfast is served from 6:30 to 10:00 AM on the 2nd floor, and Wi-Fi password is 'GrandGuest2026'. Anything else?",
                            recognizedUserText = if (input.isNotBlank()) input else "What time is breakfast and what is the wifi password?"
                        )
                    }
                    input.contains("thank") || input.contains("great") || input.contains("appreciate") -> {
                        SimResponse(
                            replyText = "You're very welcome! Enjoy your stay with us. Elevators are right around the corner.",
                            recognizedUserText = if (input.isNotBlank()) input else "Thank you very much, that's all!"
                        )
                    }
                    else -> {
                        SimResponse(
                            replyText = "I see. Could you confirm your reservation name so I can pull up your booking?",
                            recognizedUserText = if (input.isNotBlank()) input else "Hi there."
                        )
                    }
                }
            }
            else -> {
                SimResponse(
                    replyText = "I understand. Let's continue practicing!",
                    recognizedUserText = input
                )
            }
        }
    }

    private fun handleJapanese(scenario: Scenario, input: String, turn: Int): SimResponse {
        return when (scenario.id) {
            "convenience_store_ja" -> {
                when {
                    input.contains("温め") || input.contains("あたため") || input.contains("お願") || input.contains("はい") -> {
                        SimResponse(
                            replyText = "かしこまりました、少々お待ちください。レジ袋とお箸はどうなさいますか？",
                            recognizedUserText = if (input.isNotBlank()) input else "はい、温めてください。"
                        )
                    }
                    input.contains("袋") || input.contains("ふくろ") || input.contains("いりません") || input.contains("結構") || input.contains("箸") || input.contains("スプーン") -> {
                        SimResponse(
                            replyText = "承知いたしました。お会計は780円になります。お支払い方法はどうされますか？",
                            recognizedUserText = if (input.isNotBlank()) input else "袋はいりません、お箸だけお願いします。"
                        )
                    }
                    input.contains("paypay") || input.contains("カード") || input.contains("スイカ") || input.contains("suica") || input.contains("現金") || input.contains("ありがとう") -> {
                        SimResponse(
                            replyText = "お支払い確認いたしました！お待たせしました、温かいお弁当です。またお越しくださいませ！",
                            recognizedUserText = if (input.isNotBlank()) input else "PayPayで支払います。"
                        )
                    }
                    input.contains("ゲーム") || input.contains("宇宙") || input.contains("天気") -> {
                        SimResponse(
                            replyText = "恐れ入りますが、他のお客様もお待ちですので、お会計の確認をお願いできますでしょうか？",
                            recognizedUserText = input
                        )
                    }
                    else -> {
                        SimResponse(
                            replyText = "いらっしゃいませ！お弁当のお預かりと、温めのご希望はございますか？",
                            recognizedUserText = if (input.isNotBlank()) input else "こんにちは。"
                        )
                    }
                }
            }
            else -> {
                SimResponse(
                    replyText = "はい！ご注文を承りました！麺の硬さやお好みはいかがなさいますか？",
                    recognizedUserText = if (input.isNotBlank()) input else "とんこつラーメンをお願いします。"
                )
            }
        }
    }
}
