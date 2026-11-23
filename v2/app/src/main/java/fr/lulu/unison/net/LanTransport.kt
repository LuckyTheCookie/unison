package fr.lulu.unison.net

import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.session.Msg
import fr.lulu.unison.session.decodeMsg
import fr.lulu.unison.session.encode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.java_websocket.WebSocket as ServerSocketConn
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URI
import java.util.UUID

/** Connexion côté hôte (un invité). */
private class ServerConnection(private val socket: ServerSocketConn) : Connection {
    override val id: String = UUID.randomUUID().toString()
    override val incoming = Channel<Msg>(Channel.UNLIMITED)

    override suspend fun send(msg: Msg) {
        runCatching { if (socket.isOpen) socket.send(msg.encode()) }
    }

    override suspend fun close() {
        runCatching { socket.close() }
        incoming.close()
    }
}

/** Hôte Wi-Fi : petit serveur WebSocket embarqué sur le téléphone. */
class LanHost : HostTransport {
    override val connections = Channel<Connection>(Channel.UNLIMITED)
    private var server: WebSocketServer? = null

    override suspend fun start(): Pair<String?, Int?> = withContext(Dispatchers.IO) {
        val ip = localIpv4() ?: throw TransportException("Aucun réseau Wi-Fi ou partage de connexion détecté")
        val port = ServerSocket(0).use { it.localPort }
        val started = CompletableDeferred<Unit>()
        val s = object : WebSocketServer(InetSocketAddress(port)) {
            override fun onOpen(conn: ServerSocketConn, handshake: ClientHandshake) {
                val c = ServerConnection(conn)
                conn.setAttachment(c)
                this@LanHost.connections.trySend(c)
            }

            override fun onClose(conn: ServerSocketConn, code: Int, reason: String?, remote: Boolean) {
                conn.getAttachment<ServerConnection>()?.incoming?.close()
            }

            override fun onMessage(conn: ServerSocketConn, message: String) {
                decodeMsg(message)?.let { conn.getAttachment<ServerConnection>()?.incoming?.trySend(it) }
            }

            override fun onError(conn: ServerSocketConn?, ex: Exception) {
                if (conn == null) started.completeExceptionally(ex)
                else conn.getAttachment<ServerConnection>()?.incoming?.close()
            }

            override fun onStart() {
                started.complete(Unit)
            }
        }
        s.isReuseAddr = true
        s.isTcpNoDelay = true
        s.connectionLostTimeout = 20
        s.start()
        server = s
        try {
            withTimeout(5_000) { started.await() }
        } catch (t: Throwable) {
            runCatching { s.stop(200) }
            throw TransportException("Le serveur de session n'a pas pu démarrer", t)
        }
        ip to port
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) { runCatching { server?.stop(300) } }
        server = null
        connections.close()
    }
}

/**
 * Connexion côté invité, avec le client de Java-WebSocket (déjà utilisé pour le serveur). Une socket brute,
 * hors de la politique réseau d'Android : l'app peut interdire le HTTP en clair partout ailleurs.
 */
private class ClientConnection(uri: URI) : Connection {
    override val id: String = UUID.randomUUID().toString()
    override val incoming = Channel<Msg>(Channel.UNLIMITED)
    val opened = CompletableDeferred<Unit>()

    val socket = object : WebSocketClient(uri, Draft_6455(), emptyMap(), CONNECT_TIMEOUT_MS) {
        override fun onOpen(handshake: ServerHandshake) { opened.complete(Unit) }
        override fun onMessage(message: String) { decodeMsg(message)?.let { incoming.trySend(it) } }
        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            opened.completeExceptionally(TransportException("Connexion fermée"))
            incoming.close()
        }
        override fun onError(ex: Exception) {
            opened.completeExceptionally(ex)
            incoming.close()
        }
    }.apply {
        // Ping toutes les 10 s : un hôte parti sans prévenir est détecté.
        connectionLostTimeout = 10
    }

    override suspend fun send(msg: Msg) {
        runCatching { if (socket.isOpen) socket.send(msg.encode()) }
    }

    override suspend fun close() {
        runCatching { socket.close(1000) }
        incoming.close()
    }
}

private const val CONNECT_TIMEOUT_MS = 8_000

/** Invité Wi-Fi. */
class LanClient : ClientTransport {
    override suspend fun connect(info: JoinInfo, myName: String): Connection = withContext(Dispatchers.IO) {
        val conn = ClientConnection(URI("ws://${info.host}:${info.port}/"))
        try {
            conn.socket.connect()
            withTimeout(10_000) { conn.opened.await() }
        } catch (t: Throwable) {
            conn.close()
            throw TransportException("Impossible de joindre ${info.hostName}. Êtes-vous sur le même Wi-Fi ?", t)
        }
        conn
    }

    override suspend fun shutdown() = Unit
}

/**
 * Adresse IPv4 locale utilisable par les autres téléphones : Wi-Fi ou partage
 * de connexion. Les interfaces mobiles et VPN sont ignorées.
 */
fun localIpv4(): String? {
    val skip = listOf("rmnet", "ccmni", "tun", "ppp", "dummy", "lo", "v4-", "ipsec", "clat", "radio")
    val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
        .filter { it.isUp && !it.isLoopback && skip.none { p -> it.name.startsWith(p) } }
        .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
        .filter { it.second.isSiteLocalAddress }
    val preferred = candidates.sortedBy { (name, _) ->
        when {
            name.startsWith("wlan") -> 0
            name.startsWith("ap") || name.startsWith("swlan") -> 1
            else -> 2
        }
    }
    return preferred.firstOrNull()?.second?.hostAddress
}
