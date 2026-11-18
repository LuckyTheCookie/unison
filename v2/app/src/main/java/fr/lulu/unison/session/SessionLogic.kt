package fr.lulu.unison.session

import fr.lulu.unison.model.PlayAnchor
import fr.lulu.unison.model.QueueItem
import fr.lulu.unison.model.RepeatMode
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.model.Track

/**
 * Règles de la file, sans effet de bord : l'hôte les applique à son état et diffuse le résultat.
 * Seul, le téléphone est son propre hôte.
 */
class SessionLogic(
    private val now: () -> Long = System::currentTimeMillis,
    private val newUid: () -> String = { java.util.UUID.randomUUID().toString().take(12) },
    private val shuffler: (List<QueueItem>) -> List<QueueItem> = { it.shuffled() },
    /** Entier au hasard dans [0, n) : place d'un ajout en mode aléatoire, titre de départ du bouton « Aléatoire ». */
    private val random: (Int) -> Int = { kotlin.random.Random.nextInt(it) },
) {
    fun apply(s: SessionSnapshot, cmd: Cmd, by: String): SessionSnapshot = when (cmd) {
        is Cmd.AddTrack -> add(s, listOf(cmd.track), cmd.playNext, by)

        is Cmd.AddTracks -> add(s, cmd.tracks.take(MAX_BATCH), cmd.playNext, if (cmd.auto) QueueItem.AUTO else by)

        is Cmd.PlayTracks -> {
            val items = cmd.tracks.take(MAX_BATCH).map { QueueItem(newUid(), it, by) }
            val shuffle = cmd.shuffle || s.shuffle
            val i = if (cmd.shuffle && items.isNotEmpty()) random(items.size) else cmd.index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            if (items.isEmpty()) s else s.copy(
                history = pushHistory(s.history, s.anchor.item),
                anchor = start(s, items[i]),
                // En aléatoire, tous les autres titres de la liste suivent, mélangés ; l'ordre de la liste est gardé.
                queue = if (shuffle) shuffler(items.filterIndexed { j, _ -> j != i }) else items.drop(i + 1),
                shuffle = shuffle,
                queueOrder = if (shuffle) items.map { it.uid } else emptyList(),
            ).bump()
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
            val i = s.queue.indexOfFirst { it.uid == cmd.uid }
            if (i < 0) s else s.copy(
                // Les titres sautés restent dans la file, derrière ceux qui suivent le titre choisi.
                queue = s.queue.drop(i + 1) + s.queue.take(i),
                history = pushHistory(s.history, s.anchor.item),
                anchor = start(s, s.queue[i]),
            ).bump()
        }

        Cmd.Skip -> advance(s, manual = true)

        Cmd.Previous -> {
            val t = now()
            val prev = s.history.lastOrNull()
            if (s.anchor.positionAt(t) > 5_000 || prev == null) {
                s.copy(anchor = s.anchor.copy(positionMs = 0, hostTimeMs = t)).bump()
            } else {
                s.copy(
                    history = s.history.dropLast(1),
                    queue = listOfNotNull(s.anchor.item) + s.queue,
                    anchor = start(s, prev),
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
            else if (s.anchor.item == null) advance(s, manual = true)
            else s.copy(anchor = s.anchor.copy(hostTimeMs = now() + startDelay(s), playing = true)).bump()
        }

        is Cmd.Seek -> {
            if (s.anchor.item == null) s
            else s.copy(anchor = s.anchor.copy(positionMs = cmd.positionMs.coerceAtLeast(0), hostTimeMs = now())).bump()
        }

        is Cmd.SetShuffle -> when {
            s.shuffle == cmd.on -> s
            // L'ordre d'origine (titre en cours compris) est mémorisé avant de mélanger.
            cmd.on -> s.copy(
                shuffle = true,
                queueOrder = listOfNotNull(s.anchor.item?.uid) + s.queue.map { it.uid },
                queue = shuffler(s.queue),
            ).bump()
            else -> s.copy(shuffle = false, queue = originalOrder(s), queueOrder = emptyList()).bump()
        }

        Cmd.Clear -> if (s.queue.isEmpty()) s else s.copy(queue = emptyList()).bump()

        is Cmd.SetRepeat -> if (s.repeat == cmd.mode) s else s.copy(repeat = cmd.mode).bump()

        is Cmd.SetAutoplay -> if (s.autoplay == cmd.on) s else s.copy(autoplay = cmd.on).bump()
    }

    private fun add(s: SessionSnapshot, tracks: List<Track>, playNext: Boolean, by: String): SessionSnapshot {
        val items = tracks.map { QueueItem(newUid(), it, by) }
        return when {
            items.isEmpty() -> s
            // Rien ne joue : le premier titre démarre, les suivants forment la file.
            s.anchor.item == null -> {
                val first = items.first()
                val started = s.copy(anchor = start(s, first), queueOrder = if (s.shuffle) s.queueOrder + first.uid else s.queueOrder)
                insert(started, items.drop(1), playNext).bump()
            }
            else -> insert(s, items, playNext).bump()
        }
    }

    /**
     * Range [items] dans la file : en tête pour « lire ensuite », sinon à la fin, ou à une place au hasard quand
     * l'aléatoire est actif. Dans l'ordre d'origine, « lire ensuite » se place juste après le titre en cours
     * (il reste en tête quand l'aléatoire se coupe) et un ajout se place à la fin.
     */
    private fun insert(s: SessionSnapshot, items: List<QueueItem>, playNext: Boolean): SessionSnapshot {
        if (items.isEmpty()) return s
        if (!s.shuffle) return s.copy(queue = if (playNext) items + s.queue else s.queue + items)
        val uids = items.map { it.uid }
        if (playNext) {
            val at = s.queueOrder.indexOf(s.anchor.item?.uid) + 1
            return s.copy(queue = items + s.queue, queueOrder = s.queueOrder.take(at) + uids + s.queueOrder.drop(at))
        }
        val queue = s.queue.toMutableList()
        for (item in items) queue.add(random(queue.size + 1), item)
        return s.copy(queue = queue, queueOrder = s.queueOrder + uids)
    }

    /**
     * File remise dans l'ordre d'origine, en repartant du titre en cours : d'abord les titres qui le suivaient,
     * puis ceux qui le précédaient ; les titres absents de cet ordre (ajoutés pendant le mélange) passent à la fin.
     */
    private fun originalOrder(s: SessionSnapshot): List<QueueItem> {
        val rank = HashMap<String, Int>()
        s.queueOrder.forEachIndexed { i, uid -> rank.putIfAbsent(uid, i) }
        val n = s.queueOrder.size
        val current = s.anchor.item?.uid?.let { rank[it] } ?: -1
        return s.queue.withIndex().sortedBy { (pos, item) ->
            val r = rank[item.uid]
            when {
                r == null -> 2 * n + pos
                r > current -> r
                else -> r + n
            }
        }.map { it.value }
    }

    /**
     * Passe au titre suivant. [manual] : demandé par quelqu'un (« suivant ») ; sinon le titre est arrivé à sa fin,
     * et « répéter le titre » s'applique.
     */
    fun advance(s: SessionSnapshot, manual: Boolean = false): SessionSnapshot {
        val current = s.anchor.item
        if (!manual && s.repeat == RepeatMode.ONE && current != null) {
            return s.copy(anchor = start(s, current)).bump()
        }
        val next = s.queue.firstOrNull()
        if (next != null) {
            return s.copy(queue = s.queue.drop(1), history = pushHistory(s.history, current), anchor = start(s, next)).bump()
        }
        if (s.repeat == RepeatMode.ALL && current != null) {
            // Fin de file : on repart du début avec tout ce qui a été joué.
            val all = s.history + current
            if (s.shuffle && all.size > 1) {
                // En aléatoire, chaque tour est remélangé, sans rejouer tout de suite le titre qui vient de finir.
                var mixed = shuffler(all)
                if (mixed.first().uid == current.uid) mixed = mixed.drop(1) + mixed.first()
                return s.copy(history = emptyList(), queue = mixed.drop(1), anchor = start(s, mixed.first())).bump()
            }
            return s.copy(history = emptyList(), queue = all.drop(1), anchor = start(s, all.first())).bump()
        }
        return s.copy(history = pushHistory(s.history, current), anchor = PlayAnchor()).bump()
    }

    /** Le lecteur de l'hôte a fini [uid] : on enchaîne (sans effet si on est déjà passé à autre chose). */
    fun ended(s: SessionSnapshot, uid: String): SessionSnapshot =
        if (s.anchor.item?.uid == uid && s.anchor.playing) advance(s) else s

    /**
     * Secours quand le lecteur de l'hôte ne joue pas (« écouter sur ce téléphone » coupé) :
     * vrai si le titre a dépassé sa durée annoncée.
     */
    fun isOverdue(s: SessionSnapshot, marginMs: Long = 250): Boolean {
        val item = s.anchor.item ?: return false
        val d = item.track.durationMs
        return s.anchor.playing && d > 0 && s.anchor.positionAt(now()) >= d + marginMs
    }

    /**
     * Seul, c'est le lecteur qui fait foi (une mise en mémoire tampon retarde le son, pas l'inverse) :
     * l'ancre se recale sur sa position réelle.
     */
    fun reanchor(s: SessionSnapshot, uid: String, positionMs: Long): SessionSnapshot {
        if (s.anchor.item?.uid != uid || s.shared) return s
        return s.copy(anchor = s.anchor.copy(positionMs = positionMs.coerceAtLeast(0), hostTimeMs = now())).bump()
    }

    /** Départ différé quand on est plusieurs : chaque téléphone a le temps de charger le titre. */
    private fun startDelay(s: SessionSnapshot) = if (s.shared) START_DELAY_MS else 0L

    private fun start(s: SessionSnapshot, item: QueueItem) = PlayAnchor(item, 0, now() + startDelay(s), true)

    private fun pushHistory(h: List<QueueItem>, item: QueueItem?): List<QueueItem> =
        if (item == null) h else (h + item).takeLast(MAX_HISTORY)

    /**
     * Nouvelle version de l'état. L'ordre d'origine ne garde que les titres encore présents (historique compris,
     * pour « répéter la file ») : il reste borné.
     */
    private fun SessionSnapshot.bump(): SessionSnapshot {
        val order = when {
            !shuffle -> emptyList()
            queueOrder.isEmpty() -> queueOrder
            else -> {
                val present = HashSet<String>(queue.size + history.size + 1)
                queue.forEach { present += it.uid }
                history.forEach { present += it.uid }
                anchor.item?.let { present += it.uid }
                queueOrder.filter { it in present }
            }
        }
        return copy(version = version + 1, queueOrder = order)
    }

    companion object {
        const val MAX_HISTORY = 50
        const val MAX_BATCH = 200
        const val START_DELAY_MS = 1_500L
    }
}
