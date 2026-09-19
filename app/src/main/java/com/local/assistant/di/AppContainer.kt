package com.local.assistant.di

import android.content.Context
import com.local.assistant.context.CalibrationProbe
import com.local.assistant.context.ChatController
import com.local.assistant.context.PromptAssembler
import com.local.assistant.context.Summarizer
import com.local.assistant.data.AppSettings
import com.local.assistant.data.AssistantDatabase
import com.local.assistant.download.ModelDownloadManager
import com.local.assistant.download.ModelDownloader
import com.local.assistant.llm.LlmEngine
import com.local.assistant.voice.TranscriptionSource
import com.local.assistant.voice.UnavailableTranscriptionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Manual dependency injection.
 *
 * Hilt would normally do this, but its processor runs on KSP and no KSP release
 * yet supports Kotlin 2.4, which LiteRT-LM 0.17.1 requires. The graph here is a
 * handful of objects built once, so wiring it by hand costs less than working
 * around that.
 */
class AppContainer(context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = AppSettings(context)
    val database = AssistantDatabase(context)
    val downloader = ModelDownloader(context)
    val downloadManager = ModelDownloadManager(context)

    val engine = LlmEngine()
    val calibrationProbe = CalibrationProbe(engine)
    val summarizer = Summarizer(engine)
    val promptAssembler = PromptAssembler()

    val cacheDir: File = File(context.cacheDir, "litertlm").apply { mkdirs() }

    /** Phase 2 swaps this for the Moonshine-backed implementation. */
    val transcriptionSource: TranscriptionSource = UnavailableTranscriptionSource()

    val chatController = ChatController(
        engine = engine,
        db = database,
        promptAssembler = promptAssembler,
        summarizer = summarizer,
        scope = appScope,
    )
}
