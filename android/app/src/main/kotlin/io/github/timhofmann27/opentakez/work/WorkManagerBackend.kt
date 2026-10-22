package io.github.timhofmann27.opentakez.work

import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkInfo
import io.github.timhofmann27.opentakez.core.model.JobProgress
import io.github.timhofmann27.opentakez.core.model.Phase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The only class that touches WorkManager's scheduling API. */
class WorkManagerBackend(private val workManager: WorkManager) : WorkBackend {

    override suspend fun pendingCount(queue: String): Int =
        workManager.getWorkInfosForUniqueWorkFlow(queue).first().count { !it.state.isFinished }

    override fun enqueue(queue: String, rowId: String) {
        workManager
            .beginUniqueWork(queue, ExistingWorkPolicy.APPEND_OR_REPLACE, DownloadWorker.request(rowId))
            .enqueue()
    }

    override fun cancel(rowId: String) {
        workManager.cancelAllWorkByTag(DownloadWorker.tagFor(rowId))
    }

    override fun observeProgress(rowId: String): Flow<JobProgress?> =
        workManager.getWorkInfosByTagFlow(DownloadWorker.tagFor(rowId)).map { infos ->
            infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.progress?.toJobProgress()
        }

    override fun schedulePeriodicYtDlpUpdate() {
        workManager.enqueueUniquePeriodicWork(
            YtDlpUpdateWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            YtDlpUpdateWorker.periodicRequest(),
        )
    }
}

private const val KEY_PHASE = "phase"
private const val KEY_FRACTION = "fraction"
private const val KEY_SPEED = "speed"
private const val KEY_ETA = "eta"
private const val UNKNOWN = -1.0

internal fun JobProgress.toData(): Data = Data.Builder()
    .putString(KEY_PHASE, phase.name)
    .putDouble(KEY_FRACTION, fraction ?: UNKNOWN)
    .putDouble(KEY_SPEED, speedBytesPerSecond ?: UNKNOWN)
    .putInt(KEY_ETA, etaSeconds ?: -1)
    .build()

/** Null when the data holds no progress (a fresh worker has not reported yet). */
fun Data.toJobProgress(): JobProgress? {
    val phase = getString(KEY_PHASE)?.let { name -> Phase.entries.firstOrNull { it.name == name } }
        ?: return null
    return JobProgress(
        phase = phase,
        fraction = getDouble(KEY_FRACTION, UNKNOWN).takeIf { it >= 0 },
        speedBytesPerSecond = getDouble(KEY_SPEED, UNKNOWN).takeIf { it >= 0 },
        etaSeconds = getInt(KEY_ETA, -1).takeIf { it >= 0 },
    )
}
