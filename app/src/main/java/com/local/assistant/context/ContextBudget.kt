package com.local.assistant.context

/**
 * How the measured context window is divided.
 *
 * Every field is a fraction of the calibrated ceiling rather than a constant. The
 * usable window on-device is a property of the model bundle and is often far
 * smaller than the model's advertised context, so hardcoding slice sizes would
 * either waste the window on a large bundle or overflow it on a small one.
 */
data class ContextBudget(
    val usableCeiling: Int,
    val systemTokens: Int,
    val summaryTokens: Int,
    val recentTokens: Int,
    val responseTokens: Int,
) {
    /**
     * Occupancy at which compaction starts, in tokens.
     *
     * Deliberately close to half the window. Compaction needs room to run — it
     * still has to prefill the turns it is folding up — and waiting until the
     * window is nearly full is how you end up compacting inside an already-failing
     * conversation.
     */
    val compactionWatermark: Int get() = (usableCeiling * WATERMARK).toInt()

    /** Hard stop. Past this we force a rollover before accepting another turn. */
    val hardLimit: Int get() = (usableCeiling * HARD_LIMIT).toInt()

    /** Character budget for the running summary. */
    val summaryChars: Int get() = (summaryTokens * CHARS_PER_TOKEN).toInt()

    companion object {
        private const val WATERMARK = 0.55
        private const val HARD_LIMIT = 0.85

        /**
         * Only used to turn the summary's token budget into a character budget for
         * the summariser prompt. Token accounting proper uses the runtime's own
         * `Conversation.getTokenCount()`, never this.
         */
        const val CHARS_PER_TOKEN = 3.6

        fun from(usableCeiling: Int): ContextBudget = ContextBudget(
            usableCeiling = usableCeiling,
            systemTokens = (usableCeiling * 0.08).toInt(),
            summaryTokens = (usableCeiling * 0.25).toInt(),
            recentTokens = (usableCeiling * 0.50).toInt(),
            responseTokens = (usableCeiling * 0.17).toInt(),
        )
    }
}
