package com.local.assistant.context

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.local.assistant.data.AssistantDatabase
import com.local.assistant.data.ChatMessage
import com.local.assistant.data.Speaker
import com.local.assistant.data.SessionSummary
import com.local.assistant.data.Summary
import com.local.assistant.llm.LlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
    private val db: AssistantDatabase,
    private val promptAssembler: PromptAssembler,
    private val summarizer: Summarizer,
    private val scope: CoroutineScope,
) {

    private val mutex = Mutex()

    private var conversation: Conversation? = null
    private var sessionId: Long = 0
    private var budget: ContextBudget = ContextBudget.from(DEFAULT_CEILING)

    private val _contextState = MutableStateFlow(
        ContextState(0, budget.usableCeiling, 0, isCompacting = false)
    )
    val contextState: StateFlow<ContextState> = _contextState.asStateFlow()

    private var compactionCount = 0

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow(0L)
    val activeSessionId: StateFlow<Long> = _activeSessionId.asStateFlow()

    /**
     * Applies the calibrated ceiling and opens a chat for this launch.
     *
     * Opening the app starts a fresh chat rather than resuming the last one, which
     * is what people expect from a chat app. The exception is an unused chat: if the
     * newest one has no messages, it is reused rather than stacking blank rows in
     * the history.
     */
    suspend fun start(usableCeiling: Int, resumeSessionId: Long? = null) = mutex.withLock {
        budget = ContextBudget.from(usableCeiling)

        sessionId = resumeSessionId
            ?: db.latestSessionId()?.takeIf { db.isSessionEmpty(it) }
            ?: db.createSession(System.currentTimeMillis())

        compactionCount = 0
        rebuildConversation()
        publishState()
    }

    /** Starts a new chat. Reuses the current one if nothing has been said in it. */
    fun startNewSession() {
        scope.launch {
            mutex.withLock {
                if (db.isSessionEmpty(sessionId)) return@withLock
                sessionId = db.createSession(System.currentTimeMillis())
                compactionCount = 0
                rebuildConversation()
                publishState()
            }
            refreshSessions()
        }
    }

    /** Reopens an earlier chat, with its summary and recent turns restored. */
    fun openSession(id: Long) {
        scope.launch {
            if (sessionId == id) return@launch
            mutex.withLock {
                sessionId = id
                compactionCount = 0
                rebuildConversation()
                publishState()
            }
            refreshSessions()
        }
    }

    fun deleteSession(id: Long) {
        scope.launch {
            mutex.withLock {
                db.deleteSession(id)
                if (sessionId == id) {
                    sessionId = db.createSession(System.currentTimeMillis())
                    compactionCount = 0
                    rebuildConversation()
                    publishState()
                }
            }
            refreshSessions()
        }
    }

    fun renameSession(id: Long, title: String) {
        scope.launch {
            db.setSessionTitle(id, title)
            refreshSessions()
        }
    }

    fun refreshSessions() {
        scope.launch { _sessions.value = db.listSessions() }
    }

    fun currentSessionId(): Long = sessionId

    /**
     * Sends a turn and streams the reply.
     *
     * The user's message is persisted before inference so a crash mid-generation
     * cannot lose what they typed.
     */
    fun send(userText: String): Flow<TurnEvent> = flow {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return@flow

        val now = System.currentTimeMillis()
        // Checked before the insert: this is what decides whether the chat has just
        // acquired a name and needs to appear in the history.
        val isFirstTurn = db.isSessionEmpty(sessionId)
        val userMessageId = db.insertMessage(
            ChatMessage(sessionId = sessionId, speaker = Speaker.USER, text = trimmed, createdAt = now)
        )
        db.touchSession(sessionId, now)

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
                val assistantId = db.insertMessage(
                    ChatMessage(
                        sessionId = sessionId,
                        speaker = Speaker.ASSISTANT,
                        text = full,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                emit(TurnEvent.Complete(full, assistantId))
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
                // Drop the half-written turn so the retry sees a clean window.
                compact(force = true, excludeFromId = userMessageId)
            }
        }

        db.touchSession(sessionId, System.currentTimeMillis())
        if (isFirstTurn) refreshSessions()

        // Compact after the turn rather than before it, so the reply streams without
        // a stall and the next turn starts with room already made.
        if (currentTokens() >= budget.compactionWatermark) {
            val summary = compact(force = false, excludeFromId = null)
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
    private suspend fun compact(force: Boolean, excludeFromId: Long?): String? {
        _contextState.value = _contextState.value.copy(isCompacting = true)
        try {
            val recent = db.recentMessages(sessionId, RECENCY_TURNS)
            val oldestKeptId = recent.firstOrNull()?.id ?: Long.MAX_VALUE
            val toFold = db.unsummarizedBefore(sessionId, oldestKeptId)
                .filter { excludeFromId == null || it.id != excludeFromId }

            if (toFold.isEmpty() && !force) return null

            val previous = db.currentSummary(sessionId)
            val merged = summarizer.compact(
                previousSummary = previous?.text,
                turns = toFold,
                charBudget = budget.summaryChars,
            )

            if (merged != null && toFold.isNotEmpty()) {
                db.replaceSummary(
                    Summary(
                        sessionId = sessionId,
                        text = merged,
                        firstMessageId = previous?.firstMessageId ?: toFold.first().id,
                        lastMessageId = toFold.last().id,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                db.markSummarized(toFold.map { it.id })
                compactionCount++
            }

            mutex.withLock { rebuildConversation(excludeMessageId = excludeFromId) }
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
    private fun rebuildConversation(excludeMessageId: Long? = null) {
        runCatching { conversation?.close() }
        conversation = null

        if (!engine.isReady) return

        val summary = db.currentSummary(sessionId)?.text
        val system = promptAssembler.build(summary)

        // On a retry the user's turn is already persisted but has not been answered.
        // Seeding it here as well as resending it would show the model the same
        // message twice.
        val seed = db.recentMessages(sessionId, RECENCY_TURNS)
            .filter { it.id != excludeMessageId }
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
        _activeSessionId.value = sessionId
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
