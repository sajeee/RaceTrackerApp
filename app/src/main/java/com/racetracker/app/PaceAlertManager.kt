package com.racetracker.app

import android.os.Handler
import android.os.Looper
import kotlin.math.abs

class PaceAlertManager(
    private val onTooSlow: (() -> Unit)? = null,
    private val onTooFast: (() -> Unit)? = null,
    private val onHrZoneAlert: ((zone: Int, hr: Int) -> Unit)? = null
) {
    var paceLowerMinsPerKm: Double? = null // e.g., 6.0 means 6:00/km
    var paceUpperMinsPerKm: Double? = null // e.g., 4.0 means 4:00/km

    // HR zones as simple bounds in bpm for each zone (1..5)
    var hrZones: List<Int> = listOf() // [z1_upper, z2_upper, z3_upper, ...]

    private val handler = Handler(Looper.getMainLooper())
    private var lastTooSlowSent = 0L
    private var lastTooFastSent = 0L

    fun checkPace(speedMps: Double) {
        if (speedMps <= 0) return
        val paceMinPerKm = 1000.0 / speedMps / 60.0
        val now = System.currentTimeMillis()
        paceLowerMinsPerKm?.let { lower ->
            if (paceMinPerKm > lower && now - lastTooSlowSent > 15_000) {
                lastTooSlowSent = now
                onTooSlow?.invoke()
            }
        }
        paceUpperMinsPerKm?.let { upper ->
            if (paceMinPerKm < upper && now - lastTooFastSent > 15_000) {
                lastTooFastSent = now
                onTooFast?.invoke()
            }
        }
    }

    fun checkHeartRate(hr: Int) {
        if (hrZones.isEmpty()) return
        for (i in hrZones.indices) {
            if (hr <= hrZones[i]) {
                onHrZoneAlert?.invoke(i + 1, hr)
                return
            }
        }
        // if above last zone
        onHrZoneAlert?.invoke(hrZones.size + 1, hr)
    }
}
