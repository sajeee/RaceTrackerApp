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
    suspend fun saveRace(raceData: RaceData): Long = withContext(Dispatchers.IO) {
        try {
            val id = database.raceDao().insertRace(raceData)
            Log.d(TAG, "Race saved successfully with ID: $id")
            
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
                return@withContext PersonalRecords(
                    fastestPace = 0.0,
                    fastestRaceId = 0,
                    fastestRaceDate = 0,
                    longestDistance = 0.0,
                    longestRaceId = 0,
                    longestRaceDate = 0,
                    mostElevationGain = 0.0,
                    mostElevationRaceId = 0,
                    mostElevationRaceDate = 0,
                    longestDuration = 0,
                    longestDurationRaceId = 0,
                    longestDurationRaceDate = 0
                )
            }

            val fastestPaceRace = races.filter { it.averagePaceMinPerKm > 0 }.minByOrNull { it.averagePaceMinPerKm }
            val longestDistanceRace = races.maxByOrNull { it.totalDistanceMeters }
            val mostElevationRace = races.maxByOrNull { it.elevationGainMeters }
            val longestDurationRace = races.maxByOrNull { it.duration }

            PersonalRecords(
                fastestPace = fastestPaceRace?.averagePaceMinPerKm ?: 0.0,
                fastestRaceId = fastestPaceRace?.id ?: 0,
                fastestRaceDate = fastestPaceRace?.startTime ?: 0,
                longestDistance = longestDistanceRace?.totalDistanceMeters ?: 0.0,
                longestRaceId = longestDistanceRace?.id ?: 0,
                longestRaceDate = longestDistanceRace?.startTime ?: 0,
                mostElevationGain = mostElevationRace?.elevationGainMeters ?: 0.0,
                mostElevationRaceId = mostElevationRace?.id ?: 0,
                mostElevationRaceDate = mostElevationRace?.startTime ?: 0,
                longestDuration = longestDurationRace?.duration ?: 0,
                longestDurationRaceId = longestDurationRace?.id ?: 0,
                longestDurationRaceDate = longestDurationRace?.startTime ?: 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating personal records", e)
            PersonalRecords(
                fastestPace = 0.0,
                fastestRaceId = 0,
                fastestRaceDate = 0,
                longestDistance = 0.0,
                longestRaceId = 0,
                longestRaceDate = 0,
                mostElevationGain = 0.0,
                mostElevationRaceId = 0,
                mostElevationRaceDate = 0,
                longestDuration = 0,
                longestDurationRaceId = 0,
                longestDurationRaceDate = 0
            )
        }
    }

    /**
     * Get races from specific time period
     */
    suspend fun getRacesAfterDate(startTime: Long): List<RaceData> = withContext(Dispatchers.IO) {
        try {
            database.raceDao().getRacesByTimeRange(startTime, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching races after date", e)
            emptyList()
        }
    }

    /**
     * Get races from last N days
     */
    suspend fun getRecentRaces(days: Int): List<RaceData> = withContext(Dispatchers.IO) {
        try {
            val cutoffTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
            getRacesAfterDate(cutoffTime)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recent races", e)
            emptyList()
        }
    }

    /**
     * Calculate weekly summary
     */
    suspend fun getWeeklySummary(): PeriodSummary = withContext(Dispatchers.IO) {
        try {
            val races = getRecentRaces(7)
            val now = System.currentTimeMillis()
            val weekStart = now - (7 * 24 * 60 * 60 * 1000L)
            
            PeriodSummary(
                periodStart = weekStart,
                periodEnd = now,
                totalRaces = races.size,
                totalDistance = races.sumOf { it.totalDistanceMeters } / 1000.0, // Convert to km
                totalDuration = races.sumOf { it.duration },
                totalElevationGain = races.sumOf { it.elevationGainMeters },
                totalCalories = races.sumOf { it.caloriesBurned },
                averagePace = if (races.isNotEmpty()) races.map { it.averagePaceMinPerKm }.average() else 0.0,
                averageSpeed = if (races.isNotEmpty()) races.map { it.averageSpeedKmh }.average() else 0.0,
                bestPace = races.filter { it.averagePaceMinPerKm > 0 }.minOfOrNull { it.averagePaceMinPerKm } ?: 0.0,
                longestRun = races.maxOfOrNull { it.totalDistanceMeters / 1000.0 } ?: 0.0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating weekly summary", e)
            PeriodSummary(
                periodStart = 0,
                periodEnd = 0,
                totalRaces = 0,
                totalDistance = 0.0,
                totalDuration = 0,
                totalElevationGain = 0.0,
                totalCalories = 0.0,
                averagePace = 0.0,
                averageSpeed = 0.0,
                bestPace = 0.0,
                longestRun = 0.0
            )
        }
    }

    /**
     * Calculate monthly summary
     */
    suspend fun getMonthlySummary(): PeriodSummary = withContext(Dispatchers.IO) {
        try {
            val races = getRecentRaces(30)
            val now = System.currentTimeMillis()
            val monthStart = now - (30 * 24 * 60 * 60 * 1000L)
            
            PeriodSummary(
                periodStart = monthStart,
                periodEnd = now,
                totalRaces = races.size,
                totalDistance = races.sumOf { it.totalDistanceMeters } / 1000.0,
                totalDuration = races.sumOf { it.duration },
                totalElevationGain = races.sumOf { it.elevationGainMeters },
                totalCalories = races.sumOf { it.caloriesBurned },
                averagePace = if (races.isNotEmpty()) races.map { it.averagePaceMinPerKm }.average() else 0.0,
                averageSpeed = if (races.isNotEmpty()) races.map { it.averageSpeedKmh }.average() else 0.0,
                bestPace = races.filter { it.averagePaceMinPerKm > 0 }.minOfOrNull { it.averagePaceMinPerKm } ?: 0.0,
                longestRun = races.maxOfOrNull { it.totalDistanceMeters / 1000.0 } ?: 0.0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating monthly summary", e)
            PeriodSummary(
                periodStart = 0,
                periodEnd = 0,
                totalRaces = 0,
                totalDistance = 0.0,
                totalDuration = 0,
                totalElevationGain = 0.0,
                totalCalories = 0.0,
                averagePace = 0.0,
                averageSpeed = 0.0,
                bestPace = 0.0,
                longestRun = 0.0
            )
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
                    distanceDiff = (race1.totalDistanceMeters - race2.totalDistanceMeters) / 1000.0,
                    paceDiff = race1.averagePaceMinPerKm - race2.averagePaceMinPerKm,
                    durationDiff = race1.duration - race2.duration,
                    elevationDiff = race1.elevationGainMeters - race2.elevationGainMeters,
                    caloriesDiff = race1.caloriesBurned - race2.caloriesBurned
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
                dates = races.map { it.startTime },
                distances = races.map { it.totalDistanceMeters / 1000.0 },
                paces = races.map { it.averagePaceMinPerKm },
                speeds = races.map { it.averageSpeedKmh },
                elevations = races.map { it.elevationGainMeters },
                calories = races.map { it.caloriesBurned }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting performance trend", e)
            PerformanceTrend(
                dates = emptyList(),
                distances = emptyList(),
                paces = emptyList(),
                speeds = emptyList(),
                elevations = emptyList(),
                calories = emptyList()
            )
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
}
