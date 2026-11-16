package fr.lulu.unison.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import fr.lulu.unison.youtube.Quality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

data class Profile(val deviceId: String, val name: String, val color: Int)

data class Settings(
    val quality: Quality = Quality.HIGH,
    val cacheMb: Int = 512,
    val normalizeVolume: Boolean = true,
    val animatedBackground: Boolean = true,
)

private val Context.store by preferencesDataStore("profile")

/** Profil (nom et couleur vus par les autres) et réglages de l'app. */
class Prefs(private val context: Context) {
    private val kId = stringPreferencesKey("device_id")
    private val kName = stringPreferencesKey("name")
    private val kColor = intPreferencesKey("color")
    private val kQuality = stringPreferencesKey("quality")
    private val kCacheMb = intPreferencesKey("cache_mb")
    private val kNormalize = booleanPreferencesKey("normalize")
    private val kAnimated = booleanPreferencesKey("animated_bg")

    val profile: Flow<Profile> = context.store.data.map { p ->
        Profile(p[kId] ?: "", p[kName]?.ifBlank { null } ?: defaultName(), p[kColor] ?: AvatarColors[5])
    }

    val settings: Flow<Settings> = context.store.data.map { p ->
        Settings(
            quality = runCatching { Quality.valueOf(p[kQuality] ?: "") }.getOrDefault(Quality.HIGH),
            cacheMb = p[kCacheMb] ?: 512,
            normalizeVolume = p[kNormalize] ?: true,
            animatedBackground = p[kAnimated] ?: true,
        )
    }

    /** Profil avec un identifiant d'appareil, créé au premier besoin. */
    suspend fun current(): Profile {
        val p = profile.first()
        if (p.deviceId.isNotEmpty()) return p
        val id = UUID.randomUUID().toString().take(12)
        context.store.edit { if (it[kId] == null) it[kId] = id }
        return profile.first()
    }

    suspend fun saveProfile(name: String, color: Int) {
        context.store.edit {
            if (it[kId] == null) it[kId] = UUID.randomUUID().toString().take(12)
            it[kName] = name.trim().take(24)
            it[kColor] = color
        }
    }

    suspend fun setQuality(q: Quality) { context.store.edit { it[kQuality] = q.name } }
    suspend fun setCacheMb(mb: Int) { context.store.edit { it[kCacheMb] = mb } }
    suspend fun setNormalize(on: Boolean) { context.store.edit { it[kNormalize] = on } }
    suspend fun setAnimatedBackground(on: Boolean) { context.store.edit { it[kAnimated] = on } }

    private fun defaultName(): String = Build.MODEL?.take(24)?.ifBlank { null } ?: "Mon téléphone"
}

/** Palette des avatars (ARGB). */
val AvatarColors = listOf(
    0xFFFF5E8A.toInt(), // rose
    0xFFFF9F43.toInt(), // orange
    0xFFFFD93D.toInt(), // jaune
    0xFF4CD9A0.toInt(), // menthe
    0xFF3EC5FF.toInt(), // ciel
    0xFF7C6CFF.toInt(), // indigo
    0xFFC86BFF.toInt(), // lilas
    0xFFFFFFFF.toInt(), // blanc
)
