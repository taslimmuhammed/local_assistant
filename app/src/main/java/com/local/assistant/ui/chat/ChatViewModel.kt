package com.local.assistant.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.assistant.LocalAssistantApp
import com.local.assistant.context.ContextState
import com.local.assistant.context.TurnEvent
import com.local.assistant.data.Speaker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A row in the transcript. */
sealed interface ChatRow {
    data class Bubble(
        val id: Long,
        val speaker: Speaker,
        val text: String,
        val streaming: Boolean = false,
    ) : ChatRow

    /**
     * Marks where older turns were folded into the running summary. Shown so
     * compaction is legible rather than mysterious when the model's recall of the
     * early conversation gets coarser.
     */
    data class CompactionMarker(val id: Long) : ChatRow

    /** The safety net fired. Rare, and worth showing honestly when it does. */
    data class Notice(val id: Long, val text: String) : ChatRow
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = (app as LocalAssistantApp).container.chatController

    private val _rows = MutableStateFlow<List<ChatRow>>(emptyList())
    val rows: StateFlow<List<ChatRow>> = _rows.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    val contextState: StateFlow<ContextState> = controller.contextState

    private var sendJob: Job? = null
    private var rowId = 0L

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return

        _rows.value = _rows.value + ChatRow.Bubble(nextRowId(), Speaker.USER, trimmed)
        val placeholderId = nextRowId()
        _rows.value = _rows.value +
            ChatRow.Bubble(placeholderId, Speaker.ASSISTANT, "", streaming = true)
        _isGenerating.value = true

        sendJob = viewModelScope.launch {
            val buffer = StringBuilder()
            try {
                controller.send(trimmed).collect { event ->
                    when (event) {
                        is TurnEvent.Delta -> {
                            buffer.append(event.text)
                            updateRow(placeholderId) {
                                it.copy(text = buffer.toString(), streaming = true)
                            }
                        }

                        is TurnEvent.Complete -> updateRow(placeholderId) {
                            it.copy(text = event.fullText, streaming = false)
                        }

                        is TurnEvent.Compacted -> _rows.value =
                            _rows.value + ChatRow.CompactionMarker(nextRowId())

                        is TurnEvent.Recovered -> _rows.value =
                            _rows.value + ChatRow.Notice(nextRowId(), event.reason)

                        is TurnEvent.Failed -> updateRow(placeholderId) {
                            it.copy(text = event.error, streaming = false)
                        }
                    }
                }
            } finally {
                _isGenerating.value = false
                updateRow(placeholderId) { it.copy(streaming = false) }
            }
        }
    }

    fun stop() {
        sendJob?.cancel()
        sendJob = null
        _isGenerating.value = false
    }

    fun newChat() {
        stop()
        _rows.value = emptyList()
        viewModelScope.launch { controller.newChat() }
    }

    private fun updateRow(id: Long, transform: (ChatRow.Bubble) -> ChatRow.Bubble) {
        _rows.value = _rows.value.map { row ->
            if (row is ChatRow.Bubble && row.id == id) transform(row) else row
        }
    }

    private fun nextRowId(): Long = rowId++
}
