package com.nuvio.tv.ui.screens.player

import android.util.Log
import java.io.File

/**
 * CPU clock / thermal-throttle indicator for the playback stats HUD.
 *
 * Why this and not a temperature. This box (Homatics, S905X4) exposes no app-readable SoC
 * temperature: its only thermal sensor is TYPE_CPU (getThermalHeadroom aggregates TYPE_SKIN
 * only, so it returns NaN), and the sysfs thermal tree carries the vendor sysfs_thermal
 * SELinux label and is unreadable even to the adb shell user (probed 2026-07-13/15). What IS
 * app-readable is cpufreq under /sys/devices/system/cpu/, which standard AOSP policy permits.
 *
 * What throttling looks like here. dumpsys thermalservice names the cooling devices as
 * cpufreq-cpu0 and thermal-gpufreq-0 — this SoC throttles by CAPPING CLOCK FREQUENCY. The cap
 * lives in scaling_max_freq; when the thermal governor engages it lowers that value below
 * cpuinfo_max_freq. So:
 *
 *   scaling_max_freq  == cpuinfo_max_freq  -> not throttling (green)
 *   scaling_max_freq  <  cpuinfo_max_freq  -> actively throttled (red)
 *
 * scaling_cur_freq (what the CPU is doing right now) is load-driven and is NOT a throttle
 * signal on its own — at idle it sits well below max because the governor scales down for load.
 * It is shown only as context; the dot is driven by the CAP vs max comparison.
 *
 * Relevance to this app specifically: libdovi's P7->8.1 RPU conversion is per-frame CPU work,
 * so a sustained thermal cap can degrade DV playback in particular — this row makes that visible
 * where a temperature never could.
 *
 * Fails closed: cpuinfo_max_freq is read once at init (it is constant). If it or scaling_max_freq
 * is unreadable, the row is omitted (no dead rows). Per-tick cost is two tiny sysfs reads, on the
 * same HUD sampling path as ProcessCpuSampler; nothing runs when the panel is closed.
 */
internal class PlaybackCpuClockSampler {

    data class Reading(
        val currentKHz: Long,
        val maxKHz: Long,
        val capKHz: Long
    ) {
        /** True when the governor's ceiling has been pulled below the hardware maximum. */
        val isThrottled: Boolean get() = capKHz in 1 until maxKHz
    }

    @Volatile
    private var maxKHzCached: Long = -1L

    @Volatile
    private var maxProbeFailed: Boolean = false

    fun sample(): Reading? {
        val maxKHz = readMaxKHz()
        if (maxKHz <= 0L) return null
        val capKHz = readLong(CAP_PATH) ?: return null
        // current is best-effort context; if it fails, fall back to the cap so the row still renders.
        val currentKHz = readLong(CUR_PATH) ?: capKHz
        return Reading(currentKHz = currentKHz, maxKHz = maxKHz, capKHz = capKHz)
    }

    private fun readMaxKHz(): Long {
        if (maxProbeFailed) return -1L
        val cached = maxKHzCached
        if (cached > 0L) return cached
        val v = readLong(MAX_PATH)
        if (v == null || v <= 0L) {
            maxProbeFailed = true
            Log.i(TAG, "cpuinfo_max_freq unreadable — CPU clock row disabled")
            return -1L
        }
        maxKHzCached = v
        return v
    }

    private fun readLong(path: String): Long? =
        runCatching { File(path).readText().trim().toLong() }.getOrNull()

    private companion object {
        const val TAG = "CpuClockSampler"
        const val CUR_PATH = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"
        const val MAX_PATH = "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq"
        const val CAP_PATH = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq"
    }
}
