package fr.lulu.unison.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import fr.lulu.unison.R
import fr.lulu.unison.ui.components.Glyph
import fr.lulu.unison.ui.components.IconBtn
import fr.lulu.unison.ui.components.TopBar
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Radius
import fr.lulu.unison.ui.theme.Type

/**
 * Barre du haut d'une liste de titres (playlist, album, favoris) : une loupe la remplace par un champ qui
 * filtre la liste. [query] vaut null tant que la recherche est fermée ; retour la referme.
 */
@Composable
fun SearchableTopBar(
    query: String?,
    onQuery: (String?) -> Unit,
    hint: String,
    onBack: () -> Unit,
    canSearch: Boolean,
    actions: @Composable RowScope.() -> Unit = {},
) {
    if (query == null) {
        TopBar("", onBack = onBack) {
            if (canSearch) IconBtn(R.drawable.ic_search_line, "Chercher dans la liste", { onQuery("") }, tint = Ink.Secondary)
            actions()
        }
        return
    }
    BackHandler { onQuery(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(R.drawable.ic_left_line, "Fermer la recherche", { onQuery(null) })
        Row(
            Modifier.weight(1f).height(46.dp).clip(Radius.Item).background(Ink.FillHover).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(R.drawable.ic_search_line, tint = Ink.Secondary, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(hint, style = Type.Row, color = Ink.Tertiary)
                BasicTextField(
                    value = query,
                    onValueChange = { onQuery(it) },
                    singleLine = true,
                    textStyle = Type.Row.copy(color = Ink.Primary),
                    cursorBrush = SolidColor(Ink.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester),
                )
            }
            if (query.isNotEmpty()) {
                IconBtn(R.drawable.ic_close_line, "Effacer", { onQuery(""); runCatching { requester.requestFocus() } }, size = 38.dp, iconSize = 16.dp, tint = Ink.Secondary)
            }
        }
    }
}
