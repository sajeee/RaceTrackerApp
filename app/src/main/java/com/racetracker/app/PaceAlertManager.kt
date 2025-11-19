package com.racetracker.app

import android.content.Context
import android.widget.Toast
import java.util.concurrent.TimeUnit

class PaceAlertManager(
    private val context: Context,
    private val targetPace: Float
) {
    private var lastAlertTime: Long = 0
    private val alertCooldown = TimeUnit.SECONDS.toMillis(30) // Alert every 30 seconds max
    private var hasStartedTracking = false
    private var lastDistance = 0.0

    fun reset() {
        lastAlertTime = 0
        hasStartedTracking = false
        lastDistance = 0.0
    }

    fun checkPace(currentPace: Double, currentDistance: Double) {
        // Only check pace if we've moved at least 0.1 km (100 meters)
        if (currentDistance < 0.1) {
            return
        }

        // Only alert if distance has actually changed
        if (currentDistance == lastDistance) {
            return
        }
        lastDistance = currentDistance

        val currentTime = System.currentTimeMillis()
        
        // Check if enough time has passed since last alert
        if (currentTime - lastAlertTime < alertCooldown) {
            return
        }

        // Check if pace is too fast (more than 20% faster than target)
        val paceDifference = targetPace - currentPace
        if (paceDifference > targetPace * 0.2) {
            Toast.makeText(
                context,
                "Pace too fast! Target: ${String.format("%.2f", targetPace)} min/km",
                Toast.LENGTH_SHORT
            ).show()
            lastAlertTime = currentTime
        }
        // Check if pace is too slow (more than 20% slower than target)
        else if (paceDifference < -targetPace * 0.2) {
            Toast.makeText(
                context,
                "Pace too slow! Target: ${String.format("%.2f", targetPace)} min/km",
                Toast.LENGTH_SHORT
            ).show()
            lastAlertTime = currentTime
        }
    }
}
