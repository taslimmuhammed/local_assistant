package com.local.assistant.data

/** Who produced a turn. */
enum class Speaker { USER, ASSISTANT }

/**
 * One turn of the live conversation.
 *
 * Held in memory only. Nothing about a chat is written to disk, so ids are
 * sequence numbers for this process rather than database keys, and everything here
 * is gone when the app closes.
 */
data class ChatMessage(
    val id: Long,
    val speaker: Speaker,
    val text: String,
    val createdAt: Long,
    /** True once this turn has been folded into the running summary. */
    val summarized: Boolean = false,
)
