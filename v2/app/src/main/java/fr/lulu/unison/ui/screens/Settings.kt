package fr.lulu.unison.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import fr.lulu.unison.ui.components.Glyph
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.lulu.unison.BuildConfig
import fr.lulu.unison.R
import fr.lulu.unison.apkshare.ApkSharer
import fr.lulu.unison.data.AvatarColors
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.LocalBottomInset
import fr.lulu.unison.ui.LocalNav
import fr.lulu.unison.ui.components.Avatar
import fr.lulu.unison.ui.components.Chip
import fr.lulu.unison.ui.components.GlassButton
import fr.lulu.unison.ui.components.GlassPanel
import fr.lulu.unison.ui.components.PrimaryButton
import fr.lulu.unison.ui.components.Spinner
import fr.lulu.unison.ui.components.TopBar
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Radius
import fr.lulu.unison.ui.theme.Type
import fr.lulu.unison.update.Release
import fr.lulu.unison.update.UpdateState
import fr.lulu.unison.youtube.Quality
import kotlinx.coroutines.launch
import java.util.Locale

/** Pages des réglages : l'accueil les range par thème, chacune s'ouvre à part. */
enum class SettingsPage(val route: String, val title: String) {
    Profile("profil", "Profil"),
    Playback("lecture", "Lecture"),
    Sources("sources", "Sources audio"),
    Account("compte", "Compte YouTube"),
    Storage("stockage", "Téléchargements et stockage"),
    Appearance("apparence", "Apparence"),
    About("a-propos", "À propos"),
    ;

    companion object {
        fun of(route: String?) = entries.firstOrNull { it.route == route }
    }
}

/** Accueil des réglages : le profil, puis les thèmes, chacun avec un résumé de ce qui est choisi. */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val nav = LocalNav.current
    val profile by vm.profile.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val login by vm.youtubeLogin.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("Réglages", onBack = nav.back)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = LocalBottomInset.current + 24.dp)) {
            val p = profile
            Row(
                Modifier.fillMaxWidth().clip(Radius.Panel).background(Ink.Fill).border(0.5.dp, Ink.Hairline, Radius.Panel)
                    .pressable(scale = 0.985f) { nav.settingsPage(SettingsPage.Profile.route) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(p?.name.orEmpty(), p?.color ?: AvatarColors[5], size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p?.name.orEmpty(), style = Type.Title, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Ton nom et ta couleur en écoute partagée", style = Type.Caption, color = Ink.Tertiary)
                }
                Glyph(R.drawable.ic_right_line, tint = Ink.Tertiary, size = 18.dp)
            }

            Label("Écoute")
            GlassPanel(Modifier.fillMaxWidth()) {
                val activeName = sources.firstOrNull { it.id == settings.activeSource }?.name ?: "YouTube Music"
                PageRow(SettingsPage.Playback, R.drawable.ic_headphone_line, listOfNotNull(
                    "Qualité ${settings.quality.label.lowercase()}",
                    "volume égalisé".takeIf { settings.normalizeVolume },
                    "graves renforcés".takeIf { settings.bassBoostOn },
                ).joinToString(" · "))
                RowDivider()
                PageRow(SettingsPage.Sources, R.drawable.ic_music_line,
                    if (sources.isEmpty()) "YouTube Music" else "$activeName · ${sources.size + 1} sources")
                RowDivider()
                PageRow(SettingsPage.Account, R.drawable.ic_user_line, login?.account ?: "Non connecté · titres réservés aux adultes")
            }

            Label("Bibliothèque")
            GlassPanel(Modifier.fillMaxWidth()) {
                PageRow(SettingsPage.Storage, R.drawable.ic_download_line, listOf(
                    if (settings.downloadWifiOnly) "Wi-Fi uniquement" else "Wi-Fi et données mobiles",
                    "cache de ${cacheLabel(settings.cacheMb)}",
                ).joinToString(" · "))
                RowDivider()
                LinkRow(R.drawable.ic_import_line, "Importer", "Favoris et playlists d'un export CSV (Deezer, Spotify…)", nav.import)
            }

            Label("Application")
            GlassPanel(Modifier.fillMaxWidth()) {
                PageRow(SettingsPage.Appearance, R.drawable.ic_sparkles_line, listOf(
                    if (settings.animatedBackground) "Fond animé" else "Fond fixe",
                    if (settings.reduceMotion) "animations réduites" else "animations complètes",
                ).joinToString(" · "))
                RowDivider()
                PageRow(SettingsPage.About, R.drawable.ic_info_line, "Version ${BuildConfig.VERSION_NAME}" +
                    if (update is UpdateState.Available) " · mise à jour disponible" else "")
            }

            Text(
                "Unison n'est affilié ni à YouTube ni à Google.",
                style = Type.Caption, color = Ink.Tertiary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
            )
        }
    }
}

/** Une page des réglages, ouverte depuis l'accueil des réglages. */
@Composable
fun SettingsPageScreen(page: SettingsPage, vm: AppViewModel) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar(page.title, onBack = nav.back)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(top = 8.dp, bottom = LocalBottomInset.current + 24.dp)) {
            when (page) {
                SettingsPage.Profile -> ProfilePage(vm)
                SettingsPage.Playback -> PlaybackPage(vm)
                SettingsPage.Sources -> SourcesPanel(vm)
                SettingsPage.Account -> YouTubeAccountPanel(vm)
                SettingsPage.Storage -> StoragePage(vm)
                SettingsPage.Appearance -> AppearancePage(vm)
                SettingsPage.About -> AboutPage(vm)
            }
        }
    }
}

@Composable
private fun ProfilePage(vm: AppViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    val p = profile
    GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(p?.name.orEmpty(), p?.color ?: AvatarColors[5], size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p?.name.orEmpty(), style = Type.Title, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("C'est le nom que voient les autres quand vous écoutez ensemble", style = Type.Caption, color = Ink.Tertiary)
            }
            GlassButton("Modifier", { renaming = true })
        }
    }
    Label("Couleur")
    GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AvatarColors.forEach { c ->
                val selected = p?.color == c
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                        .then(if (selected) Modifier.border(2.5.dp, Color.White, CircleShape) else Modifier)
                        .pressable { p?.let { vm.saveProfile(it.name, c) } },
                )
            }
        }
    }
    if (renaming) TextPrompt("Ton nom", "Nom affiché", initial = p?.name.orEmpty(), confirm = "Enregistrer", onDismiss = { renaming = false }) { name ->
        renaming = false
        vm.saveProfile(name.take(24), p?.color ?: AvatarColors[5])
    }
}

@Composable
private fun PlaybackPage(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Label("Son", top = 4.dp)
    GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Text("Qualité audio", style = Type.Row, color = Ink.Primary)
        Text("« Économie de données » consomme environ trois fois moins en données mobiles", style = Type.Caption, color = Ink.Tertiary)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Quality.entries.forEach { q -> Chip(q.label, settings.quality == q, onClick = { vm.setQuality(q) }) }
        }
        Divider()
        SwitchRow("Égaliser le volume", "Ramène les titres trop forts au même niveau que les autres", settings.normalizeVolume) { vm.setNormalize(!settings.normalizeVolume) }
        Divider()
        SwitchRow("Bass Booster", "Renforce les graves sur ce téléphone, sans saturer. S'applique tout de suite.", settings.bassBoostOn) {
            vm.setBassBoost(!settings.bassBoostOn)
        }
        if (settings.bassBoostOn) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { level -> Chip("$level", settings.bassLevel == level, onClick = { vm.setBassLevel(level) }) }
            }
        }
    }
    Label("Radio")
    GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        SwitchRow("Privilégier mes artistes", "La radio automatique glisse environ un titre sur quatre de tes artistes suivis", settings.preferMyArtists) { vm.setPreferMyArtists(!settings.preferMyArtists) }
    }
}

@Composable
private fun StoragePage(vm: AppViewModel) {
    val nav = LocalNav.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    Label("Téléchargements", top = 4.dp)
    GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        SwitchRow("Wi-Fi uniquement", "Les téléchargements attendent un réseau Wi-Fi au lieu d'utiliser les données mobiles", settings.downloadWifiOnly) {
            vm.setDownloadWifiOnly(!settings.downloadWifiOnly)
        }
        Divider()
        GlassButton("Gérer les téléchargements", nav.downloads, Modifier.fillMaxWidth(), icon = R.drawable.ic_download_line)
    }
    Label("Cache d'écoute")
    GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Text("Titres gardés sur le téléphone", style = Type.Row, color = Ink.Primary)
        Text("Un titre déjà écouté se relance sans le retélécharger. Pris en compte au prochain lancement.", style = Type.Caption, color = Ink.Tertiary)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(256, 512, 1024, 2048).forEach { mb -> Chip(cacheLabel(mb), settings.cacheMb == mb, onClick = { vm.setCacheMb(mb) }) }
        }
    }
}

@Composable
private fun AppearancePage(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        SwitchRow("Fond animé", "Le fond tiré de la pochette ondule doucement. Le couper économise un peu de batterie.", settings.animatedBackground) {
            vm.setAnimatedBackground(!settings.animatedBackground)
        }
        Divider()
        SwitchRow("Réduire les animations", "La pochette, le lecteur, la barre de lecture, les paroles et le libellé de qualité changent sans transition.", settings.reduceMotion) {
            vm.setReduceMotion(!settings.reduceMotion)
        }
    }
}

@Composable
private fun AboutPage(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by vm.updateState.collectAsStateWithLifecycle()
    GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Unison ${BuildConfig.VERSION_NAME}", style = Type.Row, color = Ink.Primary)
                Text(
                    when (update) {
                        UpdateState.Checking -> "Recherche d'une mise à jour…"
                        UpdateState.UpToDate -> "Tu as la dernière version"
                        is UpdateState.Available -> "Une nouvelle version est disponible"
                        else -> "Mises à jour depuis GitHub"
                    },
                    style = Type.Caption, color = Ink.Tertiary,
                )
            }
            GlassButton("Vérifier", { vm.updates.check(manual = true) }, enabled = update !is UpdateState.Checking)
        }
        Divider()
        GlassButton("Envoyer Unison à un ami", { scope.launch { ApkSharer.share(context) } }, Modifier.fillMaxWidth(), icon = R.drawable.ic_share_line)
    }
    Text(
        "Unison n'est affilié ni à YouTube ni à Google. Fond animé : Kawarp, de Better Lyrics. Icônes : Mingcute.",
        style = Type.Caption, color = Ink.Tertiary, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
    )
}

private fun cacheLabel(mb: Int) = if (mb >= 1024) "${mb / 1024} Go" else "$mb Mo"

/** Ligne de l'accueil des réglages : icône, titre, résumé de ce qui est choisi, chevron. */
@Composable
private fun PageRow(page: SettingsPage, @DrawableRes icon: Int, summary: String) {
    val nav = LocalNav.current
    LinkRow(icon, page.title, summary) { nav.settingsPage(page.route) }
}

@Composable
private fun LinkRow(@DrawableRes icon: Int, title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(scale = 0.985f, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(Radius.Control).background(Ink.FillStrong), contentAlignment = Alignment.Center) {
            Glyph(icon, tint = Ink.Primary, size = 20.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Row, color = Ink.Primary, maxLines = 1)
            Text(summary, style = Type.Caption, color = Ink.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Glyph(R.drawable.ic_right_line, tint = Ink.Tertiary, size = 18.dp)
    }
}

/** Séparation entre deux lignes, alignée sur le texte (après l'icône). */
@Composable
private fun RowDivider() = Box(Modifier.padding(start = 66.dp).fillMaxWidth().height(0.5.dp).background(Ink.Hairline))

@Composable
private fun Label(text: String, top: Dp = 22.dp) =
    Text(text, style = Type.Heading, color = Ink.Primary, modifier = Modifier.padding(start = 8.dp, top = top, bottom = 10.dp))

@Composable
private fun Divider() = Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(0.5.dp).background(Ink.Hairline))

@Composable
private fun SwitchRow(title: String, detail: String, on: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(scale = 0.99f, onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Type.Row, color = Ink.Primary)
            Text(detail, style = Type.Caption, color = Ink.Tertiary)
        }
        Toggle(on)
    }
}

/** Vrai quand l'état mérite d'afficher la carte de mise à jour. */
fun UpdateState.isActionable() = this is UpdateState.Available || this is UpdateState.Downloading ||
    this is UpdateState.NeedsPermission || this is UpdateState.Installing || this is UpdateState.Failed

@Composable
fun UpdateCard(state: UpdateState, onDownload: (Release) -> Unit, onGrantPermission: () -> Unit, onLater: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pressable(scale = 1f) {}.padding(28.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().clip(Radius.Panel).background(Ink.Panel).border(0.5.dp, Ink.Hairline, Radius.Panel).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                is UpdateState.Available -> {
                    Text("Nouvelle version disponible", style = Type.Heading, color = Ink.Primary, textAlign = TextAlign.Center)
                    Text("Unison ${state.release.version} · ${megabytes(state.release.sizeBytes)}", style = Type.Body, color = Ink.Secondary, modifier = Modifier.padding(top = 4.dp))
                    if (state.release.notes.isNotBlank()) {
                        Column(Modifier.padding(top = 14.dp).fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                            Text(cleanNotes(state.release.notes), style = Type.Body, color = Ink.Secondary)
                        }
                    }
                    Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton("Plus tard", onLater)
                        PrimaryButton("Télécharger", { onDownload(state.release) })
                    }
                }
                is UpdateState.Downloading -> {
                    Text("Téléchargement…", style = Type.Heading, color = Ink.Primary)
                    Box(Modifier.padding(top = 18.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Ink.FillStrong)) {
                        Box(Modifier.fillMaxWidth(state.progress ?: 0.08f).height(6.dp).clip(RoundedCornerShape(50)).background(Color.White))
                    }
                    Text(state.progress?.let { "${(it * 100).toInt()} %" } ?: "Connexion à GitHub…", style = Type.Caption, color = Ink.Secondary, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                    GlassButton("Annuler", onCancel)
                }
                is UpdateState.NeedsPermission -> {
                    Text("Autorisation requise", style = Type.Heading, color = Ink.Primary)
                    Text(
                        "Pour installer la mise à jour, autorise Unison à installer des applications dans les réglages Android, puis reviens ici : l'installation reprendra toute seule.",
                        style = Type.Body, color = Ink.Secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton("Plus tard", onLater)
                        PrimaryButton("Autoriser", onGrantPermission)
                    }
                }
                is UpdateState.Installing -> {
                    Text("Installation de la ${state.release.version}…", style = Type.Heading, color = Ink.Primary, textAlign = TextAlign.Center)
                    Text("Confirme l'installation si Android te le demande.", style = Type.Body, color = Ink.Secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                    Spinner()
                }
                is UpdateState.Failed -> {
                    Text("Mise à jour impossible", style = Type.Heading, color = Ink.Primary)
                    Text(state.message, style = Type.Body, color = Ink.Secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton("Fermer", onLater)
                        state.release?.let { r -> PrimaryButton("Réessayer", { onDownload(r) }) }
                    }
                }
                else -> Unit
            }
        }
    }
}

private fun megabytes(bytes: Long) = String.format(Locale.FRANCE, "%.1f Mo", bytes / 1_048_576.0)

/** Garde le texte lisible : retire les titres, la mise en gras et les citations Markdown. */
private fun cleanNotes(md: String) = md.lines()
    .map { it.trim().trimStart('#', '>', ' ').replace("**", "").replace("`", "") }
    .filter { it.isNotBlank() && !it.startsWith("Signature SHA") }
    .joinToString("\n")
