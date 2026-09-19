package com.local.assistant

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.local.assistant.data.SessionSummary
import com.local.assistant.ui.chat.ChatHistoryDrawer
import com.local.assistant.ui.theme.LocalAssistantTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Renders the history drawer against fixed data.
 *
 * The chat screen itself is gated behind a three-gigabyte model download, so this
 * exercises the drawer directly rather than driving the whole app.
 */
@RunWith(AndroidJUnit4::class)
class DrawerRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()

    private fun sample() = listOf(
        row(1, "Explain grouped query attention", 0),
        row(2, "Weekend trip to Munnar", 0),
        row(3, "Debugging the Gradle build", 1),
        row(4, "Ideas for the launch post", 4),
        row(5, "Old notes on SQLite", 22),
    )

    private fun row(id: Long, title: String, daysAgo: Long) = SessionSummary(
        id = id,
        title = title,
        preview = "$title — first message preview",
        messageCount = 6,
        lastActiveAt = now - TimeUnit.DAYS.toMillis(daysAgo),
    )

    @Test
    fun drawerShowsGroupedChats() {
        compose.setContent {
            LocalAssistantTheme {
                ChatHistoryDrawer(
                    sessions = sample(),
                    activeSessionId = 1L,
                    onNewChat = {},
                    onOpenChat = {},
                    onDeleteChat = {},
                    onRenameChat = { _, _ -> },
                    onOpenSettings = {},
                    searchQuery = "",
                    searchResults = emptyList(),
                    onSearch = {},
                )
            }
        }

        compose.onNodeWithText("New chat").assertIsDisplayed()
        compose.onNodeWithText("Search chats").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
        compose.onNodeWithText("Explain grouped query attention").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun emptyHistoryExplainsItself() {
        compose.setContent {
            LocalAssistantTheme {
                ChatHistoryDrawer(
                    sessions = emptyList(),
                    activeSessionId = 0L,
                    onNewChat = {},
                    onOpenChat = {},
                    onDeleteChat = {},
                    onRenameChat = { _, _ -> },
                    onOpenSettings = {},
                    searchQuery = "",
                    searchResults = emptyList(),
                    onSearch = {},
                )
            }
        }
        compose.onNodeWithText("No earlier chats yet.").assertIsDisplayed()
        compose.onNodeWithText("New chat").assertIsDisplayed()
    }
}
