@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import com.nuvio.tv.R
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.data.local.WatchProgressSource

@Composable
fun MDBListSettingsContent(
    viewModel: MDBListSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showWatchProgressDialog by remember { mutableStateOf(false) }
    val notSetLabel = stringResource(R.string.mdblist_not_set)
    // Hoisted at composable scope on purpose: the formatter below is a plain
    // function type, so a composable-only call cannot be made from inside it.
    // This mirrors the tracking settings screen, where the picker's other
    // entry point lives.
    val strWatchProgressTrakt = stringResource(R.string.trakt_watch_progress_source_trakt)
    val strWatchProgressNuvio = stringResource(R.string.trakt_watch_progress_source_nuvio)
    val strWatchProgressMdbList = stringResource(R.string.trakt_watch_progress_source_mdblist)
    val strWatchProgressSimkl = stringResource(R.string.simkl_name)
    val watchProgressFormatter: (WatchProgressSource) -> String = { source ->
        when (source) {
            WatchProgressSource.TRAKT -> strWatchProgressTrakt
            WatchProgressSource.SIMKL -> strWatchProgressSimkl
            WatchProgressSource.NUVIO_SYNC -> strWatchProgressNuvio
            WatchProgressSource.MDBLIST -> strWatchProgressMdbList
        }
    }

    LaunchedEffect(uiState.enabled, uiState.apiKey) {
        viewModel.refreshAccount()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.mdblist_title),
            subtitle = stringResource(R.string.mdblist_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val mdbListState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = mdbListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "mdblist_enabled") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_enable_title),
                        subtitle = stringResource(R.string.mdblist_enable_subtitle),
                        checked = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleEnabled(!uiState.enabled)) },
                        modifier = Modifier
                            .padding(top = NuvioTheme.spacing.xxs)
                            .then(
                                if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        })
                    )
                }

                item(key = "mdblist_api_key") {
                    SettingsActionRow(
                        title = stringResource(R.string.mdblist_api_key_title),
                        subtitle = stringResource(R.string.mdblist_api_key_subtitle),
                        value = maskApiKey(uiState.apiKey, stringResource(R.string.mdblist_not_set)),
                        onClick = { showApiKeyDialog = true },
                        enabled = uiState.enabled
                    )
                }

                item(key = "mdblist_tracking") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_tracking_title),
                        subtitle = stringResource(R.string.mdblist_tracking_subtitle),
                        checked = uiState.trackingEnabled,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleTracking(!uiState.trackingEnabled)) }
                    )
                }

                item(key = "mdblist_account") {
                    SettingsActionRow(
                        title = stringResource(R.string.mdblist_account_title),
                        subtitle = stringResource(R.string.mdblist_account_subtitle),
                        value = uiState.username ?: notSetLabel,
                        onClick = { viewModel.refreshAccount() },
                        enabled = uiState.enabled,
                        trailingIcon = null
                    )
                }

                item(key = "mdblist_plan") {
                    SettingsActionRow(
                        title = stringResource(R.string.mdblist_plan_title),
                        subtitle = stringResource(R.string.mdblist_plan_subtitle),
                        value = uiState.plan ?: notSetLabel,
                        onClick = { viewModel.refreshAccount() },
                        enabled = uiState.enabled,
                        trailingIcon = null
                    )
                }

                item(key = "mdblist_requests") {
                    val used = uiState.requestsUsed
                    val limit = uiState.requestsLimit
                    SettingsActionRow(
                        title = stringResource(R.string.mdblist_requests_title),
                        subtitle = stringResource(R.string.mdblist_requests_subtitle),
                        value = if (used != null && limit != null) "" + used + " / " + limit else notSetLabel,
                        onClick = { viewModel.refreshAccount() },
                        enabled = uiState.enabled,
                        trailingIcon = null
                    )
                }

                item(key = "mdblist_watch_progress") {
                    SettingsActionRow(
                        title = stringResource(R.string.trakt_watch_progress_title),
                        subtitle = stringResource(R.string.trakt_watch_progress_subtitle),
                        value = watchProgressFormatter(uiState.watchProgressSource),
                        onClick = { showWatchProgressDialog = true },
                        enabled = uiState.enabled
                    )
                }

                item(key = "mdblist_ratings_heading") {
                    Text(
                        text = stringResource(R.string.mdblist_ratings_heading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextTertiary,
                        modifier = Modifier.padding(top = NuvioTheme.spacing.sm)
                    )
                }

                item(key = "mdblist_trakt") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_trakt_title),
                        subtitle = stringResource(R.string.mdblist_trakt_subtitle),
                        checked = uiState.showTrakt,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleTrakt(!uiState.showTrakt)) }
                    )
                }

                item(key = "mdblist_imdb") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_imdb_title),
                        subtitle = stringResource(R.string.mdblist_imdb_subtitle),
                        checked = uiState.showImdb,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleImdb(!uiState.showImdb)) }
                    )
                }

                item(key = "mdblist_tmdb") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_tmdb_title),
                        subtitle = stringResource(R.string.mdblist_tmdb_subtitle),
                        checked = uiState.showTmdb,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleTmdb(!uiState.showTmdb)) }
                    )
                }

                item(key = "mdblist_letterboxd") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_letterboxd_title),
                        subtitle = stringResource(R.string.mdblist_letterboxd_subtitle),
                        checked = uiState.showLetterboxd,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleLetterboxd(!uiState.showLetterboxd)) }
                    )
                }

                item(key = "mdblist_tomatoes") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_tomatoes_title),
                        subtitle = stringResource(R.string.mdblist_tomatoes_subtitle),
                        checked = uiState.showTomatoes,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleTomatoes(!uiState.showTomatoes)) }
                    )
                }

                item(key = "mdblist_audience") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_audience_title),
                        subtitle = stringResource(R.string.mdblist_audience_subtitle),
                        checked = uiState.showAudience,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleAudience(!uiState.showAudience)) }
                    )
                }

                item(key = "mdblist_metacritic") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_metacritic_title),
                        subtitle = stringResource(R.string.mdblist_metacritic_subtitle),
                        checked = uiState.showMetacritic,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleMetacritic(!uiState.showMetacritic)) }
                    )
                }

                item(key = "mdblist_mal") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_mal_title),
                        subtitle = stringResource(R.string.mdblist_mal_subtitle),
                        checked = uiState.showMal,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleMal(!uiState.showMal)) }
                    )
                }
            }
            SettingsVerticalScrollIndicators(state = mdbListState)
            }
        }
    }

    if (showWatchProgressDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.trakt_watch_progress_dialog_title),
            subtitle = stringResource(R.string.trakt_watch_progress_dialog_subtitle),
            options = listOfNotNull(
                SettingsPickerOption(WatchProgressSource.TRAKT, stringResource(R.string.trakt_watch_progress_source_trakt)),
                SettingsPickerOption(WatchProgressSource.NUVIO_SYNC, stringResource(R.string.trakt_watch_progress_source_nuvio)),
                if (uiState.trackingReady || uiState.watchProgressSource == WatchProgressSource.MDBLIST) {
                    SettingsPickerOption(WatchProgressSource.MDBLIST, stringResource(R.string.trakt_watch_progress_source_mdblist))
                } else {
                    null
                }
            ),
            selectedValue = uiState.watchProgressSource,
            onOptionSelected = { source ->
                viewModel.onWatchProgressSourceSelected(source)
                showWatchProgressDialog = false
            },
            onDismiss = { showWatchProgressDialog = false },
            width = 620.dp,
            maxHeight = 320.dp
        )
    }

    if (showApiKeyDialog) {
        MDBListApiKeyDialog(
            currentValue = uiState.apiKey,
            viewModel = viewModel,
            onSaved = { showApiKeyDialog = false },
            onClear = { viewModel.validateAndSaveApiKey("") {}; showApiKeyDialog = false },
            onDismiss = { showApiKeyDialog = false }
        )
    }
}

// internal (not private): the Tracking settings screen hosts the same key entry,
// so a user who reaches MDBList from there is not sent to another screen.
@Composable
internal fun MDBListApiKeyDialog(
    currentValue: String,
    viewModel: MDBListSettingsViewModel,
    onSaved: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val validating by viewModel.validating.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val invalidApiKeyMsg = stringResource(R.string.mdblist_invalid_api_key)

    LaunchedEffect(Unit) {
        viewModel.validationError.collect {
            Toast.makeText(context, invalidApiKeyMsg, Toast.LENGTH_SHORT).show()
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.mdblist_dialog_title),
        subtitle = stringResource(R.string.mdblist_dialog_subtitle),
        width = 700.dp
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = androidx.compose.foundation.BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { keyboardController?.hide() }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NuvioTheme.colors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.mdblist_dialog_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_clear))
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = { if (!validating) viewModel.validateAndSaveApiKey(value, onSaved) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(if (validating) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
            }
        }
    }
}

internal fun maskApiKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}
