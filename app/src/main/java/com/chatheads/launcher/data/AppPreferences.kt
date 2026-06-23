package com.chatheads.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "chathead_prefs")

class AppPreferences(private val context: Context) {

    private val selectedAppsKey = stringSetPreferencesKey("selected_apps")

    val selectedApps: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[selectedAppsKey] ?: emptySet()
    }

    suspend fun addApp(packageName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[selectedAppsKey] ?: emptySet()
            prefs[selectedAppsKey] = current + packageName
        }
    }

    suspend fun removeApp(packageName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[selectedAppsKey] ?: emptySet()
            prefs[selectedAppsKey] = current - packageName
        }
    }
}
