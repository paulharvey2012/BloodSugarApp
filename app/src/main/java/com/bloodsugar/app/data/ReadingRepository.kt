package com.bloodsugar.app.data

import kotlinx.coroutines.flow.Flow
import java.util.Date

class ReadingRepository(private val readingDao: ReadingDao) {
    companion object {
        // Default tolerances for duplicate detection
        // Consider readings duplicates if values differ by <= EPSILON and timestamps within TIME_WINDOW_MS
        private const val DEFAULT_EPSILON = 0.01 // allow small floating point differences
        private const val DEFAULT_TIME_WINDOW_MS: Long = 60_000 // 1 minute window
    }

    fun getAllReadings(): Flow<List<Reading>> {
        return readingDao.getAllReadings()
    }

    suspend fun getAllReadingsForBackup(): List<Reading> {
        return readingDao.getAllReadingsForBackup()
    }

    suspend fun insertReading(reading: Reading) {
        readingDao.insertReading(reading)
    }

    suspend fun updateReading(reading: Reading) {
        readingDao.updateReading(reading)
    }

    suspend fun deleteReading(reading: Reading) {
        readingDao.deleteReading(reading)
    }

    suspend fun getReadingById(id: Long): Reading? {
        return readingDao.getReadingById(id)
    }

    // Clear all readings from the database
    suspend fun clearAllReadings() {
        readingDao.clearAllReadings()
    }

    // Insert the reading only if a matching reading (type, value, date) does not already exist.
    // Returns true if inserted, false if skipped as duplicate.
    suspend fun insertReadingIfNotExists(reading: Reading): Boolean {
        return try {
            // Use a fuzzy match: check for readings with same type where value within epsilon and date within window
            val startDate = Date(reading.date.time - DEFAULT_TIME_WINDOW_MS)
            val endDate = Date(reading.date.time + DEFAULT_TIME_WINDOW_MS)
            val count = readingDao.countMatchingFuzzy(reading.type, reading.value, startDate, endDate, DEFAULT_EPSILON)
            if (count <= 0) {
                readingDao.insertReading(reading)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            // If something goes wrong, fall back to inserting to avoid data loss
            try {
                readingDao.insertReading(reading)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}