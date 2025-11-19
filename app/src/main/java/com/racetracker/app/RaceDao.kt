package com.racetracker.app

import androidx.room.*

/**
 * Data Access Object for Race data
 */
@Dao
interface RaceDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRace(race: RaceData): Long
    
    @Update
    suspend fun updateRace(race: RaceData)
    
    @Delete
    suspend fun deleteRace(race: RaceData)
    
    @Query("SELECT * FROM races ORDER BY startTime DESC")
    suspend fun getAllRaces(): List<RaceData>
    
    @Query("SELECT * FROM races WHERE id = :id")
    suspend fun getRaceById(id: Long): RaceData?
    
    @Query("SELECT * FROM races WHERE raceId = :raceId AND runnerId = :runnerId ORDER BY startTime DESC")
    suspend fun getRacesByRaceAndRunner(raceId: Int, runnerId: Int): List<RaceData>
    
    @Query("SELECT * FROM races WHERE startTime >= :startTime AND startTime <= :endTime ORDER BY startTime DESC")
    suspend fun getRacesByTimeRange(startTime: Long, endTime: Long): List<RaceData>
    
    @Query("SELECT * FROM races ORDER BY totalDistanceMeters DESC LIMIT 1")
    suspend fun getLongestRace(): RaceData?
    
    @Query("SELECT * FROM races ORDER BY averagePaceMinPerKm ASC LIMIT 1")
    suspend fun getFastestPaceRace(): RaceData?
    
    @Query("SELECT * FROM races ORDER BY elevationGainMeters DESC LIMIT 1")
    suspend fun getMostElevationRace(): RaceData?
    
    @Query("SELECT * FROM races ORDER BY duration DESC LIMIT 1")
    suspend fun getLongestDurationRace(): RaceData?
    
    @Query("SELECT * FROM races ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecentRaces(limit: Int): List<RaceData>
    
    @Query("DELETE FROM races")
    suspend fun deleteAllRaces()
    
    @Query("SELECT COUNT(*) FROM races")
    suspend fun getRaceCount(): Int
    
    @Query("SELECT SUM(totalDistanceMeters) FROM races")
    suspend fun getTotalDistanceAllTime(): Double?
    
    @Query("SELECT SUM(duration) FROM races")
    suspend fun getTotalDurationAllTime(): Long?
    
    @Query("SELECT AVG(averagePaceMinPerKm) FROM races")
    suspend fun getAveragePaceAllTime(): Double?
}
