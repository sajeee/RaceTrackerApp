package com.racetracker.app

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Race data entity for Room database
 */
@Entity(tableName = "races")
@TypeConverters(Converters::class)
data class RaceData(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val raceId: Int,
    val runnerId: Int,
    val startTime: Long,
    val endTime: Long,
    val duration: Long, // milliseconds
    
    // Distance and speed metrics
    val totalDistanceMeters: Double,
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val averagePaceMinPerKm: Double,
    val bestPaceMinPerKm: Double,
    
    // Elevation metrics
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val maxElevationMeters: Double,
    val minElevationMeters: Double,
    
    // Calories and heart rate
    val caloriesBurned: Double,
    val averageHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    
    // Path data
    val pathPoints: List<LatLng>,
    val elevations: List<Double>,
    val timestamps: List<Long>,
    val speeds: List<Double>, // km/h
    
    // Split times (every km)
    val splitTimes: List<Long>, // milliseconds for each km
    val splitPaces: List<Double>, // min/km for each split
    
    // Weather data (optional)
    val temperature: Double? = null,
    val weatherCondition: String? = null,
    
    // Notes
    val notes: String = ""
)

/**
 * Summary statistics for analytics
 */
data class PersonalRecords(
    val fastestPace: Double, // min/km
    val fastestRaceId: Long,
    val fastestRaceDate: Long,
    
    val longestDistance: Double, // km
    val longestRaceId: Long,
    val longestRaceDate: Long,
    
    val mostElevationGain: Double, // meters
    val mostElevationRaceId: Long,
    val mostElevationRaceDate: Long,
    
    val longestDuration: Long, // milliseconds
    val longestDurationRaceId: Long,
    val longestDurationRaceDate: Long
)

/**
 * Race comparison data
 */
data class RaceComparison(
    val race1: RaceData,
    val race2: RaceData,
    val distanceDiff: Double, // km
    val paceDiff: Double, // min/km
    val durationDiff: Long, // milliseconds
    val elevationDiff: Double, // meters
    val caloriesDiff: Double
)

/**
 * Performance trends over time
 */
data class PerformanceTrend(
    val dates: List<Long>,
    val distances: List<Double>,
    val paces: List<Double>,
    val speeds: List<Double>,
    val elevations: List<Double>,
    val calories: List<Double>
)

/**
 * Weekly/Monthly summary
 */
data class PeriodSummary(
    val periodStart: Long,
    val periodEnd: Long,
    val totalRaces: Int,
    val totalDistance: Double, // km
    val totalDuration: Long, // milliseconds
    val totalElevationGain: Double, // meters
    val totalCalories: Double,
    val averagePace: Double, // min/km
    val averageSpeed: Double, // km/h
    val bestPace: Double, // min/km
    val longestRun: Double // km
)

/**
 * Heart rate zone data
 */
data class HeartRateZone(
    val zone: Int, // 1-5
    val name: String,
    val minBpm: Int,
    val maxBpm: Int,
    val timeInZone: Long, // milliseconds
    val percentage: Double
)

/**
 * Split analysis
 */
data class SplitAnalysis(
    val splitNumber: Int,
    val distanceKm: Double,
    val timeMillis: Long,
    val paceMinPerKm: Double,
    val speedKmh: Double,
    val elevationChangeMeters: Double,
    val paceVariation: Double // compared to average
)

/**
 * Type converters for Room database
 */
class Converters {
    private val gson = Gson()
    
    @TypeConverter
    fun fromLatLngList(value: List<LatLng>?): String {
        if (value == null) return "[]"
        val points = value.map { mapOf("lat" to it.latitude, "lng" to it.longitude) }
        return gson.toJson(points)
    }
    
    @TypeConverter
    fun toLatLngList(value: String): List<LatLng> {
        val type = object : TypeToken<List<Map<String, Double>>>() {}.type
        val points: List<Map<String, Double>> = gson.fromJson(value, type)
        return points.map { LatLng(it["lat"] ?: 0.0, it["lng"] ?: 0.0) }
    }
    
    @TypeConverter
    fun fromDoubleList(value: List<Double>?): String {
        return gson.toJson(value ?: emptyList<Double>())
    }
    
    @TypeConverter
    fun toDoubleList(value: String): List<Double> {
        val type = object : TypeToken<List<Double>>() {}.type
        return gson.fromJson(value, type)
    }
    
    @TypeConverter
    fun fromLongList(value: List<Long>?): String {
        return gson.toJson(value ?: emptyList<Long>())
    }
    
    @TypeConverter
    fun toLongList(value: String): List<Long> {
        val type = object : TypeToken<List<Long>>() {}.type
        return gson.fromJson(value, type)
    }
}
