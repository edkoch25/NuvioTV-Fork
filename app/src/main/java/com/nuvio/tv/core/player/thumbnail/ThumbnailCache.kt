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
package com.nuvio.tv.core.player.thumbnail

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.StatFs
import android.util.Log
import android.util.LruCache
import java.io.File
import java.security.MessageDigest

/**
 * Disk + memory cache for seek thumbnails (rev5 S5 rules, v1):
 * - Key: sha1(titleKey|durationMs) - video identity, never the URL (debrid URLs rotate).
 *   v1 deviation, documented: rev5 asks for contentId+fileSize; title+durationMs is the
 *   identity available at the player layer today and survives URL rotation. Fold in
 *   contentId+size when the provider layer exposes them.
 * - Persistent LRU across sessions: 200 MB standard / 50 MB low tier, evict oldest by
 *   lastModified across all title dirs; free-space check before every write.
 * - In-RAM bitmap LRU: 10 thumbs standard / 4 low tier.
 * - Entries: one JPEG per 30 s bucket, ~640x360 (worker downscales via GL effect).
 */
class ThumbnailCache(context: Context, titleKey: String, durationMs: Long) {
    companion object {
        private const val TAG = "ThumbCache"
        private const val ROOT_DIR = "seek_thumbs"
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024

        fun isLowTier(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return true
            if (am.isLowRamDevice) return true
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            return info.totalMem < 2_500L * 1024 * 1024
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }

    private val appContext = context.applicationContext
    private val lowTier = isLowTier(appContext)
    private val diskBudgetBytes = if (lowTier) 50L * 1024 * 1024 else 200L * 1024 * 1024
    private val rootDir = File(appContext.cacheDir, ROOT_DIR)
    private val titleDir = File(rootDir, sha1("$titleKey|$durationMs").take(24))
    private val memCache = LruCache<Long, Bitmap>(if (lowTier) 4 else 10)

    fun getMem(bucket: Long): Bitmap? = memCache.get(bucket)

    fun putMem(bucket: Long, bitmap: Bitmap) {
        memCache.put(bucket, bitmap)
    }

    fun hasDisk(bucket: Long): Boolean = File(titleDir, "$bucket.jpg").exists()

    /** IO-thread only. */
    fun readDisk(bucket: Long): Bitmap? {
        val f = File(titleDir, "$bucket.jpg")
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /** IO-thread only. Returns true when the entry was written. */
    fun writeDisk(bucket: Long, bitmap: Bitmap): Boolean {
        return runCatching {
            if (!titleDir.exists() && !titleDir.mkdirs()) return false
            val stat = StatFs(appContext.cacheDir.absolutePath)
            if (stat.availableBytes < MIN_FREE_BYTES) {
                Log.w(TAG, "skip write: low free space")
                return false
            }
            val f = File(titleDir, "$bucket.jpg")
            f.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out) }
            enforceBudget()
            true
        }.getOrDefault(false)
    }

    /** IO-thread only. Evict oldest files across all title dirs until under budget. */
    private fun enforceBudget() {
        val files = rootDir.walkTopDown().filter { it.isFile }.toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= diskBudgetBytes) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= diskBudgetBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
