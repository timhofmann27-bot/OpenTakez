package io.github.timhofmann27.opentakez

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.work.WorkManager
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.MediaInfo
import io.github.timhofmann27.opentakez.data.AppDatabase
import io.github.timhofmann27.opentakez.data.DownloadEntity
import io.github.timhofmann27.opentakez.data.DownloadRepository
import io.github.timhofmann27.opentakez.data.KeystoreTokenCipher
import io.github.timhofmann27.opentakez.data.SettingsRepository
import io.github.timhofmann27.opentakez.data.TokenCipher
import io.github.timhofmann27.opentakez.data.YtDlpUpdateStoreAdapter
import io.github.timhofmann27.opentakez.storage.AndroidFileSaver
import io.github.timhofmann27.opentakez.storage.FileSaver
import io.github.timhofmann27.opentakez.work.CacheWorkDirFactory
import io.github.timhofmann27.opentakez.work.DownloadRunner
import io.github.timhofmann27.opentakez.work.EngineProvider
import io.github.timhofmann27.opentakez.work.Notifications
import io.github.timhofmann27.opentakez.work.ResourceTexts
import io.github.timhofmann27.opentakez.work.StatFsFreeSpaceChecker
import io.github.timhofmann27.opentakez.work.WorkManagerBackend
import io.github.timhofmann27.opentakez.work.WorkScheduler
import io.github.timhofmann27.opentakez.work.YtDlpUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container, created once by [OpenTakezApp]. Everything heavy is lazy.
 *
 * The integrator sets [engines] and [ytDlpUpdater] right after construction (in
 * `OpenTakezApp.onCreate`), before any worker can run. Until then using them fails loudly.
 */
class AppContainer(
    private val context: Context,
    private val tokenCipher: TokenCipher = KeystoreTokenCipher(),
) {
    private val appContext = context.applicationContext

    /** Device and server engines; wired by the integrator. */
    @Volatile
    var engines: EngineProvider = EngineProvider { error("AppContainer.engines is not wired") }

    /** `DeviceEngine.updateYtDlp()`; wired by the integrator. */
    @Volatile
    var ytDlpUpdater: YtDlpUpdater = YtDlpUpdater { error("AppContainer.ytDlpUpdater is not wired") }

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }

    val repository: DownloadRepository by lazy { DownloadRepository(database.downloadDao()) }

    val settings: SettingsRepository by lazy {
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { appContext.preferencesDataStoreFile("settings") },
        )
        SettingsRepository(store, tokenCipher)
    }

    /** For `DeviceEngine`'s `YtDlpUpdateStore`: wrap its two methods around this adapter. */
    val ytDlpUpdateStore: YtDlpUpdateStoreAdapter by lazy { YtDlpUpdateStoreAdapter.from(settings) }

    val fileSaver: FileSaver by lazy { AndroidFileSaver(appContext) }

    val notifications: Notifications by lazy { Notifications(appContext, ResourceTexts(appContext)) }

    val scheduler: WorkScheduler by lazy {
        WorkScheduler(WorkManagerBackend(WorkManager.getInstance(appContext)))
    }

    val downloadRunner: DownloadRunner by lazy {
        DownloadRunner(
            repository = repository,
            engines = EngineProvider { engines.engineFor(it) },
            saver = fileSaver,
            workDirs = CacheWorkDirFactory(appContext.cacheDir),
            freeSpace = StatFsFreeSpaceChecker(),
        )
    }

    /** Creates the row and queues the job. Returns the row so the UI can navigate to it. */
    suspend fun startDownload(info: MediaInfo, option: DownloadOption, engine: Engine): DownloadEntity {
        val row = repository.create(info, option, engine)
        scheduler.enqueue(row.id, engine)
        return row
    }

    /** Marks the row first, so a job that never started is cancelled too. */
    suspend fun cancelDownload(id: String) {
        repository.markCancelled(id)
        scheduler.cancel(id)
    }

    /** Failed or cancelled -> queued again. False when the row is gone or not retryable. */
    suspend fun retryDownload(id: String): Boolean {
        val row = repository.get(id) ?: return false
        if (!repository.requeue(id)) return false
        scheduler.enqueue(id, row.engine)
        return true
    }

    /** Called once from [OpenTakezApp.onCreate]; never throws. */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                notifications.ensureChannel()
                scheduler.scheduleYtDlpUpdates()
            }
        }
    }
}
