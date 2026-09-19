package com.local.assistant.context

import android.util.Log
import com.local.assistant.data.ChatMessage
import com.local.assistant.data.Speaker
import com.local.assistant.llm.LlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Folds old turns into a running summary.
 *
 * Runs in its own [com.google.ai.edge.litertlm.Conversation] so the summarisation
 * prompt never enters the chat's KV cache — otherwise compaction would consume
 * exactly the window it is trying to free.
 */
class Summarizer(private val engine: LlmEngine) {

    suspend fun compact(
        previousSummary: String?,
        turns: List<ChatMessage>,
        charBudget: Int,
    ): String? = withContext(Dispatchers.Default) {
        if (turns.isEmpty()) return@withContext previousSummary

        val transcript = turns.joinToString("\n") { m ->
            val who = if (m.speaker == Speaker.USER) "User" else "Assistant"
            "$who: ${m.text}"
        }

        val prompt = buildString {
            if (!previousSummary.isNullOrBlank()) {
                append("EXISTING SUMMARY:\n").append(previousSummary).append("\n\n")
            }
            append("NEW TRANSCRIPT:\n").append(transcript).append("\n\n")
            append(
                if (previousSummary.isNullOrBlank()) {
                    "Write the summary now."
                } else {
                    "Merge the new transcript into the existing summary. Write the merged summary now."
                }
            )
        }

        val conversation = try {
            engine.createConversation(
                systemInstruction = systemPrompt(charBudget),
                temperature = 0.3,
                topK = 40,
                // Give the summariser a little headroom over its character budget so
                // it can finish a sentence instead of being cut mid-word.
                maxOutputToken = (charBudget / ContextBudget.CHARS_PER_TOKEN).toInt() + 128,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Could not create summariser conversation", t)
            return@withContext previousSummary
        }

        try {
            val reply = conversation.sendMessage(prompt)
            val text = reply.contents.contents
                .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                .joinToString("") { it.text }
                .trim()

            if (text.isBlank()) {
                Log.w(TAG, "Summariser returned nothing; keeping previous summary")
                previousSummary
            } else {
                text.take(charBudget)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Summarisation failed", t)
            previousSummary
        } finally {
            runCatching { conversation.close() }
        }
    }

    /**
     * Tuned for a small instruction-following model: concrete rules, an explicit
     * length ceiling, and a named list of what must survive. Gemma-class models
     * drop specifics and keep atmosphere unless told the opposite in so many words.
     */
    private fun systemPrompt(charBudget: Int) = """
        You compress a conversation into a running summary that another assistant will
        read as its only memory of everything older than the last few turns.

        Rules:
        - Stay under $charBudget characters. This is a hard limit.
        - Preserve verbatim: names, dates, numbers, file paths, decisions, and any
          commitment either side made.
        - Keep unresolved threads and open questions. They matter more than
          conclusions that are already acted on.
        - Drop greetings, acknowledgements, restatements, and your own earlier
          phrasing. Keep the substance, not the conversation's texture.
        - Write plain declarative sentences in the third person. No headings, no
          bullet characters, no preamble, no sign-off.
        - Output only the summary text. Never explain what you are doing.
    """.trimIndent()

    companion object {
        private const val TAG = "Summarizer"
    }
}
