package io.github.timhofmann27.opentakez.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val listState = rememberLazyListState()
    LaunchedEffect(notice) {
        if (notice != null) listState.animateScrollToItem(0)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenTitle(stringResource(R.string.nav_downloads)) }
        notice?.let { error -> item { ErrorBanner(error.toMessage(context)) } }
        if (items != null) {
            if (items.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.Download,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.downloads_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                items(items, key = { it.id }) { item -> DownloadRow(item, onCancel, onRetry, onRemove) }
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

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(item.thumbnail, item.kind, Modifier.size(width = 72.dp, height = 72.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PlatformChip(item.platform)
                        Text(
                            stringResource(item.option.label()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }

            if (failed) {
                ErrorBanner(item.error?.toMessage(context) ?: stringResource(R.string.error_unknown, ""))
            } else {
                ProgressBlock(item)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (progress != null && fraction != null && progress.phase == Phase.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                phase,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (numbers.isNotEmpty()) {
                Text(
                    numbers,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
