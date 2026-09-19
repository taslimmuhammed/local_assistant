package com.local.assistant.data

import android.content.Context
import android.util.Log
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File

/**
 * Storage for conversations, summaries and the forget bookkeeping.
 *
 * Two deliberate departures from the obvious choices.
 *
 * **Not Room.** The recall layer needs FTS5 with `bm25()` ranking, which Room
 * cannot express through `@Fts4` and would need raw migration SQL for regardless.
 * Room's compiler also runs on KSP, and no KSP release yet targets Kotlin 2.4,
 * which LiteRT-LM 0.17.1 requires.
 *
 * **Not the platform SQLite.** Android's bundled SQLite is compiled without FTS5 —
 * `CREATE VIRTUAL TABLE ... USING fts5` fails with "no such module: fts5" even on
 * API 36. So this ships its own SQLite through `androidx.sqlite:sqlite-bundled`,
 * which also means the same SQLite version and feature set on every device rather
 * than whatever the OEM happened to build.
 *
 * [SQLiteConnection] is not thread-safe and this is reached from several
 * coroutines, so every method holds [lock].
 */
class AssistantDatabase(context: Context, databaseName: String = DB_NAME) : AutoCloseable {

    private val lock = Any()
    private val path = File(context.getDatabasePath(databaseName).parentFile!!, databaseName)

    private val connection: SQLiteConnection by lazy {
        path.parentFile?.mkdirs()
        BundledSQLiteDriver().open(path.absolutePath).also { migrate(it) }
    }

    private fun migrate(db: SQLiteConnection) {
        db.execSQL("PRAGMA journal_mode=WAL")
        db.execSQL("PRAGMA foreign_keys=ON")

        val version = db.prepare("PRAGMA user_version").use {
            if (it.step()) it.getInt(0) else 0
        }
        if (version >= DB_VERSION) return

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                last_active_at INTEGER NOT NULL,
                title TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                speaker TEXT NOT NULL,
                text TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                summarized INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, id)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS summaries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                text TEXT NOT NULL,
                first_message_id INTEGER NOT NULL,
                last_message_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                is_current INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_summaries_session ON summaries(session_id, is_current)"
        )

        // External-content FTS5 index, kept in sync by triggers so it can never
        // drift from the rows. `content=` means the text is not stored twice.
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
                text,
                content='messages',
                content_rowid='id',
                tokenize='unicode61 remove_diacritics 2'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
                INSERT INTO messages_fts(rowid, text) VALUES (new.id, new.text);
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
                INSERT INTO messages_fts(messages_fts, rowid, text)
                VALUES('delete', old.id, old.text);
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE OF text ON messages BEGIN
                INSERT INTO messages_fts(messages_fts, rowid, text)
                VALUES('delete', old.id, old.text);
                INSERT INTO messages_fts(rowid, text) VALUES (new.id, new.text);
            END
            """.trimIndent()
        )

        db.execSQL("PRAGMA user_version=$DB_VERSION")
    }

    // ---- helpers ------------------------------------------------------------

    private inline fun <T> read(sql: String, bind: SQLiteStatement.() -> Unit = {}, map: (SQLiteStatement) -> T): T =
        synchronized(lock) { connection.prepare(sql).use { it.bind(); map(it) } }

    private fun write(sql: String, bind: SQLiteStatement.() -> Unit = {}) =
        synchronized(lock) { connection.prepare(sql).use { it.bind(); it.step() } }

    private fun insertAndGetId(sql: String, bind: SQLiteStatement.() -> Unit): Long =
        synchronized(lock) {
            connection.prepare(sql).use { it.bind(); it.step() }
            connection.prepare("SELECT last_insert_rowid()").use {
                it.step()
                it.getLong(0)
            }
        }

    // ---- sessions -----------------------------------------------------------

    fun createSession(now: Long): Long = insertAndGetId(
        "INSERT INTO sessions (started_at, last_active_at) VALUES (?, ?)"
    ) {
        bindLong(1, now)
        bindLong(2, now)
    }

    fun touchSession(sessionId: Long, now: Long) =
        write("UPDATE sessions SET last_active_at = ? WHERE id = ?") {
            bindLong(1, now)
            bindLong(2, sessionId)
        }

    /**
     * Chats for the history drawer, most recently active first.
     *
     * Empty sessions are left out. A launch that opens the app and closes it again
     * should not leave a blank row behind.
     */
    fun listSessions(limit: Int = 200): List<SessionSummary> = read(
        """
        SELECT s.id, s.title, s.last_active_at, COUNT(m.id),
               (SELECT text FROM messages WHERE session_id = s.id AND speaker = 'USER'
                ORDER BY id ASC LIMIT 1)
        FROM sessions s
        JOIN messages m ON m.session_id = s.id
        GROUP BY s.id
        HAVING COUNT(m.id) > 0
        ORDER BY s.last_active_at DESC
        LIMIT ?
        """.trimIndent(),
        { bindInt(1, limit) }
    ) { st ->
        buildList {
            while (st.step()) {
                val firstUserMessage = if (st.isNull(4)) "" else st.getText(4)
                add(
                    SessionSummary(
                        id = st.getLong(0),
                        title = if (st.isNull(1)) deriveTitle(firstUserMessage) else st.getText(1),
                        preview = firstUserMessage.replace(WHITESPACE, " ").trim().take(90),
                        messageCount = st.getInt(3),
                        lastActiveAt = st.getLong(2),
                    )
                )
            }
        }
    }

    fun setSessionTitle(sessionId: Long, title: String) =
        write("UPDATE sessions SET title = ? WHERE id = ?") {
            bindText(1, title.trim().take(120))
            bindLong(2, sessionId)
        }

    /** Cascades to messages and summaries via the foreign keys, and so to the FTS index. */
    fun deleteSession(sessionId: Long) =
        write("DELETE FROM sessions WHERE id = ?") { bindLong(1, sessionId) }

    /** True when a session exists but nobody has said anything in it yet. */
    fun isSessionEmpty(sessionId: Long): Boolean = messageCount(sessionId) == 0

    fun latestSessionId(): Long? =
        read("SELECT id FROM sessions ORDER BY last_active_at DESC LIMIT 1", {}) {
            if (it.step()) it.getLong(0) else null
        }

    // ---- messages -----------------------------------------------------------

    fun insertMessage(message: ChatMessage): Long = insertAndGetId(
        """
        INSERT INTO messages (session_id, speaker, text, created_at, summarized)
        VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
    ) {
        bindLong(1, message.sessionId)
        bindText(2, message.speaker.name)
        bindText(3, message.text)
        bindLong(4, message.createdAt)
        bindBoolean(5, message.summarized)
    }

    fun updateMessageText(id: Long, text: String) =
        write("UPDATE messages SET text = ? WHERE id = ?") {
            bindText(1, text)
            bindLong(2, id)
        }

    fun messagesFor(sessionId: Long, limit: Int = Int.MAX_VALUE): List<ChatMessage> =
        read("$MESSAGE_COLUMNS WHERE session_id = ? ORDER BY id ASC LIMIT ?", {
            bindLong(1, sessionId)
            bindInt(2, limit)
        }) { it.readMessages() }

    /** The most recent [count] turns, oldest-first — the verbatim recency window. */
    fun recentMessages(sessionId: Long, count: Int): List<ChatMessage> =
        read("$MESSAGE_COLUMNS WHERE session_id = ? ORDER BY id DESC LIMIT ?", {
            bindLong(1, sessionId)
            bindInt(2, count)
        }) { it.readMessages().reversed() }

    /** Turns older than the recency window that have not yet been folded into a summary. */
    fun unsummarizedBefore(sessionId: Long, beforeId: Long): List<ChatMessage> =
        read(
            "$MESSAGE_COLUMNS WHERE session_id = ? AND id < ? AND summarized = 0 ORDER BY id ASC",
            {
                bindLong(1, sessionId)
                bindLong(2, beforeId)
            }
        ) { it.readMessages() }

    fun markSummarized(ids: List<Long>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        write("UPDATE messages SET summarized = 1 WHERE id IN ($placeholders)") {
            ids.forEachIndexed { i, id -> bindLong(i + 1, id) }
        }
    }

    fun messageCount(sessionId: Long): Int =
        read("SELECT COUNT(*) FROM messages WHERE session_id = ?", { bindLong(1, sessionId) }) {
            if (it.step()) it.getInt(0) else 0
        }

    // ---- summaries ----------------------------------------------------------

    fun currentSummary(sessionId: Long): Summary? = read(
        """
        SELECT id, session_id, text, first_message_id, last_message_id, created_at, is_current
        FROM summaries WHERE session_id = ? AND is_current = 1 ORDER BY id DESC LIMIT 1
        """.trimIndent(),
        { bindLong(1, sessionId) }
    ) { s ->
        if (!s.step()) null else Summary(
            id = s.getLong(0),
            sessionId = s.getLong(1),
            text = s.getText(2),
            firstMessageId = s.getLong(3),
            lastMessageId = s.getLong(4),
            createdAt = s.getLong(5),
            isCurrent = s.getBoolean(6),
        )
    }

    /** Supersedes any existing current summary, in one transaction. */
    fun replaceSummary(summary: Summary): Long = synchronized(lock) {
        connection.execSQL("BEGIN IMMEDIATE")
        try {
            connection.prepare(
                "UPDATE summaries SET is_current = 0 WHERE session_id = ? AND is_current = 1"
            ).use {
                it.bindLong(1, summary.sessionId)
                it.step()
            }
            connection.prepare(
                """
                INSERT INTO summaries
                    (session_id, text, first_message_id, last_message_id, created_at, is_current)
                VALUES (?, ?, ?, ?, ?, 1)
                """.trimIndent()
            ).use {
                it.bindLong(1, summary.sessionId)
                it.bindText(2, summary.text)
                it.bindLong(3, summary.firstMessageId)
                it.bindLong(4, summary.lastMessageId)
                it.bindLong(5, summary.createdAt)
                it.step()
            }
            val id = connection.prepare("SELECT last_insert_rowid()").use {
                it.step()
                it.getLong(0)
            }
            connection.execSQL("COMMIT")
            id
        } catch (t: Throwable) {
            runCatching { connection.execSQL("ROLLBACK") }
            throw t
        }
    }

    // ---- recall -------------------------------------------------------------

    /** BM25-ranked full-text search over past turns. */
    fun search(query: String, limit: Int = 8): List<SearchHit> {
        val match = FtsQuery.build(query) ?: return emptyList()
        return try {
            read(
                """
                SELECT m.id, m.session_id, m.speaker, m.text, m.created_at,
                       snippet(messages_fts, 0, '', '', '…', 12)
                FROM messages_fts
                JOIN messages m ON m.id = messages_fts.rowid
                WHERE messages_fts MATCH ?
                ORDER BY bm25(messages_fts)
                LIMIT ?
                """.trimIndent(),
                {
                    bindText(1, match)
                    bindInt(2, limit)
                }
            ) { s ->
                buildList {
                    while (s.step()) {
                        add(
                            SearchHit(
                                messageId = s.getLong(0),
                                sessionId = s.getLong(1),
                                speaker = Speaker.valueOf(s.getText(2)),
                                text = s.getText(3),
                                createdAt = s.getLong(4),
                                snippet = s.getText(5),
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTS query failed for '$query'", e)
            emptyList()
        }
    }

    /** Escape hatch for tests and the debug screen. */
    fun <T> withConnection(block: (SQLiteConnection) -> T): T =
        synchronized(lock) { block(connection) }

    override fun close() = synchronized(lock) { connection.close() }

    private fun SQLiteStatement.readMessages(): List<ChatMessage> = buildList {
        while (step()) {
            add(
                ChatMessage(
                    id = getLong(0),
                    sessionId = getLong(1),
                    speaker = Speaker.valueOf(getText(2)),
                    text = getText(3),
                    createdAt = getLong(4),
                    summarized = getBoolean(5),
                )
            )
        }
    }

    companion object {
        private const val TAG = "AssistantDatabase"
        const val DB_NAME = "assistant.db"
        private const val DB_VERSION = 1

        private val WHITESPACE = Regex("\\s+")

        /**
         * A chat's name, taken from the first thing the user said.
         *
         * Deliberately not model-generated. A title is worth roughly nothing and a
         * generation costs a second of GPU on every new chat, which is a bad trade
         * when the first line of the conversation is already a good label.
         */
        fun deriveTitle(firstUserMessage: String, maxChars: Int = 44): String {
            val clean = firstUserMessage.replace(WHITESPACE, " ").trim()
            if (clean.isEmpty()) return "New chat"
            if (clean.length <= maxChars) return clean
            // Cut on a word boundary so titles do not end mid-word.
            val cut = clean.take(maxChars)
            val lastSpace = cut.lastIndexOf(' ')
            return (if (lastSpace > maxChars / 2) cut.take(lastSpace) else cut).trimEnd(',', '.', ';') + "…"
        }

        private const val MESSAGE_COLUMNS =
            "SELECT id, session_id, speaker, text, created_at, summarized FROM messages"
    }
}
