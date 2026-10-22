package io.github.timhofmann27.opentakez.ui.history

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.timhofmann27.opentakez.R
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.ui.components.ErrorBanner
import io.github.timhofmann27.opentakez.ui.components.PlatformChip
import io.github.timhofmann27.opentakez.ui.components.ScreenTitle
import io.github.timhofmann27.opentakez.ui.components.Thumbnail
import io.github.timhofmann27.opentakez.ui.components.minTouchTarget
import io.github.timhofmann27.opentakez.ui.label
import io.github.timhofmann27.opentakez.ui.ports.HistoryEntry
import io.github.timhofmann27.opentakez.ui.toMessage

@Composable
fun HistoryScreen(
    items: List<HistoryEntry>?,
    notice: EngineError?,
    fileActions: FileActions,
    onDownloadAgain: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Set when no app could open or share a file; cleared by the next successful action.
    var fileError by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScreenTitle(stringResource(R.string.nav_history), Modifier.padding(vertical = 16.dp))
        notice?.let { ErrorBanner(it.toMessage(context), Modifier.padding(bottom = 8.dp)) }
        if (fileError) ErrorBanner(stringResource(R.string.history_openFailed), Modifier.padding(bottom = 8.dp))
        val chooserTitle = stringResource(R.string.history_share)
        when {
            items == null -> Unit
            items.isEmpty() -> Text(
                stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onOpen = { fileError = !fileActions.open(entry) },
                        onShare = { fileError = !fileActions.share(entry, chooserTitle) },
                        onDownloadAgain = { onDownloadAgain(entry.id) },
                        onRemove = { onRemove(entry.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item {
                    TextButton(onClick = onClear, modifier = Modifier.minTouchTarget().padding(vertical = 8.dp)) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.history_clear), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryRow(
    entry: HistoryEntry,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDownloadAgain: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val date = DateUtils.formatDateTime(
        context,
        entry.finishedAtMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
    )
    val removeDescription = stringResource(R.string.a11y_withTitle, stringResource(R.string.job_remove), entry.title)
    val hasFiles = entry.files.isNotEmpty()

    Row(modifier = modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Thumbnail(entry.thumbnail, entry.kind, Modifier.size(width = 80.dp, height = 56.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PlatformChip(entry.platform)
                Text(
                    stringResource(entry.option.label()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(entry.title, style = MaterialTheme.typography.bodyLarge)
            if (entry.files.size > 1) {
                Text(
                    stringResource(R.string.job_files, entry.files.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.skipped > 0) {
                Text(
                    stringResource(R.string.job_skipped, entry.skipped),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasFiles) {
                    FilledTonalButton(onClick = onOpen, modifier = Modifier.minTouchTarget()) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.history_open), modifier = Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = onShare, modifier = Modifier.minTouchTarget()) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.history_share), modifier = Modifier.padding(start = 6.dp))
                    }
                }
                TextButton(onClick = onDownloadAgain, modifier = Modifier.minTouchTarget()) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.history_again), modifier = Modifier.padding(start = 6.dp))
                }
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.minTouchTarget().semantics { contentDescription = removeDescription },
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.job_remove), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}
