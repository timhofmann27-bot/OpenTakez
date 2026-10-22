package io.github.timhofmann27.opentakez

import android.app.Application
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.engine.device.DeviceEngine
import io.github.timhofmann27.opentakez.engine.device.UpdateResult
import io.github.timhofmann27.opentakez.engine.device.YoutubeDlAndroidRunner
import io.github.timhofmann27.opentakez.engine.device.YtDlpUpdateStore
import io.github.timhofmann27.opentakez.engine.server.ServerEngine
import io.github.timhofmann27.opentakez.ui.ports.AppServices
import io.github.timhofmann27.opentakez.ui.ports.AppServicesProvider
import io.github.timhofmann27.opentakez.work.EngineProvider
import io.github.timhofmann27.opentakez.work.YtDlpUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OpenTakezApp : Application(), AppServicesProvider {
    lateinit var container: AppContainer
        private set
    override lateinit var services: AppServices
        private set

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val updates = container.ytDlpUpdateStore
        val device = DeviceEngine(
            YoutubeDlAndroidRunner(this),
            object : YtDlpUpdateStore {
                override suspend fun lastUpdateMillis(): Long? = updates.lastUpdateMillis()
                override suspend fun setLastUpdateMillis(millis: Long) = updates.setLastUpdateMillis(millis)
            },
        )
        container.engines = EngineProvider { engine ->
            when (engine) {
                Engine.DEVICE -> device
                Engine.SERVER -> {
                    val config = container.settings.serverConfig() ?: throw EngineError.NeedsServer()
                    ServerEngine(config)
                }
            }
        }
        container.ytDlpUpdater = YtDlpUpdater {
            when (val result = device.updateYtDlp()) {
                is UpdateResult.Updated -> result.version
                is UpdateResult.AlreadyLatest -> null
                is UpdateResult.Failed -> throw EngineError.Network(result.message)
            }
        }
        services = RealAppServices(container, device)
        container.start(appScope)
        appScope.launch(Dispatchers.IO) { device.initialize() }
    }
}
