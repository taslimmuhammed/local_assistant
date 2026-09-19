package com.local.assistant.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.assistant.LocalAssistantApp
import com.local.assistant.context.ContextState
import com.local.assistant.context.TurnEvent
import com.local.assistant.data.SearchHit
import com.local.assistant.data.SessionSummary
import com.local.assistant.data.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val container = (app as LocalAssistantApp).container
    private val controller = container.chatController

    private val _rows = MutableStateFlow<List<ChatRow>>(emptyList())
    val rows: StateFlow<List<ChatRow>> = _rows.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    val contextState: StateFlow<ContextState> = controller.contextState

    /** Chats for the history drawer, newest first. */
    val sessions: StateFlow<List<SessionSummary>> = controller.sessions

    val activeSessionId: StateFlow<Long> = controller.activeSessionId

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchHit>>(emptyList())
    val searchResults: StateFlow<List<SearchHit>> = _searchResults.asStateFlow()

    private var searchJob: Job? = null

    private var sendJob: Job? = null
    private var syntheticId = -1L

    init {
        loadHistory()
        controller.refreshSessions()

        // The transcript follows whichever chat is open, including switches the
        // controller makes on its own (deleting the open chat, for instance).
        viewModelScope.launch {
            controller.activeSessionId.collect { loadHistory() }
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            val sessionId = controller.currentSessionId()
            val history = container.database.messagesFor(sessionId)
            _rows.value = history.map {
                ChatRow.Bubble(it.id, it.speaker, it.text)
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return

        _rows.value = _rows.value + ChatRow.Bubble(nextSyntheticId(), Speaker.USER, trimmed)
        val placeholderId = nextSyntheticId()
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
                            _rows.value + ChatRow.CompactionMarker(nextSyntheticId())

                        is TurnEvent.Recovered -> _rows.value =
                            _rows.value + ChatRow.Notice(nextSyntheticId(), event.reason)

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

    fun newSession() {
        stop()
        controller.startNewSession()
        _rows.value = emptyList()
    }

    fun openSession(id: Long) {
        if (id == controller.currentSessionId()) return
        stop()
        controller.openSession(id)
    }

    fun deleteSession(id: Long) {
        stop()
        controller.deleteSession(id)
    }

    fun renameSession(id: Long, title: String) = controller.renameSession(id, title)

    /**
     * BM25-ranked search across every chat.
     *
     * Debounced because it runs on each keystroke. FTS5 answers in single-digit
     * milliseconds at this scale, but the query still crosses to a background
     * dispatcher and there is no sense queueing one per character.
     */
    fun search(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(180)
            _searchResults.value = withContext(Dispatchers.IO) {
                container.database.search(query, limit = 40)
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    private fun updateRow(id: Long, transform: (ChatRow.Bubble) -> ChatRow.Bubble) {
        _rows.value = _rows.value.map { row ->
            if (row is ChatRow.Bubble && row.id == id) transform(row) else row
        }
    }

    /** Negative ids for rows the UI invents, so they cannot collide with database ids. */
    private fun nextSyntheticId(): Long = syntheticId--
}
