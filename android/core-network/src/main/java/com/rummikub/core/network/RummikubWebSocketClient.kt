package com.rummikub.core.network

import com.rummikub.core.model.NetworkPacket
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Low-level WebSocket client managing Ktor WebSocket sessions,
 * serialization of [NetworkPacket] polymorphically, and packet dispatching.
 */
class RummikubWebSocketClient(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    private val httpClient = HttpClient(OkHttp) {
        install(WebSockets) {
            pingIntervalMillis = 0L // Heartbeat handled at application protocol level
        }
        engine {
            config {
                connectTimeout(20, TimeUnit.SECONDS)
                readTimeout(0, TimeUnit.MILLISECONDS) // Persistent streaming
            }
        }
    }

    private var session: DefaultClientWebSocketSession? = null
    private var receiveJob: Job? = null

    private val _incomingPackets = MutableSharedFlow<NetworkPacket>(extraBufferCapacity = 64)
    val incomingPackets: SharedFlow<NetworkPacket> = _incomingPackets.asSharedFlow()

    /** Emitted when the server closes the socket or the stream breaks (not on local disconnect). */
    private val _connectionClosed = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val connectionClosed: SharedFlow<String> = _connectionClosed.asSharedFlow()

    val isConnected: Boolean
        get() = session?.isActive == true

    /**
     * Plain HTTP GET on the server's health endpoint. On a free-tier host this wakes the
     * instance up so the WebSocket handshake that follows is fast. Errors are ignored.
     */
    suspend fun warmUp(httpUrl: String, timeoutMs: Long = 70_000L) {
        try {
            withTimeout(timeoutMs) {
                httpClient.get(httpUrl)
            }
        } catch (_: Exception) {
            // Best effort only
        }
    }

    /**
     * Establishes a WebSocket connection to the authoritative server.
     * The handshake is bounded by [handshakeTimeoutMs] so a sleeping server does not hang the UI forever.
     */
    suspend fun connect(serverUrl: String, handshakeTimeoutMs: Long = 30_000L) {
        disconnect()
        val webSocketSession = try {
            withTimeout(handshakeTimeoutMs) {
                httpClient.webSocketSession(serverUrl)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // withTimeout signals a CancellationException: turn it into a plain failure so callers retry
            throw java.io.IOException("Délai de connexion dépassé")
        }
        session = webSocketSession

        receiveJob = scope.launch {
            var closeReason = "Connexion fermée par le serveur"
            try {
                for (frame in webSocketSession.incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        try {
                            val packet = json.decodeFromString<NetworkPacket>(text)
                            _incomingPackets.emit(packet)
                        } catch (e: Exception) {
                            System.err.println("[RummikubWebSocketClient] Erreur décodage JSON: ${e.message} | Payload: $text")
                        }
                    }
                }
            } catch (e: CancellationException) {
                return@launch // Local disconnect: do not report
            } catch (e: Exception) {
                closeReason = "Flux interrompu: ${e.message}"
                System.err.println("[RummikubWebSocketClient] Erreur flux entrant: ${e.message}")
            }
            if (session === webSocketSession) {
                session = null
                _connectionClosed.tryEmit(closeReason)
            }
        }
    }

    /**
     * Sends a [NetworkPacket] to the server.
     */
    suspend fun send(packet: NetworkPacket) {
        val currentSession = session ?: throw IllegalStateException("WebSocket is not connected")
        val payload = json.encodeToString(NetworkPacket.serializer(), packet)
        currentSession.send(Frame.Text(payload))
    }

    /**
     * Disconnects the active session cleanly.
     */
    suspend fun disconnect() {
        receiveJob?.cancel()
        receiveJob = null
        val current = session
        session = null
        try {
            current?.close()
        } catch (_: Exception) {}
    }

    fun close() {
        httpClient.close()
    }
}
