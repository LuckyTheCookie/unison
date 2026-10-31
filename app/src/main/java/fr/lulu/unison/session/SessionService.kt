package fr.lulu.unison.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import fr.lulu.unison.MainActivity
import fr.lulu.unison.R
import fr.lulu.unison.UnisonApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** Garde la session (réseau + synchro) en vie écran éteint. */
class SessionService : LifecycleService() {
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Session en cours", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        startForeground(ID, build("Unison", "Session en cours"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "unison:session")
            .apply { setReferenceCounted(false); acquire() }

        val manager = (application as UnisonApp).graph.sessions
        @OptIn(ExperimentalCoroutinesApi::class)
        lifecycleScope.launch {
            manager.active.flatMapLatest { h ->
                h?.snapshot?.let { snap -> combine(snap, h.status) { s, st -> Triple(h, s, st) } } ?: emptyFlow()
            }.collect { (h, s, st) ->
                val title = if (h.isHost) "Ta session · ${s.members.size} pers." else "Session de ${s.hostName} · ${s.members.size} pers."
                val text = when (st) {
                    is LinkStatus.Reconnecting -> "Reconnexion…"
                    is LinkStatus.Lost -> st.reason
                    LinkStatus.Connected -> s.anchor.item?.track?.let { "${it.title} — ${it.artist}" } ?: "File vide · ajoute un titre"
                }
                nm.notify(ID, build(title, text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE) {
            lifecycleScope.launch { (application as UnisonApp).graph.sessions.leave() }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun build(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val leave = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_LEAVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFFC86BFF.toInt())
            .addAction(0, "Quitter", leave)
            .build()
    }

    override fun onDestroy() {
        wifiLock?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "session"
        private const val ID = 7
        private const val ACTION_LEAVE = "fr.lulu.unison.LEAVE"
    }
}
