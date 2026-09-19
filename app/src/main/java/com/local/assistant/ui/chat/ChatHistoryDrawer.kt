package com.local.assistant.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.local.assistant.data.SearchHit
import com.local.assistant.data.SessionSummary
import com.local.assistant.ui.theme.Palette
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * The chat history drawer.
 *
 * Chats are grouped by age rather than listed flat, because the useful question is
 * almost always "what was I doing recently" and a bare list of forty titles does
 * not answer it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatHistoryDrawer(
    sessions: List<SessionSummary>,
    activeSessionId: Long,
    onNewChat: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onDeleteChat: (Long) -> Unit,
    onRenameChat: (Long, String) -> Unit,
    onOpenSettings: () -> Unit,
    searchQuery: String,
    searchResults: List<SearchHit>,
    onSearch: (String) -> Unit,
) {
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var deleting by remember { mutableStateOf<SessionSummary?>(null) }

    ModalDrawerSheet(
        drawerContainerColor = Palette.White,
        drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
        modifier = Modifier.width(310.dp),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            NewChatButton(onNewChat)
            SearchField(searchQuery, onSearch)
            HorizontalDivider(color = Palette.Border)

            if (searchQuery.isNotBlank()) {
                SearchResults(
                    results = searchResults,
                    titleFor = { id -> sessions.firstOrNull { it.id == id }?.title ?: "Chat" },
                    onOpen = onOpenChat,
                    modifier = Modifier.weight(1f),
                )
            } else if (sessions.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "No earlier chats yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextSecondary,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    groupByAge(sessions).forEach { (label, group) ->
                        item(key = "h-$label") { GroupLabel(label) }
                        items(group, key = { it.id }) { session ->
                            SessionRow(
                                session = session,
                                active = session.id == activeSessionId,
                                onOpen = { onOpenChat(session.id) },
                                onRename = { renaming = session },
                                onDelete = { deleting = session },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Palette.Border)
            FooterAction(Icons.Outlined.Settings, "Settings", onOpenSettings)
            Spacer(Modifier.height(10.dp))
        }
    }

    renaming?.let { session ->
        var text by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = Palette.White,
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) onRenameChat(session.id, text)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            },
        )
    }

    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Palette.White,
            title = { Text("Delete this chat?") },
            text = {
                Text(
                    "\"${session.title}\" and its ${session.messageCount} messages are " +
                        "permanently removed from this device."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteChat(session.id)
                    deleting = null
                }) { Text("Delete", color = Palette.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SearchField(query: String, onSearch: (String) -> Unit) {
    Surface(
        color = Palette.SurfaceMuted,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.Search, null,
                tint = Palette.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = query,
                    onValueChange = onSearch,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = Palette.TextPrimary
                    ),
                    cursorBrush = SolidColor(Palette.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.isEmpty()) {
                    Text(
                        "Search chats",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextSecondary,
                    )
                }
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close, "Clear search",
                    tint = Palette.TextSecondary,
                    modifier = Modifier.size(18.dp).clickable { onSearch("") },
                )
            }
        }
    }
}

/**
 * Matching messages rather than matching chats.
 *
 * Search here answers "where did I say that", so the useful result is the line
 * itself with its chat as context — not a list of chat names you then have to open
 * one by one.
 */
@Composable
private fun SearchResults(
    results: List<SearchHit>,
    titleFor: (Long) -> String,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (results.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "No matches.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextSecondary,
            )
        }
        return
    }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        items(results, key = { it.messageId }) { hit ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(hit.sessionId) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    titleFor(hit.sessionId),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    hit.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NewChatButton(onClick: () -> Unit) {
    Surface(
        color = Palette.White,
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Add, null, tint = Palette.TextPrimary)
            Text(
                "New chat",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextPrimary,
            )
        }
    }
}

@Composable
private fun GroupLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextSecondary,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSummary,
    active: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Surface(
            color = if (active) Palette.AccentMuted else Palette.White,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    // Long-press for rename and delete keeps the row itself clean;
                    // a visible trailing button on every row is a lot of noise for
                    // something used rarely.
                    .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.preview.isNotBlank() && session.preview != session.title) {
                    Text(
                        session.preview,
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = Palette.White,
        ) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = { menuOpen = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = Palette.Danger) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

@Composable
private fun FooterAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(color = Palette.White, onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .background(Palette.White)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, null, tint = Palette.TextSecondary)
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
        }
    }
}

/**
 * Buckets chats the way a person thinks about them. Order is preserved within each
 * group because [SessionSummary] arrives newest-first.
 */
internal fun groupByAge(
    sessions: List<SessionSummary>,
    now: Long = System.currentTimeMillis(),
): List<Pair<String, List<SessionSummary>>> {
    val startOfToday = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val startOfYesterday = startOfToday - TimeUnit.DAYS.toMillis(1)
    val sevenDaysAgo = startOfToday - TimeUnit.DAYS.toMillis(7)
    val thirtyDaysAgo = startOfToday - TimeUnit.DAYS.toMillis(30)

    return sessions
        .groupBy { s ->
            when {
                s.lastActiveAt >= startOfToday -> "Today"
                s.lastActiveAt >= startOfYesterday -> "Yesterday"
                s.lastActiveAt >= sevenDaysAgo -> "Previous 7 days"
                s.lastActiveAt >= thirtyDaysAgo -> "Previous 30 days"
                else -> "Older"
            }
        }
        .toList()
        .sortedBy { (label, _) -> GROUP_ORDER.indexOf(label) }
}

private val GROUP_ORDER =
    listOf("Today", "Yesterday", "Previous 7 days", "Previous 30 days", "Older")
