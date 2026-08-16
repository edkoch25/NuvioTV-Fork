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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails

/**
 * T-series Build 3: seek-thumbnail pane. Renders only while a held-key preview seek is in
 * flight (uiState.pendingPreviewSeekPosition non-null). Pure local lookup - memory hit
 * shows immediately, async disk hits arrive via SeekThumbnails.tick, a true miss renders
 * nothing (frontier gap = blank + the timestamp the controls already show). v1 places the
 * pane bottom-centre above the controls, not tracking the scrubber x-position.
 * Surface uses black-alpha per the player-overlay UI rule.
 */
@Composable
fun SeekThumbnailOverlayHost(uiState: PlayerUiState, modifier: Modifier = Modifier) {
    val previewPositionMs = uiState.pendingPreviewSeekPosition ?: return
    val tick by SeekThumbnails.tick
    val bitmap = remember(previewPositionMs, tick) { SeekThumbnails.thumbFor(previewPositionMs) }
        ?: return
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .padding(bottom = 120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(3.dp)
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .width(224.dp)
                    .height(126.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
        }
    }
}
