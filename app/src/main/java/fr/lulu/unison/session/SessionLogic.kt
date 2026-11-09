package fr.lulu.unison.session

import fr.lulu.unison.model.PlayAnchor
import fr.lulu.unison.model.QueueItem
import fr.lulu.unison.model.SessionSnapshot

/**
 * Règles de la file partagée, sans effet de bord : l'hôte les applique
 * à son état et diffuse le résultat. `queue` = titres à venir,
 * `anchor.item` = titre en cours, `history` = titres déjà joués.
 */
class SessionLogic(
    private val now: () -> Long = System::currentTimeMillis,
    private val newUid: () -> String = { java.util.UUID.randomUUID().toString().take(12) },
) {
    fun apply(s: SessionSnapshot, cmd: Cmd, by: String): SessionSnapshot = when (cmd) {
        is Cmd.AddTrack -> {
            val item = QueueItem(newUid(), cmd.track, by)
            if (s.anchor.item == null) {
                s.copy(anchor = start(item)).bump()
            } else {
                val q = if (cmd.playNext) listOf(item) + s.queue else s.queue + item
                s.copy(queue = q).bump()
            }
        }

        is Cmd.AddTracks -> {
            val items = cmd.tracks.take(MAX_BATCH).map { QueueItem(newUid(), it, by) }
            if (items.isEmpty()) s
            else if (s.anchor.item == null) {
                // Rien ne joue : le premier titre démarre, les suivants forment la file (en tête si « ensuite »).
                val rest = items.drop(1)
                s.copy(anchor = start(items.first()), queue = if (cmd.playNext) rest + s.queue else s.queue + rest).bump()
            } else {
                s.copy(queue = if (cmd.playNext) items + s.queue else s.queue + items).bump()
            }
        }

        is Cmd.Remove -> {
            if (s.queue.none { it.uid == cmd.uid }) s
            else s.copy(queue = s.queue.filterNot { it.uid == cmd.uid }).bump()
        }

        is Cmd.Move -> {
            val from = s.queue.indexOfFirst { it.uid == cmd.uid }
            if (from < 0) s else {
                val list = s.queue.toMutableList()
                val item = list.removeAt(from)
                list.add(cmd.toIndex.coerceIn(0, list.size), item)
                s.copy(queue = list).bump()
            }
        }

        is Cmd.PlayNow -> {
            val item = s.queue.firstOrNull { it.uid == cmd.uid }
            if (item == null) s else s.copy(
                queue = s.queue - item,
                history = pushHistory(s.history, s.anchor.item),
                anchor = start(item),
            ).bump()
        }

        Cmd.Skip -> advance(s)

        Cmd.Previous -> {
            val t = now()
            val prev = s.history.lastOrNull()
            if (s.anchor.positionAt(t) > 5_000 || prev == null) {
                s.copy(anchor = s.anchor.copy(positionMs = 0, hostTimeMs = t)).bump()
            } else {
                s.copy(
                    history = s.history.dropLast(1),
                    queue = listOfNotNull(s.anchor.item) + s.queue,
                    anchor = start(prev),
                ).bump()
            }
        }

        Cmd.Pause -> {
            if (!s.anchor.playing) s else {
                val t = now()
                s.copy(anchor = s.anchor.copy(positionMs = s.anchor.positionAt(t), hostTimeMs = t, playing = false)).bump()
            }
        }

        Cmd.Resume -> {
            if (s.anchor.playing) s
            else if (s.anchor.item == null) advance(s)
            else s.copy(anchor = s.anchor.copy(hostTimeMs = now(), playing = true)).bump()
        }

        is Cmd.Seek -> {
            if (s.anchor.item == null) s
            else s.copy(anchor = s.anchor.copy(positionMs = cmd.positionMs.coerceAtLeast(0), hostTimeMs = now())).bump()
        }
    }

    /** Passe au titre suivant de la file (ou s'arrête si elle est vide). */
    fun advance(s: SessionSnapshot): SessionSnapshot {
        val next = s.queue.firstOrNull()
        return s.copy(
            queue = s.queue.drop(1),
            history = pushHistory(s.history, s.anchor.item),
            anchor = if (next != null) start(next) else PlayAnchor(),
        ).bump()
    }

    /** Vrai si le titre en cours est arrivé à sa fin (avec une petite marge). */
    fun isFinished(s: SessionSnapshot, marginMs: Long = 250): Boolean {
        val item = s.anchor.item ?: return false
        val d = item.track.durationMs
        return s.anchor.playing && d > 0 && s.anchor.positionAt(now()) >= d - marginMs
    }

    /** Départ différé : laisse le temps à chaque téléphone de charger le titre avant la lecture commune. */
    private fun start(item: QueueItem) = PlayAnchor(item, 0, now() + START_DELAY_MS, true)

    private fun pushHistory(h: List<QueueItem>, item: QueueItem?): List<QueueItem> =
        if (item == null) h else (h + item).takeLast(MAX_HISTORY)

    private fun SessionSnapshot.bump() = copy(version = version + 1)

    companion object {
        const val MAX_HISTORY = 25
        const val MAX_BATCH = 100
        const val START_DELAY_MS = 3_000L
    }
}
