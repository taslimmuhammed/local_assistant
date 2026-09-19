package com.local.assistant.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.local.assistant.download.ModelDownloader
import com.local.assistant.download.ModelSpec
import com.local.assistant.ui.BootState
import com.local.assistant.ui.theme.Palette

@Composable
fun OnboardingScreen(
    state: BootState,
    onDownload: (ModelSpec) -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Local Assistant",
            style = MaterialTheme.typography.titleLarge,
            color = Palette.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Runs entirely on your phone. Nothing you say is sent anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))

        when (state) {
            is BootState.NeedsModel -> NeedsModel(state, onDownload)
            is BootState.Downloading -> Downloading(state, onPause)
            is BootState.Preparing -> Preparing(state)
            is BootState.Failed -> Failed(state.message, onRetry)
            else -> CircularProgressIndicator(color = Palette.Accent)
        }
    }
}

@Composable
private fun NeedsModel(state: BootState.NeedsModel, onDownload: (ModelSpec) -> Unit) {
    val spec = state.spec
    val resuming = state.resumableBytes > 0

    Surface(
        color = Palette.SurfaceMuted,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(spec.displayName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                spec.blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                ModelDownloader.formatGb(spec.sizeBytes) + " download, one time",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextSecondary,
            )
        }
    }

    state.error?.let {
        Spacer(Modifier.height(14.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Danger,
            textAlign = TextAlign.Center,
        )
    }

    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { onDownload(spec) },
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
    ) {
        Text(
            if (resuming) {
                "Resume from " + ModelDownloader.formatGb(state.resumableBytes)
            } else {
                "Download model"
            }
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Best on Wi-Fi. You can close the app and resume later.",
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextSecondary,
    )
}

@Composable
private fun Downloading(state: BootState.Downloading, onPause: () -> Unit) {
    val fraction = if (state.total == 0L) 0f
    else (state.bytes.toFloat() / state.total).coerceIn(0f, 1f)

    Text(
        if (state.verifying) "Verifying download" else "Downloading ${state.spec.displayName}",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(16.dp))
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().height(6.dp),
        color = Palette.Accent,
        trackColor = Palette.Border,
        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
        drawStopIndicator = {},
    )
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            ModelDownloader.formatGb(state.bytes) + " of " + ModelDownloader.formatGb(state.total),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextSecondary,
        )
        if (!state.verifying && state.bytesPerSecond > 0) {
            Text(
                "%.1f MB/s".format(state.bytesPerSecond / 1_000_000.0),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextSecondary,
            )
        }
    }
    if (!state.verifying) {
        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = onPause, shape = RoundedCornerShape(12.dp)) { Text("Pause") }
    }
}

@Composable
private fun Preparing(state: BootState.Preparing) {
    CircularProgressIndicator(color = Palette.Accent)
    Spacer(Modifier.height(18.dp))
    Text(state.step, style = MaterialTheme.typography.titleMedium)
    state.detail?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
    }
}

@Composable
private fun Failed(message: String, onRetry: () -> Unit) {
    Text(
        message,
        style = MaterialTheme.typography.bodyLarge,
        color = Palette.Danger,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onRetry) { Text("Try again") }
}
