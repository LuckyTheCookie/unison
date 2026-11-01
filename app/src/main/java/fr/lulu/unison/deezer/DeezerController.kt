package fr.lulu.unison.deezer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class DeezerState(
    val installed: Boolean = false,
    val connected: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val mediaId: String? = null,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val stateCode: Int = PlaybackState.STATE_NONE,
    val position: Long = 0,
    val positionUpdateTime: Long = 0,
    val speed: Float = 1f,
) {
    /** Position actuelle extrapolée (horloge elapsedRealtime). */
    fun positionNow(): Long =
        if (playing) position + ((SystemClock.elapsedRealtime() - positionUpdateTime) * speed).toLong() else position
}

/**
 * Pilote Deezer via sa session média (aucune modification de Deezer).
 * Deux façons d'obtenir le contrôleur :
 *  1. la session active (nécessite l'accès aux notifications) ;
 *  2. la connexion au MediaBrowserService exporté de Deezer, qui réveille Deezer.
 */
class DeezerController(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val _state = MutableStateFlow(DeezerState(installed = isInstalled()))
    val state: StateFlow<DeezerState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var browser: MediaBrowser? = null
    private var listening = false

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() {
            controller = null
            publish()
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        list?.firstOrNull { it.packageName == DEEZER }?.let { attach(it) }
    }

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(DEEZER, PackageManager.PackageInfoFlags.of(0)); true
    }.getOrDefault(false)

    /** À appeler quand l'accès aux notifications vient d'être accordé. */
    fun refresh() = main.post {
        _state.value = _state.value.copy(installed = isInstalled())
        startListening()
        findActiveSession()?.let { attach(it) }
    }

    private fun startListening() {
        if (listening || !UnisonNotificationListener.isGranted(context)) return
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, UnisonNotificationListener.component(context), main)
            listening = true
        }
    }

    private fun findActiveSession(): MediaController? = runCatching {
        sessionManager.getActiveSessions(UnisonNotificationListener.component(context)).firstOrNull { it.packageName == DEEZER }
    }.getOrNull()

    private fun attach(c: MediaController) {
        if (controller?.sessionToken == c.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = c
        c.registerCallback(callback, main)
        publish()
    }

    /** Se connecte au service média de Deezer (le démarre si besoin). */
    private suspend fun connectBrowser(): MediaController? = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<MediaController?>()
        browser?.disconnect()
        val b = MediaBrowser(
            context, ComponentName(DEEZER, DEEZER_BROWSER_SERVICE),
            object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val token = browser?.sessionToken
                    done.complete(token?.let { MediaController(context, it) })
                }
                override fun onConnectionFailed() { done.complete(null) }
                override fun onConnectionSuspended() { done.complete(null) }
            },
            Bundle(),
        )
        browser = b
        runCatching { b.connect() }.onFailure { done.complete(null) }
        withTimeoutOrNull(6_000) { done.await() }
    }

    suspend fun ensureController(): MediaController? {
        controller?.let { return it }
        withContext(Dispatchers.Main) {
            startListening()
            findActiveSession()?.let { attach(it) }
        }
        controller?.let { return it }
        // Deezer éteint : Android interdit de le démarrer en arrière-plan (touche média
        // refusée, MediaBrowser sans « root » pour nous). On tente quand même le
        // MediaBrowser ; sinon playTrack() ouvrira Deezer à l'écran une fois.
        val viaBrowser = connectBrowser()
        if (viaBrowser != null) withContext(Dispatchers.Main) { attach(viaBrowser) }
        return controller
    }

    /** Lance un titre précis dans Deezer. [viaIntent] : ouvre Deezer sur le titre (Unison doit être au premier plan). */
    suspend fun playTrack(trackId: Long, viaIntent: Boolean = false): Boolean {
        val uri = Uri.parse("deezer://www.deezer.com/track/$trackId")
        if (!viaIntent) {
            val c = ensureController() ?: return false
            withContext(Dispatchers.Main) { c.transportControls.playFromUri(uri, Bundle()) }
            return true
        }
        return runCatching {
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(DEEZER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            true
        }.getOrElse { Log.w(TAG, "Impossible d'ouvrir Deezer", it); false }
    }

    suspend fun pause() = withContext(Dispatchers.Main) { controller?.transportControls?.pause() }
    suspend fun resume() = withContext(Dispatchers.Main) { controller?.transportControls?.play() }
    suspend fun seekTo(ms: Long) = withContext(Dispatchers.Main) { controller?.transportControls?.seekTo(ms.coerceAtLeast(0)) }

    fun openDeezer() {
        context.packageManager.getLaunchIntentForPackage(DEEZER)?.let {
            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun publish() {
        val c = controller
        val pb = c?.playbackState
        val md = c?.metadata
        _state.value = DeezerState(
            installed = true,
            connected = c != null,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            mediaId = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            playing = pb?.state == PlaybackState.STATE_PLAYING,
            stateCode = pb?.state ?: PlaybackState.STATE_NONE,
            position = pb?.position ?: 0,
            positionUpdateTime = pb?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = pb?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
        )
    }

    companion object {
        const val DEEZER = "deezer.android.app"
        private const val DEEZER_BROWSER_SERVICE = "com.deezer.feature.mediabrowser.DeezerMediaBrowserService"
        private const val TAG = "UnisonDeezer"
    }
}
