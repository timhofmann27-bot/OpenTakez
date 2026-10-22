package io.github.timhofmann27.opentakez.work

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.timhofmann27.opentakez.OpenTakezApp
import io.github.timhofmann27.opentakez.core.model.JobProgress
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Thin shell around [DownloadRunner]: foreground notification, progress, final notification. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container get() = (applicationContext as OpenTakezApp).container

    // Needed so expedited work also runs on Android 11 and older, and for out-of-quota fallbacks.
    override suspend fun getForegroundInfo(): ForegroundInfo =
        container.notifications.foregroundInfo(rowId(), "", null, id)

    @OptIn(FlowPreview::class)
    override suspend fun doWork(): Result {
        val rowId = inputData.getString(KEY_ROW_ID) ?: return Result.failure()
        val title = container.repository.get(rowId)?.title.orEmpty()
        runCatching { setForeground(container.notifications.foregroundInfo(rowId, title, null, id)) }
            .onFailure { if (it is CancellationException) throw it } // not allowed right now: carry on

        val latest = MutableStateFlow<JobProgress?>(null)
        return coroutineScope {
            val reporter = launch {
                latest.filterNotNull().sample(PROGRESS_INTERVAL_MS).collect { progress ->
                    setProgress(progress.toData())
                    runCatching {
                        setForeground(container.notifications.foregroundInfo(rowId, title, progress, id))
                    }.onFailure { if (it is CancellationException) throw it }
                }
            }
            val outcome = try {
                container.downloadRunner.run(
                    rowId,
                    onProgress = { latest.value = it },
                    stoppedBySystem = ::stoppedBySystem,
                )
            } finally {
                reporter.cancel()
            }
            when (outcome) {
                DownloadRunner.Outcome.DONE -> {
                    container.notifications.showDone(rowId, title)
                    Result.success()
                }
                DownloadRunner.Outcome.FAILED -> {
                    container.notifications.showFailed(rowId, title)
                    Result.failure()
                }
                DownloadRunner.Outcome.NOT_RUNNABLE -> Result.success()
            }
        }
    }

    private fun rowId(): String = inputData.getString(KEY_ROW_ID).orEmpty()

    /**
     * Anything but our own cancel (or an unknown reason) is Android stopping us. Before API 31
     * the reason is not available, so every stop counts as a cancel there.
     */
    private fun stoppedBySystem(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val reason = stopReason
        return reason != WorkInfo.STOP_REASON_CANCELLED_BY_APP &&
            reason != WorkInfo.STOP_REASON_UNKNOWN &&
            reason != WorkInfo.STOP_REASON_NOT_STOPPED
    }

    companion object {
        const val KEY_ROW_ID = "rowId"
        private const val PROGRESS_INTERVAL_MS = 500L

        fun tagFor(rowId: String) = "download-$rowId"

        fun request(rowId: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_ROW_ID to rowId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .addTag(tagFor(rowId))
                .build()
    }
}
