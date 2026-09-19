package com.local.assistant.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class InferenceBackend { GPU, CPU }

/**
 * Owns the single [Engine] instance for the process.
 *
 * The engine is expensive to build (seconds, and a few hundred MB of state), so it
 * is created once and shared; individual chats are cheap [Conversation] objects
 * created from it.
 */
class LlmEngine {

    @Volatile
    private var engine: Engine? = null

    @Volatile
    var activeBackend: InferenceBackend? = null
        private set

    @Volatile
    var configuredMaxTokens: Int = 0
        private set

    val isReady: Boolean get() = engine?.isInitialized() == true

    /**
     * Builds and initialises the engine.
     *
     * Tries [preferred] first and falls back to CPU if it throws. GPU is worth
     * fighting for: on a flagship it is roughly 6x the prefill throughput of CPU
     * and about a quarter of the peak RAM, because the weights stay in GPU buffers
     * instead of being staged through the heap.
     */
    suspend fun initialize(
        modelFile: File,
        cacheDir: File,
        maxNumTokens: Int,
        preferred: InferenceBackend = InferenceBackend.GPU,
        enableSpeculativeDecoding: Boolean = true,
    ): Result<InferenceBackend> = withContext(Dispatchers.IO) {
        close()

        // Multi-Token Prediction. LiteRT-LM ships an MTP drafter for Gemma 4 that
        // roughly doubles decode throughput on mobile GPUs at no quality cost.
        // It is a global flag and must be set before the engine is constructed.
        ExperimentalFlags.enableSpeculativeDecoding = enableSpeculativeDecoding

        // Constrained decoding makes tool calls emit well-formed JSON rather than
        // hopeful prose. The memory tools depend on this being reliable.
        ExperimentalFlags.enableConversationConstrainedDecoding = true

        val order = when (preferred) {
            InferenceBackend.GPU -> listOf(InferenceBackend.GPU, InferenceBackend.CPU)
            InferenceBackend.CPU -> listOf(InferenceBackend.CPU)
        }

        var lastError: Throwable? = null
        for (backend in order) {
            try {
                val created = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = when (backend) {
                            InferenceBackend.GPU -> Backend.GPU()
                            InferenceBackend.CPU -> Backend.CPU()
                        },
                        maxNumTokens = maxNumTokens,
                        // Compiled kernels land here, which makes every launch after
                        // the first dramatically faster.
                        cacheDir = cacheDir.absolutePath,
                    )
                )
                created.initialize()
                engine = created
                activeBackend = backend
                configuredMaxTokens = maxNumTokens
                Log.i(TAG, "Engine ready on $backend (maxNumTokens=$maxNumTokens)")
                return@withContext Result.success(backend)
            } catch (e: Throwable) {
                Log.w(TAG, "Engine init failed on $backend", e)
                lastError = e
            }
        }
        Result.failure(lastError ?: IllegalStateException("Engine initialisation failed"))
    }

    /**
     * Creates a conversation.
     *
     * [initialMessages] is how rollover works: after compaction the old conversation
     * is closed and a new one is seeded with the running summary plus the recency
     * window, which resets the KV cache without losing the thread.
     */
    fun createConversation(
        systemInstruction: String,
        initialMessages: List<Message> = emptyList(),
        tools: List<com.google.ai.edge.litertlm.ToolProvider> = emptyList(),
        temperature: Double = 0.8,
        topK: Int = 64,
        topP: Double = 0.95,
        maxOutputToken: Int? = null,
    ): Conversation {
        val e = engine ?: error("Engine not initialised")
        return e.createConversation(
            ConversationConfig(
                systemInstruction = com.google.ai.edge.litertlm.Contents.of(systemInstruction),
                initialMessages = initialMessages,
                tools = tools,
                samplerConfig = SamplerConfig(
                    topK = topK,
                    topP = topP,
                    temperature = temperature,
                    seed = 0,
                ),
                automaticToolCalling = tools.isNotEmpty(),
                maxOutputToken = maxOutputToken,
            )
        )
    }

    fun close() {
        try {
            engine?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Engine close failed", e)
        }
        engine = null
        activeBackend = null
    }

    companion object {
        private const val TAG = "LlmEngine"

        /**
         * The context-exhaustion signature from LiteRT-LM.
         *
         * `EngineConfig` accepts a `maxNumTokens` larger than the model bundle
         * actually serves and offers no way to query the real ceiling, so a
         * conversation can die mid-flight once the remaining window drops below the
         * smallest prefill work group. Matching on the message is unpleasant but it
         * is the only signal the API exposes today.
         * See https://github.com/google-ai-edge/LiteRT-LM/issues/3444.
         */
        fun isContextExhausted(t: Throwable): Boolean {
            val message = generateSequence(t) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" ")
                .lowercase()
            return "exceeds available state entries" in message ||
                "prefill work group" in message ||
                ("kv cache" in message && "full" in message)
        }

        fun isJniFailure(t: Throwable): Boolean = t is LiteRtLmJniException
    }
}
