package com.rummikub.core.network

import com.rummikub.core.model.NetworkPacket
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
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
            pingIntervalMillis = 0 // Heartbeat handled at application protocol level
        }
        engine {
            config {
                connectTimeout(5, TimeUnit.SECONDS)
                readTimeout(0, TimeUnit.MILLISECONDS) // Persistent streaming
            }
        }
    }

    private var session: DefaultClientWebSocketSession? = null
    private var receiveJob: Job? = null

    private val _incomingPackets = MutableSharedFlow<NetworkPacket>(extraBufferCapacity = 64)
    val incomingPackets: SharedFlow<NetworkPacket> = _incomingPackets.asSharedFlow()

    val isConnected: Boolean
        get() = session?.isActive == true

    /**
     * Establishes a WebSocket connection to the authoritative server.
     */
    suspend fun connect(serverUrl: String) {
        disconnect()
        val webSocketSession = httpClient.webSocketSession(serverUrl)
        session = webSocketSession

        receiveJob = scope.launch {
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
                // Normal cancellation
            } catch (e: Exception) {
                System.err.println("[RummikubWebSocketClient] Erreur flux entrant: ${e.message}")
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
        try {
            session?.close()
        } catch (_: Exception) {}
        session = null
    }

    fun close() {
        httpClient.close()
    }
}
