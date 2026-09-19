package com.local.assistant

import com.local.assistant.data.AssistantDatabase
import com.local.assistant.data.SessionSummary
import com.local.assistant.ui.chat.groupByAge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

class SessionTitleTest {

    @Test
    fun `a short first message becomes the title verbatim`() {
        assertEquals("What is a monad?", AssistantDatabase.deriveTitle("What is a monad?"))
    }

    @Test
    fun `a long message is cut on a word boundary`() {
        val title = AssistantDatabase.deriveTitle(
            "Explain how grouped query attention reduces the key value cache size on mobile"
        )
        assertTrue("should be elided", title.endsWith("…"))
        assertTrue("should not cut mid-word", !title.dropLast(1).endsWith("-"))
        assertTrue(title.length <= 46)
    }

    @Test
    fun `newlines are flattened so the drawer stays single-line`() {
        val title = AssistantDatabase.deriveTitle("first line\n\nsecond line")
        assertEquals("first line second line", title)
    }

    @Test
    fun `an empty message still yields a usable name`() {
        assertEquals("New chat", AssistantDatabase.deriveTitle("   \n "))
    }

    @Test
    fun `trailing punctuation is not left dangling before the ellipsis`() {
        val title = AssistantDatabase.deriveTitle(
            "I want to talk about databases, indexes, and query planning in depth"
        )
        assertTrue(title, !title.contains(",…"))
    }
}

class ChatGroupingTest {

    private val now = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 14)
        set(Calendar.MINUTE, 0)
    }.timeInMillis

    private fun session(id: Long, ageDays: Long, hoursBack: Long = 0) = SessionSummary(
        id = id,
        title = "chat $id",
        preview = "",
        messageCount = 2,
        lastActiveAt = now - TimeUnit.DAYS.toMillis(ageDays) - TimeUnit.HOURS.toMillis(hoursBack),
    )

    @Test
    fun `chats land in the bucket a person would expect`() {
        val groups = groupByAge(
            listOf(session(1, 0), session(2, 1), session(3, 3), session(4, 15), session(5, 200)),
            now,
        ).toMap()

        assertEquals(listOf(1L), groups["Today"]?.map { it.id })
        assertEquals(listOf(2L), groups["Yesterday"]?.map { it.id })
        assertEquals(listOf(3L), groups["Previous 7 days"]?.map { it.id })
        assertEquals(listOf(4L), groups["Previous 30 days"]?.map { it.id })
        assertEquals(listOf(5L), groups["Older"]?.map { it.id })
    }

    @Test
    fun `groups come back newest bucket first`() {
        val labels = groupByAge(
            listOf(session(1, 200), session(2, 0), session(3, 3)),
            now,
        ).map { it.first }
        assertEquals(listOf("Today", "Previous 7 days", "Older"), labels)
    }

    @Test
    fun `order within a bucket is preserved`() {
        // listSessions already returns newest-first; grouping must not disturb it.
        val ordered = groupByAge(
            listOf(session(1, 0), session(2, 0, hoursBack = 2), session(3, 0, hoursBack = 5)),
            now,
        ).single().second
        assertEquals(listOf(1L, 2L, 3L), ordered.map { it.id })
    }

    @Test
    fun `an empty history produces no groups`() {
        assertTrue(groupByAge(emptyList(), now).isEmpty())
    }
}
