package io.github.timhofmann27.opentakez.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.timhofmann27.opentakez.R
import io.github.timhofmann27.opentakez.ui.components.minTouchTarget
import io.github.timhofmann27.opentakez.ui.downloads.DownloadsScreen
import io.github.timhofmann27.opentakez.ui.downloads.DownloadsViewModel
import io.github.timhofmann27.opentakez.ui.history.AndroidFileActions
import io.github.timhofmann27.opentakez.ui.history.FileActions
import io.github.timhofmann27.opentakez.ui.history.HistoryScreen
import io.github.timhofmann27.opentakez.ui.history.HistoryViewModel
import io.github.timhofmann27.opentakez.ui.home.HomeScreen
import io.github.timhofmann27.opentakez.ui.home.HomeViewModel
import io.github.timhofmann27.opentakez.ui.ports.AppServices
import io.github.timhofmann27.opentakez.ui.ports.ShareInbox
import io.github.timhofmann27.opentakez.ui.settings.SettingsScreen
import io.github.timhofmann27.opentakez.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

enum class Tab(val icon: ImageVector, val label: Int) {
    HOME(Icons.Outlined.Download, R.string.nav_home),
    DOWNLOADS(Icons.AutoMirrored.Outlined.List, R.string.nav_downloads),
    HISTORY(Icons.Outlined.History, R.string.nav_history),
    SETTINGS(Icons.Outlined.Settings, R.string.nav_settings),
}

/** Which runtime permissions to ask for before the first download. Declining never blocks it. */
object DownloadPermissions {
    fun missing(sdkInt: Int, isGranted: (String) -> Boolean): List<String> = buildList {
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU && !isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (sdkInt <= Build.VERSION_CODES.P && !isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

@Composable
fun AppRoot(
    services: AppServices,
    shareInbox: ShareInbox,
    modifier: Modifier = Modifier,
    fileActions: FileActions = AndroidFileActions(LocalContext.current),
    sdkInt: Int = Build.VERSION.SDK_INT,
    isGranted: ((String) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val factory = remember(services) { UiViewModelFactory(services) }
    val homeVm = viewModel<HomeViewModel>(factory = factory)
    val downloadsVm = viewModel<DownloadsViewModel>(factory = factory)
    val historyVm = viewModel<HistoryViewModel>(factory = factory)
    val settingsVm = viewModel<SettingsViewModel>(factory = factory)

    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    val homeState by homeVm.state.collectAsStateWithLifecycle()
    val downloadItems by downloadsVm.items.collectAsStateWithLifecycle()
    val downloadsNotice by downloadsVm.notice.collectAsStateWithLifecycle()
    val runningCount by downloadsVm.runningCount.collectAsStateWithLifecycle()
    val historyItems by historyVm.items.collectAsStateWithLifecycle()
    val historyNotice by historyVm.notice.collectAsStateWithLifecycle()
    val settingsState by settingsVm.state.collectAsStateWithLifecycle()
    val shareEvent by shareInbox.events.collectAsStateWithLifecycle()

    // A share from another app lands on Home: a link opens the preview, no link shows the error.
    LaunchedEffect(shareEvent) {
        shareEvent?.let { event ->
            tab = Tab.HOME
            homeVm.openShared(event.link)
            shareInbox.consume(event)
        }
    }
    LaunchedEffect(homeVm) {
        homeVm.events.collect { tab = Tab.DOWNLOADS }
    }
    LaunchedEffect(historyVm) {
        historyVm.events.collect { tab = Tab.DOWNLOADS }
    }

    // Permissions: ask once, before the first download. Whatever the answer, the download starts.
    var asked by rememberSaveable { mutableStateOf(false) }
    var missing by remember { mutableStateOf<List<String>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        homeVm.start()
    }
    val granted: (String) -> Boolean = isGranted ?: { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun beforeDownload() {
        val needed = if (asked) emptyList() else DownloadPermissions.missing(sdkInt, granted)
        when {
            needed.isEmpty() -> homeVm.start()
            Manifest.permission.POST_NOTIFICATIONS in needed -> missing = needed
            else -> {
                asked = true
                launcher.launch(needed.toTypedArray())
            }
        }
    }

    missing?.let { needed ->
        AlertDialog(
            onDismissRequest = {
                asked = true
                missing = null
                homeVm.start()
            },
            text = { Text(stringResource(R.string.perm_notifications_rationale)) },
            dismissButton = {
                TextButton(
                    onClick = {
                        asked = true
                        missing = null
                        homeVm.start()
                    },
                    modifier = Modifier.minTouchTarget(),
                ) { Text(stringResource(R.string.perm_notNow)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        asked = true
                        missing = null
                        launcher.launch(needed.toTypedArray())
                    },
                    modifier = Modifier.minTouchTarget(),
                ) { Text(stringResource(R.string.perm_continue)) }
            },
        )
    }

    BackHandler(enabled = tab != Tab.HOME) { tab = Tab.HOME }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            val navLabel = stringResource(R.string.nav_main)
            NavigationBar(modifier = Modifier.semantics { contentDescription = navLabel }) {
                Tab.entries.forEach { entry ->
                    val label = stringResource(entry.label)
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {
                            if (entry == Tab.DOWNLOADS && runningCount > 0) {
                                val running = stringResource(R.string.downloads_active, runningCount)
                                BadgedBox(
                                    badge = {
                                        Badge(modifier = Modifier.semantics { contentDescription = running }) {
                                            Text(runningCount.toString())
                                        }
                                    },
                                ) { Icon(entry.icon, contentDescription = null) }
                            } else {
                                Icon(entry.icon, contentDescription = null)
                            }
                        },
                        label = { Text(label) },
                        modifier = Modifier.minTouchTarget(),
                    )
                }
            }
        },
    ) { padding ->
        val content = Modifier.padding(padding)
        when (tab) {
            Tab.HOME -> HomeScreen(
                state = homeState,
                onTextChange = homeVm::setText,
                onPaste = {
                    scope.launch {
                        val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString()
                        homeVm.paste(text)
                    }
                },
                onClear = homeVm::clear,
                onSubmit = homeVm::submit,
                onSelectOption = homeVm::selectOption,
                onSelectEngine = homeVm::selectEngine,
                onDownload = ::beforeDownload,
                onTokenSubmit = homeVm::submitToken,
                onTokenDismiss = homeVm::dismissTokenDialog,
                modifier = content,
            )
            Tab.DOWNLOADS -> DownloadsScreen(
                items = downloadItems,
                notice = downloadsNotice,
                onCancel = downloadsVm::cancel,
                onRetry = downloadsVm::retry,
                onRemove = downloadsVm::remove,
                modifier = content,
            )
            Tab.HISTORY -> HistoryScreen(
                items = historyItems,
                notice = historyNotice,
                fileActions = fileActions,
                onDownloadAgain = historyVm::downloadAgain,
                onRemove = historyVm::remove,
                onClear = historyVm::clear,
                modifier = content,
            )
            Tab.SETTINGS -> SettingsScreen(
                state = settingsState,
                onServerUrlChange = settingsVm::setServerUrl,
                onTokenChange = settingsVm::setToken,
                onSave = settingsVm::save,
                onTest = settingsVm::testConnection,
                onRemoveToken = settingsVm::removeToken,
                onDefaultEngine = settingsVm::setDefaultEngine,
                onLanguage = settingsVm::setLanguage,
                onUpdateYtDlp = settingsVm::updateYtDlp,
                modifier = content,
            )
        }
    }
}
