package com.nuvio.tv.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.assessment.AssessmentItem
import com.nuvio.tv.core.network.StreamSweepEngine
import com.nuvio.tv.core.assessment.AssessmentResult
import com.nuvio.tv.core.assessment.AssessmentTier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.core.assessment.DeviceAssessmentApplier
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine
import com.nuvio.tv.core.assessment.ProfileKind
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Device Settings Assessment, Stage 1 UI (REVIEW-ONLY).
 *
 * State lives at the SCREEN level (rememberDeviceAssessmentState in
 * AdvancedSettingsContent) and the results render as MULTIPLE LazyColumn
 * items via deviceAssessmentItems. Both are deliberate: lazy items are
 * disposed when scrolled out of composition, so state held inside a single
 * tall item died on scroll-away (results vanished), and one tall item also
 * cut cards off between focus stops. Per-card items scroll individually and
 * screen-level state survives recycling; the run coroutine launches on the
 * screen scope so a mid-run scroll can't cancel the sweep.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface AssessmentDataStoreEntryPoint {
    fun playerSettingsDataStore(): com.nuvio.tv.data.local.PlayerSettingsDataStore
}

internal fun assessmentDataStore(context: Context): com.nuvio.tv.data.local.PlayerSettingsDataStore =
    dagger.hilt.android.EntryPointAccessors.fromApplication(
        context.applicationContext,
        AssessmentDataStoreEntryPoint::class.java
    ).playerSettingsDataStore()

// Assessment inner cards use a gently rounded rectangle instead of the
// settings pill: with multi-line content the pill's corner curvature
// clipped first/last-line text at the card edges (field-reported on the
// Device-facts card).
@Composable
private fun assessmentCardShape() = RoundedCornerShape(12.dp)

internal class DeviceAssessmentState {
    var running by mutableStateOf(false)
    var sweepState by mutableStateOf("")
    var passRows by mutableStateOf(listOf<Pair<String, Double?>>())
    // N3d-a2: why a row has no number, keyed by row label.
    var passNotes by mutableStateOf(mapOf<String, StreamSweepEngine.PassNote>())
    var result by mutableStateOf<AssessmentResult?>(null)
    var selectedProfile by mutableStateOf<ProfileKind?>(null)
    var applyArmed by mutableStateOf(false)
    var applying by mutableStateOf(false)
    var appliedCount by mutableStateOf<Int?>(null)
}

@Composable
internal fun rememberDeviceAssessmentState(): DeviceAssessmentState =
    remember { DeviceAssessmentState() }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun runDeviceAssessment(
    scope: CoroutineScope,
    context: Context,
    state: DeviceAssessmentState,
    settings: PlayerSettings,
    diagnostics: LastPlaybackDiagnostics
) {
    if (state.running) return
    scope.launch {
        state.running = true
        state.passRows = emptyList()
        state.passNotes = emptyMap()
        state.sweepState = ""
        state.result = null
        state.applyArmed = false
        state.appliedCount = null
        val outcome = DeviceAssessmentEngine.run(
            context = context,
            activity = context.findActivity(),
            settings = settings,
            diagnostics = diagnostics,
            onSweepState = { state.sweepState = it },
            onSweepPassAdded = { label ->
                state.passRows = state.passRows + (label to null)
            },
            onSweepPassResult = { label, mbps, note ->
                state.passRows = state.passRows.map { if (it.first == label) label to mbps else it }
                if (note != null) state.passNotes = state.passNotes + (label to note)
            }
        )
        state.result = outcome
        state.selectedProfile = outcome.suggestedProfile
        state.running = false
    }
}

internal fun runApplyAssessment(
    scope: CoroutineScope,
    context: Context,
    state: DeviceAssessmentState
) {
    val res = state.result ?: return
    if (state.applying) return
    scope.launch {
        state.applying = true
        val profile = res.profiles.firstOrNull { it.kind == state.selectedProfile }
        val outcome = DeviceAssessmentApplier.apply(
            dataStore = assessmentDataStore(context),
            plan = res.applyPlan,
            profile = profile
        )
        state.appliedCount = outcome.writtenCount
        state.applyArmed = false
        state.applying = false
    }
}

internal fun runRevertAssessment(
    scope: CoroutineScope,
    context: Context,
    state: DeviceAssessmentState
) {
    if (state.applying) return
    scope.launch {
        state.applying = true
        DeviceAssessmentApplier.revert(assessmentDataStore(context))
        state.appliedCount = null
        state.applyArmed = false
        state.applying = false
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun LazyListScope.deviceAssessmentItems(
    state: DeviceAssessmentState,
    diagnostics: LastPlaybackDiagnostics,
    onRun: () -> Unit,
    onApply: () -> Unit,
    onRevert: () -> Unit
) {
    item(key = "assessment_run") {
        val hasStream = !diagnostics.streamUrl.isNullOrBlank()
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                // The three-line subtitle crowds the CLASSIC pill's curved corner;
                // match the screen's own 12 dp results-card shape instead.
                shape = assessmentCardShape(),
                title = stringResource(
                    if (state.running) R.string.assessment_run_running else R.string.assessment_run_title
                ),
                subtitle = if (state.running) {
                    null
                } else {
                    stringResource(
                        if (hasStream) R.string.assessment_run_subtitle else R.string.assessment_no_stream
                    )
                },
                value = if (state.running && state.sweepState.isNotBlank()) state.sweepState else null,
                enabled = !state.running,
                onClick = onRun
            )
        }
    }

    if (state.passRows.isNotEmpty()) {
        item(key = "assessment_passes") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(NuvioTheme.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                ) {
                    state.passRows.forEach { (label, speed) ->
                        AssessmentPassRow(
                            label = label,
                            speed = speed,
                            note = state.passNotes[label],
                            isRunning = state.running && state.sweepState == label &&
                                speed == null && state.passNotes[label] == null
                        )
                    }
                }
            }
        }
    }

    val res = state.result ?: return

    res.errorText?.let { err ->
        item(key = "assessment_error") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.assessment_error_prefix, err),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.Error,
                    modifier = Modifier.padding(NuvioTheme.spacing.sm)
                )
            }
        }
    }

    item(key = "assessment_facts") {
        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.assessment_header_title)
        ) {
            // Focusable shell: the facts block was the one non-focusable card,
            // so DPAD never brought it fully into view and it straddled the
            // viewport between focus stops.
            AssessmentFocusCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                AssessmentFactRow(
                    label = stringResource(R.string.assessment_header_device),
                    value = stringResource(
                        R.string.assessment_header_device_value,
                        res.header.deviceRamLabel,
                        res.header.safeLimitMb,
                        res.header.warningLimitMb
                    )
                )
                AssessmentFactRow(
                    label = stringResource(R.string.assessment_header_display),
                    value = res.header.displaySummary
                        ?: stringResource(R.string.assessment_header_display_unknown)
                )
                AssessmentFactRow(
                    label = stringResource(R.string.assessment_header_stream),
                    value = res.header.streamLabel
                        ?: stringResource(R.string.assessment_header_stream_none),
                    valueMaxLines = 2
                )
                res.header.streamBitrateMbps?.let { mbps ->
                    AssessmentFactRow(
                        label = stringResource(R.string.assessment_header_bitrate),
                        value = "%.1f Mbps".format(mbps)
                    )
                }
                val hdrBits = listOfNotNull(res.header.streamDvProfile, res.header.streamHdrType)
                if (hdrBits.isNotEmpty()) {
                    AssessmentFactRow(
                        label = stringResource(R.string.assessment_header_hdr),
                        value = hdrBits.joinToString(" \u00b7 ")
                    )
                }
            }
            }
        }
    }

    item(key = "assessment_sec_measured") {
        AssessmentSectionLabel(stringResource(R.string.assessment_section_measured))
    }
    item(key = "assessment_measured") {
        val measured = res.items.filter { it.tier == AssessmentTier.MEASURED }
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            if (res.sweepRan && measured.isNotEmpty()) {
                measured.forEach { AssessmentItemRow(it) }
                res.sweepVerdictText?.let { verdict ->
                    Text(
                        text = verdict,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(
                            horizontal = NuvioTheme.spacing.sm,
                            vertical = NuvioTheme.spacing.xs
                        )
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.assessment_measured_skipped),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(NuvioTheme.spacing.sm)
                )
            }
        }
    }

    item(key = "assessment_sec_calculated") {
        AssessmentSectionLabel(stringResource(R.string.assessment_section_calculated))
    }
    res.items.filter { it.tier == AssessmentTier.CALCULATED }.forEach { calcItem ->
        item(key = "assessment_calc_${calcItem.key}") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                AssessmentItemRow(calcItem)
            }
        }
    }

    item(key = "assessment_sec_priority") {
        AssessmentSectionLabel(stringResource(R.string.assessment_section_priority))
    }
    item(key = "assessment_priority") {
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            res.profiles.forEach { profile ->
                val values = if (profile.minCappedFromMs != null) {
                    stringResource(
                        R.string.assessment_profile_values_capped,
                        profile.initialBufferMs / 1000,
                        profile.rebufferMs / 1000,
                        profile.minBufferMs / 1000,
                        profile.minCappedFromMs / 1000
                    )
                } else {
                    stringResource(
                        R.string.assessment_profile_values,
                        profile.initialBufferMs / 1000,
                        profile.rebufferMs / 1000,
                        profile.minBufferMs / 1000
                    )
                }
                val suggested = res.suggestedProfile == profile.kind
                SettingsToggleRow(
                    title = if (suggested) {
                        profile.title + " \u00b7 " + stringResource(R.string.assessment_profile_suggested_tag)
                    } else {
                        profile.title
                    },
                    subtitle = profile.subtitle + "\n" + values,
                    checked = state.selectedProfile == profile.kind,
                    onToggle = { state.selectedProfile = profile.kind }
                )
            }
            run {
                val cov = res.stabilityCoV
                Text(
                    text = if (cov != null) {
                        stringResource(
                            R.string.assessment_stability_line,
                            "%.2f".format(cov),
                            res.stabilityPassCount
                        )
                    } else {
                        stringResource(
                            R.string.assessment_stability_insufficient,
                            res.stabilityPassCount
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.padding(
                        horizontal = NuvioTheme.spacing.sm,
                        vertical = NuvioTheme.spacing.xs
                    )
                )
            }
        }
    }

    item(key = "assessment_sec_verify") {
        AssessmentSectionLabel(stringResource(R.string.assessment_section_verify))
    }
    res.items.filter { it.tier == AssessmentTier.VERIFY }.forEach { verifyItem ->
        item(key = "assessment_verify_${verifyItem.key}") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                AssessmentItemRow(verifyItem)
            }
        }
    }

    item(key = "assessment_apply") {
        val context = LocalContext.current
        val dataStore = remember { assessmentDataStore(context) }
        val snapshotJson by dataStore.assessmentRevertSnapshot.collectAsStateWithLifecycle(
            initialValue = null
        )
        val res = state.result
        if (res != null || snapshotJson != null) {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                if (res != null) {
                    val previewCount = res.applyPlan.touchedCount +
                        (if (state.selectedProfile != null) 3 else 0)
                    SettingsActionRow(
                        title = stringResource(
                            if (state.applying) R.string.assessment_apply_applying
                            else R.string.assessment_apply_title
                        ),
                        subtitle = when {
                            state.appliedCount != null -> stringResource(
                                R.string.assessment_apply_done_sub, state.appliedCount ?: 0
                            )
                            state.applyArmed -> stringResource(
                                R.string.assessment_apply_confirm_sub, previewCount
                            )
                            else -> stringResource(R.string.assessment_apply_arm_sub)
                        },
                        enabled = !state.applying && previewCount > 0,
                        onClick = {
                            if (state.applyArmed) onApply() else state.applyArmed = true
                        }
                    )
                }
                if (snapshotJson != null) {
                    SettingsActionRow(
                        title = stringResource(R.string.assessment_revert_title),
                        subtitle = stringResource(R.string.assessment_revert_sub),
                        enabled = !state.applying,
                        onClick = onRevert
                    )
                }
            }
        }
    }

    item(key = "assessment_footer") {
        Text(
            text = stringResource(R.string.assessment_footer),
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.6f)
        )
    }
}

/** Focusable read-only recommendation row: title + recommended value, grounds, current. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun AssessmentItemRow(item: AssessmentItem) {
    var isFocused by remember { mutableStateOf(false) }
    val zen = isFlatSettingsStyle()
    val valueColor = when (item.memoryStatus) {
        com.nuvio.tv.ui.screens.settings.MemoryUsageStatus.DANGER -> NuvioTheme.colors.Error
        com.nuvio.tv.ui.screens.settings.MemoryUsageStatus.WARNING ->
            NuvioTheme.colors.Error.copy(alpha = 0.75f)
        else -> NuvioTheme.colors.TextPrimary
    }
    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (zen) androidx.compose.ui.graphics.Color.Transparent
            else NuvioTheme.colors.Background,
            focusedContainerColor = if (zen) settingsFocusFillColor()
            else NuvioTheme.colors.Background
        ),
        border = if (zen) {
            CardDefaults.border(border = Border.None, focusedBorder = Border.None)
        } else {
            CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                    shape = assessmentCardShape()
                )
            )
        },
        shape = CardDefaults.shape(assessmentCardShape()),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                Text(
                    text = item.recommendedValue,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = valueColor
                )
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
            Text(
                text = item.grounds,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
            if (item.changeNeeded && !item.currentValue.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                Text(
                    text = stringResource(R.string.assessment_currently, item.currentValue),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            } else if (!item.changeNeeded && !item.currentValue.isNullOrBlank() &&
                item.recommendedValue != stringResource(R.string.assessment_value_your_call) &&
                item.recommendedValue != stringResource(R.string.assessment_value_leave_as_is)
            ) {
                // Endorsement marker: the row asserts a concrete value and the
                // device already holds it. "No change" stays reserved for rows
                // where the assessment declines to recommend at all.
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                Text(
                    text = stringResource(R.string.assessment_already_set),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.Success
                )
            }
        }
    }
}

@Composable
private fun AssessmentFocusCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val zen = isFlatSettingsStyle()
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = if (zen) androidx.compose.ui.graphics.Color.Transparent
            else NuvioTheme.colors.Background,
            focusedContainerColor = if (zen) settingsFocusFillColor()
            else NuvioTheme.colors.Background
        ),
        border = if (zen) {
            CardDefaults.border(border = Border.None, focusedBorder = Border.None)
        } else {
            CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                    shape = assessmentCardShape()
                )
            )
        },
        shape = CardDefaults.shape(assessmentCardShape()),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.sm),
            content = content
        )
    }
}

@Composable
private fun AssessmentFactRow(label: String, value: String, valueMaxLines: Int = 1) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            maxLines = valueMaxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AssessmentSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
    )
}

@Composable
private fun AssessmentPassRow(
    label: String,
    speed: Double?,
    note: StreamSweepEngine.PassNote? = null,
    isRunning: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            note?.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
        }
        Text(
            text = when {
                isRunning -> stringResource(R.string.stream_test_btn_running)
                speed != null -> "%.1f Mbps".format(speed)
                note != null -> note.state
                else -> "---"
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = if (speed != null && !isRunning) {
                NuvioTheme.colors.TextPrimary
            } else {
                NuvioTheme.colors.TextTertiary
            }
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
