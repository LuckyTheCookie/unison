package fr.lulu.unison.apkshare

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Partage l'APK d'Unison pour que les invités puissent l'installer. */
object ApkSharer {
    suspend fun share(context: Context) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            File(dir, "Unison.apk").also { out ->
                File(context.applicationInfo.sourceDir).copyTo(out, overwrite = true)
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "Installe Unison pour écouter Deezer ensemble 🎧")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("Unison.apk", uri)
        context.startActivity(Intent.createChooser(send, "Partager Unison").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
