package com.bloodsugar.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// DataStore delegate (top-level) — uses "settings" as filename
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class UnitPreferences(private val dataStore: DataStore<Preferences>) {
    companion object {
        private val UNIT_KEY = stringPreferencesKey("default_unit")
        const val DEFAULT_UNIT = "mmol/L"
    }

    val unitFlow: Flow<String> = dataStore.data
        .map { prefs ->
            prefs[UNIT_KEY] ?: DEFAULT_UNIT
        }

    suspend fun setUnit(unit: String) {
        dataStore.edit { prefs ->
            prefs[UNIT_KEY] = unit
        }
    }
}

