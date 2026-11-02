package com.tutorapp.fluenttutor.model

enum class MessageSender {
    USER,
    TUTOR,
    SYSTEM
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val audioDurationMs: Long = 0,
    val isStreaming: Boolean = false,
    val isOffTopic: Boolean = false,
    val offTopicReason: String? = null
)
