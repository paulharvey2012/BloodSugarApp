package com.bloodsugar.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "readings")
data class Reading(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String, // "blood_sugar" or "ketone"
    val value: Double,
    val unit: String,
    val date: Date,
    val notes: String = ""
)
