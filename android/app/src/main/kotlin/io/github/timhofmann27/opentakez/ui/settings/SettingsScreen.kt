package io.github.timhofmann27.opentakez.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.timhofmann27.opentakez.BuildConfig
import io.github.timhofmann27.opentakez.R
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.ui.components.ErrorBanner
import io.github.timhofmann27.opentakez.ui.components.ScreenTitle
import io.github.timhofmann27.opentakez.ui.components.SectionTitle
import io.github.timhofmann27.opentakez.ui.components.minTouchTarget
import io.github.timhofmann27.opentakez.ui.failureMessage
import io.github.timhofmann27.opentakez.ui.home.SecretField
import io.github.timhofmann27.opentakez.ui.ports.ConnectionResult
import io.github.timhofmann27.opentakez.ui.ports.UpdateResult
import io.github.timhofmann27.opentakez.ui.theme.successColor

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onServerUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onRemoveToken: () -> Unit,
    onDefaultEngine: (Engine) -> Unit,
    onLanguage: (String) -> Unit,
    onUpdateYtDlp: () -> Unit,
    modifier: Modifier = Modifier,
    versionName: String = BuildConfig.VERSION_NAME,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenTitle(stringResource(R.string.nav_settings))
        ServerSection(state, onServerUrlChange, onTokenChange, onSave, onTest, onRemoveToken)
        DefaultEngineSection(state, onDefaultEngine)
        LanguageSection(state.language, onLanguage)
        YtDlpSection(state, onUpdateYtDlp)
        AboutSection(versionName)
    }
}

@Composable
private fun SettingsPanel(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                SectionTitle(title, modifier = Modifier.weight(1f))
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerSection(
    state: SettingsUiState,
    onServerUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onRemoveToken: () -> Unit,
) {
    SettingsPanel(stringResource(R.string.settings_server), Icons.Outlined.CloudQueue) {

        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = onServerUrlChange,
            modifier = Modifier.fillMaxWidth().minTouchTarget(),
            label = { Text(stringResource(R.string.settings_serverUrl)) },
            placeholder = { Text("https://dl.example.com") },
            singleLine = true,
            isError = state.urlInvalid,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            supportingText = {
                Text(
                    stringResource(
                        if (state.urlInvalid) R.string.settings_serverUrlInvalid else R.string.settings_serverUrlHint,
                    ),
                )
            },
        )

        SecretField(
            value = state.tokenInput,
            onValueChange = onTokenChange,
            label = stringResource(R.string.settings_token),
            supportingText = stringResource(R.string.settings_tokenHint) +
                if (state.tokenSet) "\n" + stringResource(R.string.settings_tokenSaved) else "",
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onSave, enabled = !state.urlInvalid, modifier = Modifier.minTouchTarget()) {
                Text(stringResource(R.string.settings_save))
            }
            FilledTonalButton(
                onClick = onTest,
                enabled = state.normalizedUrl?.isNotEmpty() == true && state.test != TestState.Testing,
                modifier = Modifier.minTouchTarget(),
            ) {
                Text(stringResource(R.string.settings_test))
            }
            if (state.tokenSet) {
                TextButton(onClick = onRemoveToken, modifier = Modifier.minTouchTarget()) {
                    Text(stringResource(R.string.settings_tokenRemove))
                }
            }
        }

        // Outcomes are announced; success text carries words, not just green.
        Column(
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.saved) Text(stringResource(R.string.settings_saved), color = successColor())
            if (state.tokenRemoved) Text(stringResource(R.string.settings_tokenRemoved), color = successColor())
            when (val test = state.test) {
                TestState.Idle -> Unit
                TestState.Testing -> Text(stringResource(R.string.settings_testing))
                is TestState.Done -> ConnectionOutcome(test.result)
            }
        }
    }
}

@Composable
private fun ConnectionOutcome(result: ConnectionResult) {
    if (result is ConnectionResult.Connected) {
        val text = if (result.ytDlpVersion != null) {
            stringResource(R.string.settings_connected, result.version, result.ytDlpVersion)
        } else {
            stringResource(R.string.settings_connectedShort, result.version)
        }
        Text(text, color = successColor())
        if (result.platforms.isNotEmpty()) {
            Text(
                stringResource(R.string.settings_platforms, result.platforms.joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        result.failureMessage()?.let { ErrorBanner(stringResource(it)) }
    }
}

@Composable
private fun DefaultEngineSection(state: SettingsUiState, onSelect: (Engine) -> Unit) {
    SettingsPanel(stringResource(R.string.settings_defaultEngine), Icons.Outlined.PhoneAndroid) {
        Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChoiceRow(
                label = stringResource(R.string.engine_device),
                selected = state.defaultEngine == Engine.DEVICE,
                enabled = true,
                onClick = { onSelect(Engine.DEVICE) },
            )
            ChoiceRow(
                label = stringResource(R.string.engine_server),
                selected = state.defaultEngine == Engine.SERVER,
                enabled = state.serverSaved,
                onClick = { onSelect(Engine.SERVER) },
            )
        }
    }
}

@Composable
private fun LanguageSection(language: String, onSelect: (String) -> Unit) {
    SettingsPanel(stringResource(R.string.settings_language), Icons.Outlined.Language) {
        Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChoiceRow(stringResource(R.string.settings_languageSystem), language.isEmpty(), true) { onSelect("") }
            ChoiceRow(stringResource(R.string.language_en), language == "en", true) { onSelect("en") }
            ChoiceRow(stringResource(R.string.language_de), language == "de", true) { onSelect("de") }
        }
    }
}

/** A whole row is the touch target, so long German labels wrap instead of being cut off. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .minTouchTarget()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun YtDlpSection(state: SettingsUiState, onUpdate: () -> Unit) {
    val updating = state.update == UpdateState.Updating
    SettingsPanel(stringResource(R.string.ytdlp_version), Icons.Outlined.SystemUpdateAlt) {
        Text(
            state.ytDlpVersion ?: "–",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
        )
        FilledTonalButton(onClick = onUpdate, enabled = !updating, modifier = Modifier.minTouchTarget()) {
            Text(stringResource(if (updating) R.string.ytdlp_updating else R.string.ytdlp_update))
        }
        Column(modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
            when (val update = state.update) {
                UpdateState.Idle, UpdateState.Updating -> Unit
                is UpdateState.Done -> when (val result = update.result) {
                    is UpdateResult.Updated ->
                        Text(stringResource(R.string.ytdlp_updated, result.version), color = successColor())
                    UpdateResult.AlreadyLatest -> Text(stringResource(R.string.ytdlp_updateAlreadyLatest))
                    is UpdateResult.Failed -> ErrorBanner(stringResource(R.string.ytdlp_updateFailed))
                }
            }
        }
    }
}

@Composable
private fun AboutSection(versionName: String) {
    SettingsPanel(stringResource(R.string.settings_about), Icons.Outlined.Info) {
        Text(
            stringResource(R.string.about_version, versionName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.about_licenses_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.about_licenses_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
