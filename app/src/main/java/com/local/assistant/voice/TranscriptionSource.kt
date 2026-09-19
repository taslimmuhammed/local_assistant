package com.local.assistant.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One chunk of recognised speech.
 *
 * @param isFinal false while the recogniser may still revise this text. The input
 *   field shows partials live and settles on the final.
 */
data class Transcript(val text: String, val isFinal: Boolean)

/**
 * The seam for phase-2 voice input.
 *
 * Speech goes to text, the text lands in the input field, and the user reads and
 * edits it before pressing send. Nothing is ever sent on the recogniser's say-so.
 * That is the reason this is a separate speech-to-text path rather than feeding
 * audio to the model directly: Gemma 4 E4B does accept audio natively, but a model
 * that hears the microphone gives the user nothing to review or correct.
 *
 * The intended implementation is Moonshine v2 (`ai.moonshine:moonshine-voice`,
 * ONNX `.ort` runtime, ~26 MB for the tiny English model, MIT-licensed for
 * English). Its streaming encoder emits partials continuously, which maps onto
 * [Transcript] directly.
 */
interface TranscriptionSource {
    val isAvailable: Boolean

    /** Begins listening. Collect until the flow completes or [stop] is called. */
    fun start(): Flow<Transcript>

    fun stop()
}

/** Phase-1 placeholder. The mic button stays hidden while [isAvailable] is false. */
class UnavailableTranscriptionSource : TranscriptionSource {
    override val isAvailable = false
    override fun start(): Flow<Transcript> = emptyFlow()
    override fun stop() = Unit
}
