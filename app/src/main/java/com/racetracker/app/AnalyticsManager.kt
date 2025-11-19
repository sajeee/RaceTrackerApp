package com.racetracker.app

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

class AnalyticsManager(private val context: Context) {

    private val TAG = "AnalyticsManager"
    private val database: RaceDatabase by lazy {
        Room.databaseBuilder(
            context.applicationContext,
            RaceDatabase::class.java,
            "race_database"
        ).build()
    }

    // Track splits data during active race
    private val splitsList = mutableListOf<SplitData>()
    private var totalDistanceMeters = 0.0
    private var lastSplitDistance = 0.0
    private val SPLIT_INTERVAL_METERS = 1000.0 // 1 km

    data class SplitData(
        val splitNumber: Int,
        val distance: Double,
        val time: Long,
        val pace: Double
    )

    /**
     * Process location updates and detect split completions
     */
    fun processLocationUpdate(latLng: LatLng, elapsedTimeMs: Long): SplitData? {
        // Calculate distance for this segment
        // In actual implementation, this should accumulate from polyline points
        // For now, this is a placeholder that should be called with cumulative distance
        
        return checkForNewSplit(totalDistanceMeters, elapsedTimeMs)
    }

    /**
     * Update total distance traveled (call this from LiveTrackerActivity)
     */
    fun updateTotalDistance(distanceMeters: Double, elapsedTimeMs: Long): SplitData? {
        totalDistanceMeters = distanceMeters
        return checkForNewSplit(totalDistanceMeters, elapsedTimeMs)
    }

    /**
     * Check if a new split milestone has been reached
     */
    private fun checkForNewSplit(currentDistance: Double, elapsedTimeMs: Long): SplitData? {
        val currentSplitNumber = (currentDistance / SPLIT_INTERVAL_METERS).toInt()
        val expectedSplits = currentSplitNumber

        // Check if we've crossed a new split boundary
        if (expectedSplits > splitsList.size) {
            val splitDistance = (expectedSplits * SPLIT_INTERVAL_METERS)
            
            // Calculate pace for this split (min/km)
            val splitTime = if (splitsList.isEmpty()) {
                elapsedTimeMs
            } else {
                elapsedTimeMs - splitsList.last().time
            }
            
            val splitPace = if (splitTime > 0) {
                (splitTime / 60000.0) // Convert to minutes per km
            } else {
                0.0
            }

            val split = SplitData(
                splitNumber = expectedSplits,
                distance = splitDistance,
                time = elapsedTimeMs,
                pace = splitPace
            )
            
            splitsList.add(split)
            Log.d(TAG, "New split detected: #${split.splitNumber} at ${split.distance}m, pace: ${split.pace} min/km")
            return split
        }

        return null
    }

    /**
     * Save completed race to database
     */
    suspend fun saveRace(
        distance: Double,
        duration: Long,
        avgPace: Double,
        maxSpeed: Double,
        avgHeartRate: Int,
        maxHeartRate: Int,
        caloriesBurned: Int,
        elevationGain: Double,
        polylinePoints: List<LatLng>
    ): Long = withContext(Dispatchers.IO) {
        try {
            val raceData = RaceData(
                date = System.currentTimeMillis(),
                distance = distance,
                duration = duration,
                avgPace = avgPace,
                maxSpeed = maxSpeed,
                avgHeartRate = avgHeartRate,
                maxHeartRate = maxHeartRate,
                caloriesBurned = caloriesBurned,
                elevationGain = elevationGain,
                splits = splitsList.map { 
                    "${it.splitNumber}:${it.distance}:${it.time}:${it.pace}" 
                }.joinToString(";"),
                polyline = polylinePoints.joinToString(";") { "${it.latitude},${it.longitude}" }
            )

            val id = database.raceDao().insertRace(raceData)
            Log.d(TAG, "Race saved successfully with ID: $id, splits: ${splitsList.size}")
            
            // Reset splits for next race
            splitsList.clear()
            totalDistanceMeters = 0.0
            lastSplitDistance = 0.0
            
            id
        } catch (e: Exception) {
            Log.e(TAG, "Error saving race", e)
            -1L
        }
    }

    /**
     * Get all races sorted by date (most recent first)
     */
    suspend fun getAllRaces(): List<RaceData> = withContext(Dispatchers.IO) {
        try {
            database.raceDao().getAllRaces()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching races", e)
            emptyList()
        }
    }

    /**
     * Get personal records
     */
    suspend fun getPersonalRecords(): PersonalRecords = withContext(Dispatchers.IO) {
        try {
            val races = database.raceDao().getAllRaces()
            
            if (races.isEmpty()) {
                return@withContext PersonalRecords()
            }

            PersonalRecords(
                longestDistance = races.maxByOrNull { it.distance }?.distance ?: 0.0,
                fastestPace = races.filter { it.avgPace > 0 }.minByOrNull { it.avgPace }?.avgPace ?: 0.0,
                longestDuration = races.maxByOrNull { it.duration }?.duration ?: 0L,
                maxSpeed = races.maxByOrNull { it.maxSpeed }?.maxSpeed ?: 0.0,
                totalRaces = races.size,
                totalDistance = races.sumOf { it.distance },
                totalDuration = races.sumOf { it.duration },
                avgPaceAllTime = calculateOverallAvgPace(races)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating personal records", e)
            PersonalRecords()
        }
    }

    /**
     * Calculate overall average pace across all races
     */
    private fun calculateOverallAvgPace(races: List<RaceData>): Double {
        val validRaces = races.filter { it.avgPace > 0 && it.distance > 0 }
        if (validRaces.isEmpty()) return 0.0

        val totalTime = validRaces.sumOf { it.duration }
        val totalDistance = validRaces.sumOf { it.distance }

        return if (totalDistance > 0) {
            (totalTime / 60000.0) / (totalDistance / 1000.0) // min/km
        } else {
            0.0
        }
    }

    /**
     * Get races from last N days
     */
    suspend fun getRecentRaces(days: Int): List<RaceData> = withContext(Dispatchers.IO) {
        try {
            val cutoffTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
            database.raceDao().getRacesAfterDate(cutoffTime)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recent races", e)
            emptyList()
        }
    }

    /**
     * Calculate weekly summary
     */
    suspend fun getWeeklySummary(): WeeklySummary = withContext(Dispatchers.IO) {
        try {
            val races = getRecentRaces(7)
            
            WeeklySummary(
                totalRuns = races.size,
                totalDistance = races.sumOf { it.distance },
                totalDuration = races.sumOf { it.duration },
                avgPace = calculateOverallAvgPace(races),
                totalCalories = races.sumOf { it.caloriesBurned }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating weekly summary", e)
            WeeklySummary()
        }
    }

    /**
     * Calculate monthly summary
     */
    suspend fun getMonthlySummary(): MonthlySummary = withContext(Dispatchers.IO) {
        try {
            val races = getRecentRaces(30)
            
            MonthlySummary(
                totalRuns = races.size,
                totalDistance = races.sumOf { it.distance },
                totalDuration = races.sumOf { it.duration },
                avgPace = calculateOverallAvgPace(races),
                totalCalories = races.sumOf { it.caloriesBurned },
                totalElevationGain = races.sumOf { it.elevationGain }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating monthly summary", e)
            MonthlySummary()
        }
    }

    /**
     * Compare two races
     */
    suspend fun compareRaces(race1Id: Long, race2Id: Long): RaceComparison? = withContext(Dispatchers.IO) {
        try {
            val race1 = database.raceDao().getRaceById(race1Id)
            val race2 = database.raceDao().getRaceById(race2Id)

            if (race1 != null && race2 != null) {
                RaceComparison(
                    race1 = race1,
                    race2 = race2,
                    distanceDiff = race1.distance - race2.distance,
                    durationDiff = race1.duration - race2.duration,
                    paceDiff = race1.avgPace - race2.avgPace,
                    speedDiff = race1.maxSpeed - race2.maxSpeed
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing races", e)
            null
        }
    }

    /**
     * Get performance trend data for charts
     */
    suspend fun getPerformanceTrend(days: Int): PerformanceTrend = withContext(Dispatchers.IO) {
        try {
            val races = getRecentRaces(days)
            
            PerformanceTrend(
                dates = races.map { it.date },
                distances = races.map { it.distance },
                paces = races.map { it.avgPace },
                durations = races.map { it.duration }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting performance trend", e)
            PerformanceTrend()
        }
    }

    /**
     * Delete a race by ID
     */
    suspend fun deleteRace(raceId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val race = database.raceDao().getRaceById(raceId)
            if (race != null) {
                database.raceDao().deleteRace(race)
                Log.d(TAG, "Race deleted: $raceId")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting race", e)
            false
        }
    }

    /**
     * Reset splits for new race
     */
    fun resetSplits() {
        splitsList.clear()
        totalDistanceMeters = 0.0
        lastSplitDistance = 0.0
        Log.d(TAG, "Splits reset for new race")
    }

    /**
     * Get current splits (for display during active race)
     */
    fun getCurrentSplits(): List<SplitData> = splitsList.toList()

    // Data classes for analytics results
    data class PersonalRecords(
        val longestDistance: Double = 0.0,
        val fastestPace: Double = 0.0,
        val longestDuration: Long = 0L,
        val maxSpeed: Double = 0.0,
        val totalRaces: Int = 0,
        val totalDistance: Double = 0.0,
        val totalDuration: Long = 0L,
        val avgPaceAllTime: Double = 0.0
    )

    data class WeeklySummary(
        val totalRuns: Int = 0,
        val totalDistance: Double = 0.0,
        val totalDuration: Long = 0L,
        val avgPace: Double = 0.0,
        val totalCalories: Int = 0
    )

    data class MonthlySummary(
        val totalRuns: Int = 0,
        val totalDistance: Double = 0.0,
        val totalDuration: Long = 0L,
        val avgPace: Double = 0.0,
        val totalCalories: Int = 0,
        val totalElevationGain: Double = 0.0
    )

    data class RaceComparison(
        val race1: RaceData,
        val race2: RaceData,
        val distanceDiff: Double,
        val durationDiff: Long,
        val paceDiff: Double,
        val speedDiff: Double
    )

    data class PerformanceTrend(
        val dates: List<Long> = emptyList(),
        val distances: List<Double> = emptyList(),
        val paces: List<Double> = emptyList(),
        val durations: List<Long> = emptyList()
    )
}
