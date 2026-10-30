package fr.lulu.unison.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

data class Profile(val deviceId: String, val name: String, val color: Int) {
    val isComplete get() = name.isNotBlank()
}

private val Context.store by preferencesDataStore("profile")

class ProfileStore(private val context: Context) {
    private val kId = stringPreferencesKey("device_id")
    private val kName = stringPreferencesKey("name")
    private val kColor = intPreferencesKey("color")
    private val kTheme = stringPreferencesKey("theme")

    /** Design choisi dans les réglages (verre liquide par défaut). */
    val theme: Flow<fr.lulu.unison.ui.theme.UiStyle> = context.store.data.map { p ->
        runCatching { fr.lulu.unison.ui.theme.UiStyle.valueOf(p[kTheme] ?: "") }.getOrDefault(fr.lulu.unison.ui.theme.UiStyle.Glass)
    }

    suspend fun setTheme(style: fr.lulu.unison.ui.theme.UiStyle) {
        context.store.edit { it[kTheme] = style.name }
    }

    val profile: Flow<Profile> = context.store.data.map { p ->
        Profile(p[kId] ?: "", p[kName] ?: "", p[kColor] ?: AvatarColors.first())
    }

    suspend fun current(): Profile {
        val p = profile.first()
        if (p.deviceId.isNotEmpty()) return p
        val id = UUID.randomUUID().toString().take(12)
        context.store.edit { it[kId] = id }
        return p.copy(deviceId = id)
    }

    suspend fun save(name: String, color: Int) {
        context.store.edit {
            if (it[kId] == null) it[kId] = UUID.randomUUID().toString().take(12)
            it[kName] = name.trim().take(24)
            it[kColor] = color
        }
    }
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
