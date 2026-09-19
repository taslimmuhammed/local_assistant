package com.local.assistant.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** A snapshot of the download, flattened out of [WorkInfo] for the UI. */
sealed interface DownloadStatus {
    data object Idle : DownloadStatus
    data object Enqueued : DownloadStatus
    data class Running(
        val bytes: Long,
        val total: Long,
        val bytesPerSecond: Long,
        val verifying: Boolean,
    ) : DownloadStatus

    data object Succeeded : DownloadStatus
    data class Failed(val reason: String) : DownloadStatus
}

class ModelDownloadManager(context: Context) {

    private val workManager = WorkManager.getInstance(context)

    fun start(spec: ModelSpec, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(Data.Builder().putString(ModelDownloadWorker.KEY_MODEL_ID, spec.id).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                    )
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        // REPLACE rather than KEEP: choosing a different model in Settings should
        // supersede an in-flight download instead of silently doing nothing.
        workManager.enqueueUniqueWork(
            ModelDownloadWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel() = workManager.cancelUniqueWork(ModelDownloadWorker.WORK_NAME)

    val status: Flow<DownloadStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.WORK_NAME)
            .map { infos -> infos.firstOrNull().toStatus() }

    private fun WorkInfo?.toStatus(): DownloadStatus = when (this?.state) {
        null -> DownloadStatus.Idle
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadStatus.Enqueued
        WorkInfo.State.RUNNING -> DownloadStatus.Running(
            bytes = progress.getLong(ModelDownloadWorker.KEY_BYTES, 0),
            total = progress.getLong(ModelDownloadWorker.KEY_TOTAL, 0),
            bytesPerSecond = progress.getLong(ModelDownloadWorker.KEY_RATE, 0),
            verifying = progress.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false),
        )

        WorkInfo.State.SUCCEEDED -> DownloadStatus.Succeeded
        WorkInfo.State.FAILED -> DownloadStatus.Failed(
            outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Download failed."
        )

        WorkInfo.State.CANCELLED -> DownloadStatus.Idle
    }
}
