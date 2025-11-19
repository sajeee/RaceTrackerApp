package com.racetracker.app

import android.util.Log
import android.widget.Toast
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Extension methods for LiveTrackerActivity to handle analytics integration
 */

/**
 * Save race data to analytics database
 */
fun LiveTrackerActivity.saveRaceToAnalytics(
    raceId: Int,
    runnerId: Int,
    startTime: Long,
    pathPoints: List<LatLng>,
    elevations: List<Double>,
    totalDistance: Double
) {
    if (pathPoints.isEmpty() || totalDistance < 0.1) {
        Log.w("LiveTrackerActivity", "No valid data to save to analytics")
        return
    }
    
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val analyticsManager = AnalyticsManager(this@saveRaceToAnalytics)
            val endTime = System.currentTimeMillis()
            
            // Collect timestamps and speeds
            val timestamps = mutableListOf<Long>()
            val speeds = mutableListOf<Double>()
            
            // Generate timestamps based on path points
            val duration = endTime - startTime
            val timePerPoint = if (pathPoints.size > 1) duration / pathPoints.size else 0L
            for (i in pathPoints.indices) {
                timestamps.add(startTime + (i * timePerPoint))
            }
            
            // Estimate speeds between points
            for (i in 0 until pathPoints.size - 1) {
                val dist = distanceBetweenPoints(pathPoints[i], pathPoints[i + 1])
                val time = timePerPoint / 1000.0 // seconds
                val speedMps = if (time > 0) dist / time else 0.0
                val speedKmh = speedMps * 3.6
                speeds.add(speedKmh)
            }
            if (speeds.isNotEmpty()) {
                speeds.add(speeds.last())
            }
            
            val raceDbId = analyticsManager.saveRace(
                raceId = raceId,
                runnerId = runnerId,
                startTime = startTime,
                endTime = endTime,
                pathPoints = pathPoints,
                elevations = elevations.ifEmpty { List(pathPoints.size) { 0.0 } },
                timestamps = timestamps,
                speeds = speeds,
                userWeightKg = 70.0,
                notes = ""
            )
            
            Log.i("LiveTrackerActivity", "Race saved to analytics database with ID: $raceDbId")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@saveRaceToAnalytics, "✅ Race saved to analytics", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("LiveTrackerActivity", "Failed to save race to analytics: ${e.message}", e)
        }
    }
}

private fun distanceBetweenPoints(point1: LatLng, point2: LatLng): Double {
    val R = 6371000.0
    val lat1 = Math.toRadians(point1.latitude)
    val lat2 = Math.toRadians(point2.latitude)
    val dLat = Math.toRadians(point2.latitude - point1.latitude)
    val dLon = Math.toRadians(point2.longitude - point1.longitude)
    
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat1) * Math.cos(lat2) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    
    return R * c
}
