package dev.clouddrive.core.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.clouddrive.core.model.FileViewMode
import dev.clouddrive.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("cloud_drive_settings")

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fileViewMode: FileViewMode = FileViewMode.LIST,
    val cacheLimitBytes: Long = 1L shl 30,
    val cacheAgeDays: Int = 7,
    val maxConcurrentTransfers: Int = 3,
    val wifiOnly: Boolean = false,
    val metadataIndexPaused: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { values ->
        AppSettings(
            themeMode = values[THEME]?.let(ThemeMode::valueOf) ?: ThemeMode.SYSTEM,
            fileViewMode = values[VIEW]?.let(FileViewMode::valueOf) ?: FileViewMode.LIST,
            cacheLimitBytes = values[CACHE_LIMIT] ?: (1L shl 30),
            cacheAgeDays = values[CACHE_AGE] ?: 7,
            maxConcurrentTransfers = (values[MAX_TRANSFERS] ?: 3).coerceIn(1, 4),
            wifiOnly = values[WIFI_ONLY] ?: false,
            metadataIndexPaused = values[INDEX_PAUSED] ?: false,
        )
    }

    suspend fun setTheme(value: ThemeMode) = edit { it[THEME] = value.name }
    suspend fun setViewMode(value: FileViewMode) = edit { it[VIEW] = value.name }
    suspend fun setCacheLimit(value: Long) = edit { it[CACHE_LIMIT] = value.coerceAtLeast(256L shl 20) }
    suspend fun setMaxTransfers(value: Int) = edit { it[MAX_TRANSFERS] = value.coerceIn(1, 4) }
    suspend fun setWifiOnly(value: Boolean) = edit { it[WIFI_ONLY] = value }
    suspend fun setMetadataIndexPaused(value: Boolean) = edit { it[INDEX_PAUSED] = value }

    private suspend inline fun edit(crossinline block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val VIEW = stringPreferencesKey("view")
        val CACHE_LIMIT = longPreferencesKey("cache_limit")
        val CACHE_AGE = intPreferencesKey("cache_age_days")
        val MAX_TRANSFERS = intPreferencesKey("max_transfers")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val INDEX_PAUSED = booleanPreferencesKey("metadata_index_paused")
    }
}
