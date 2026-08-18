@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.SourceChipStatus
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.ui.components.StreamBadgeChips
import com.nuvio.tv.ui.components.sourceBadgeResources
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
internal fun StreamItem(
    stream: Stream,
    focusRequester: FocusRequester,
    requestInitialFocus: Boolean,
    isCurrentStream: Boolean = false,
    isDeadSource: Boolean = false,
    showFileSizeBadges: Boolean = true,
    showAddonLogo: Boolean = true,
    badgePlacement: StreamBadgePlacement = StreamBadgePlacement.BOTTOM,
    onClick: () -> Unit,
    onUpKey: (() -> Unit)? = null
) {
    // Title: "addon — release group" (release group dropped when underivable, worst
    // case addon name alone). Format details live in the badge row below the title —
    // the user's Fusion badges when configured, else the built-in badge set — so
    // resolution/quality/HDR/audio/channels/size read off badges rather than a text
    // subline that goes sparse on debrid sources whose resolver parse is empty.
    val releaseGroup = remember(stream) {
        com.nuvio.tv.core.debrid.DirectDebridStreamFilter.releaseGroupOf(stream)
    }
    val rowTitle = listOfNotNull(
        stream.addonName,
        releaseGroup.takeIf { it.isNotBlank() }
    ).joinToString(" — ")

    // Fusion badges (if the user configured them) are already computed off-thread and
    // ride on stream.badges. Otherwise derive the built-in badge set from the parsed
    // facts. factsFor's badge fields are preference-independent (only its *Rank fields
    // read preferences), so default preferences give correct badges.
    val fusionBadges = remember(stream.badges) {
        stream.badges.filter { it.imageURL.isNotBlank() }
    }
    val builtinBadgeRes = remember(stream, fusionBadges.isEmpty()) {
        if (fusionBadges.isNotEmpty()) emptyList()
        else sourceBadgeResources(
            com.nuvio.tv.core.debrid.DirectDebridStreamFilter.factsFor(
                stream,
                DebridStreamPreferences()
            )
        )
    }
    val sizeBytes = if (showFileSizeBadges) stream.behaviorHints?.videoSize else null
    val hasBadges = fusionBadges.isNotEmpty() || builtinBadgeRes.isNotEmpty()

    // Zero-badge fallback: keep the old "size · audio" text subline so the row is never
    // bare when nothing parses.
    val fallbackSubtitle = if (hasBadges) null else {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val audioSegment = ((parsed?.audio ?: emptyList()) + (parsed?.channels ?: emptyList()))
            .filter { it.isNotBlank() }
            .joinToString(" ").ifBlank { null }
        val sizeSegment = sizeBytes?.let { bytes ->
            if (bytes >= 1_073_741_824L) "%.1f GB".format(bytes / 1_073_741_824.0)
            else "%.0f MB".format(bytes / 1_048_576.0)
        }
        listOfNotNull(sizeSegment, audioSegment).joinToString(" · ").ifBlank { null }
    }

    PlayerPanelRow(
        title = rowTitle,
        subtitle = fallbackSubtitle,
        selected = isCurrentStream,
        onClick = onClick,
        focusRequester = if (requestInitialFocus) focusRequester else null,
        modifier = Modifier
            .then(if (isDeadSource) Modifier.alpha(0.45f) else Modifier)
            .then(if (onUpKey != null) Modifier.onKeyEvent { event ->
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                    event.key == Key.DirectionUp) {
                    onUpKey(); true
                } else false
            } else Modifier),
        belowContent = if (hasBadges) {
            { focused ->
                StreamRowBadges(
                    fusionBadges = fusionBadges,
                    builtinBadgeRes = builtinBadgeRes,
                    sizeBytes = sizeBytes,
                    focused = focused
                )
            }
        } else null,
        trailing = null
    )
}

/**
 * Badge row rendered below a stream's title. Prefers the user's Fusion badges
 * (image chips) when present; otherwise renders the built-in drawable badge set.
 * A file-size chip is appended when [sizeBytes] is non-null. The row clips and
 * marquee-scrolls on focus, matching the pre-play stream list.
 */
@Composable
private fun StreamRowBadges(
    fusionBadges: List<com.nuvio.tv.domain.model.StreamBadge>,
    builtinBadgeRes: List<Int>,
    sizeBytes: Long?,
    focused: Boolean
) {
    if (fusionBadges.isNotEmpty()) {
        StreamBadgeChips(
            badges = fusionBadges,
            fileSizeBytes = sizeBytes,
            showFileSizeBadge = sizeBytes != null,
            focused = focused,
            modifier = Modifier
        )
        return
    }
    if (builtinBadgeRes.isEmpty() && sizeBytes == null) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .then(
                if (focused) Modifier.basicMarquee(
                    iterations = Int.MAX_VALUE,
                    velocity = 45.dp,
                    spacing = MarqueeSpacing(36.dp)
                ) else Modifier
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            for (res in builtinBadgeRes) {
                Image(
                    painter = painterResource(id = res),
                    contentDescription = null,
                    modifier = Modifier.height(20.dp),
                    contentScale = ContentScale.FillHeight
                )
            }
            if (sizeBytes != null) {
                val label = if (sizeBytes >= 1_073_741_824L) "%.1f GB".format(sizeBytes / 1_073_741_824.0)
                    else "%.0f MB".format(sizeBytes / 1_048_576.0)
                Box(
                    modifier = Modifier
                        .height(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0A0C0C))
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AddonFilterChips(
    addons: List<String>,
    sourceChips: List<SourceChipItem> = emptyList(),
    selectedAddon: String?,
    onAddonSelected: (String?) -> Unit,
    externalFocusRequesters: List<FocusRequester>? = null,
    externalOrderedNames: List<String>? = null
) {
    val chipMap = sourceChips.associateBy { it.name }
    val orderedNames = externalOrderedNames ?: buildList {
        addAll(addons)
        sourceChips.forEach { chip -> if (chip.name !in this) add(chip.name) }
    }
    val focusRequesters = externalFocusRequesters ?: remember(orderedNames.size) {
        List(orderedNames.size + 1) { FocusRequester() }
    }


    val selectedIndex = if (selectedAddon == null) 0 else orderedNames.indexOf(selectedAddon) + 1
    // Track the focused chip index to handle duplicate addon names correctly.
    var focusedChipIndex by remember { mutableStateOf(selectedIndex.coerceAtLeast(0)) }
    LaunchedEffect(selectedAddon, orderedNames) {
        val idx = if (selectedAddon == null) 0 else (orderedNames.indexOf(selectedAddon) + 1).coerceAtLeast(0)
        focusedChipIndex = idx
    }
    LaunchedEffect(selectedAddon) {
        if (selectedIndex >= 0 && selectedIndex < focusRequesters.size) {
            try { focusRequesters[selectedIndex].requestFocus() } catch (_: Exception) {}
        }
    }

    var chipRowHasFocus by remember { mutableStateOf(false) }
    val lastKeyRepeatDispatchRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        modifier = Modifier
            .focusRestorer {
                val idx = focusedChipIndex.coerceIn(0, focusRequesters.lastIndex)
                focusRequesters[idx]
            }
            .onFocusChanged { chipRowHasFocus = it.hasFocus }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onKeyEvent false

                // Throttle rapid key repeats (long-press)
                if (event.nativeKeyEvent.repeatCount > 0) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastKeyRepeatDispatchRef.get() < 112L) return@onKeyEvent true
                    lastKeyRepeatDispatchRef.set(now)
                }

                val allOptions = listOf<String?>(null) + orderedNames
                val currentIdx = focusedChipIndex.coerceIn(0, allOptions.lastIndex)
                when (event.key) {
                    androidx.compose.ui.input.key.Key.DirectionLeft -> {
                        if (currentIdx > 0) { focusedChipIndex = currentIdx - 1; onAddonSelected(allOptions[currentIdx - 1]); true } else false
                    }
                    androidx.compose.ui.input.key.Key.DirectionRight -> {
                        if (currentIdx < allOptions.lastIndex) { focusedChipIndex = currentIdx + 1; onAddonSelected(allOptions[currentIdx + 1]); true } else false
                    }
                    else -> false
                }
            }
    ) {
        item {
            SourceStatusFilterChip(
                name = stringResource(R.string.stream_filter_all),
                isSelected = selectedAddon == null,
                status = SourceChipStatus.SUCCESS,
                onClick = { onAddonSelected(null) },
                modifier = Modifier
                    .focusRequester(focusRequesters[0])
                    .focusProperties { canFocus = selectedAddon == null || chipRowHasFocus }
            )
        }

        items(orderedNames.size) { i ->
            val addon = orderedNames[i]
            val chipStatus = chipMap[addon]?.status ?: SourceChipStatus.SUCCESS
            val isSelectable = addon in addons && chipStatus == SourceChipStatus.SUCCESS
            SourceStatusFilterChip(
                name = addon,
                isSelected = selectedAddon == addon,
                status = chipStatus,
                isSelectable = isSelectable,
                onClick = { onAddonSelected(addon) },
                modifier = Modifier.focusRequester(focusRequesters[i + 1])
            )
        }
    }
}
