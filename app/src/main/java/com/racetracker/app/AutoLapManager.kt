package com.racetracker.app

import android.content.Context
import android.widget.Toast

class AutoLapManager(
    private val context: Context,
    private val lapDistance: Double = 1.0 // Default 1 km per lap
) {
    private var lastLapDistance = 0.0
    private var lapNumber = 0

    fun reset() {
        lastLapDistance = 0.0
        lapNumber = 0
    }

    fun checkDistance(currentDistance: Double) {
        if (currentDistance <= 0) return

        val lapsPassed = (currentDistance / lapDistance).toInt()
        val expectedLapDistance = lapsPassed * lapDistance

        // Check if we've crossed a lap boundary
        if (lapsPassed > lapNumber) {
            lapNumber = lapsPassed
            lastLapDistance = currentDistance

            // Calculate lap pace
            val lapTime = calculateLapTime()
            val lapPace = if (lapDistance > 0) lapTime / lapDistance else 0.0

            Toast.makeText(
                context,
                "🏁 Lap $lapNumber complete! (${String.format("%.2f", lapDistance)} km at ${String.format("%.2f", lapPace)} min/km)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun calculateLapTime(): Double {
        // This is a simplified calculation
        // In a real implementation, you'd track actual lap times
        return 5.0 // Placeholder: 5 minutes per lap
    }

    fun getLapNumber(): Int = lapNumber
}
