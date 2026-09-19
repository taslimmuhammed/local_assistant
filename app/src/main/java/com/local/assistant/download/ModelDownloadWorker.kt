package com.local.assistant.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.local.assistant.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.transformWhile

/**
 * Runs the model download as foreground work.
 *
 * A three-gigabyte transfer outlives the screen being switched off and the user
 * wandering off to another app, so it cannot live in a ViewModel scope. WorkManager
 * keeps it alive across those, and the notification is what buys the process the
 * right to keep running.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val spec = ModelCatalog.byId(inputData.getString(KEY_MODEL_ID))
        val downloader = ModelDownloader(applicationContext)

        promote(spec, 0, spec.sizeBytes, verifying = false)

        var failure: DownloadEvent.Failed? = null

        downloader.download(spec)
            .transformWhile { event ->
                emit(event)
                event !is DownloadEvent.Done && event !is DownloadEvent.Failed
            }
            .collect { event ->
                when (event) {
                    is DownloadEvent.Progress -> {
                        setProgress(
                            Data.Builder()
                                .putLong(KEY_BYTES, event.bytes)
                                .putLong(KEY_TOTAL, event.total)
                                .putLong(KEY_RATE, event.bytesPerSecond)
                                .putBoolean(KEY_VERIFYING, false)
                                .build()
                        )
                        promote(spec, event.bytes, event.total, verifying = false)
                    }

                    DownloadEvent.Verifying -> {
                        setProgress(
                            Data.Builder()
                                .putLong(KEY_BYTES, spec.sizeBytes)
                                .putLong(KEY_TOTAL, spec.sizeBytes)
                                .putBoolean(KEY_VERIFYING, true)
                                .build()
                        )
                        promote(spec, spec.sizeBytes, spec.sizeBytes, verifying = true)
                    }

                    is DownloadEvent.Done -> Unit
                    is DownloadEvent.Failed -> failure = event
                }
            }

        return when {
            failure == null -> Result.success()
            // Retrying a transient network drop is free: the partial file is on
            // disk and the next attempt resumes from where it stopped.
            failure.retryable && runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure(
                Data.Builder().putString(KEY_ERROR, failure.reason).build()
            )
        }
    }

    /**
     * Moves the worker to the foreground, tolerating refusal.
     *
     * Foreground-service rules tighten with most Android releases: a missing
     * manifest type, a quota, or the wrong process state all raise here. None of
     * that should end the download, which works perfectly well as ordinary
     * background work minus the notification, so a refusal is logged and ignored.
     */
    private suspend fun promote(spec: ModelSpec, bytes: Long, total: Long, verifying: Boolean) {
        try {
            setForeground(foregroundInfo(spec, bytes, total, verifying))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!foregroundRefused) {
                foregroundRefused = true
                Log.w(TAG, "Foreground promotion refused; downloading in the background", e)
            }
        }
    }

    private var foregroundRefused = false

    private fun foregroundInfo(
        spec: ModelSpec,
        bytes: Long,
        total: Long,
        verifying: Boolean,
    ): ForegroundInfo {
        ensureChannel()
        val percent = if (total == 0L) 0 else ((bytes * 100) / total).toInt()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(
                if (verifying) "Verifying ${spec.displayName}"
                else "Downloading ${spec.displayName}"
            )
            .setContentText(
                if (verifying) "Checking the file is intact"
                else "${ModelDownloader.formatGb(bytes)} of ${ModelDownloader.formatGb(total)}"
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, verifying)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getString(R.string.download_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ModelDownloadWorker"
        const val WORK_NAME = "model-download"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_RATE = "rate"
        const val KEY_VERIFYING = "verifying"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 4711
        private const val MAX_ATTEMPTS = 5
    }
}
