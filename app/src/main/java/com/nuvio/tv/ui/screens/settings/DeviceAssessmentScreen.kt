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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.assessment.AssessmentItem
import com.nuvio.tv.core.assessment.AssessmentResult
import com.nuvio.tv.core.assessment.AssessmentTier
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine
import com.nuvio.tv.core.assessment.ProfileKind
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

/**
 * Device Settings Assessment, Stage 1 UI (REVIEW-ONLY).
 *
 * One card in the Advanced pane: a Run row, the live sweep pass rows (same
 * presentation as the stream test), then the tiered results - Measured /
 * Calculated / Choose-your-priority / Verify-yourself - with an honest
 * grounds line under every recommendation and the current value shown
 * whenever it differs. Nothing here writes a setting.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun DeviceAssessmentCard(
    playerSettings: PlayerSettings,
    diagnostics: LastPlaybackDiagnostics
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(false) }
    var sweepState by remember { mutableStateOf("") }
    var passRows by remember { mutableStateOf(listOf<Pair<String, Double?>>()) }
    var result by remember { mutableStateOf<AssessmentResult?>(null) }
    var selectedProfile by remember { mutableStateOf<ProfileKind?>(null) }

    val hasStream = !diagnostics.streamUrl.isNullOrBlank()

    fun runAssessment() {
        if (running) return
        scope.launch {
            running = true
            passRows = emptyList()
            sweepState = ""
            result = null
            val outcome = DeviceAssessmentEngine.run(
                context = context,
                activity = context.findActivity(),
                settings = playerSettings,
                diagnostics = diagnostics,
                onSweepState = { sweepState = it },
                onSweepPassAdded = { label ->
                    passRows = passRows + (label to null)
                },
                onSweepPassResult = { label, mbps ->
                    passRows = passRows.map { if (it.first == label) label to mbps else it }
                }
            )
            result = outcome
            selectedProfile = outcome.suggestedProfile
            running = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(
                    if (running) R.string.assessment_run_running else R.string.assessment_run_title
                ),
                subtitle = stringResource(
                    if (hasStream) R.string.assessment_run_subtitle else R.string.assessment_no_stream
                ),
                value = if (running && sweepState.isNotBlank()) sweepState else null,
                enabled = !running,
                onClick = { runAssessment() }
            )
        }

        if (passRows.isNotEmpty()) {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(NuvioTheme.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                ) {
                    passRows.forEach { (label, speed) ->
                        AssessmentPassRow(
                            label = label,
                            speed = speed,
                            isRunning = running && sweepState == label && speed == null
                        )
                    }
                }
            }
        }

        result?.let { res ->
            res.errorText?.let { err ->
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.assessment_error_prefix, err),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.Error,
                        modifier = Modifier.padding(NuvioTheme.spacing.sm)
                    )
                }
            }

            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.assessment_header_title)
            ) {
                Column(
                    modifier = Modifier.padding(NuvioTheme.spacing.xs),
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
                            ?: stringResource(R.string.assessment_header_stream_none)
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

            val measured = res.items.filter { it.tier == AssessmentTier.MEASURED }
            AssessmentSectionLabel(stringResource(R.string.assessment_section_measured))
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

            AssessmentSectionLabel(stringResource(R.string.assessment_section_calculated))
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                res.items.filter { it.tier == AssessmentTier.CALCULATED }
                    .forEach { AssessmentItemRow(it) }
            }

            AssessmentSectionLabel(stringResource(R.string.assessment_section_priority))
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                res.profiles.forEach { profile ->
                    val values = stringResource(
                        R.string.assessment_profile_values,
                        profile.initialBufferMs / 1000,
                        profile.rebufferMs / 1000,
                        profile.minBufferMs / 1000
                    )
                    val suggested = res.suggestedProfile == profile.kind
                    val suggestion = if (suggested) {
                        stringResource(
                            if (profile.kind == ProfileKind.STALL_RESISTANT) {
                                R.string.assessment_profile_suggested_variable
                            } else {
                                R.string.assessment_profile_suggested_stable
                            }
                        )
                    } else {
                        null
                    }
                    SettingsToggleRow(
                        title = profile.title,
                        subtitle = listOfNotNull(profile.subtitle, values, suggestion)
                            .joinToString("\n"),
                        checked = selectedProfile == profile.kind,
                        onToggle = { selectedProfile = profile.kind }
                    )
                }
                res.stabilityCoV?.let { cov ->
                    Text(
                        text = stringResource(
                            R.string.assessment_stability_line,
                            "%.2f".format(cov)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.padding(
                            horizontal = NuvioTheme.spacing.sm,
                            vertical = NuvioTheme.spacing.xs
                        )
                    )
                }
            }

            AssessmentSectionLabel(stringResource(R.string.assessment_section_verify))
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                res.items.filter { it.tier == AssessmentTier.VERIFY }
                    .forEach { AssessmentItemRow(it) }
            }

            Text(
                text = stringResource(R.string.assessment_footer),
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.6f)
            )
        }
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
                    shape = settingsRowShape()
                )
            )
        },
        shape = CardDefaults.shape(settingsRowShape()),
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
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            if (item.changeNeeded && !item.currentValue.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                Text(
                    text = stringResource(R.string.assessment_currently, item.currentValue),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
        }
    }
}

@Composable
private fun AssessmentFactRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
    isRunning: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Text(
            text = when {
                isRunning -> stringResource(R.string.stream_test_btn_running)
                speed != null -> "%.1f Mbps".format(speed)
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
