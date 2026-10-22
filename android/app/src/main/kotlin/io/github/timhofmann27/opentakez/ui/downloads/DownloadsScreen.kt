package io.github.timhofmann27.opentakez.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.timhofmann27.opentakez.R
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.Phase
import io.github.timhofmann27.opentakez.ui.Format
import io.github.timhofmann27.opentakez.ui.components.ErrorBanner
import io.github.timhofmann27.opentakez.ui.components.PlatformChip
import io.github.timhofmann27.opentakez.ui.components.ScreenTitle
import io.github.timhofmann27.opentakez.ui.components.Thumbnail
import io.github.timhofmann27.opentakez.ui.components.minTouchTarget
import io.github.timhofmann27.opentakez.ui.label
import io.github.timhofmann27.opentakez.ui.ports.ActiveDownload
import io.github.timhofmann27.opentakez.ui.ports.DownloadStatus
import io.github.timhofmann27.opentakez.ui.toMessage

@Composable
fun DownloadsScreen(
    items: List<ActiveDownload>?,
    notice: EngineError?,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScreenTitle(stringResource(R.string.nav_downloads), Modifier.padding(vertical = 16.dp))
        notice?.let { ErrorBanner(it.toMessage(context), Modifier.padding(bottom = 8.dp)) }
        when {
            items == null -> Unit
            items.isEmpty() -> Text(
                stringResource(R.string.downloads_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    DownloadRow(item, onCancel, onRetry, onRemove)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadRow(
    item: ActiveDownload,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val failed = item.status == DownloadStatus.FAILED
    val removeDescription = stringResource(R.string.a11y_withTitle, stringResource(R.string.job_remove), item.title)
    val cancelDescription = stringResource(R.string.a11y_withTitle, stringResource(R.string.job_cancel), item.title)

    Row(modifier = modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Thumbnail(item.thumbnail, item.kind, Modifier.size(width = 80.dp, height = 56.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PlatformChip(item.platform)
                Text(
                    stringResource(item.option.label()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(item.title, style = MaterialTheme.typography.bodyLarge)

            if (failed) {
                ErrorBanner(item.error?.toMessage(context) ?: stringResource(R.string.error_unknown, ""))
            } else {
                ProgressBlock(item)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed) {
                    FilledTonalButton(onClick = { onRetry(item.id) }, modifier = Modifier.minTouchTarget()) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.job_retry), modifier = Modifier.padding(start = 6.dp))
                    }
                    TextButton(
                        onClick = { onRemove(item.id) },
                        modifier = Modifier.minTouchTarget().semantics { contentDescription = removeDescription },
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.job_remove), modifier = Modifier.padding(start = 6.dp))
                    }
                } else {
                    TextButton(
                        onClick = { onCancel(item.id) },
                        modifier = Modifier.minTouchTarget().semantics { contentDescription = cancelDescription },
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.job_cancel), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

/**
 * Phase text is the live region (it changes rarely, so a screen reader is not flooded); the
 * numbers beside it change on every tick and stay silent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressBlock(item: ActiveDownload) {
    val progress = item.progress
    val queued = item.status == DownloadStatus.QUEUED || progress == null
    val phase = stringResource(
        when {
            queued -> R.string.job_queued
            progress.phase == Phase.PROCESSING -> R.string.job_processing
            else -> R.string.job_downloading
        },
    )
    val numbers = if (queued || progress.phase == Phase.PROCESSING) {
        ""
    } else {
        listOf(
            Format.percent(progress.fraction),
            Format.speed(progress.speedBytesPerSecond),
            progress.etaSeconds?.takeIf { it > 0 }?.let { Format.duration(it) }.orEmpty(),
        ).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    val fraction = progress?.fraction?.toFloat()?.coerceIn(0f, 1f)
    if (progress != null && fraction != null && progress.phase == Phase.DOWNLOADING) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            phase,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (numbers.isNotEmpty()) {
            Text(numbers, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
