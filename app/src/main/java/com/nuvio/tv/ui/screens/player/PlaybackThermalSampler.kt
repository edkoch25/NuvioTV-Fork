package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File

/**
 * SoC temperature for the playback stats HUD.
 *
 * Why the ladder. The APIs that hand out exact temperatures are gated: sysfs thermal
 * zones (`/sys/class/thermal/thermal_zone<N>/temp`) carry the vendor SELinux label
 * `sysfs_thermal` on the Homatics and are unreadable even to the adb shell user
 * (probed 2026-07-13), and HardwarePropertiesManager is device-owner-only. What a
 * normal app *can* get on API 30+ is PowerManager.getThermalHeadroom(): a normalised
 * "fraction of the way to severe throttling", where 1.0 is the SEVERE threshold —
 * computed by the framework from the same thermal HAL that dumpsys thermalservice
 * shows healthy on this box (AIDL v1, soc_thermal sensor).
 *
 * Tiers, tried in order:
 *   1. sysfs °C — attempted ONCE at first sample and the verdict cached, so the
 *      expected SELinux denial is a single log line, not one per tick. Kept because
 *      vendor policy differs per box: the Xiaomi (task 4.0) may answer differently,
 *      and if it does the row upgrades to real degrees for free. Reads the first zone
 *      whose type contains "soc" or "cpu", millidegrees.
 *   2. getThermalHeadroom(0) — the shipping tier on the Homatics. NaN (HAL absent or
 *      called faster than ~1 Hz) reads as unavailable for that tick; the HUD tick is
 *      1 Hz, which matches the API's own rate limit.
 *
 * Both dead: sample() returns null and the row is hidden (the Request-row precedent:
 * no dead rows).
 *
 * Calibration: headroom is linear in the HAL temperature against the severe threshold,
 * and this box's ladder is known (severe = 95 °C). Whether the normalisation window's
 * low anchor is 0 °C or vendor-chosen is implementation-defined, so no °C conversion
 * is displayed unless calibrated against dumpsys pairs — the acceptance procedure
 * covers taking those pairs; until then the row shows raw headroom and never fakes a
 * unit.
 *
 * Threading: called from the same HUD sampling path as ProcessCpuSampler (which reads
 * /proc/self/stat there); one tiny sysfs read at init plus one binder call per tick
 * matches that existing cost profile. HUD-only — nothing here runs when the panel is
 * closed.
 */
internal class PlaybackThermalSampler(context: Context) {

    data class Reading(
        /** Exact SoC temperature, only when sysfs is app-readable on this device. */
        val celsius: Float?,
        /** Normalised distance to severe throttling (1.0 = severe), when the HAL serves it. */
        val headroom: Float?
    )

    private val powerManager = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as? PowerManager

    // Tier-1 verdict, decided once. null = not yet probed; File = readable zone temp
    // node; NO_SYSFS = probed and denied/absent.
    @Volatile
    private var sysfsTempNode: File? = null

    @Volatile
    private var sysfsProbed: Boolean = false

    fun sample(): Reading? {
        val celsius = readSysfsCelsius()
        val headroom = readHeadroom()
        if (celsius == null && headroom == null) return null
        return Reading(celsius = celsius, headroom = headroom)
    }

    private fun readSysfsCelsius(): Float? {
        if (!sysfsProbed) probeSysfsOnce()
        val node = sysfsTempNode ?: return null
        return runCatching {
            node.readText().trim().toLong().let { milli ->
                if (milli > 1000L) milli / 1000f else milli.toFloat()
            }
        }.getOrElse {
            // Went unreadable after a successful probe (shouldn't happen): demote once.
            Log.w(TAG, "sysfs temp read failed after successful probe: ${it.message}")
            sysfsTempNode = null
            null
        }
    }

    private fun probeSysfsOnce() {
        sysfsProbed = true
        val result = runCatching {
            for (i in 0 until MAX_ZONES) {
                val zone = File("/sys/class/thermal/thermal_zone$i")
                if (!zone.exists()) break
                val type = runCatching { File(zone, "type").readText().trim().lowercase() }
                    .getOrNull() ?: continue
                if ("soc" in type || "cpu" in type) {
                    val temp = File(zone, "temp")
                    // Prove readability now so per-tick reads cannot surprise.
                    temp.readText().trim().toLong()
                    sysfsTempNode = temp
                    Log.i(TAG, "sysfs thermal readable: zone$i type=$type")
                    return@runCatching
                }
            }
            Log.i(TAG, "sysfs thermal: no readable soc/cpu zone (expected under vendor SELinux)")
        }
        result.onFailure {
            Log.i(TAG, "sysfs thermal unreadable (${it.javaClass.simpleName}) — using headroom tier")
        }
    }

    private fun readHeadroom(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val pm = powerManager ?: return null
        if (headroomHalDead) return null
        val headroom = runCatching { pm.getThermalHeadroom(0) }
            .getOrNull()
            ?.takeIf { !it.isNaN() && it >= 0f }
        if (headroom == null) {
            headroomConsecutiveFailures += 1
            if (headroomConsecutiveFailures >= HEADROOM_FAILURES_BEFORE_DEAD) {
                headroomHalDead = true
                Log.i(
                    TAG,
                    "thermal headroom HAL unusable ($headroomConsecutiveFailures consecutive " +
                        "failures) — not polling again this process; each poll makes " +
                        "system_server hammer the broken HAL"
                )
            }
        } else {
            headroomConsecutiveFailures = 0
        }
        return headroom
    }

    private companion object {
        const val TAG = "ThermalSampler"
        const val MAX_ZONES = 16

        // nt7: on boxes whose thermal HAL cannot serve temperatures at all (Xiaomi
        // MiTV-AFMU0: IThermal.getTemperaturesWithType throws "cannot read any sensor
        // data"), every getThermalHeadroom() call makes system_server's
        // TemperatureWatcher retry against the dead HAL and log a full stack trace
        // per attempt — measured at ~1,360 log lines/second sustained for as long as
        // the stats HUD stays open. Mirror the sysfs probe-once precedent: after this
        // many CONSECUTIVE failures, latch the headroom tier off for the rest of the
        // process and let the row hide. Occasional NaN is legitimate (the API
        // rate-limits at ~1 Hz and HUD ticks can jitter under it), hence a
        // consecutive-failure count with reset-on-success rather than probe-once — a
        // healthy HAL that answers even occasionally never latches.
        const val HEADROOM_FAILURES_BEFORE_DEAD = 10

        // Process-wide: a dead HAL is a property of the box, not of one player session.
        @Volatile
        var headroomHalDead: Boolean = false

        @Volatile
        var headroomConsecutiveFailures: Int = 0
    }
}
