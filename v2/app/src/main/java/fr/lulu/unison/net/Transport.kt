package fr.lulu.unison.net

import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.session.Msg
import kotlinx.coroutines.channels.ReceiveChannel

/** Un lien bidirectionnel avec un autre téléphone. [incoming] se ferme à la déconnexion. */
interface Connection {
    val id: String
    val incoming: ReceiveChannel<Msg>
    suspend fun send(msg: Msg)
    suspend fun close()
}

/** Côté hôte : accepte les invités. */
interface HostTransport {
    val connections: ReceiveChannel<Connection>

    /** Démarre l'écoute et renvoie les paramètres à mettre dans le QR code (hôte, port). */
    suspend fun start(): Pair<String?, Int?>
    suspend fun stop()
}

/** Côté invité : rejoint un hôte à partir du QR code. */
interface ClientTransport {
    suspend fun connect(info: JoinInfo, myName: String): Connection
    suspend fun shutdown() {}
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
