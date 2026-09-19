package com.local.assistant.context

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.local.assistant.data.ChatMessage
import com.local.assistant.data.Speaker
import com.local.assistant.llm.LlmEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the chat UI needs to know about the window without understanding tokens. */
data class ContextState(
    val usedTokens: Int,
    val usableCeiling: Int,
    val compactionCount: Int,
    val isCompacting: Boolean,
    val lastRecoveredAt: Long? = null,
) {
    val fraction: Float
        get() = if (usableCeiling <= 0) 0f else (usedTokens.toFloat() / usableCeiling).coerceIn(0f, 1f)
}

/** One streamed reply. [Delta] carries text as it arrives. */
sealed interface TurnEvent {
    data class Delta(val text: String) : TurnEvent
    data class Complete(val fullText: String, val messageId: Long) : TurnEvent
    data class Compacted(val summaryChars: Int) : TurnEvent
    data class Recovered(val reason: String) : TurnEvent
    data class Failed(val error: String) : TurnEvent
}

/**
 * Owns the live conversation and keeps it from ever running out of context.
 *
 * Nothing here is persisted. The transcript and the running summary live in this
 * object for as long as the process does and are gone afterwards, which is why
 * there is no database, no session table and no history.
 *
 * Three layers stand between the user and a dead conversation:
 *
 *  1. Budgets derived from the calibrated ceiling, so we plan against the window
 *     the bundle actually serves rather than the one we asked for.
 *  2. Compaction at the watermark, which folds old turns into a running summary
 *     and rebuilds the conversation from that summary plus the recency window.
 *  3. A catch-and-retry net around every send. If the runtime still hits its
 *     internal prefill limit, we compact immediately and replay the turn once. The
 *     user sees a pause, not a failure.
 *
 * The third layer should almost never fire. It is instrumented so that if it does,
 * that shows up as a signal to tighten the watermark rather than as a mystery.
 */
class ChatController(
    private val engine: LlmEngine,
    private val promptAssembler: PromptAssembler,
    private val summarizer: Summarizer,
) {

    private val mutex = Mutex()

    private var conversation: Conversation? = null
    private var budget: ContextBudget = ContextBudget.from(DEFAULT_CEILING)

    /** The live transcript, oldest first. In memory only. */
    private val transcript = mutableListOf<ChatMessage>()
    private var runningSummary: String? = null
    private var nextId = 1L
    private var compactionCount = 0

    private val _contextState = MutableStateFlow(
        ContextState(0, budget.usableCeiling, 0, isCompacting = false)
    )
    val contextState: StateFlow<ContextState> = _contextState.asStateFlow()

    /** Applies the calibrated ceiling and opens an empty chat. */
    suspend fun start(usableCeiling: Int) = mutex.withLock {
        budget = ContextBudget.from(usableCeiling)
        resetLocked()
    }

    /** Clears the conversation and starts over. */
    suspend fun newChat() = mutex.withLock { resetLocked() }

    private fun resetLocked() {
        transcript.clear()
        runningSummary = null
        compactionCount = 0
        rebuildConversation()
        publishState()
    }

    /** Sends a turn and streams the reply. */
    fun send(userText: String): Flow<TurnEvent> = flow {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return@flow

        val userMessage = mutex.withLock {
            ChatMessage(
                id = nextId++,
                speaker = Speaker.USER,
                text = trimmed,
                createdAt = System.currentTimeMillis(),
            ).also { transcript.add(it) }
        }

        var attempt = 0
        while (true) {
            attempt++
            val convo = mutex.withLock { conversation } ?: run {
                emit(TurnEvent.Failed("The model is not loaded."))
                return@flow
            }

            val accumulator = StreamAccumulator()
            try {
                convo.sendMessageAsync(trimmed).collect { partial ->
                    val delta = accumulator.consume(partial.textOrEmpty())
                    if (delta.isNotEmpty()) emit(TurnEvent.Delta(delta))
                }

                val full = accumulator.text().trim()
                val reply = mutex.withLock {
                    ChatMessage(
                        id = nextId++,
                        speaker = Speaker.ASSISTANT,
                        text = full,
                        createdAt = System.currentTimeMillis(),
                    ).also { transcript.add(it) }
                }
                emit(TurnEvent.Complete(full, reply.id))
                break
            } catch (t: Throwable) {
                val recoverable = LlmEngine.isContextExhausted(t) ||
                    (LlmEngine.isJniFailure(t) && attempt == 1)
                if (!recoverable || attempt > 1) {
                    Log.e(TAG, "Turn failed (attempt $attempt)", t)
                    emit(TurnEvent.Failed(t.message ?: "Generation failed."))
                    break
                }

                // The safety net. We planned to stay under the ceiling and did not,
                // so free the window now and replay this one turn.
                Log.w(TAG, "Context exhausted mid-turn; compacting and retrying", t)
                emit(TurnEvent.Recovered("Ran out of room; condensing earlier messages."))
                _contextState.value = _contextState.value.copy(
                    lastRecoveredAt = System.currentTimeMillis()
                )
                compact(force = true, excludeId = userMessage.id)
            }
        }

        // Compact after the turn rather than before it, so the reply streams without
        // a stall and the next turn starts with room already made.
        if (currentTokens() >= budget.compactionWatermark) {
            val summary = compact(force = false, excludeId = null)
            if (summary != null) emit(TurnEvent.Compacted(summary.length))
        }
        publishState()
    }

    // ---- compaction ---------------------------------------------------------

    /**
     * Folds everything older than the recency window into the running summary and
     * rebuilds the conversation around it.
     *
     * Rebuilding is what actually frees the KV cache: LiteRT-LM has no way to evict
     * the middle of a conversation, so we close it and seed a fresh one with the
     * summary in the system prompt and the recent turns as `initialMessages`.
     */
    private suspend fun compact(force: Boolean, excludeId: Long?): String? {
        _contextState.value = _contextState.value.copy(isCompacting = true)
        try {
            val toFold = mutex.withLock {
                val keepFrom = (transcript.size - RECENCY_TURNS).coerceAtLeast(0)
                transcript.take(keepFrom).filter { !it.summarized && it.id != excludeId }
            }

            if (toFold.isEmpty() && !force) return null

            val merged = summarizer.compact(
                previousSummary = runningSummary,
                turns = toFold,
                charBudget = budget.summaryChars,
            )

            mutex.withLock {
                if (merged != null && toFold.isNotEmpty()) {
                    runningSummary = merged
                    val folded = toFold.mapTo(mutableSetOf()) { it.id }
                    for (i in transcript.indices) {
                        if (transcript[i].id in folded) {
                            transcript[i] = transcript[i].copy(summarized = true)
                        }
                    }
                    compactionCount++
                }
                rebuildConversation(excludeId)
            }
            return merged
        } catch (t: Throwable) {
            Log.e(TAG, "Compaction failed", t)
            return null
        } finally {
            _contextState.value = _contextState.value.copy(isCompacting = false)
            publishState()
        }
    }

    /**
     * Closes the live conversation and builds a new one from the running summary
     * and the recency window.
     */
    private fun rebuildConversation(excludeId: Long? = null) {
        runCatching { conversation?.close() }
        conversation = null

        if (!engine.isReady) return

        val system = promptAssembler.build(runningSummary)

        // On a retry the user's turn is already in the transcript but has not been
        // answered. Seeding it here as well as resending it would show the model the
        // same message twice.
        val seed = transcript.takeLast(RECENCY_TURNS)
            .filter { it.id != excludeId }
            .map { m ->
                if (m.speaker == Speaker.USER) Message.user(m.text) else Message.model(m.text)
            }

        conversation = try {
            engine.createConversation(
                systemInstruction = system,
                initialMessages = seed,
                maxOutputToken = budget.responseTokens,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Could not rebuild conversation", t)
            null
        }
    }

    private fun currentTokens(): Int = try {
        conversation?.getTokenCount() ?: 0
    } catch (t: Throwable) {
        Log.w(TAG, "tokenCount unavailable", t)
        0
    }

    private fun publishState() {
        _contextState.value = ContextState(
            usedTokens = currentTokens(),
            usableCeiling = budget.usableCeiling,
            compactionCount = compactionCount,
            isCompacting = _contextState.value.isCompacting,
            lastRecoveredAt = _contextState.value.lastRecoveredAt,
        )
    }

    fun close() {
        runCatching { conversation?.close() }
        conversation = null
        transcript.clear()
        runningSummary = null
    }

    companion object {
        private const val TAG = "ChatController"
        private const val DEFAULT_CEILING = 4096

        /** Turns always kept verbatim. Twelve is roughly six exchanges. */
        private const val RECENCY_TURNS = 12
    }
}

private fun Message.textOrEmpty(): String =
    contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

/**
 * Normalises the stream.
 *
 * Streaming APIs differ on whether each emission is the new fragment or the whole
 * response so far, and that detail is not contractual here. This accepts either:
 * if an emission extends what we already have, only the tail is new; otherwise it
 * is treated as a fragment and appended.
 */
internal class StreamAccumulator {
    private val builder = StringBuilder()

    fun consume(chunk: String): String {
        if (chunk.isEmpty()) return ""
        val soFar = builder.toString()
        return when {
            chunk == soFar -> ""
            chunk.length > soFar.length && chunk.startsWith(soFar) -> {
                val delta = chunk.substring(soFar.length)
                builder.append(delta)
                delta
            }
            else -> {
                builder.append(chunk)
                chunk
            }
        }
    }

    fun text(): String = builder.toString()
}
