package io.github.timhofmann27.opentakez.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import io.github.timhofmann27.opentakez.OpenTakezApp
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Weekly yt-dlp update on an unmetered network; the real work is the [YtDlpUpdater]'s. */
class YtDlpUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val updater = (applicationContext as OpenTakezApp).container.ytDlpUpdater
        return try {
            updater.update()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Retry a couple of times with backoff, then wait for the next weekly run.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "ytdlp-update"
        private const val MAX_ATTEMPTS = 3

        fun periodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<YtDlpUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
    }
}
