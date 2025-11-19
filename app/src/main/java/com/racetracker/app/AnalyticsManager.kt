package com.racetracker.app

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Analytics Manager - FIXED VERSION with accurate split calculations
 */
class AnalyticsManager(private val context: Context) {
    
    private val raceDao = RaceDatabase.getDatabase(context).raceDao()
    
    /**
     * Save a completed race to database
     */
    suspend fun saveRace(
        raceId: Int,
        runnerId: Int,
        startTime: Long,
        endTime: Long,
        pathPoints: List<LatLng>,
        elevations: List<Double>,
        timestamps: List<Long>,
        speeds: List<Double>,
        userWeightKg: Double = 70.0,
        notes: String = ""
    ): Long = withContext(Dispatchers.IO) {
        
        if (pathPoints.size < 2) {
            throw IllegalArgumentException("Need at least 2 points to save a race")
        }
        
        val duration = endTime - startTime
        
        // Calculate cumulative distances for each point
        val cumulativeDistances = mutableListOf<Double>()
        var totalDistance = 0.0
        cumulativeDistances.add(0.0)
        
        for (i in 1 until pathPoints.size) {
            val segmentDistance = distanceBetween(pathPoints[i-1], pathPoints[i])
            totalDistance += segmentDistance
            cumulativeDistances.add(totalDistance)
        }
        
        val totalDistanceKm = totalDistance / 1000.0
        
        // Speed metrics
        val averageSpeedKmh = if (duration > 0) {
            (totalDistanceKm / (duration / 3600000.0))
        } else 0.0
        
        val maxSpeedKmh = speeds.maxOrNull() ?: 0.0
        
        // Pace metrics
        val averagePaceMinPerKm = if (averageSpeedKmh > 0) 60.0 / averageSpeedKmh else 0.0
        val bestPaceMinPerKm = if (maxSpeedKmh > 0) 60.0 / maxSpeedKmh else 0.0
        
        // Elevation metrics
        val elevationGain = calculateElevationGain(elevations)
        val elevationLoss = calculateElevationLoss(elevations)
        val maxElevation = elevations.maxOrNull() ?: 0.0
        val minElevation = elevations.minOrNull() ?: 0.0
        
        // Calculate ACCURATE split times using cumulative distances
        val splits = calculateAccurateSplitTimes(
            pathPoints = pathPoints,
            timestamps = timestamps,
            cumulativeDistances = cumulativeDistances,
            elevations = elevations
        )
        
        val splitTimes = splits.map { it.timeMillis }
        val splitPaces = splits.map { it.paceMinPerKm }
        
        // Calculate calories
        val calories = calculateCalories(totalDistanceKm, duration, userWeightKg, elevationGain)
        
        val raceData = RaceData(
            raceId = raceId,
            runnerId = runnerId,
            startTime = startTime,
            endTime = endTime,
            duration = duration,
            totalDistanceMeters = totalDistance,
            averageSpeedKmh = averageSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            averagePaceMinPerKm = averagePaceMinPerKm,
            bestPaceMinPerKm = bestPaceMinPerKm,
            elevationGainMeters = elevationGain,
            elevationLossMeters = elevationLoss,
            maxElevationMeters = maxElevation,
            minElevationMeters = minElevation,
            caloriesBurned = calories,
            pathPoints = pathPoints,
            elevations = elevations,
            timestamps = timestamps,
            speeds = speeds,
            splitTimes = splitTimes,
            splitPaces = splitPaces,
            notes = notes
        )
        
        raceDao.insertRace(raceData)
    }
    
    /**
     * FIXED: Calculate accurate split times based on cumulative distance
     */
    private fun calculateAccurateSplitTimes(
        pathPoints: List<LatLng>,
        timestamps: List<Long>,
        cumulativeDistances: List<Double>,
        elevations: List<Double>
    ): List<SplitData> {
        val splits = mutableListOf<SplitData>()
        
        if (pathPoints.size < 2 || timestamps.size < 2 || cumulativeDistances.size < 2) {
            return splits
        }
        
        var currentKm = 1
        var lastSplitIndex = 0
        var lastSplitDistance = 0.0
        var lastSplitTime = timestamps[0]
        var lastSplitElevation = if (elevations.isNotEmpty()) elevations[0] else 0.0
        
        // Find points where we cross each kilometer mark
        for (i in 1 until cumulativeDistances.size) {
            val targetDistance = currentKm * 1000.0 // Target distance in meters
            
            // Check if we've crossed the kilometer mark
            if (cumulativeDistances[i] >= targetDistance) {
                // Found a kilometer split
                val splitTime = timestamps[i] - lastSplitTime
                val splitDistance = cumulativeDistances[i] - lastSplitDistance
                val splitDistanceKm = splitDistance / 1000.0
                
                // Calculate pace for this split
                val splitTimeMinutes = splitTime / 60000.0
                val pace = if (splitDistanceKm > 0) splitTimeMinutes / splitDistanceKm else 0.0
                
                // Calculate speed
                val splitTimeHours = splitTime / 3600000.0
                val speed = if (splitTimeHours > 0) splitDistanceKm / splitTimeHours else 0.0
                
                // Calculate elevation change
                val elevChange = if (i < elevations.size && lastSplitIndex < elevations.size) {
                    elevations[i] - lastSplitElevation
                } else 0.0
                
                splits.add(SplitData(
                    timeMillis = splitTime,
                    paceMinPerKm = pace,
                    distanceKm = splitDistanceKm,
                    speedKmh = speed,
                    elevationChangeMeters = elevChange
                ))
                
                // Update for next split
                lastSplitIndex = i
                lastSplitDistance = cumulativeDistances[i]
                lastSplitTime = timestamps[i]
                lastSplitElevation = if (i < elevations.size) elevations[i] else lastSplitElevation
                currentKm++
                
                // If we've covered more than the target (e.g., jumped past 1km to 1.2km),
                // we still only record one split at that point
            }
        }
        
        // Add final partial split if there's remaining distance > 100m
        val finalIndex = cumulativeDistances.size - 1
        val remainingDistance = cumulativeDistances[finalIndex] - lastSplitDistance
        
        if (remainingDistance > 100.0) { // Only if > 100 meters remaining
            val splitTime = timestamps[finalIndex] - lastSplitTime
            val splitDistanceKm = remainingDistance / 1000.0
            val splitTimeMinutes = splitTime / 60000.0
            val pace = if (splitDistanceKm > 0) splitTimeMinutes / splitDistanceKm else 0.0
            val splitTimeHours = splitTime / 3600000.0
            val speed = if (splitTimeHours > 0) splitDistanceKm / splitTimeHours else 0.0
            
            val elevChange = if (finalIndex < elevations.size && lastSplitIndex < elevations.size) {
                elevations[finalIndex] - lastSplitElevation
            } else 0.0
            
            splits.add(SplitData(
                timeMillis = splitTime,
                paceMinPerKm = pace,
                distanceKm = splitDistanceKm,
                speedKmh = speed,
                elevationChangeMeters = elevChange
            ))
        }
        
        return splits
    }
    
    /**
     * Get personal records
     */
    suspend fun getPersonalRecords(): PersonalRecords? = withContext(Dispatchers.IO) {
        val fastestRace = raceDao.getFastestPaceRace()
        val longestRace = raceDao.getLongestRace()
        val mostElevationRace = raceDao.getMostElevationRace()
        val longestDurationRace = raceDao.getLongestDurationRace()
        
        if (fastestRace != null && longestRace != null && 
            mostElevationRace != null && longestDurationRace != null) {
            PersonalRecords(
                fastestPace = fastestRace.averagePaceMinPerKm,
                fastestRaceId = fastestRace.id,
                fastestRaceDate = fastestRace.startTime,
                longestDistance = longestRace.totalDistanceMeters / 1000.0,
                longestRaceId = longestRace.id,
                longestRaceDate = longestRace.startTime,
                mostElevationGain = mostElevationRace.elevationGainMeters,
                mostElevationRaceId = mostElevationRace.id,
                mostElevationRaceDate = mostElevationRace.startTime,
                longestDuration = longestDurationRace.duration,
                longestDurationRaceId = longestDurationRace.id,
                longestDurationRaceDate = longestDurationRace.startTime
            )
        } else null
    }
    
    /**
     * Compare two races
     */
    suspend fun compareRaces(raceId1: Long, raceId2: Long): RaceComparison? = withContext(Dispatchers.IO) {
        val race1 = raceDao.getRaceById(raceId1)
        val race2 = raceDao.getRaceById(raceId2)
        
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
        } else null
    }
    
    /**
     * Get performance trends
     */
    suspend fun getPerformanceTrend(limit: Int = 10): PerformanceTrend = withContext(Dispatchers.IO) {
        val races = raceDao.getRecentRaces(limit).reversed()
        
        PerformanceTrend(
            dates = races.map { it.startTime },
            distances = races.map { it.totalDistanceMeters / 1000.0 },
            paces = races.map { it.averagePaceMinPerKm },
            speeds = races.map { it.averageSpeedKmh },
            elevations = races.map { it.elevationGainMeters },
            calories = races.map { it.caloriesBurned }
        )
    }
    
    /**
     * Get weekly summary
     */
    suspend fun getWeeklySummary(): PeriodSummary? = withContext(Dispatchers.IO) {
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, -7)
        val startTime = calendar.timeInMillis
        
        getPeriodSummary(startTime, endTime)
    }
    
    /**
     * Get monthly summary
     */
    suspend fun getMonthlySummary(): PeriodSummary? = withContext(Dispatchers.IO) {
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, -30)
        val startTime = calendar.timeInMillis
        
        getPeriodSummary(startTime, endTime)
    }
    
    /**
     * Get period summary
     */
    private suspend fun getPeriodSummary(startTime: Long, endTime: Long): PeriodSummary? {
        val races = raceDao.getRacesByTimeRange(startTime, endTime)
        
        if (races.isEmpty()) return null
        
        val totalDistance = races.sumOf { it.totalDistanceMeters } / 1000.0
        val totalDuration = races.sumOf { it.duration }
        val totalElevation = races.sumOf { it.elevationGainMeters }
        val totalCalories = races.sumOf { it.caloriesBurned }
        val averagePace = races.map { it.averagePaceMinPerKm }.average()
        val averageSpeed = races.map { it.averageSpeedKmh }.average()
        val bestPace = races.minOfOrNull { it.averagePaceMinPerKm } ?: 0.0
        val longestRun = races.maxOfOrNull { it.totalDistanceMeters } ?: 0.0
        
        return PeriodSummary(
            periodStart = startTime,
            periodEnd = endTime,
            totalRaces = races.size,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            totalElevationGain = totalElevation,
            totalCalories = totalCalories,
            averagePace = averagePace,
            averageSpeed = averageSpeed,
            bestPace = bestPace,
            longestRun = longestRun / 1000.0
        )
    }
    
    /**
     * Calculate heart rate zones (if heart rate data is available)
     */
    fun calculateHeartRateZones(
        heartRates: List<Int>,
        timestamps: List<Long>,
        maxHeartRate: Int = 190
    ): List<HeartRateZone> {
        if (heartRates.isEmpty() || timestamps.isEmpty()) return emptyList()
        
        val zones = listOf(
            HeartRateZone(1, "Very Light", (maxHeartRate * 0.5).toInt(), (maxHeartRate * 0.6).toInt(), 0, 0.0),
            HeartRateZone(2, "Light", (maxHeartRate * 0.6).toInt(), (maxHeartRate * 0.7).toInt(), 0, 0.0),
            HeartRateZone(3, "Moderate", (maxHeartRate * 0.7).toInt(), (maxHeartRate * 0.8).toInt(), 0, 0.0),
            HeartRateZone(4, "Hard", (maxHeartRate * 0.8).toInt(), (maxHeartRate * 0.9).toInt(), 0, 0.0),
            HeartRateZone(5, "Maximum", (maxHeartRate * 0.9).toInt(), maxHeartRate, 0, 0.0)
        )
        
        val timeInZones = mutableMapOf<Int, Long>()
        zones.forEach { timeInZones[it.zone] = 0 }
        
        for (i in 0 until heartRates.size - 1) {
            val hr = heartRates[i]
            val duration = timestamps[i + 1] - timestamps[i]
            
            val zone = zones.find { hr >= it.minBpm && hr < it.maxBpm }
            zone?.let {
                timeInZones[it.zone] = (timeInZones[it.zone] ?: 0) + duration
            }
        }
        
        val totalTime = timestamps.last() - timestamps.first()
        
        return zones.map { zone ->
            val time = timeInZones[zone.zone] ?: 0
            val percentage = if (totalTime > 0) (time.toDouble() / totalTime) * 100.0 else 0.0
            zone.copy(timeInZone = time, percentage = percentage)
        }
    }
    
    /**
     * Get split analysis for a race
     */
    suspend fun getSplitAnalysis(raceId: Long): List<SplitAnalysis> = withContext(Dispatchers.IO) {
        val race = raceDao.getRaceById(raceId) ?: return@withContext emptyList()
        
        val avgPace = race.averagePaceMinPerKm
        
        race.splitTimes.mapIndexed { index, time ->
            val pace = if (index < race.splitPaces.size) race.splitPaces[index] else 0.0
            val distance = if (index < race.splitTimes.size - 1) 1.0 else {
                // Last split might be partial
                val totalKm = race.totalDistanceMeters / 1000.0
                totalKm - index
            }
            
            SplitAnalysis(
                splitNumber = index + 1,
                distanceKm = distance,
                timeMillis = time,
                paceMinPerKm = pace,
                speedKmh = if (pace > 0) 60.0 / pace else 0.0,
                elevationChangeMeters = 0.0, // Would need to calculate from race data
                paceVariation = pace - avgPace
            )
        }
    }
    
    /**
     * Get all races
     */
    suspend fun getAllRaces(): List<RaceData> = withContext(Dispatchers.IO) {
        raceDao.getAllRaces()
    }
    
    /**
     * Get recent races
     */
    suspend fun getRecentRaces(limit: Int = 10): List<RaceData> = withContext(Dispatchers.IO) {
        raceDao.getRecentRaces(limit)
    }
    
    // ===== PRIVATE HELPER METHODS =====
    
    /**
     * Calculate distance between two LatLng points (Haversine formula)
     */
    private fun distanceBetween(point1: LatLng, point2: LatLng): Double {
        val R = 6371000.0 // Earth radius in meters
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
    
    /**
     * Calculate elevation gain
     */
    private fun calculateElevationGain(elevations: List<Double>): Double {
        var gain = 0.0
        for (i in 0 until elevations.size - 1) {
            val diff = elevations[i + 1] - elevations[i]
            if (diff > 0) gain += diff
        }
        return gain
    }
    
    /**
     * Calculate elevation loss
     */
    private fun calculateElevationLoss(elevations: List<Double>): Double {
        var loss = 0.0
        for (i in 0 until elevations.size - 1) {
            val diff = elevations[i + 1] - elevations[i]
            if (diff < 0) loss += abs(diff)
        }
        return loss
    }
    
    private data class SplitData(
        val timeMillis: Long,
        val paceMinPerKm: Double,
        val distanceKm: Double,
        val speedKmh: Double = 0.0,
        val elevationChangeMeters: Double = 0.0
    )
    
    /**
     * Calculate calories burned
     */
    private fun calculateCalories(
        distanceKm: Double,
        durationMillis: Long,
        weightKg: Double,
        elevationGainMeters: Double
    ): Double {
        val hours = durationMillis / 3600000.0
        val speedKmh = if (hours > 0) distanceKm / hours else 0.0
        
        // MET value based on speed
        val met = when {
            speedKmh < 6.0 -> 6.0
            speedKmh < 8.0 -> 8.0
            speedKmh < 10.0 -> 9.8
            speedKmh < 12.0 -> 11.0
            speedKmh < 14.0 -> 12.8
            else -> 14.5
        }
        
        var calories = met * weightKg * hours
        
        // Add bonus for elevation gain
        calories += (elevationGainMeters / 10.0) * weightKg * 0.1
        
        return calories
    }
}
