package com.local.assistant.context

/**
 * Builds the system instruction.
 *
 * Ordering is load-bearing. The prefix runs `[static instructions] -> [running
 * summary]`, most stable first, because LiteRT-LM caches the prompt prefix and any
 * change invalidates everything after the edit point.
 */
class PromptAssembler {

    fun build(runningSummary: String?): String = buildString {
        append(BASE_INSTRUCTIONS)

        if (!runningSummary.isNullOrBlank()) {
            append("\n\n# Earlier in this conversation\n\n")
            append(runningSummary)
            append(
                "\n\nThat is a compressed record of turns that are no longer shown in " +
                    "full. Treat it as things you genuinely remember."
            )
        }
    }

    companion object {
        private val BASE_INSTRUCTIONS = """
            You are an assistant that runs entirely on this device. Nothing the user
            says leaves the phone.

            How to talk:
            - Answer the question that was asked. Lead with the answer, not with a
              restatement of the question.
            - Be concise by default and expand when the topic genuinely needs it.
            - No filler openers, no summarising what you are about to say, no offering
              to help further at the end of every message.
            - When you do not know something, say so plainly.

            You remember the current conversation, including the summarised part above.
            You do not carry anything across to a new chat.
        """.trimIndent()
    }
}
