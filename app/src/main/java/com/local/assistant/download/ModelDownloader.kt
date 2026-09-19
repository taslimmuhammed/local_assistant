package com.local.assistant.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface DownloadEvent {
    data class Progress(val bytes: Long, val total: Long, val bytesPerSecond: Long) : DownloadEvent
    data object Verifying : DownloadEvent
    data class Done(val file: File) : DownloadEvent
    data class Failed(val reason: String, val retryable: Boolean) : DownloadEvent
}

/**
 * Resumable download of a multi-gigabyte model bundle.
 *
 * Files this size do not survive a single uninterrupted session on a phone, so
 * resume is the normal path rather than an error path. Bytes land in a `.part`
 * file which is only renamed into place after the SHA-256 matches, so a partial or
 * corrupt download can never be mistaken for a usable model.
 */
class ModelDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // No read timeout: a slow link on a big file is not a failure.
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun modelsDir(): File =
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun fileFor(spec: ModelSpec) = File(modelsDir(), spec.fileName)

    private fun partFor(spec: ModelSpec) = File(modelsDir(), "${spec.fileName}.part")

    /**
     * Whether a usable model sits at the expected path.
     *
     * An exact size match means it came from our own verified download. Anything
     * else is accepted only if it actually looks like a LiteRT-LM bundle, which is
     * what lets a file pushed by hand — say, one already downloaded by AI Edge
     * Gallery — be adopted even though its size will not match this spec.
     */
    fun isInstalled(spec: ModelSpec): Boolean {
        val f = fileFor(spec)
        if (!f.exists()) return false
        if (f.length() == spec.sizeBytes) return true
        return isLiteRtLmBundle(f).also {
            if (it) Log.i(TAG, "Adopting sideloaded ${f.name} (${f.length()} bytes)")
        }
    }

    /**
     * Checks the file's magic number.
     *
     * A `.litertlm` bundle starts with the ASCII bytes `LITERTLM`. Worth reading
     * before handing a path to the engine: a truncated download or an HTML error
     * page saved under the right name would otherwise surface as a native crash
     * inside the runtime rather than something we can explain.
     */
    fun isLiteRtLmBundle(file: File): Boolean = try {
        file.inputStream().use { stream ->
            val header = ByteArray(MAGIC.size)
            stream.read(header) == MAGIC.size && header.contentEquals(MAGIC)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read header of ${file.name}", e)
        false
    }

    fun partialBytes(spec: ModelSpec): Long = partFor(spec).let { if (it.exists()) it.length() else 0L }

    fun delete(spec: ModelSpec) {
        fileFor(spec).delete()
        partFor(spec).delete()
    }

    fun download(spec: ModelSpec): Flow<DownloadEvent> = flow {
        val target = fileFor(spec)
        if (isInstalled(spec)) {
            emit(DownloadEvent.Done(target))
            return@flow
        }

        val part = partFor(spec)
        var existing = if (part.exists()) part.length() else 0L

        // A partial larger than the expected total means we are resuming against a
        // different file than the one that produced it. Start over rather than
        // splicing two unrelated byte ranges together.
        if (existing > spec.sizeBytes) {
            Log.w(TAG, "Discarding oversized partial (${part.length()} > ${spec.sizeBytes})")
            part.delete()
            existing = 0L
        }

        val free = modelsDir().usableSpace
        val needed = spec.sizeBytes - existing
        if (free < needed + SPACE_HEADROOM) {
            emit(
                DownloadEvent.Failed(
                    "Not enough space. Need ${formatGb(needed + SPACE_HEADROOM)}, " +
                        "have ${formatGb(free)} free.",
                    retryable = false,
                )
            )
            return@flow
        }

        val request = Request.Builder()
            .url(spec.url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(
                        DownloadEvent.Failed(
                            "Server returned ${response.code}.",
                            retryable = response.code >= 500 || response.code == 429,
                        )
                    )
                    return@flow
                }

                // If we asked for a range and got 200 rather than 206, the server is
                // sending the whole file; our partial is meaningless.
                if (existing > 0 && response.code != 206) {
                    Log.w(TAG, "Range ignored by server; restarting from zero")
                    part.delete()
                    existing = 0L
                }

                val body = response.body ?: run {
                    emit(DownloadEvent.Failed("Empty response body.", retryable = true))
                    return@flow
                }

                RandomAccessFile(part, "rw").use { out ->
                    out.seek(existing)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = existing
                    var lastEmit = 0L
                    var windowStart = System.currentTimeMillis()
                    var windowBytes = 0L
                    var rate = 0L

                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            windowBytes += read

                            val now = System.currentTimeMillis()
                            val windowMs = now - windowStart
                            if (windowMs >= RATE_WINDOW_MS) {
                                rate = windowBytes * 1000 / windowMs
                                windowBytes = 0
                                windowStart = now
                            }
                            if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                                lastEmit = now
                                emit(DownloadEvent.Progress(downloaded, spec.sizeBytes, rate))
                            }
                        }
                    }
                    emit(DownloadEvent.Progress(downloaded, spec.sizeBytes, rate))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download interrupted", e)
            emit(DownloadEvent.Failed(e.message ?: "Network error.", retryable = true))
            return@flow
        }

        if (part.length() != spec.sizeBytes) {
            emit(
                DownloadEvent.Failed(
                    "Incomplete: got ${part.length()} of ${spec.sizeBytes} bytes.",
                    retryable = true,
                )
            )
            return@flow
        }

        emit(DownloadEvent.Verifying)
        val actual = sha256(part)
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            Log.e(TAG, "Checksum mismatch: expected ${spec.sha256}, got $actual")
            part.delete()
            emit(
                DownloadEvent.Failed(
                    "The downloaded file did not verify. It has been deleted; try again.",
                    retryable = true,
                )
            )
            return@flow
        }

        if (!isLiteRtLmBundle(part)) {
            part.delete()
            emit(
                DownloadEvent.Failed(
                    "That file is not a LiteRT-LM model bundle.",
                    retryable = false,
                )
            )
            return@flow
        }

        if (!part.renameTo(target)) {
            emit(DownloadEvent.Failed("Could not move the model into place.", retryable = false))
            return@flow
        }
        emit(DownloadEvent.Done(target))
    }.flowOn(Dispatchers.IO)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ModelDownloader"

        /** ASCII "LITERTLM", the first eight bytes of every bundle. */
        private val MAGIC = "LITERTLM".toByteArray(Charsets.US_ASCII)
        private const val BUFFER_SIZE = 1 shl 16
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val RATE_WINDOW_MS = 1000L

        /** Leave room so a full download does not wedge the device at zero free space. */
        private const val SPACE_HEADROOM = 300L * 1024 * 1024

        fun formatGb(bytes: Long): String = "%.2f GB".format(bytes / 1_000_000_000.0)
    }
}
