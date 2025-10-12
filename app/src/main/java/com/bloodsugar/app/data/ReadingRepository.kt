package com.bloodsugar.app.data

import kotlinx.coroutines.flow.Flow

class ReadingRepository(private val readingDao: ReadingDao) {

    fun getAllReadings(): Flow<List<Reading>> {
        return readingDao.getAllReadings()
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
}