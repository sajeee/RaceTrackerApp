package com.racetracker.app

import android.util.Log
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Extension functions for LiveTrackerActivity to handle analytics
 */

private const val TAG = "LiveTrackerExtension"

/**
 * Save completed race to analytics database
 * FIXED: Uses correct RaceData constructor with all required fields
 */
fun LiveTrackerActivity.saveRaceToAnalytics(
    raceStartTime: Long,  // Actual time when race started
    distance: Double,
    duration: Long,
    avgPace: Double,
    maxSpeed: Double,
    avgHeartRate: Int,
    maxHeartRate: Int,
    calories: Double,
    elevationGain: Double,
    polylinePoints: List<LatLng>
) {
    if (!::analyticsManager.isInitialized) {
        Log.e(TAG, "AnalyticsManager not initialized")
        return
    }

    CoroutineScope(Dispatchers.Main).launch {
        try {
            // Get race and runner IDs from SharedPreferences
            val sharedPrefs = getSharedPreferences("race_tracker_prefs", MODE_PRIVATE)
            val raceId = sharedPrefs.getInt("race_id", 1)
            val runnerId = sharedPrefs.getInt("runner_id", 1)
            
            // Create RaceData with correct constructor parameters
            val raceData = RaceData(
                raceId = raceId,
                runnerId = runnerId,
                startTime = raceStartTime,
                endTime = raceStartTime + duration,
                duration = duration,
                totalDistanceMeters = distance * 1000, // Convert km to meters
                averageSpeedKmh = if (duration > 0) (distance / (duration / 3600000.0)) else 0.0,
                maxSpeedKmh = maxSpeed,
                averagePaceMinPerKm = avgPace,
                bestPaceMinPerKm = 0.0, // TODO: Calculate from splits
                elevationGainMeters = elevationGain,
                elevationLossMeters = 0.0, // TODO: Calculate
                maxElevationMeters = 0.0, // TODO: Calculate
                minElevationMeters = 0.0, // TODO: Calculate
                caloriesBurned = calories,
                averageHeartRate = avgHeartRate,
                maxHeartRate = maxHeartRate,
                pathPoints = polylinePoints,
                elevations = emptyList(), // TODO: Get elevation data
                timestamps = emptyList(), // TODO: Get timestamps
                speeds = emptyList(), // TODO: Get speed data
                splitTimes = emptyList(), // TODO: Get split times
                splitPaces = emptyList() // TODO: Calculate split paces
            )

            val savedRaceId = analyticsManager.saveRace(raceData)

            if (savedRaceId > 0) {
                val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    .format(Date(raceStartTime))
                Log.d(TAG, "Race saved successfully with ID: $savedRaceId at $dateStr")
                
                // Show success message to user
                showToast("Race saved to analytics!")
            } else {
                Log.e(TAG, "Failed to save race to analytics")
                showToast("Failed to save race data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving race to analytics", e)
            showToast("Error saving race: ${e.message}")
        }
    }
}

/**
 * Show toast message (helper function)
 */
private fun LiveTrackerActivity.showToast(message: String) {
    runOnUiThread {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * Calculate elevation gain from polyline points
 * This is a simplified version - real implementation would use elevation API
 */
fun calculateElevationGain(polylinePoints: List<LatLng>): Double {
    // Placeholder implementation
    // In real app, you would:
    // 1. Get elevation for each point using Google Elevation API
    // 2. Calculate cumulative positive elevation change
    // 3. Return total gain in meters
    
    // For now, return estimated value based on distance
    return 0.0
}

/**
 * Format duration for display
 */
fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / (1000 * 60)) % 60
    val hours = (durationMs / (1000 * 60 * 60))
    
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

/**
 * Format pace for display (min/km)
 */
fun formatPace(paceMinPerKm: Double): String {
    val minutes = paceMinPerKm.toInt()
    val seconds = ((paceMinPerKm - minutes) * 60).toInt()
    return String.format("%d:%02d", minutes, seconds)
}

/**
 * Calculate calories burned
 * Based on MET (Metabolic Equivalent) values for running
 */
fun calculateCalories(
    distanceKm: Double,
    durationMinutes: Double,
    avgSpeedKmh: Double,
    weightKg: Double = 70.0  // Default weight
): Int {
    // MET values for running:
    // 6 km/h = 6.0 MET
    // 8 km/h = 8.3 MET
    // 10 km/h = 9.8 MET
    // 12 km/h = 11.5 MET
    // 14 km/h = 13.5 MET
    // 16+ km/h = 15+ MET
    
    val met = when {
        avgSpeedKmh < 6.0 -> 6.0
        avgSpeedKmh < 8.0 -> 6.0 + (avgSpeedKmh - 6.0) * 1.15
        avgSpeedKmh < 10.0 -> 8.3 + (avgSpeedKmh - 8.0) * 0.75
        avgSpeedKmh < 12.0 -> 9.8 + (avgSpeedKmh - 10.0) * 0.85
        avgSpeedKmh < 14.0 -> 11.5 + (avgSpeedKmh - 12.0) * 1.0
        avgSpeedKmh < 16.0 -> 13.5 + (avgSpeedKmh - 14.0) * 0.75
        else -> 15.0 + (avgSpeedKmh - 16.0) * 0.5
    }
    
    // Calories = MET * weight(kg) * time(hours)
    val calories = met * weightKg * (durationMinutes / 60.0)
    
    return calories.toInt()
}
