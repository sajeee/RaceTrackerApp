package com.racetracker.app

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

object PerformanceMetrics {
    private const val G = 9.80665
    private const val Crr = 0.0025 // rolling resistance
    private const val DEFAULT_WEIGHT_KG = 70.0

    /** Estimate running power (W).
     *  speedMps: meters per second
     *  grade: decimal (0.05 = 5%)
     */
    fun estimatePowerW(speedMps: Double, grade: Double, weightKg: Double = DEFAULT_WEIGHT_KG): Double {
        if (!speedMps.isFinite()) return 0.0
        val climbing = weightKg * G * grade * speedMps
        val resistive = Crr * weightKg * G * speedMps
        val power = climbing + resistive
        return max(0.0, power)
    }

    /** Estimate instantaneous VO2 (ml/kg/min) using ACSM-like running eqn */
    fun estimateVo2(speedMps: Double, grade: Double): Double {
        val speedMpm = speedMps * 60.0
        val vo2 = (0.2 * speedMpm) + (0.9 * speedMpm * grade) + 3.5
        return vo2.coerceAtLeast(0.0)
    }

    /** Estimate VO2max from a best-effort maximal pace attempt (Cooper-style).
     *  If you have distance in meters run in 12 minutes: use cooperDistanceMeters -> VO2max = (distance - 504.9)/44.73
     *  Alternatively, you can treat the highest instantaneous VO2 observed during runs as VO2max.
     */
    fun estimateVo2MaxFromCooper(distanceMeters12min: Double): Double {
        return (distanceMeters12min - 504.9) / 44.73
    }

    /** Stride length in meters per step = speed (m/s) / (steps per second) = speed * 60 / cadenceSPM */
    fun estimateStrideLengthMeters(speedMps: Double, cadenceSpm: Double): Double {
        if (cadenceSpm <= 0.0) return 0.0
        return (speedMps * 60.0) / cadenceSpm
    }

    /** Simple grade calculation */
    fun gradeFromElevation(elevDeltaMeters: Double, distanceMeters: Double): Double {
        if (distanceMeters <= 0.0) return 0.0
        return elevDeltaMeters / distanceMeters
    }

    /** Simple smoothing: exponential moving average */
    fun smooth(prev: Double, current: Double, alpha: Double = 0.2): Double {
        return prev * (1.0 - alpha) + current * alpha
    }
}
