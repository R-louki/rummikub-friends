package com.rummikub.core.network

/**
 * Represents the current status of the WebSocket connection to the game server.
 */
sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting : ConnectionStatus()
    object Connected : ConnectionStatus()

    data class Reconnecting(
        val attempt: Int,
        val nextRetryDelayMs: Long,
        val reason: String? = null
    ) : ConnectionStatus()

    data class Failed(val error: Throwable) : ConnectionStatus()
}
