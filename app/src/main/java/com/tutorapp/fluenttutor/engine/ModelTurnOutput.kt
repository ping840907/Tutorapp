package com.tutorapp.fluenttutor.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * LFM3-Audio 單一模型每一輪輸出的結構化結果。
 * 模型負責：聽懂使用者 (transcript)、在角色中回覆 (reply)、判定是否離題、
 * 回報達成的目標 id、並給出相關度/流暢度評分；MissionStateMachine 只負責驗證與記錄。
 */
data class ModelTurnOutput(
    val transcript: String,
    val reply: String,
    val onTopic: Boolean,
    val completedGoalIds: List<String>,
    val relevance: Int,
    val fluency: Int,
    val feedback: String
) {
    /** 模型沒有聽清楚 / 沒有可辨識內容時 transcript 為空，這一輪不計入回合與評分 */
    val isUnintelligible: Boolean get() = transcript.isBlank()
}

/**
 * 模型輸出協議：口說回覆文字之後，附上單一 <verdict>{json}</verdict> 區塊。
 * {"transcript": "...", "off_topic": false, "goals": ["goal_id"], "relevance": 0-100, "fluency": 0-100, "feedback": "..."}
 */
object ModelOutputParser {
    private val VERDICT = Regex("<verdict>(.*?)</verdict>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** 取出 verdict 以外、要念出來的回覆文字 */
    fun replyOf(raw: String): String = raw.replace(VERDICT, "").trim()

    fun parse(raw: String): ModelTurnOutput? {
        val match = VERDICT.find(raw) ?: return null
        val obj: JsonObject = try {
            val el = JsonParser.parseString(match.groupValues[1].trim())
            if (!el.isJsonObject) return null
            el.asJsonObject
        } catch (e: Exception) {
            return null
        }

        return ModelTurnOutput(
            transcript = string(obj, "transcript").trim(),
            reply = replyOf(raw),
            onTopic = !bool(obj, "off_topic", default = false),
            completedGoalIds = stringList(obj, "goals"),
            relevance = int(obj, "relevance", default = 60),
            fluency = int(obj, "fluency", default = 70),
            feedback = string(obj, "feedback").trim()
        )
    }

    private fun string(o: JsonObject, key: String): String = try {
        o.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
    } catch (e: Exception) { "" }

    private fun bool(o: JsonObject, key: String, default: Boolean): Boolean = try {
        o.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: default
    } catch (e: Exception) { default }

    private fun int(o: JsonObject, key: String, default: Int): Int = try {
        o.get(key)?.takeIf { it.isJsonPrimitive }?.asNumber?.toInt() ?: default
    } catch (e: Exception) { default }

    private fun stringList(o: JsonObject, key: String): List<String> = try {
        o.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> if (e.isJsonPrimitive) e.asString else null }
            ?: emptyList()
    } catch (e: Exception) { emptyList() }
}
