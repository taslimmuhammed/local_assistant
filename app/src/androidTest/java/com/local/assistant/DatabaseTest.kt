package com.local.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.data.AssistantDatabase
import com.local.assistant.data.ChatMessage
import com.local.assistant.data.Speaker
import com.local.assistant.data.Summary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {

    private lateinit var db: AssistantDatabase
    private lateinit var dbName: String
    private var sessionId: Long = 0

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Start from a clean slate; the helper recreates the schema on open.
        // A unique file per run: the bundled driver owns its own connection, so
        // there is no SQLiteOpenHelper-style deleteDatabase to lean on.
        dbName = "test-${System.nanoTime()}.db"
        context.getDatabasePath(dbName).delete()
        db = AssistantDatabase(context, dbName)
        sessionId = db.createSession(System.currentTimeMillis())
    }

    @After
    fun tearDown() {
        db.close()
        InstrumentationRegistry.getInstrumentation().targetContext
            .getDatabasePath(dbName).delete()
    }

    private fun insert(text: String, speaker: Speaker = Speaker.USER): Long = db.insertMessage(
        ChatMessage(
            sessionId = sessionId,
            speaker = speaker,
            text = text,
            createdAt = System.currentTimeMillis(),
        )
    )

    /**
     * Chat search is built on FTS5 and `bm25()`. Android's own SQLite ships without
     * the fts5 module, which is why the app bundles its own; this failing means that
     * bundling has regressed rather than that a query is wrong.
     */
    @Test
    fun fts5IsAvailableOnThisDevice() {
        insert("The Italian place on MG Road had good pasta")
        val hits = db.search("italian")
        assertEquals(1, hits.size)
        assertTrue(hits.single().text.contains("Italian"))
    }

    @Test
    fun searchRanksByRelevanceNotInsertionOrder() {
        insert("a passing mention of pasta")
        insert("pasta pasta pasta, the whole meal was pasta")
        val hits = db.search("pasta")
        assertEquals(2, hits.size)
        assertTrue(
            "densest match should rank first",
            hits.first().text.startsWith("pasta pasta")
        )
    }

    @Test
    fun indexStaysInSyncWhenTextIsEdited() {
        val id = insert("originally about cricket")
        assertEquals(1, db.search("cricket").size)

        db.updateMessageText(id, "now about football")

        assertTrue("stale term should no longer match", db.search("cricket").isEmpty())
        assertEquals(1, db.search("football").size)
    }

    @Test
    fun punctuationHeavyInputDoesNotBreakTheQuery() {
        insert("we discussed C++ templates and the x*y operator")
        // Unescaped, these would be parsed as FTS5 syntax and throw.
        val hits = db.search("C++ x*y \"quoted\" NEAR(")
        assertNotNull(hits)
    }

    @Test
    fun recentMessagesComeBackOldestFirst() {
        val first = insert("one")
        insert("two")
        val third = insert("three")

        val recent = db.recentMessages(sessionId, 3)
        assertEquals(listOf(first, recent[1].id, third), recent.map { it.id })
        assertEquals("one", recent.first().text)
    }

    @Test
    fun recencyWindowIsBoundedByCount() {
        repeat(20) { insert("message $it") }
        assertEquals(5, db.recentMessages(sessionId, 5).size)
        assertEquals(20, db.messageCount(sessionId))
    }

    @Test
    fun onlyUnsummarizedTurnsOutsideTheWindowAreFolded() {
        val old = insert("old one")
        insert("old two")
        val boundary = insert("boundary")

        val pending = db.unsummarizedBefore(sessionId, boundary)
        assertEquals(2, pending.size)

        db.markSummarized(listOf(old))
        assertEquals(1, db.unsummarizedBefore(sessionId, boundary).size)
    }

    @Test
    fun replacingASummarySupersedesTheOldOne() {
        val a = insert("a")
        val b = insert("b")
        db.replaceSummary(
            Summary(sessionId = sessionId, text = "first", firstMessageId = a,
                lastMessageId = a, createdAt = 1)
        )
        db.replaceSummary(
            Summary(sessionId = sessionId, text = "second", firstMessageId = a,
                lastMessageId = b, createdAt = 2)
        )

        val current = db.currentSummary(sessionId)
        assertNotNull(current)
        assertEquals("second", current!!.text)
    }

    @Test
    fun emptyQueryReturnsNothingRatherThanEverything() {
        insert("something")
        assertTrue(db.search("").isEmpty())
        assertTrue(db.search("!!!").isEmpty())
    }
}
