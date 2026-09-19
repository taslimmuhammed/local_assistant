package com.local.assistant

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.context.CalibrationProbe
import com.local.assistant.context.ChatController
import com.local.assistant.context.PromptAssembler
import com.local.assistant.context.Summarizer
import com.local.assistant.context.TurnEvent
import com.local.assistant.download.ModelCatalog
import com.local.assistant.download.ModelDownloader
import com.local.assistant.llm.LlmEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The test the whole context design exists to pass.
 *
 * Drives a long conversation past the point where the window must have been
 * recycled several times, and asserts that no turn ever failed. Requires the model
 * bundle to be present on the device; it is skipped otherwise rather than failing,
 * since the file is far too large to ship with the test APK.
 *
 * Push the model first, then run:
 * ```
 * adb push gemma-4-E4B-it-gpu.litertlm \
 *   /sdcard/Android/data/com.local.assistant/files/models/
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.local.assistant.ContextSoakTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ContextSoakTest {

    private lateinit var engine: LlmEngine
    private lateinit var controller: ChatController

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val spec = ModelCatalog.default
        val downloader = ModelDownloader(context)

        assumeTrue(
            "Model ${spec.fileName} not present on device; skipping soak test.",
            downloader.fileFor(spec).exists()
        )

        engine = LlmEngine()
        val started = engine.initialize(
            modelFile = downloader.fileFor(spec),
            cacheDir = File(context.cacheDir, "litertlm").apply { mkdirs() },
            maxNumTokens = 8192,
        )
        assumeTrue("Engine failed to start: ${started.exceptionOrNull()}", started.isSuccess)

        controller = ChatController(
            engine = engine,
            promptAssembler = PromptAssembler(),
            summarizer = Summarizer(engine),
        )

        val calibration = CalibrationProbe(engine).run(requestedMax = 8192)
        Log.i(TAG, "Calibration: $calibration")
        controller.start(calibration.usableCeiling)
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
        if (::engine.isInitialized) engine.close()
    }

    @Test
    fun longConversationNeverRunsOutOfContext() = runBlocking {
        var failures = 0
        var compactions = 0
        var recoveries = 0

        // A fact planted early, to check it survives being summarised rather than
        // simply falling off the end of the window.
        send("Remember this: the project codename is Tamarind.")

        repeat(TURNS) { i ->
            val events = send(
                "Turn $i. Give me one sentence about the number ${i * 7 % 97}."
            )
            events.forEach { event ->
                when (event) {
                    is TurnEvent.Failed -> {
                        failures++
                        Log.e(TAG, "Turn $i failed: ${event.error}")
                    }

                    is TurnEvent.Compacted -> compactions++
                    is TurnEvent.Recovered -> recoveries++
                    else -> Unit
                }
            }

            val state = controller.contextState.value
            assertTrue(
                "window overran at turn $i: ${state.usedTokens}/${state.usableCeiling}",
                state.usedTokens <= state.usableCeiling
            )
        }

        Log.i(TAG, "Soak done: compactions=$compactions recoveries=$recoveries failures=$failures")

        assertTrue("$failures turns failed outright", failures == 0)
        assertTrue(
            "compaction never ran, so the test never reached the interesting case",
            compactions > 0
        )
        // The safety net is a net. If it is carrying the design, the watermark is
        // set too high.
        assertTrue("safety net fired $recoveries times; watermark is too high", recoveries <= 1)

        val recall = send("What is the project codename? Answer with one word.").text()
        assertTrue(
            "planted fact did not survive compaction, got: $recall",
            recall.contains("Tamarind", ignoreCase = true)
        )
    }

    private suspend fun send(text: String) = controller.send(text).toList()

    private fun List<TurnEvent>.text(): String =
        filterIsInstance<TurnEvent.Complete>().joinToString("") { it.fullText }

    companion object {
        private const val TAG = "ContextSoakTest"

        /** Enough turns to force several full window recycles on a small ceiling. */
        private const val TURNS = 300
    }
}
