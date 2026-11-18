package fr.lulu.unison.offline

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import fr.lulu.unison.MainActivity
import fr.lulu.unison.R
import fr.lulu.unison.UnisonApp

/**
 * Garde les téléchargements en vie quand l'app n'est plus à l'écran, avec une notification d'avancement.
 * Le gestionnaire lui-même appartient à l'application ([Downloads]).
 */
class OfflineDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel,
    R.string.download_channel_detail,
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = (application as UnisonApp).graph.downloads.manager

    /** Pas de planificateur : les téléchargements reprennent au prochain lancement de l'app. */
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val active = downloads.count { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        val message = when {
            notMetRequirements != 0 -> "En attente du Wi-Fi"
            active > 1 -> "$active titres à télécharger"
            else -> null
        }
        return helper.buildProgressNotification(this, R.drawable.ic_notification, open, message, downloads, notMetRequirements)
    }

    private companion object {
        const val NOTIFICATION_ID = 7
        const val CHANNEL_ID = "downloads"
    }
}
