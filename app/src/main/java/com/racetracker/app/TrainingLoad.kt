package com.racetracker.app

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Compute a normalized-power-like metric for running using power samples.
 * NP computed via 30-second rolling average and 4th power method (borrowed from cycling).
 * TSS-like = (durationSeconds * NP) / (FTP * 3600) * 100
 */
class TrainingLoad {
    private val samples = ArrayList<Double>() // power watts samples (1 Hz recommended)
    fun addPowerSample(watts: Double) {
        samples.add(watts)
        if (samples.size > 36000) { // keep last 10 hours at 1Hz
            samples.removeAt(0)
        }
    }

    fun normalizedPower(windowSeconds: Int = 30): Double {
        if (samples.isEmpty()) return 0.0
        // compute rolling average over windowSeconds (assume 1 sample/sec)
        val rolling = ArrayList<Double>()
        val w = windowSeconds.coerceAtLeast(1)
        for (i in 0 until samples.size) {
            var sum = 0.0
            var count = 0
            val start = (i - w + 1).coerceAtLeast(0)
            for (j in start..i) { sum += samples[j]; count++ }
            val avg = if (count>0) sum / count else samples[i]
            rolling.add(avg)
        }
        // 4th-power mean
        val mean4 = rolling.map { it.pow(4.0) }.average()
        val np = mean4.pow(1.0 / 4.0)
        return np
    }

    /** TSS-like score
     *  ftpWatts: user-configured running "FTP" (functional threshold power) equivalent
     */
    fun tssLike(ftpWatts: Double, durationSeconds: Int): Double {
        if (ftpWatts <= 0.0) return 0.0
        val np = normalizedPower()
        val intensity = if (ftpWatts > 0) np / ftpWatts else 0.0
        val tss = (durationSeconds * np * intensity) / (ftpWatts * 3600.0) * 100.0
        return tss
    }
}
