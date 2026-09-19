package com.local.assistant.data

/** Who produced a turn. Mirrors LiteRT-LM's Role but keeps storage independent of the SDK. */
enum class Speaker { USER, ASSISTANT }

data class ChatMessage(
    val id: Long = 0,
    val sessionId: Long,
    val speaker: Speaker,
    val text: String,
    val createdAt: Long,
    /** True once this turn has been folded into a running summary. */
    val summarized: Boolean = false,
)

/**
 * A running summary covering a contiguous range of messages. Compaction replaces
 * the previous summary rather than appending, so exactly one row per session is
 * current; superseded rows are kept for debugging the summariser.
 */
data class Summary(
    val id: Long = 0,
    val sessionId: Long,
    val text: String,
    val firstMessageId: Long,
    val lastMessageId: Long,
    val createdAt: Long,
    val isCurrent: Boolean = true,
)

data class Session(
    val id: Long = 0,
    val startedAt: Long,
    val lastActiveAt: Long,
    val title: String? = null,
)

/** A row in the chat-history drawer. */
data class SessionSummary(
    val id: Long,
    val title: String,
    val preview: String,
    val messageCount: Int,
    val lastActiveAt: Long,
)

/** A hit from [AssistantDatabase.search], ranked by BM25. */
data class SearchHit(
    val messageId: Long,
    val sessionId: Long,
    val speaker: Speaker,
    val text: String,
    val createdAt: Long,
    val snippet: String,
)
