package com.bloodsugar.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface ReadingDao {
    @Query("SELECT * FROM readings ORDER BY date DESC")
    fun getAllReadings(): Flow<List<Reading>>

    @Query("SELECT * FROM readings ORDER BY date DESC")
    suspend fun getAllReadingsForBackup(): List<Reading>

    @Insert
    suspend fun insertReading(reading: Reading)

    @Update
    suspend fun updateReading(reading: Reading)

    @Delete
    suspend fun deleteReading(reading: Reading)

    @Query("SELECT * FROM readings WHERE id = :id")
    suspend fun getReadingById(id: Long): Reading?

    // Fuzzy helper to support deduplication when importing/restoring backups.
    // Matches readings of the same `type` whose numeric `value` is within `epsilon` and whose
    // `date` falls within [startDate, endDate]. This allows tolerant duplicate detection.
    @Query("SELECT COUNT(*) FROM readings WHERE type = :type AND ABS(value - :value) <= :epsilon AND date BETWEEN :startDate AND :endDate")
    suspend fun countMatchingFuzzy(type: String, value: Double, startDate: Date, endDate: Date, epsilon: Double): Int
}
