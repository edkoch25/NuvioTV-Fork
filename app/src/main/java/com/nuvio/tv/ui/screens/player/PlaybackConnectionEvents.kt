package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicLong

/**
 * N6 V2: prices a connection open on the playback path.
 *
 * Three opens sit on the startup critical path -- the probe inside
 * ParallelRangeDataSource.open(), chunk 0's own, and the tail continuation.
 * Each has been measured at 600-1,000 ms against a 110 ms RTT to the APAC
 * CDN, leaving ~400-700 ms per open unexplained after subtracting every
 * plausible transport cost. That surplus sits inside the segment which is
 * 85-95% of TTFF, and no byte-count optimisation has ever touched it. Until
 * the composition of one open is known, further client-side work is guesswork.
 *
 * Deliberately connection-level only. A per-callback listener on a 5 GB
 * episode at 8 MB chunks would emit tens of thousands of lines into a 16 MB
 * logcat ring and evict the evidence it was capturing; one summary line per
 * call is ~625 lines per episode instead.
 *
 * What each field answers:
 *  - pooled=true with no connect phase is a ConnectionPool reuse, which is
 *    S1g's stated mechanism. pooled=false on the probe would mean the prewarm
 *    warmed a pool the probe does not read from.
 *  - opens>1 in a single call is a redirect to a different address, which
 *    every existing instrument counts as one open.
 *  - proto is the negotiated protocol. Playback is expected to be http/1.1,
 *    since applyNetworkOptimizations pins it when the h2 toggle is off.
 *  - range distinguishes the prewarm (bytes=0-0) from the probe and chunks.
 */
internal class PlaybackConnectionEventListener(
    private val id: Long
) : EventListener() {

    private var callT0 = 0L
    private var dnsT0 = 0L
    private var connT0 = 0L
    private var tlsT0 = 0L

    private var dnsMs = -1L
    private var connMs = -1L
    private var tlsMs = -1L
    private var headersMs = -1L

    private var opens = 0
    private var range: String? = null
    private var host: String? = null
    private var proto: String? = null
    private var code = -1

    private fun now() = SystemClock.elapsedRealtime()

    override fun callStart(call: Call) {
        callT0 = now()
        val request = call.request()
        range = request.header("Range")
        host = request.url.host
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsT0 = now()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsMs = now() - dnsT0
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        opens += 1
        connT0 = now()
    }

    override fun secureConnectStart(call: Call) {
        tlsT0 = now()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsMs = now() - tlsT0
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) {
        connMs = now() - connT0
        if (protocol != null) proto = protocol.toString()
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) {
        connMs = now() - connT0
        Log.w(TAG, "NET_CONN id=$id connectFailed after ${connMs}ms host=$host err=${ioe.message}")
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        if (proto == null) proto = connection.protocol().toString()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        headersMs = now() - callT0
        code = response.code
    }

    override fun callEnd(call: Call) {
        emit("ok")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        emit("failed")
    }

    private fun emit(outcome: String) {
        val totalMs = now() - callT0
        val rangeLabel = range ?: "none"
        val hostLabel = host ?: "unknown"
        val protoLabel = proto ?: "unknown"
        Log.i(
            TAG,
            "NET_CONN id=$id outcome=$outcome pooled=${opens == 0} opens=$opens " +
                "dns=${dnsMs}ms connect=${connMs}ms tls=${tlsMs}ms " +
                "headers=${headersMs}ms total=${totalMs}ms " +
                "code=$code proto=$protoLabel range=$rangeLabel host=$hostLabel"
        )
    }

    private companion object {
        const val TAG = "NuvioNet"
    }
}

/**
 * One listener instance per call, so the per-call state above needs no
 * synchronisation. Attached to PlayerPlaybackNetworking.playbackHttpClient,
 * which every playback client is derived from via newBuilder() -- and
 * OkHttpClient.Builder(client) copies eventListenerFactory, while
 * applyNetworkOptimizations sets no listener, so nothing overwrites it.
 */
internal object PlaybackConnectionEvents : EventListener.Factory {
    private val seq = AtomicLong(0L)

    override fun create(call: Call): EventListener =
        PlaybackConnectionEventListener(seq.incrementAndGet())
}
