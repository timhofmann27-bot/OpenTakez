package io.github.timhofmann27.opentakez.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.timhofmann27.opentakez.ui.downloads.DownloadsViewModel
import io.github.timhofmann27.opentakez.ui.history.HistoryViewModel
import io.github.timhofmann27.opentakez.ui.home.HomeViewModel
import io.github.timhofmann27.opentakez.ui.ports.AppServices
import io.github.timhofmann27.opentakez.ui.settings.SettingsViewModel

class UiViewModelFactory(private val services: AppServices) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        HomeViewModel::class.java ->
            HomeViewModel(services.resolve, services.downloads, services.settings, services.routing)
        DownloadsViewModel::class.java -> DownloadsViewModel(services.downloads)
        HistoryViewModel::class.java -> HistoryViewModel(services.downloads)
        SettingsViewModel::class.java -> SettingsViewModel(services.settings)
        else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    } as T
}
