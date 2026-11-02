package com.tutorapp.fluenttutor.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * 【開發替身，不是 LFM3-Audio】
 * 在真正的 LFM3-Audio 執行期接上之前，讓 UI / 狀態機 / 流程可以端到端跑起來。
 * - 只理解「手動輸入文字」；純語音輸入無法轉寫，會誠實地回報「沒聽清楚」(不計回合)。
 * - 以非常粗糙的關鍵字規則模擬模型的 <verdict> 輸出，語音用系統 TTS 合成。
 * 換成真實模型時，實作 [LfmBackend] 並在 TutorViewModel 注入即可，整個類別可以刪除。
 */
class StandInLfmBackend(private val context: Context) : LfmBackend, TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "StandInLfmBackend"

        private val OFF_TOPIC = Regex(
            "\\b(minecraft|fortnite|playstation|xbox|video\\s*game|alien|ufo|politics|president|homework)\\b",
            RegexOption.IGNORE_CASE
        )

        /** goalId -> 需要「全部群組」都命中，群組內任一正規式命中即可 */
        private val GOAL_HINTS: Map<String, List<List<Regex>>> = mapOf(
            "goal_drink_oat" to groups(listOf("latte", "coffee"), listOf("oat")),
            "goal_temp_size" to groups(listOf("iced", "hot", "cold"), listOf("small", "medium", "large", "tall", "grande", "venti")),
            "goal_payment" to groups(listOf("card", "cash", "apple\\s*pay", "pay", "credit"), listOf("thanks", "thank\\s*you")),
            "goal_hotel_checkin" to groups(listOf("check[\\s-]*in", "checking\\s*in"), listOf("reservation", "booking", "name")),
            "goal_hotel_amenities" to groups(listOf("breakfast", "wi-?fi", "internet", "password")),
            "goal_hotel_key" to groups(listOf("key", "room", "elevator"), listOf("thank", "appreciate")),
            "goal_flight_info" to groups(listOf("flight", "flying", "ticket", "passport")),
            "goal_seat_choice" to groups(listOf("window", "aisle")),
            "goal_gate_time" to groups(listOf("gate", "boarding"))
        )

        private val NEXT_LINES: Map<String, String> = mapOf(
            "goal_drink_oat" to "Got it, one oat milk latte! Would you like that hot or iced, and what size?",
            "goal_temp_size" to "Perfect! That's \$5.25. How would you like to pay, card or cash?",
            "goal_payment" to "Payment went through! Your latte will be right up. Have a wonderful day!",
            "goal_hotel_checkin" to "I found your reservation. Here is your keycard for Room 704. Anything else I can help with?",
            "goal_hotel_amenities" to "Breakfast is on the 2nd floor from 6:30 to 10:00 AM, and the Wi-Fi is HyattGuest with no password.",
            "goal_hotel_key" to "You're very welcome! The elevators are behind you on the left. Enjoy your stay!",
            "goal_flight_info" to "Thank you, passport confirmed. Do you prefer a window or an aisle seat?",
            "goal_seat_choice" to "All set! I've assigned you seat 14A. Here is your boarding pass.",
            "goal_gate_time" to "Your flight departs from Gate B22 and boarding begins at 10:15 AM. Have a smooth flight!"
        )

        private fun groups(vararg g: List<String>): List<List<Regex>> =
            g.map { words -> words.map { Regex("\\b$it\\b", RegexOption.IGNORE_CASE) } }
    }

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    @Volatile
    private var isTtsReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        } else {
            Log.e(TAG, "TTS init failed: $status")
        }
    }

    override suspend fun generate(request: LfmRequest): LfmRawResponse {
        val text = request.manualText
        val verdict = JsonObject()
        val reply: String

        if (text == null) {
            // 沒有真實 ASR：誠實回報聽不懂
            reply = LfmSpeechToSpeechEngine.FALLBACK_REPLY
            verdict.addProperty("transcript", "")
        } else {
            val offTopic = OFF_TOPIC.containsMatchIn(text)
            val completed = mutableListOf<String>()
            if (!offTopic) {
                for (goal in request.goals.sortedBy { it.orderIndex }.filter { !it.isCompleted }) {
                    val hints = GOAL_HINTS[goal.id] ?: break
                    if (hints.all { group -> group.any { it.containsMatchIn(text) } }) completed.add(goal.id) else break
                }
            }
            reply = when {
                offTopic -> "Hmm, I'm a little confused! Let's get back to our conversation, ${request.scenario.roleName.substringBefore(" (")} here."
                completed.isNotEmpty() -> NEXT_LINES[completed.last()] ?: "Great, let's continue!"
                else -> "Sorry, could you tell me a bit more?"
            }
            verdict.addProperty("transcript", text)
            verdict.addProperty("off_topic", offTopic)
            verdict.add("goals", JsonArray().also { arr -> completed.forEach { arr.add(it) } })
            verdict.addProperty("relevance", if (offTopic) 20 else if (completed.isNotEmpty()) 95 else 70)
            verdict.addProperty("fluency", 80)
            verdict.addProperty("feedback", "")
        }

        return LfmRawResponse(
            text = "$reply\n<verdict>$verdict</verdict>",
            audioBytes = synthesize(reply)
        )
    }

    private suspend fun synthesize(text: String): ByteArray? = withContext(Dispatchers.IO) {
        val engine = tts
        if (!isTtsReady || engine == null) return@withContext null

        val tempWav = File(context.cacheDir, "s2s_${UUID.randomUUID()}.wav")
        val uId = "synth_${System.nanoTime()}"
        val deferred = CompletableDeferred<Boolean>()
        try {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == uId) deferred.complete(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { if (id == uId) deferred.complete(false) }
            })
            if (engine.synthesizeToFile(text, null, tempWav, uId) == TextToSpeech.SUCCESS) {
                val ok = withTimeoutOrNull(4000L) { deferred.await() } ?: false
                if (ok && tempWav.exists() && tempWav.length() > 44L) return@withContext tempWav.readBytes()
            }
            null
        } finally {
            if (tempWav.exists()) tempWav.delete()
        }
    }

    override fun release() {
        tts?.shutdown()
        tts = null
    }
}
