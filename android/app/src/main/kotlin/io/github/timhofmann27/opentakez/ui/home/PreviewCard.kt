package io.github.timhofmann27.opentakez.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.timhofmann27.opentakez.R
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.MediaInfo
import io.github.timhofmann27.opentakez.core.model.MediaKind
import io.github.timhofmann27.opentakez.ui.Format
import io.github.timhofmann27.opentakez.ui.components.ErrorBanner
import io.github.timhofmann27.opentakez.ui.components.PlatformChip
import io.github.timhofmann27.opentakez.ui.components.Thumbnail
import io.github.timhofmann27.opentakez.ui.components.minTouchTarget
import io.github.timhofmann27.opentakez.ui.label
import io.github.timhofmann27.opentakez.ui.toMessage

@Composable
fun PreviewCard(
    state: PreviewState.Ready,
    serverConfigured: Boolean,
    onSelectOption: (DownloadOption) -> Unit,
    onSelectEngine: (Engine) -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = state.info
    val context = LocalContext.current
    val duration = Format.duration(info.duration)
    val truncated = info.itemCount != null && info.totalItems != null && info.totalItems > info.itemCount
    val wide = info.kind == MediaKind.VIDEO

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (wide) {
                Thumbnail(info.thumbnail, info.kind, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Details(info, duration)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Thumbnail(info.thumbnail, info.kind, Modifier.size(96.dp))
                    Column(modifier = Modifier.weight(1f)) { Details(info, duration) }
                }
            }

            if (truncated) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.media_truncated, info.itemCount, info.totalItems),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            OptionPicker(info.options, state.option, onSelectOption)
            EngineSwitch(state.engine, serverConfigured, state.switching, onSelectEngine)

            state.notice?.let { ErrorBanner(it.toMessage(context)) }

            Button(
                onClick = onDownload,
                enabled = !state.starting && !state.switching,
                modifier = Modifier.fillMaxWidth().minTouchTarget(),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(if (state.starting) R.string.preview_starting else R.string.preview_download),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Details(info: MediaInfo, duration: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PlatformChip(info.platform)
            if (duration.isNotEmpty()) {
                Text(duration, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            info.itemCount?.let {
                Text(
                    stringResource(R.string.media_items, it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(info.title, style = MaterialTheme.typography.titleMedium)
        info.uploader?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Exactly the options the engine offered, grouped like the PWA: Video, Audio only, Photos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionPicker(
    options: List<DownloadOption>,
    selected: DownloadOption,
    onSelect: (DownloadOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = listOf(
        stringResource(R.string.option_group_video) to options.filter { it.isVideo },
        stringResource(R.string.option_group_audio) to options.filter { it.isAudio },
        stringResource(R.string.option_group_image) to options.filter { !it.isVideo && !it.isAudio },
    ).filter { it.second.isNotEmpty() }
    val formatLabel = stringResource(R.string.preview_format)

    Column(
        modifier = modifier.semantics { contentDescription = formatLabel },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        groups.forEach { (title, items) ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                    items.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = option == selected,
                            onClick = { onSelect(option) },
                            shape = SegmentedButtonDefaults.itemShape(index, items.size),
                            modifier = Modifier.minTouchTarget(),
                        ) {
                            Text(stringResource(option.label()))
                        }
                    }
                }
            }
        }
    }
}

/** "On this device | Via my server"; the server side stays disabled until a server is configured. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineSwitch(
    engine: Engine,
    serverConfigured: Boolean,
    busy: Boolean,
    onSelect: (Engine) -> Unit,
    modifier: Modifier = Modifier,
) {
    val choices = listOf(
        Engine.DEVICE to stringResource(R.string.engine_device),
        Engine.SERVER to stringResource(R.string.engine_server),
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.engine_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().selectableGroup()) {
            choices.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = engine == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                    enabled = !busy && (value == Engine.DEVICE || serverConfigured),
                    modifier = Modifier.minTouchTarget(),
                ) {
                    Text(label)
                }
            }
        }
    }
}
