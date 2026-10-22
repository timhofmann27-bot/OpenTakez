package io.github.timhofmann27.opentakez.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.timhofmann27.opentakez.R
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.ui.components.ErrorBanner
import io.github.timhofmann27.opentakez.ui.components.ScreenTitle
import io.github.timhofmann27.opentakez.ui.components.minTouchTarget
import io.github.timhofmann27.opentakez.ui.failureMessage
import io.github.timhofmann27.opentakez.ui.ports.DeviceEngineState
import io.github.timhofmann27.opentakez.ui.toMessage

@Composable
fun HomeScreen(
    state: HomeUiState,
    onTextChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    onSelectOption: (DownloadOption) -> Unit,
    onSelectEngine: (Engine) -> Unit,
    onDownload: () -> Unit,
    onTokenSubmit: (String) -> Unit,
    onTokenDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val failed = state.preview as? PreviewState.Failed

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle(stringResource(R.string.nav_home))

        LinkField(
            text = state.text,
            hasError = failed != null,
            onTextChange = onTextChange,
            onPaste = onPaste,
            onClear = onClear,
            onSubmit = onSubmit,
        )

        EngineStatus(state.deviceEngine)

        // One live region for everything that appears below the field, so a screen reader hears
        // "Checking link", the preview, or the error without hunting for it.
        Column(
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (val preview = state.preview) {
                PreviewState.Idle ->
                    Text(
                        stringResource(R.string.home_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                PreviewState.Resolving ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text(stringResource(R.string.home_resolving), style = MaterialTheme.typography.bodyMedium)
                    }
                is PreviewState.Failed -> ErrorBanner(preview.error.toMessage(context))
                is PreviewState.Ready ->
                    PreviewCard(
                        state = preview,
                        serverConfigured = state.serverConfigured,
                        onSelectOption = onSelectOption,
                        onSelectEngine = onSelectEngine,
                        onDownload = onDownload,
                    )
            }
        }
    }

    state.tokenDialog?.let { dialog ->
        TokenDialog(dialog, onSubmit = onTokenSubmit, onDismiss = onTokenDismiss)
    }
}

@Composable
private fun LinkField(
    text: String,
    hasError: Boolean,
    onTextChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
) {
    val label = stringResource(R.string.url_label)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth().minTouchTarget(),
            label = { Text(label) },
            placeholder = { Text(stringResource(R.string.url_placeholder)) },
            singleLine = true,
            isError = hasError,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = onClear, modifier = Modifier.minTouchTarget()) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.url_clear))
                    }
                }
            },
        )
        if (text.isBlank()) {
            Button(onClick = onPaste, modifier = Modifier.fillMaxWidth().minTouchTarget()) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.url_paste), modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth().minTouchTarget()) {
                Text(stringResource(R.string.url_submit))
            }
        }
    }
}

@Composable
private fun EngineStatus(engine: DeviceEngineState) {
    when (engine) {
        DeviceEngineState.Ready -> Unit
        DeviceEngineState.Initializing ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.engine_preparing), style = MaterialTheme.typography.bodyMedium)
            }
        is DeviceEngineState.Failed -> ErrorBanner(stringResource(R.string.engine_failed))
    }
}

/** Shown after a 401. The token is typed into a masked field and kept only in this composition. */
@Composable
fun TokenDialog(
    state: TokenDialogState,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Deliberately remember, not rememberSaveable: the token must not land in saved instance state.
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.token_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.token_body), style = MaterialTheme.typography.bodyMedium)
                SecretField(
                    value = token,
                    onValueChange = { token = it },
                    label = stringResource(R.string.settings_token),
                    isError = state.failure != null,
                )
                state.failure?.failureMessage()?.let { ErrorBanner(stringResource(it)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(token) },
                enabled = token.isNotBlank() && !state.checking,
                modifier = Modifier.minTouchTarget(),
            ) { Text(stringResource(R.string.token_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.minTouchTarget()) {
                Text(stringResource(R.string.token_cancel))
            }
        },
    )
}

/** A password field: masked, no copy/cut, no suggestions, never shown in clear text. */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().minTouchTarget(),
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        supportingText = supportingText?.let { { Text(it) } },
    )
}
