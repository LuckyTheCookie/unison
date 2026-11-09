package fr.lulu.unison.deezer

import android.content.Context
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DeezerPlaylist(val id: String, val title: String, val trackCount: Int, val collaborative: Boolean)

/** Ce qu'Unison sait de Deezer : absent, officiel, ou patché avec le pont Unison. */
sealed interface BridgeState {
    data object DeezerMissing : BridgeState
    data object NotPatched : BridgeState
    /** [hasToken] : Deezer a déjà ouvert sa session API depuis son démarrage (sinon, l'ouvrir une fois). */
    data class Patched(val hasToken: Boolean) : BridgeState
}

enum class AddOutcome { Added, Duplicate, NotAllowed, PlaylistFull }

class BridgeException(val code: String) : Exception(code) {
    val needsDeezer get() = code == "no_token"
}

/**
 * Client du pont ajouté à Deezer par le patch Morphe « Unison bridge »
 * (voir le dépôt unison-morphe-patches). Deezer non patché : [state] renvoie [BridgeState.NotPatched].
 */
class DeezerBridge(private val context: Context) {
    private val uri = Uri.parse("content://$AUTHORITY")

    suspend fun state(): BridgeState = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val installed = runCatching { pm.getPackageInfo(DEEZER_PACKAGE, 0) }.isSuccess
        if (!installed) return@withContext BridgeState.DeezerMissing
        val provider = runCatching { pm.resolveContentProvider(AUTHORITY, 0) }.getOrNull()
        if (provider?.packageName != DEEZER_PACKAGE) return@withContext BridgeState.NotPatched
        val hasToken = runCatching { JSONObject(call("info").getString("json")).optBoolean("hasToken") }.getOrDefault(false)
        BridgeState.Patched(hasToken)
    }

    suspend fun playlists(): List<DeezerPlaylist> = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray(call("playlists").getString("json"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            DeezerPlaylist(o.getString("id"), o.optString("title"), o.optInt("count"), o.optBoolean("collaborative"))
        }
    }

    suspend fun addToPlaylist(playlistId: String, trackId: Long): AddOutcome = withContext(Dispatchers.IO) {
        val r = JSONObject(
            call("addToPlaylist", Bundle().apply {
                putString("playlistId", playlistId)
                putStringArray("trackIds", arrayOf(trackId.toString()))
            }).getString("json")
        )
        when {
            r.optBoolean("notAllowed") -> AddOutcome.NotAllowed
            r.optJSONArray("beyondLimit")?.length()?.let { it > 0 } == true -> AddOutcome.PlaylistFull
            r.optJSONArray("duplicated")?.length()?.let { it > 0 } == true -> AddOutcome.Duplicate
            else -> AddOutcome.Added
        }
    }

    private fun call(method: String, extras: Bundle? = null): Bundle {
        val out = try {
            context.contentResolver.call(uri, method, null, extras)
        } catch (e: Exception) {
            throw BridgeException("unavailable")
        } ?: throw BridgeException("unavailable")
        if (!out.getBoolean("ok")) throw BridgeException(out.getString("error") ?: "error")
        return out
    }

    companion object {
        const val DEEZER_PACKAGE = "deezer.android.app"
        const val AUTHORITY = "deezer.android.app.unison.bridge"
        /** Ajoute la source de patches à Morphe Manager en un clic. */
        const val MORPHE_ADD_SOURCE = "https://morphe.software/add-source?github=LuckyTheCookie/unison-morphe-patches"
    }
}
