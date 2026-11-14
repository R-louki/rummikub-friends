package com.rummikub.core.network

import kotlinx.serialization.Serializable

/** One STUN/TURN entry as served by the game server's /ice-servers endpoint. */
@Serializable
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

@Serializable
data class IceServersResponse(val iceServers: List<IceServerConfig> = emptyList())

/**
 * Represents the current status of the WebSocket connection to the game server.
 */
sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()

    /**
     * First connection in progress. [attempt] grows while the (free-tier) server wakes up,
     * [message] is a human readable hint shown by the UI.
     */
    data class Connecting(
        val attempt: Int = 1,
        val message: String = "Connexion au serveur..."
    ) : ConnectionStatus()

    object Connected : ConnectionStatus()

    data class Reconnecting(
        val attempt: Int,
        val nextRetryDelayMs: Long,
        val reason: String? = null
    ) : ConnectionStatus()

    data class Failed(val error: Throwable) : ConnectionStatus()
}
