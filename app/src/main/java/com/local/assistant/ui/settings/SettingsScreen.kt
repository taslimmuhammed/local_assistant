package com.local.assistant.ui.settings

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.assistant.LocalAssistantApp
import com.local.assistant.data.AppSettings
import com.local.assistant.data.Settings
import com.local.assistant.download.ModelCatalog
import com.local.assistant.llm.InferenceBackend
import com.local.assistant.ui.BootState
import com.local.assistant.ui.theme.Palette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LocalAssistantApp).container

    val settings: StateFlow<Settings?> = container.settings.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setBackend(backend: InferenceBackend) =
        viewModelScope.launch { container.settings.setBackend(backend) }

    fun setSpeculative(enabled: Boolean) =
        viewModelScope.launch { container.settings.setSpeculativeDecoding(enabled) }

    fun setWifiOnly(enabled: Boolean) =
        viewModelScope.launch { container.settings.setWifiOnlyDownload(enabled) }

    /**
     * Changing the requested window invalidates the measured ceiling, so the probe
     * has to run again against the new request.
     */
    fun setRequestedMaxTokens(tokens: Int) = viewModelScope.launch {
        container.settings.setRequestedMaxTokens(tokens)
        container.settings.clearCalibration()
    }

    fun modelName(id: String?) = ModelCatalog.byId(id).displayName
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    bootState: BootState,
    onBack: () -> Unit,
    onRecalibrate: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Palette.White,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.White),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { SectionLabel("Model") }
            item {
                InfoRow("Loaded", viewModel.modelName(settings?.modelId))
            }
            item {
                val backendLabel = (bootState as? BootState.Ready)?.backend?.name ?: "—"
                InfoRow(
                    "Running on",
                    backendLabel,
                    hint = if (backendLabel == "CPU") {
                        "GPU failed to initialise, so this is the fallback. Expect slower replies."
                    } else null,
                )
            }
            item {
                ToggleRow(
                    title = "Multi-token prediction",
                    subtitle = "Roughly doubles generation speed on the GPU. Takes effect next launch.",
                    checked = settings?.speculativeDecoding ?: true,
                    onChange = viewModel::setSpeculative,
                )
            }

            item { Spacer(Modifier.height(16.dp)); SectionLabel("Context") }
            item {
                val ready = bootState as? BootState.Ready
                InfoRow(
                    "Usable window",
                    ready?.usableCeiling?.let { "$it tokens" } ?: "—",
                    hint = "Measured on this device. The runtime accepts a larger " +
                        "request than the model bundle actually serves, so this is the " +
                        "number everything is budgeted against.",
                )
            }
            item {
                (bootState as? BootState.Ready)?.observedCeiling?.let {
                    InfoRow("Measured ceiling", "$it tokens")
                }
            }
            item {
                ContextSizeRow(
                    selected = settings?.requestedMaxTokens ?: AppSettings.DEFAULT_REQUESTED_MAX,
                    onSelect = {
                        viewModel.setRequestedMaxTokens(it)
                        onRecalibrate()
                    },
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    TextButton(onClick = onRecalibrate) { Text("Measure again") }
                }
            }

            item { Spacer(Modifier.height(16.dp)); SectionLabel("Downloads") }
            item {
                ToggleRow(
                    title = "Wi-Fi only",
                    subtitle = "Model files are around 3 GB.",
                    checked = settings?.wifiOnlyDownload ?: true,
                    onChange = viewModel::setWifiOnly,
                )
            }

            item { Spacer(Modifier.height(16.dp)); SectionLabel("Privacy") }
            item {
                InfoRow(
                    "Data location",
                    "This device only",
                    hint = "Conversations, memory and the model file never leave the " +
                        "phone. Network access is used only to download the model.",
                )
            }
        }
    }
}

/**
 * The window we ask the engine for.
 *
 * Memory is not the binding constraint here -- Gemma 4 E4B's grouped-query
 * attention, shared KV layers and mostly sliding-window attention put the cache
 * near a kilobyte per token -- but decode slows as the full-attention layers fill,
 * so bigger is not automatically better.
 */
@Composable
private fun ContextSizeRow(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text("Requested window", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(2.dp))
        Text(
            "Larger keeps more of the conversation verbatim and summarises less " +
                "often, at some cost to generation speed. Re-measures on change.",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppSettings.CONTEXT_CHOICES.forEach { tokens ->
                val active = tokens == selected
                Surface(
                    color = if (active) Palette.Accent else Palette.SurfaceMuted,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.clickable { onSelect(tokens) },
                ) {
                    Text(
                        "${tokens / 1024}K",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) Palette.White else Palette.TextPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 14.dp), color = Palette.Border)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextSecondary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun InfoRow(title: String, value: String, hint: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
        }
        hint?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
        }
        HorizontalDivider(Modifier.padding(top = 10.dp), color = Palette.Border)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Palette.White,
                    checkedTrackColor = Palette.Accent,
                ),
            )
        }
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
        HorizontalDivider(Modifier.padding(top = 10.dp), color = Palette.Border)
    }
}
