package fr.lulu.unison.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import fr.lulu.unison.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float?) : UpdateState
    /** L'APK est prêt mais Android n'autorise pas encore Unison à installer des apps. */
    data class NeedsPermission(val release: Release) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/**
 * Mises à jour depuis les releases GitHub : recherche, téléchargement vérifié,
 * puis installation via PackageInstaller (Android demande une confirmation).
 */
class UpdateManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val dir get() = File(context.cacheDir, "update").apply { mkdirs() }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    @Serializable private data class GhAsset(
        val name: String = "", val size: Long = 0, val browser_download_url: String = "", val digest: String? = null,
    )
    @Serializable private data class GhRelease(
        val tag_name: String = "", val body: String? = null, val draft: Boolean = false,
        val prerelease: Boolean = false, val assets: List<GhAsset> = emptyList(),
    )

    /** Cherche une version plus récente. [manual] : affiche aussi « à jour » et les erreurs réseau. */
    fun check(manual: Boolean = false) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val release = fetchLatest()
                if (release != null && Versions.isNewer(release.version, currentVersion)) UpdateState.Available(release)
                else if (manual) UpdateState.UpToDate else UpdateState.Idle
            } catch (t: Throwable) {
                Log.w(TAG, "recherche de mise à jour", t)
                if (manual) UpdateState.Failed("Impossible de joindre GitHub. Vérifie ta connexion.") else UpdateState.Idle
            }
        }
    }

    private fun fetchLatest(): Release? {
        val req = Request.Builder().url(API).header("Accept", "application/vnd.github+json").build()
        val gh = http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            check(r.isSuccessful) { "HTTP ${r.code}" }
            json.decodeFromString<GhRelease>(r.body.string())
        }
        if (gh.draft || gh.prerelease) return null
        val apk = gh.assets.firstOrNull { it.name.endsWith(".apk") && it.browser_download_url.startsWith(TRUSTED_PREFIX) } ?: return null
        return Release(
            version = gh.tag_name.removePrefix("v"),
            notes = gh.body.orEmpty().trim(),
            apkUrl = apk.browser_download_url,
            sizeBytes = apk.size,
            sha256 = apk.digest?.removePrefix("sha256:")?.takeIf { it.length == 64 },
        )
    }

    /** Télécharge puis lance l'installation. */
    fun download(release: Release) {
        if (_state.value is UpdateState.Downloading) return
        job = scope.launch {
            try {
                val file = downloadTo(release)
                install(release, file)
            } catch (t: Throwable) {
                if (!isActive) return@launch
                Log.w(TAG, "téléchargement", t)
                _state.value = UpdateState.Failed(t.message ?: "Téléchargement impossible", release)
            }
        }
    }

    private suspend fun downloadTo(release: Release): File {
        _state.value = UpdateState.Downloading(release, null)
        val target = File(dir, "Unison-${release.version}.apk")
        val part = File(dir, "download.part")
        val req = Request.Builder().url(release.apkUrl).build()
        val sha = MessageDigest.getInstance("SHA-256")
        http.newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            check(r.isSuccessful) { "Téléchargement refusé (HTTP ${r.code})" }
            val body = r.body
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            var done = 0L
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); sha.update(buf, 0, n)
                        done += n
                        if (total > 0) _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val hex = sha.digest().joinToString("") { "%02x".format(it) }
        check(release.sha256 == null || release.sha256.equals(hex, ignoreCase = true)) { "Fichier corrompu (empreinte différente)" }
        target.delete()
        check(part.renameTo(target)) { "Impossible d'enregistrer le fichier" }
        verifyApk(target, release)
        return target
    }

    /** Refuse tout fichier qui n'est pas une version plus récente de cette même app. */
    private fun verifyApk(file: File, release: Release) {
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
        check(info != null && info.packageName == context.packageName) { "Ce fichier n'est pas Unison" }
        val current = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        check(info.longVersionCode > current) { "Cette version n'est pas plus récente" }
    }

    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    /** Ouvre le réglage Android « Installer des applis inconnues » pour Unison. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** À appeler au retour des réglages : reprend l'installation si l'autorisation est accordée. */
    fun resumeAfterPermission() {
        val s = _state.value as? UpdateState.NeedsPermission ?: return
        if (!canInstall()) return
        val file = File(dir, "Unison-${s.release.version}.apk")
        if (file.exists()) job = scope.launch { install(s.release, file) } else download(s.release)
    }

    private suspend fun install(release: Release, file: File) = withContext(Dispatchers.IO) {
        if (!canInstall()) { _state.value = UpdateState.NeedsPermission(release); return@withContext }
        _state.value = UpdateState.Installing(release)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("unison.apk", 0, file.length()).use { out ->
                    input.copyTo(out); session.fsync(out)
                }
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
    }

    /** Résultat renvoyé par le système (voir [UpdateInstallReceiver]). */
    internal fun onInstallStatus(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> { _state.value = UpdateState.Idle; clean() }
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.update { s ->
                (s as? UpdateState.Installing)?.let { UpdateState.Available(it.release) } ?: s
            }
            else -> _state.update { s ->
                UpdateState.Failed(message ?: "L'installation a échoué", (s as? UpdateState.Installing)?.release)
            }
        }
    }

    fun dismiss() {
        val s = _state.value
        if (s is UpdateState.Downloading || s is UpdateState.Installing) return
        _state.value = UpdateState.Idle
    }

    fun cancelDownload() { job?.cancel(); _state.update { (it as? UpdateState.Downloading)?.let { d -> UpdateState.Available(d.release) } ?: it } }

    /** Supprime les APK déjà téléchargés. */
    fun clean() { runCatching { dir.listFiles()?.forEach { it.delete() } } }

    companion object {
        private const val TAG = "UnisonUpdate"
        private const val REPO = "LuckyTheCookie/unison"
        private const val API = "https://api.github.com/repos/$REPO/releases/latest"
        private const val TRUSTED_PREFIX = "https://github.com/$REPO/releases/download/"
    }
}
