package com.local.assistant.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.assistant.LocalAssistantApp
import com.local.assistant.data.Settings
import com.local.assistant.download.DownloadStatus
import com.local.assistant.download.ModelCatalog
import com.local.assistant.download.ModelSpec
import com.local.assistant.llm.InferenceBackend
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface BootState {
    data object Checking : BootState

    data class NeedsModel(
        val spec: ModelSpec,
        val resumableBytes: Long,
        val error: String? = null,
    ) : BootState

    data class Downloading(
        val spec: ModelSpec,
        val bytes: Long,
        val total: Long,
        val bytesPerSecond: Long,
        val verifying: Boolean = false,
    ) : BootState

    /** Engine initialisation and, on first run for a model, the calibration probe. */
    data class Preparing(val step: String, val detail: String? = null) : BootState

    data class Ready(
        val backend: InferenceBackend,
        val usableCeiling: Int,
        val observedCeiling: Int?,
    ) : BootState

    data class Failed(val message: String) : BootState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as LocalAssistantApp).container

    private val _state = MutableStateFlow<BootState>(BootState.Checking)
    val state: StateFlow<BootState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    init {
        viewModelScope.launch { boot() }
    }

    private suspend fun boot() {
        _state.value = BootState.Checking
        val settings = container.settings.flow.first()
        val spec = ModelCatalog.byId(settings.modelId)

        if (!container.downloader.isInstalled(spec)) {
            _state.value = BootState.NeedsModel(spec, container.downloader.partialBytes(spec))
            return
        }
        prepare(spec, settings)
    }

    /**
     * Hands the transfer to WorkManager rather than running it here.
     *
     * Three gigabytes takes long enough that the user will lock the screen or switch
     * apps mid-way, and a ViewModel-scoped download would die there. The worker
     * survives that; this only watches its progress.
     */
    fun startDownload(spec: ModelSpec) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val settings = container.settings.flow.first()
            container.settings.setModelId(spec.id)
            container.downloadManager.start(spec, wifiOnly = settings.wifiOnlyDownload)

            container.downloadManager.status.collect { status ->
                when (status) {
                    DownloadStatus.Idle -> _state.value = BootState.NeedsModel(
                        spec, container.downloader.partialBytes(spec)
                    )

                    DownloadStatus.Enqueued -> _state.value = BootState.Downloading(
                        spec, container.downloader.partialBytes(spec), spec.sizeBytes, 0
                    )

                    is DownloadStatus.Running -> _state.value = BootState.Downloading(
                        spec = spec,
                        bytes = status.bytes,
                        total = if (status.total > 0) status.total else spec.sizeBytes,
                        bytesPerSecond = status.bytesPerSecond,
                        verifying = status.verifying,
                    )

                    DownloadStatus.Succeeded -> {
                        // A new model file invalidates the old measurement.
                        container.settings.clearCalibration()
                        prepare(spec, container.settings.flow.first())
                        return@collect
                    }

                    is DownloadStatus.Failed -> {
                        _state.value = BootState.NeedsModel(
                            spec, container.downloader.partialBytes(spec), status.reason
                        )
                        return@collect
                    }
                }
            }
        }
    }

    fun pauseDownload() {
        downloadJob?.cancel()
        downloadJob = null
        container.downloadManager.cancel()
        val spec = (_state.value as? BootState.Downloading)?.spec ?: return
        _state.value = BootState.NeedsModel(spec, container.downloader.partialBytes(spec))
    }

    fun retry() {
        viewModelScope.launch { boot() }
    }

    /** Wipes the measured ceiling and measures again. Exposed from Settings. */
    fun recalibrate() {
        viewModelScope.launch {
            container.settings.clearCalibration()
            boot()
        }
    }

    private suspend fun prepare(spec: ModelSpec, settings: Settings) {
        _state.value = BootState.Preparing("Loading ${spec.displayName}", "First load takes longer")

        val modelFile = container.downloader.fileFor(spec)
        val init = container.engine.initialize(
            modelFile = modelFile,
            cacheDir = container.cacheDir,
            maxNumTokens = settings.requestedMaxTokens,
            preferred = settings.backend,
            enableSpeculativeDecoding = settings.speculativeDecoding,
        )

        val backend = init.getOrElse { error ->
            _state.value = BootState.Failed(
                "Could not load the model: ${error.message ?: "unknown error"}"
            )
            return
        }

        var observed: Int? = null
        val cached = settings.usableCeiling?.takeIf { settings.calibratedForModel == spec.id }

        val usableCeiling = cached ?: run {
            _state.value = BootState.Preparing(
                "Measuring usable context",
                "Runs once per model"
            )
            try {
                val result = container.calibrationProbe.run(
                    requestedMax = settings.requestedMaxTokens,
                    onProgress = { tokens ->
                        _state.value = BootState.Preparing(
                            "Measuring usable context",
                            "$tokens tokens so far"
                        )
                    },
                )
                observed = result.observedCeiling
                container.settings.saveCalibration(spec.id, result.usableCeiling)
                result.usableCeiling
            } catch (t: Throwable) {
                // A failed probe is not fatal; fall back to a deliberately small
                // window. Under-using the context is survivable, overrunning it is not.
                Log.e(TAG, "Calibration failed; using conservative fallback", t)
                FALLBACK_CEILING
            }
        }

        _state.value = BootState.Preparing("Restoring your conversation")
        container.chatController.start(usableCeiling)

        _state.value = BootState.Ready(backend, usableCeiling, observed)
    }

    override fun onCleared() {
        super.onCleared()
        container.chatController.close()
    }

    companion object {
        private const val TAG = "AppViewModel"
        private const val FALLBACK_CEILING = 2048
    }
}
