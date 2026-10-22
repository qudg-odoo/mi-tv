package be.magin.launcher.model

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "launcher")

/** [favorites] is null until the user changes them; the defaults are then the four brands. */
data class LauncherSettings(val favorites: List<String>?, val hidden: Set<String>)

class LauncherPrefs(private val context: Context) {
    private val favoritesKey = stringPreferencesKey("favorites")
    private val hiddenKey = stringSetPreferencesKey("hidden")

    val settings: Flow<LauncherSettings> = context.dataStore.data.map { prefs ->
        LauncherSettings(
            favorites = prefs[favoritesKey]?.split(',')?.filter { it.isNotBlank() },
            hidden = prefs[hiddenKey] ?: emptySet(),
        )
    }

    suspend fun setFavorites(packages: List<String>) {
        context.dataStore.edit { it[favoritesKey] = packages.joinToString(",") }
    }

    suspend fun setHidden(packages: Set<String>) {
        context.dataStore.edit { it[hiddenKey] = packages }
    }
}
