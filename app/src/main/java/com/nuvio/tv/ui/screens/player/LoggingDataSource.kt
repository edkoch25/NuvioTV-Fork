package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

class LoggingDataSource(
    private val upstream: DataSource,
    private val site: String
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val len = upstream.open(dataSpec)
            Log.i("DS_OPEN", "[$site] pos=${dataSpec.position} reqLen=${dataSpec.length} -> len=$len ms=${SystemClock.elapsedRealtime() - t0}")
            len
        } catch (e: Exception) {
            Log.w("DS_OPEN", "[$site] pos=${dataSpec.position} FAILED ${e.javaClass.simpleName}: ${e.message} ms=${SystemClock.elapsedRealtime() - t0}")
            throw e
        }
    }
    override fun close() = upstream.close()
}

class LoggingDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val site: String
) : DataSource.Factory {
    override fun createDataSource(): DataSource = LoggingDataSource(upstream.createDataSource(), site)
}
