/*
 * NuvioTV-Fork - seek-thumbnail workstream (T-series)
 * Copyright (C) 2026 NuvioTV-Fork contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails

/**
 * T-series Build 6: seek-thumbnail pane. Renders only while a held-key preview seek is in
 * flight (pendingPreviewSeekPosition non-null). Pure local lookup - memory hit shows
 * immediately, async disk hits arrive via SeekThumbnails.tick, a true miss renders nothing
 * (frontier gap = blank + the timestamp the controls already show).
 *
 * Placement: bar-anchored (bottom), and horizontally tracks the playhead via BiasAlignment
 * (fraction = previewPos/duration), clamping at the screen insets so it never overflows -
 * the same span as the ProgressBar (spacing.xxl = 32.dp inset). Thumb is sized by the source
 * bitmap's aspect (fixed height, width = height * aspect) so non-16:9 frames don't distort.
 * Surface is black-alpha per the player-overlay UI rule.
 */
@Composable
fun SeekThumbnailOverlayHost(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    // Composable reads first (consistent call order), then early returns.
    val timeline by viewModel.playbackTimeline.collectAsState()
    val tick by SeekThumbnails.tick
    val previewPositionMs = uiState.pendingPreviewSeekPosition ?: return
    val durationMs = timeline.duration
    if (durationMs <= 0L) return
    val bitmap = remember(previewPositionMs, tick) { SeekThumbnails.thumbFor(previewPositionMs) }
        ?: return

    val fraction = (previewPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
    val thumbHeight = 108.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, bottom = 140.dp),
        contentAlignment = BiasAlignment(
            horizontalBias = fraction * 2f - 1f,
            verticalBias = 1f
        )
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.85f))
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .height(thumbHeight)
                    .width(thumbHeight * aspect)
                    .clip(RoundedCornerShape(6.dp))
            )
        }
    }
}
