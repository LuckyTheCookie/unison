package fr.lulu.unison.net

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.session.Msg
import fr.lulu.unison.session.decodeMsg
import fr.lulu.unison.session.encode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private const val SERVICE_ID = "fr.lulu.unison"
private val STRATEGY = Strategy.P2P_STAR

private fun pack(msg: Msg): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(msg.encode().toByteArray()) } }.toByteArray()

private fun unpack(bytes: ByteArray): Msg? =
    runCatching { GZIPInputStream(ByteArrayInputStream(bytes)).use { decodeMsg(it.readBytes().decodeToString()) } }.getOrNull()

private class NearbyConnection(
    private val client: ConnectionsClient,
    private val endpointId: String,
) : Connection {
    override val id: String = endpointId
    override val incoming = Channel<Msg>(Channel.UNLIMITED)

    override suspend fun send(msg: Msg) {
        runCatching { client.sendPayload(endpointId, Payload.fromBytes(pack(msg))).await() }
    }

    override suspend fun close() {
        client.disconnectFromEndpoint(endpointId)
        incoming.close()
    }
}

/** Réception des paquets, routés vers la bonne connexion. */
private class Router : PayloadCallback() {
    val links = ConcurrentHashMap<String, NearbyConnection>()

    override fun onPayloadReceived(endpointId: String, payload: Payload) {
        val bytes = payload.asBytes() ?: return
        val msg = unpack(bytes) ?: return
        links[endpointId]?.incoming?.trySend(msg)
    }

    override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
}

/** Hôte sans Wi-Fi : Bluetooth + Wi-Fi Direct via Nearby Connections. */
class NearbyHost(context: Context, private val endpointName: String) : HostTransport {
    private val client = Nearby.getConnectionsClient(context)
    private val router = Router()
    override val connections = Channel<Connection>(Channel.UNLIMITED)

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // L'authentification réelle se fait avec le secret du QR code (message Hello).
            router.links[endpointId] = NearbyConnection(client, endpointId)
            client.acceptConnection(endpointId, router)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val link = router.links[endpointId] ?: return
            if (result.status.isSuccess) connections.trySend(link)
            else router.links.remove(endpointId)?.incoming?.close()
        }

        override fun onDisconnected(endpointId: String) {
            router.links.remove(endpointId)?.incoming?.close()
        }
    }

    override suspend fun start(): Pair<String?, Int?> {
        try {
            client.startAdvertising(
                endpointName, SERVICE_ID, lifecycle,
                AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
            ).await()
        } catch (t: Throwable) {
            throw TransportException("Le mode sans Wi-Fi n'a pas pu démarrer (Bluetooth activé ?)", t)
        }
        return null to null
    }

    override suspend fun stop() {
        client.stopAdvertising()
        client.stopAllEndpoints()
        router.links.values.forEach { it.incoming.close() }
        router.links.clear()
        connections.close()
    }
}

/** Invité sans Wi-Fi. */
class NearbyClient(context: Context) : ClientTransport {
    private val client = Nearby.getConnectionsClient(context)
    private val router = Router()

    override suspend fun connect(info: JoinInfo, myName: String): Connection {
        val result = CompletableDeferred<Connection>()

        val lifecycle = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, ci: ConnectionInfo) {
                router.links[endpointId] = NearbyConnection(client, endpointId)
                client.acceptConnection(endpointId, router)
            }

            override fun onConnectionResult(endpointId: String, res: ConnectionResolution) {
                val link = router.links[endpointId]
                if (res.status.isSuccess && link != null) result.complete(link)
                else result.completeExceptionally(TransportException("Connexion refusée par ${info.hostName}"))
            }

            override fun onDisconnected(endpointId: String) {
                router.links.remove(endpointId)?.incoming?.close()
            }
        }

        val discovery = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, ei: DiscoveredEndpointInfo) {
                if (!ei.endpointName.endsWith("|${info.sessionId}")) return
                client.stopDiscovery()
                client.requestConnection(myName, endpointId, lifecycle)
                    .addOnFailureListener { result.completeExceptionally(TransportException("Connexion impossible", it)) }
            }

            override fun onEndpointLost(endpointId: String) = Unit
        }

        try {
            client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build()).await()
            return withTimeout(45_000) { result.await() }
        } catch (t: TransportException) {
            client.stopDiscovery(); throw t
        } catch (t: Throwable) {
            client.stopDiscovery()
            throw TransportException("${info.hostName} est introuvable à proximité", t)
        }
    }

    override suspend fun shutdown() {
        client.stopDiscovery()
        client.stopAllEndpoints()
    }
}

/** Nom annoncé par l'hôte : « Prénom|sessionId ». */
fun nearbyEndpointName(hostName: String, sessionId: String) = "${hostName.take(20)}|$sessionId"
