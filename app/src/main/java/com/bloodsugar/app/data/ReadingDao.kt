package com.bloodsugar.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    @Query("SELECT * FROM readings ORDER BY date DESC")
    fun getAllReadings(): Flow<List<Reading>>

    @Insert
    suspend fun insertReading(reading: Reading)

    @Update
    suspend fun updateReading(reading: Reading)

    @Delete
    suspend fun deleteReading(reading: Reading)

    @Query("SELECT * FROM readings WHERE id = :id")
    suspend fun getReadingById(id: Long): Reading?
}
