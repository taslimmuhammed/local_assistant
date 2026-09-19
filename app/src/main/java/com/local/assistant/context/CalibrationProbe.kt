package com.local.assistant.context

import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.local.assistant.llm.LlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class CalibrationResult(
    /** Highest token count the bundle actually served without failing. */
    val observedCeiling: Int,
    /** [observedCeiling] minus a safety margin. This is what the budgeter plans against. */
    val usableCeiling: Int,
    /** True when we hit [requestedMax] without any failure, so the real ceiling is at least this. */
    val reachedRequestedMax: Boolean,
    val requestedMax: Int,
    val elapsedMs: Long,
)

/**
 * Measures the context window the loaded bundle will actually serve.
 *
 * This exists because `EngineConfig.maxNumTokens` is a request, not a contract.
 * The runtime accepts a value larger than the bundle's KV tensors can hold and
 * only fails much later, mid-conversation, once the remaining window drops below
 * the smallest prefill work group. There is no API to ask for the true ceiling,
 * so the only honest way to know it is to walk up to it once and remember where
 * the wall was.
 *
 * Every downstream budget is a fraction of the result, so this runs before the
 * first real turn and its answer is cached until the model file changes.
 */
class CalibrationProbe(private val engine: LlmEngine) {

    suspend fun run(
        requestedMax: Int,
        /**
         * Scaled so the walk stays around thirty steps whatever the ceiling. A fixed
         * small step would mean sixty-odd prefill round trips at a 16k window, which
         * turns a one-time measurement into a minute of staring at onboarding.
         */
        stepTokens: Int = (requestedMax / 32).coerceIn(256, 1024),
        onProgress: (Int) -> Unit = {},
    ): CalibrationResult = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        var conversation: Conversation? = null
        var lastGood = 0
        var reachedMax = false

        try {
            // maxOutputToken = 1 keeps each step to a prefill plus a single decode.
            // Prefill is the fast path, so the whole probe costs seconds rather than
            // minutes even when the ceiling is large.
            conversation = engine.createConversation(
                systemInstruction = PROBE_SYSTEM,
                maxOutputToken = 1,
                temperature = 0.0,
                topK = 1,
            )

            val filler = buildFiller(stepTokens)
            while (true) {
                coroutineContext.ensureActive()
                try {
                    conversation.sendMessage(filler)
                } catch (t: Throwable) {
                    if (LlmEngine.isContextExhausted(t) || LlmEngine.isJniFailure(t)) {
                        Log.i(TAG, "Ceiling found at ~$lastGood tokens: ${t.message}")
                        break
                    }
                    throw t
                }

                val count = try {
                    conversation.getTokenCount()
                } catch (t: Throwable) {
                    // If the count itself fails we are already past the edge.
                    Log.w(TAG, "tokenCount failed after send", t)
                    break
                }

                if (count <= lastGood) {
                    // No forward progress means the runtime stopped accepting input.
                    Log.w(TAG, "Token count stalled at $count; treating as ceiling")
                    break
                }
                lastGood = count
                onProgress(count)

                if (count >= requestedMax - stepTokens) {
                    reachedMax = true
                    break
                }
            }
        } finally {
            runCatching { conversation?.close() }
        }

        val usable = (lastGood * (1.0 - SAFETY_MARGIN)).toInt().coerceAtLeast(MIN_USABLE)
        CalibrationResult(
            observedCeiling = lastGood,
            usableCeiling = usable,
            reachedRequestedMax = reachedMax,
            requestedMax = requestedMax,
            elapsedMs = System.currentTimeMillis() - started,
        ).also { Log.i(TAG, "Calibration: $it") }
    }

    /**
     * Roughly [targetTokens] worth of ordinary prose. Real words rather than
     * repeated punctuation, so the tokenizer behaves the way it will in use.
     */
    private fun buildFiller(targetTokens: Int): String {
        val approxCharsPerToken = 4
        val target = targetTokens * approxCharsPerToken
        val sb = StringBuilder(target + 64)
        var i = 0
        while (sb.length < target) {
            sb.append(FILLER_WORDS[i % FILLER_WORDS.size]).append(' ')
            i++
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "CalibrationProbe"

        /**
         * Ten percent. The observed ceiling is where the runtime failed, not where it
         * is comfortable, and the failure threshold shifts with the size of the
         * pending prefill work group.
         */
        private const val SAFETY_MARGIN = 0.10

        private const val MIN_USABLE = 1024

        private const val PROBE_SYSTEM = "Reply with the single word: ok."

        private val FILLER_WORDS = listOf(
            "the", "quiet", "harbour", "kept", "its", "boats", "in", "even", "rows",
            "while", "morning", "traffic", "gathered", "along", "the", "seafront",
            "road", "and", "a", "vendor", "arranged", "fruit", "under", "a", "faded",
            "awning", "counting", "change", "into", "a", "tin", "box", "slowly",
        )
    }
}
