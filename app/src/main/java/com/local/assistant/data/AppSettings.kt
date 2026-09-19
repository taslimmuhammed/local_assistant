package com.local.assistant.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.local.assistant.llm.InferenceBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val modelId: String?,
    val backend: InferenceBackend,
    val speculativeDecoding: Boolean,
    val wifiOnlyDownload: Boolean,
    /**
     * Result of the calibration probe, in tokens. Null until it has run.
     * Stored against [calibratedForModel] so swapping models forces a re-measure.
     */
    val usableCeiling: Int?,
    val calibratedForModel: String?,
    val requestedMaxTokens: Int,
)

class AppSettings(private val context: Context) {

    val flow: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = Settings(
        modelId = this[KEY_MODEL_ID],
        backend = this[KEY_BACKEND]?.let { runCatching { InferenceBackend.valueOf(it) }.getOrNull() }
            ?: InferenceBackend.GPU,
        speculativeDecoding = this[KEY_SPECULATIVE] ?: true,
        wifiOnlyDownload = this[KEY_WIFI_ONLY] ?: true,
        usableCeiling = this[KEY_USABLE_CEILING],
        calibratedForModel = this[KEY_CALIBRATED_FOR],
        requestedMaxTokens = this[KEY_REQUESTED_MAX] ?: DEFAULT_REQUESTED_MAX,
    )

    suspend fun setModelId(id: String) =
        context.dataStore.edit { it[KEY_MODEL_ID] = id }

    suspend fun setBackend(backend: InferenceBackend) =
        context.dataStore.edit { it[KEY_BACKEND] = backend.name }

    suspend fun setSpeculativeDecoding(enabled: Boolean) =
        context.dataStore.edit { it[KEY_SPECULATIVE] = enabled }

    suspend fun setWifiOnlyDownload(enabled: Boolean) =
        context.dataStore.edit { it[KEY_WIFI_ONLY] = enabled }

    /** Clamped: asking past the bundle's ceiling fails mid-conversation, not at init. */
    suspend fun setRequestedMaxTokens(tokens: Int) =
        context.dataStore.edit {
            it[KEY_REQUESTED_MAX] = tokens.coerceIn(1_024, MAX_REQUESTED)
        }

    suspend fun saveCalibration(modelId: String, usableCeiling: Int) =
        context.dataStore.edit {
            it[KEY_USABLE_CEILING] = usableCeiling
            it[KEY_CALIBRATED_FOR] = modelId
        }

    suspend fun clearCalibration() = context.dataStore.edit {
        it.remove(KEY_USABLE_CEILING)
        it.remove(KEY_CALIBRATED_FOR)
    }

    companion object {
        /**
         * What we ask the engine for. The bundle may serve less, which is the whole
         * reason the calibration probe exists, so treat this as a ceiling on the
         * request rather than a promise about the window.
         *
         * The Gemma 4 E4B bundle is documented at 32k. We ask for half of that by
         * default because the cost of a larger window is not really memory -- the
         * KV cache is unusually cheap on this architecture, see [MAX_REQUESTED] --
         * but decode speed, which degrades as the full-attention layers' cache
         * fills. 16k leaves compaction rare while keeping generation quick.
         */
        const val DEFAULT_REQUESTED_MAX = 16_384

        /**
         * The bundle's documented ceiling. Asking for more than the bundle serves is
         * silently accepted and then fails mid-conversation, so this is a hard cap.
         *
         * Memory cost is modest: Gemma 4 E4B uses grouped-query attention with 2 KV
         * heads and a 256 head dim, 18 of its 42 layers share KV, and five in six of
         * the rest are sliding-window at 512. That works out near 1 KiB per token at
         * int8, so even the full 32k is a few hundred MB.
         */
        const val MAX_REQUESTED = 32_768

        val CONTEXT_CHOICES = listOf(4_096, 8_192, 16_384, 32_768)

        private val KEY_MODEL_ID = stringPreferencesKey("model_id")
        private val KEY_BACKEND = stringPreferencesKey("backend")
        private val KEY_SPECULATIVE = booleanPreferencesKey("speculative_decoding")
        private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only_download")
        private val KEY_USABLE_CEILING = intPreferencesKey("usable_ceiling")
        private val KEY_CALIBRATED_FOR = stringPreferencesKey("calibrated_for_model")
        private val KEY_REQUESTED_MAX = intPreferencesKey("requested_max_tokens")
    }
}
