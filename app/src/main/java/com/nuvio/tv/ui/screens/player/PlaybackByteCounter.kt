package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Cumulative byte counter for the playback stats HUD.
 *
 * Why this exists. media3's `DefaultBandwidthMeter` publishes a bandwidth sample
 * only from `onTransferEnd` (androidx/media 1.8.0, `DefaultBandwidthMeter.kt`:
 * `onTransferEnd` -> `maybeNotifyBandwidthSample`; `onBytesTransferred` merely adds
 * to a private field and notifies nobody). `ProgressiveMediaPeriod` opens one
 * DataSource for the whole of a progressive stream and, once the buffer is full,
 * blocks the loading thread on `loadCondition` with that transfer still open,
 * closing it only at the end of the load. So on a plain progressive stream — an MKV
 * remux with parallel connections off — no transfer ever ends, no bandwidth sample
 * is ever emitted, and no load ever completes. Both of the HUD's byte inputs then
 * freeze after the extractor's opening header reads: `bandwidthBytesTotal` (fed by
 * `onBandwidthEstimate`) and `totalBytesLoaded` (fed by `onLoadCompleted`). The
 * V-bitrate row divides those few stale megabytes by the whole media span and
 * reports "avg mux 0.1 Mbit/s" on a 4K remux, and the Speed row shows a bandwidth
 * estimate that has not been updated since the first seconds of playback.
 *
 * The chunked paths hid this. ParallelRangeDataSource and the MP4 session source
 * issue bounded range requests, and every chunk is a transfer that ends — so the
 * meter keeps sampling and the accumulator keeps growing. The bug is confined to the
 * plain path: progressive, non-MP4, parallel connections off.
 *
 * `onBytesTransferred` fires continuously during an open transfer, so counting here
 * is independent of the shape of the source. Only leaf sources report bytes
 * (`DefaultHttpDataSource` / `OkHttpDataSource` with `isNetwork = true`,
 * `FileDataSource` with `isNetwork = false`); `DefaultDataSource`, `CacheDataSource`,
 * `ParallelRangeDataSource` and `LoggingDataSource` forward listeners downward
 * without reporting bytes of their own, so nothing is double counted.
 *
 * [networkBytes] excludes VOD-cache reads so the Speed row stays a measurement of the
 * link, while [totalBytes] counts every byte handed to the extractor so the V-bitrate
 * row stays a measurement of the stream — including on a cached replay, where the
 * old network-only accounting reported nothing at all.
 */
@UnstableApi
internal object PlaybackByteCounter : TransferListener {

    private val total = AtomicLong(0L)
    private val network = AtomicLong(0L)

    /** Every byte delivered to the player this session, network or cache. */
    val totalBytes: Long get() = total.get()

    /** Bytes that crossed the network this session (cache reads excluded). */
    val networkBytes: Long get() = network.get()

    // Content length per opened URI. DataSource.open() returns the bytes readable from
    // the spec's start position, so for an unbounded request position + length is the
    // size of the whole resource. Keyed by URI because sidecar subtitles are loaded
    // through the same factory and must not be mistaken for the video.
    private val contentLengths = ConcurrentHashMap<String, Long>()

    fun recordContentLength(uri: Uri, bytes: Long) {
        if (bytes > 0L) contentLengths[uri.toString()] = bytes
    }

    /**
     * Size of the resource at [url], or the largest resource opened this session when the
     * URL does not match (a redirect, or a stream URL rewritten after the media item was
     * built) — the video is always the largest thing the player opens.
     */
    fun contentLengthFor(url: String?): Long? =
        url?.let { contentLengths[it] } ?: contentLengths.values.maxOrNull()

    /** Cleared at each player-session reset, alongside the analytics counters. */
    fun reset() {
        total.set(0L)
        network.set(0L)
        contentLengths.clear()
    }

    override fun onTransferInitializing(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean
    ) = Unit

    override fun onTransferStart(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean
    ) = Unit

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int
    ) {
        if (bytesTransferred <= 0) return
        val delta = bytesTransferred.toLong()
        total.addAndGet(delta)
        if (isNetwork) network.addAndGet(delta)
    }

    override fun onTransferEnd(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean
    ) = Unit
}

/**
 * Records the size of each resource the player opens. Only an unbounded request says
 * anything about the total: bounded ones (cache spans, the range reads inside
 * ParallelRangeDataSource) describe a slice, not the file.
 */
@UnstableApi
internal class CountingDataSource(
    private val upstream: DataSource
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        if (dataSpec.length == C.LENGTH_UNSET.toLong() && length != C.LENGTH_UNSET.toLong() && length > 0L) {
            PlaybackByteCounter.recordContentLength(dataSpec.uri, dataSpec.position + length)
        }
        return length
    }

    override fun close() = upstream.close()
}

/**
 * Attaches [PlaybackByteCounter] to every DataSource the player creates, and wraps it so
 * the content length reported at open() is captured. It sits at the outermost layer of
 * the progressive chain, so the counter reaches exactly the same leaf sources that media3
 * hands its own bandwidth meter to.
 */
@UnstableApi
internal class CountingDataSourceFactory(
    private val upstream: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        CountingDataSource(
            upstream.createDataSource().apply { addTransferListener(PlaybackByteCounter) }
        )
}
