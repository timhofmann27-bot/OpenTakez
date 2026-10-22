package io.github.timhofmann27.opentakez.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.timhofmann27.opentakez.R
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.ui.components.ErrorBanner
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
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val failed = state.preview as? PreviewState.Failed

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        BrandHeader()
        if (state.preview == PreviewState.Idle) {
            Text(
                buildAnnotatedString {
                    append(stringResource(R.string.home_headline_first))
                    append("\n")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                        append(stringResource(R.string.home_headline_second))
                    }
                },
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.semantics { heading() },
            )
        }

        LinkField(
            text = state.text,
            hasError = failed != null,
            // Once a preview is up, its Download button is the one action that should stand out.
            emphasized = state.preview !is PreviewState.Ready,
            onTextChange = onTextChange,
            onPaste = onPaste,
            onClear = onClear,
            onSubmit = {
                keyboard?.hide()
                focusManager.clearFocus()
                onSubmit()
            },
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
private fun BrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun LinkField(
    text: String,
    hasError: Boolean,
    emphasized: Boolean,
    onTextChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
) {
    val label = stringResource(R.string.url_label)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth().minTouchTarget(),
                label = { Text(label) },
                placeholder = { Text(stringResource(R.string.url_placeholder)) },
                singleLine = true,
                isError = hasError,
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = MaterialTheme.shapes.medium,
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
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
                Button(onClick = onPaste, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.url_paste), modifier = Modifier.padding(start = 8.dp))
                }
            } else {
                val submitContent: @Composable RowScope.() -> Unit = {
                    Text(stringResource(R.string.url_submit))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(20.dp))
                }
                val submitModifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                if (emphasized) {
                    Button(onClick = onSubmit, modifier = submitModifier, content = submitContent)
                } else {
                    FilledTonalButton(onClick = onSubmit, modifier = submitModifier, content = submitContent)
                }
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
