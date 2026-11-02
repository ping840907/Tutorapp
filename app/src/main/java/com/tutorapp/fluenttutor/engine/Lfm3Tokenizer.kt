package com.tutorapp.fluenttutor.engine

import android.util.Log
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.FileReader

/**
 * Liquid AI LFM3-Audio 原生 BPE Tokenizer
 * 支援 ByteLevel 編碼、特殊 Token (<|im_start|>, <|audio_start|>, <|audio|>, etc.)
 * 與反向解碼為 UTF-8 字串。
 */
class Lfm3Tokenizer(tokenizerFile: File) {

    companion object {
        private const val TAG = "Lfm3Tokenizer"

        // 特殊 Token 常數
        const val PAD_TOKEN_ID = 124893L
        const val START_OF_TEXT_ID = 124894L
        const val END_OF_TEXT_ID = 124895L
        const val IM_START_ID = 124899L
        const val IM_END_ID = 124900L
        const val THINK_START_ID = 124901L
        const val THINK_END_ID = 124902L
        const val AUDIO_START_ID = 125012L
        const val AUDIO_ID = 125031L
        const val AUDIO_OUT_ID = 125032L

        private val SPECIAL_TOKENS = mapOf(
            "<|pad|>" to PAD_TOKEN_ID,
            "<|startoftext|>" to START_OF_TEXT_ID,
            "<|endoftext|>" to END_OF_TEXT_ID,
            "<|im_start|>" to IM_START_ID,
            "<|im_end|>" to IM_END_ID,
            "<think>" to THINK_START_ID,
            "</think>" to THINK_END_ID,
            "<|audio_start|>" to AUDIO_START_ID,
            "<|audio|>" to AUDIO_ID,
            "<|audio_out|>" to AUDIO_OUT_ID
        )

        private val REVERSE_SPECIAL = SPECIAL_TOKENS.entries.associate { (k, v) -> v to k }

        // GPT-2 / Hugging Face ByteLevel 映射表
        private val byteToChar = CharArray(256)
        private val charToByte = IntArray(65536) { -1 }

        init {
            val bs = mutableListOf<Int>()
            for (i in '!'.code..'~'.code) bs.add(i)
            for (i in '¡'.code..'¬'.code) bs.add(i)
            for (i in '®'.code..'ÿ'.code) bs.add(i)

            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }

            for (i in 0 until 256) {
                val b = bs[i]
                val c = cs[i].toChar()
                byteToChar[b] = c
                charToByte[c.code] = b
            }
        }
    }

    private val tokenToId = HashMap<String, Long>(130000)
    private val idToToken = arrayOfNulls<String>(130000)

    init {
        loadTokenizerJson(tokenizerFile)
    }

    private fun loadTokenizerJson(file: File) {
        val t0 = System.currentTimeMillis()
        try {
            JsonReader(FileReader(file)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    when (name) {
                        "added_tokens" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                reader.beginObject()
                                var id = -1L
                                var content = ""
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "id" -> id = reader.nextLong()
                                        "content" -> content = reader.nextString()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                if (id in 0 until 130000 && content.isNotEmpty()) {
                                    tokenToId[content] = id
                                    idToToken[id.toInt()] = content
                                }
                            }
                            reader.endArray()
                        }
                        "model" -> {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val modelKey = reader.nextName()
                                if (modelKey == "vocab") {
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        val tokenStr = reader.nextName()
                                        val id = reader.nextLong()
                                        if (id in 0 until 130000) {
                                            tokenToId[tokenStr] = id
                                            idToToken[id.toInt()] = tokenStr
                                        }
                                    }
                                    reader.endObject()
                                } else {
                                    reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
            // 補充特殊 Token
            for ((tok, id) in SPECIAL_TOKENS) {
                tokenToId[tok] = id
                if (id in 0 until 130000) {
                    idToToken[id.toInt()] = tok
                }
            }
            val elapsed = System.currentTimeMillis() - t0
            runCatching { Log.i(TAG, "Loaded ${tokenToId.size} tokens from ${file.name} in ${elapsed}ms") }
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Failed to load tokenizer from ${file.absolutePath}: ${e.message}", e) }
        }
    }

    /**
     * 將 Token ID 串列解碼為原始 UTF-8 文字
     */
    fun decode(tokenIds: List<Long>): String {
        val outBytes = java.io.ByteArrayOutputStream()
        for (id in tokenIds) {
            if (id in REVERSE_SPECIAL) {
                // 特殊 token
                val specialStr = REVERSE_SPECIAL[id] ?: ""
                outBytes.write(specialStr.toByteArray(Charsets.UTF_8))
                continue
            }
            val tokenStr = if (id in 0 until 130000) idToToken[id.toInt()] else null
            if (tokenStr != null) {
                for (c in tokenStr) {
                    val code = c.code
                    val b = if (code < 65536) charToByte[code] else -1
                    if (b != -1) {
                        outBytes.write(b)
                    } else {
                        outBytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
        return outBytes.toString("UTF-8")
    }

    /**
     * 將單一 Token ID 解碼為字串片段
     */
    fun decodeSingle(id: Long): String {
        if (id in REVERSE_SPECIAL) return REVERSE_SPECIAL[id] ?: ""
        val tokenStr = (if (id in 0 until 130000) idToToken[id.toInt()] else null) ?: return ""
        val outBytes = java.io.ByteArrayOutputStream()
        for (c in tokenStr) {
            val code = c.code
            val b = if (code < 65536) charToByte[code] else -1
            if (b != -1) {
                outBytes.write(b)
            } else {
                outBytes.write(c.toString().toByteArray(Charsets.UTF_8))
            }
        }
        return outBytes.toString("UTF-8")
    }

    /**
     * 將純文字編碼為 Token ID 串列 (最長前綴貪婪匹配)
     */
    fun encode(text: String): LongArray {
        if (text.isEmpty()) return LongArray(0)
        val result = mutableListOf<Long>()

        // 檢查特殊 Token 標記
        var pos = 0
        while (pos < text.length) {
            var matchedSpecial = false
            for ((special, id) in SPECIAL_TOKENS) {
                if (text.startsWith(special, pos)) {
                    result.add(id)
                    pos += special.length
                    matchedSpecial = true
                    break
                }
            }
            if (matchedSpecial) continue

            // 提取下一個常規文字片段 (直到下一個特殊 Token)
            var nextSpecialIdx = text.length
            for (special in SPECIAL_TOKENS.keys) {
                val idx = text.indexOf(special, pos)
                if (idx != -1 && idx < nextSpecialIdx) {
                    nextSpecialIdx = idx
                }
            }

            val segment = text.substring(pos, nextSpecialIdx)
            pos = nextSpecialIdx

            // ByteLevel 編碼
            val utf8Bytes = segment.toByteArray(Charsets.UTF_8)
            val byteStrBuilder = StringBuilder(utf8Bytes.size)
            for (b in utf8Bytes) {
                byteStrBuilder.append(byteToChar[b.toInt() and 0xFF])
            }
            val byteStr = byteStrBuilder.toString()

            var i = 0
            while (i < byteStr.length) {
                var found = false
                val maxLen = minOf(32, byteStr.length - i)
                for (len in maxLen downTo 1) {
                    val sub = byteStr.substring(i, i + len)
                    val id = tokenToId[sub]
                    if (id != null) {
                        result.add(id)
                        i += len
                        found = true
                        break
                    }
                }
                if (!found) {
                    i++
                }
            }
        }

        return result.toLongArray()
    }
}
