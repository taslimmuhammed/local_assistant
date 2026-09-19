package com.local.assistant.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.assistant.context.ContextState
import com.local.assistant.data.Speaker
import com.local.assistant.ui.theme.Palette

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    voiceAvailable: Boolean,
    viewModel: ChatViewModel = viewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val generating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val context by viewModel.contextState.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(rows.size, generating) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }

    Scaffold(
        containerColor = Palette.White,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            "Assistant",
                            style = MaterialTheme.typography.titleMedium,
                            color = Palette.TextPrimary,
                        )
                    },
                    actions = {
                        IconButton(onClick = { viewModel.newChat() }) {
                            Icon(Icons.Filled.Add, "New chat", tint = Palette.TextSecondary)
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, "Settings", tint = Palette.TextSecondary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.White),
                )
                ContextMeter(context)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Palette.White)
        ) {
            if (rows.isEmpty()) {
                EmptyState(Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(rows, key = { it.rowKey() }) { row ->
                        when (row) {
                            is ChatRow.Bubble -> MessageBubble(row)
                            is ChatRow.CompactionMarker -> InlineMarker(
                                "earlier conversation condensed"
                            )
                            is ChatRow.Notice -> InlineMarker(row.text)
                        }
                    }
                }
            }

            Composer(
                value = draft,
                onValueChange = { draft = it },
                onSend = {
                    viewModel.send(draft)
                    draft = ""
                },
                onStop = viewModel::stop,
                generating = generating,
                voiceAvailable = voiceAvailable,
            )
        }
    }
}

private fun ChatRow.rowKey(): Long = when (this) {
    is ChatRow.Bubble -> id
    is ChatRow.CompactionMarker -> id
    is ChatRow.Notice -> id
}

/**
 * A thin, quiet progress line. It only earns attention when the window is
 * genuinely filling, so it stays near-invisible below the compaction watermark.
 */
@Composable
private fun ContextMeter(state: ContextState) {
    val fraction = state.fraction
    val color = when {
        state.isCompacting -> Palette.Accent
        fraction > 0.8f -> Palette.Warn
        else -> Palette.Border
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Palette.White)
    ) {
        Box(
            Modifier
                .fillMaxWidth(if (state.isCompacting) 1f else fraction)
                .height(2.dp)
                .background(color)
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Everything stays on this device.",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Say something and I'll start remembering.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * User turns get a tinted bubble; assistant turns run full-width with no bubble.
 * Long answers are the common case and a bubble around several paragraphs makes
 * them harder to read, not easier.
 */
@Composable
private fun MessageBubble(row: ChatRow.Bubble) {
    val isUser = row.speaker == Speaker.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Surface(
                color = Palette.AccentMuted,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    row.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.TextPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                if (row.text.isEmpty() && row.streaming) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        color = Palette.TextSecondary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        row.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.TextPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineMarker(text: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = Palette.Border)
        Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
        HorizontalDivider(Modifier.weight(1f), color = Palette.Border)
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    generating: Boolean,
    voiceAvailable: Boolean,
) {
    Column {
        HorizontalDivider(color = Palette.Border)
        Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.White)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Surface(
                color = Palette.SurfaceMuted,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.weight(1f),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyLarge
                    ).copy(color = Palette.TextPrimary),
                    cursorBrush = SolidColor(Palette.Accent),
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(
                                "Message",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Palette.TextSecondary,
                            )
                        }
                        inner()
                    },
                )
            }

            Spacer(Modifier.width(8.dp))

            // Phase 2. Hidden rather than disabled until a real transcriber exists,
            // so the UI never advertises something that does nothing.
            if (voiceAvailable && value.isEmpty() && !generating) {
                IconButton(onClick = { }) {
                    Icon(Icons.Filled.Mic, "Dictate", tint = Palette.TextSecondary)
                }
            }

            Surface(
                color = if (generating || value.isNotBlank()) Palette.Accent else Palette.Border,
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(50)),
            ) {
                IconButton(
                    onClick = if (generating) onStop else onSend,
                    enabled = generating || value.isNotBlank(),
                ) {
                    Icon(
                        if (generating) Icons.Filled.Stop else Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = if (generating) "Stop" else "Send",
                        tint = Palette.White,
                    )
                }
            }
        }
    }
}
