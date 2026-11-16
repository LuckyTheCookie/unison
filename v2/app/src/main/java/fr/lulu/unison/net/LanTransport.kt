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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.java_websocket.WebSocket as ServerSocketConn
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit

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

/** Connexion côté invité (OkHttp WebSocket). */
private class ClientConnection : Connection, WebSocketListener() {
    override val id: String = UUID.randomUUID().toString()
    override val incoming = Channel<Msg>(Channel.UNLIMITED)
    val opened = CompletableDeferred<Unit>()
    var socket: WebSocket? = null

    override fun onOpen(webSocket: WebSocket, response: Response) {
        opened.complete(Unit)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        decodeMsg(text)?.let { incoming.trySend(it) }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        incoming.close()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        incoming.close()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        opened.completeExceptionally(t)
        incoming.close()
    }

    override suspend fun send(msg: Msg) {
        socket?.send(msg.encode())
    }

    override suspend fun close() {
        socket?.close(1000, null)
        incoming.close()
    }
}

/** Invité Wi-Fi. */
class LanClient : ClientTransport {
    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    override suspend fun connect(info: JoinInfo, myName: String): Connection {
        val conn = ClientConnection()
        conn.socket = http.newWebSocket(Request.Builder().url("ws://${info.host}:${info.port}/").build(), conn)
        try {
            withTimeout(10_000) { conn.opened.await() }
        } catch (t: Throwable) {
            conn.close()
            throw TransportException("Impossible de joindre ${info.hostName}. Êtes-vous sur le même Wi-Fi ?", t)
        }
        return conn
    }

    override suspend fun shutdown() {
        http.dispatcher.executorService.shutdown()
    }
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
