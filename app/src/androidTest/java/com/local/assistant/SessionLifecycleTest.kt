package com.local.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.data.AssistantDatabase
import com.local.assistant.data.ChatMessage
import com.local.assistant.data.Speaker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Covers the chat-history drawer's data: listing, titling, deletion. */
@RunWith(AndroidJUnit4::class)
class SessionLifecycleTest {

    private lateinit var db: AssistantDatabase
    private lateinit var dbName: String

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "test-${System.nanoTime()}.db"
        context.getDatabasePath(dbName).delete()
        db = AssistantDatabase(context, dbName)
    }

    @After
    fun tearDown() {
        db.close()
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(dbName).delete()
    }

    private fun say(sessionId: Long, text: String, who: Speaker = Speaker.USER) = db.insertMessage(
        ChatMessage(
            sessionId = sessionId,
            speaker = who,
            text = text,
            createdAt = System.currentTimeMillis(),
        )
    )

    @Test
    fun emptyChatsNeverAppearInHistory() {
        // Opening the app and closing it again must not leave a blank row behind.
        db.createSession(System.currentTimeMillis())
        assertTrue(db.listSessions().isEmpty())
    }

    @Test
    fun aChatIsNamedAfterTheFirstThingTheUserSaid() {
        val id = db.createSession(System.currentTimeMillis())
        say(id, "How do I pickle a Python object?")
        say(id, "You use the pickle module.", Speaker.ASSISTANT)

        val listed = db.listSessions().single()
        assertEquals("How do I pickle a Python object?", listed.title)
        assertEquals(2, listed.messageCount)
    }

    @Test
    fun anExplicitTitleWinsOverTheDerivedOne() {
        val id = db.createSession(System.currentTimeMillis())
        say(id, "some rambling opener")
        db.setSessionTitle(id, "Pickling notes")

        assertEquals("Pickling notes", db.listSessions().single().title)
    }

    @Test
    fun historyIsOrderedByRecentActivityNotCreation() {
        val older = db.createSession(1_000)
        say(older, "first chat")
        val newer = db.createSession(2_000)
        say(newer, "second chat")

        db.touchSession(older, System.currentTimeMillis())

        assertEquals(listOf(older, newer), db.listSessions().map { it.id })
    }

    @Test
    fun deletingAChatTakesItsMessagesAndSearchIndexWithIt() {
        val id = db.createSession(System.currentTimeMillis())
        say(id, "a distinctive word: kumquat")
        assertEquals(1, db.search("kumquat").size)

        db.deleteSession(id)

        assertTrue(db.listSessions().isEmpty())
        assertTrue(db.messagesFor(id).isEmpty())
        assertTrue("FTS index must not keep orphans", db.search("kumquat").isEmpty())
    }

    @Test
    fun deletingOneChatLeavesTheOthersAlone() {
        val keep = db.createSession(System.currentTimeMillis())
        say(keep, "keep this one")
        val drop = db.createSession(System.currentTimeMillis())
        say(drop, "drop this one")

        db.deleteSession(drop)

        assertEquals(listOf(keep), db.listSessions().map { it.id })
        assertEquals(1, db.search("keep").size)
    }

    @Test
    fun emptinessIsWhatDecidesWhetherToReuseAChat() {
        val id = db.createSession(System.currentTimeMillis())
        assertTrue(db.isSessionEmpty(id))

        say(id, "hello")
        assertFalse(db.isSessionEmpty(id))
    }

    @Test
    fun aChatStartedByTheAssistantStillGetsAName() {
        // No user turn yet: the title falls back rather than coming back blank.
        val id = db.createSession(System.currentTimeMillis())
        say(id, "Welcome back.", Speaker.ASSISTANT)

        assertEquals("New chat", db.listSessions().single().title)
    }

    @Test
    fun reopeningAnOldChatRestoresItsWholeTranscript() {
        val id = db.createSession(System.currentTimeMillis())
        say(id, "turn one")
        say(id, "reply one", Speaker.ASSISTANT)
        say(id, "turn two")

        val restored = db.messagesFor(id)
        assertEquals(3, restored.size)
        assertEquals("turn one", restored.first().text)
        assertEquals("turn two", restored.last().text)
    }
}
