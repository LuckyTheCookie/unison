package fr.lulu.unison.deezer

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Ne lit aucune notification : sa seule présence autorise l'app à
 * accéder à la session média de Deezer (MediaSessionManager).
 */
class UnisonNotificationListener : NotificationListenerService() {
    companion object {
        fun component(context: Context) = ComponentName(context, UnisonNotificationListener::class.java)

        fun isGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = component(context)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
