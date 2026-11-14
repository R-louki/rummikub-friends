package com.rummikub.core.network

import com.rummikub.core.model.NetworkPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages the 3-second heartbeat cycle between Android client and server.
 * Tracks round-trip time (RTT) and detects network stalls.
 */
class HeartbeatManager(
    private val scope: CoroutineScope,
    private val sendPacket: suspend (NetworkPacket.Ping) -> Unit,
    private val onMissedHeartbeatTimeout: suspend () -> Unit,
    private val getStateVersionId: () -> Long,
    private val pingIntervalMs: Long = 3000L,
    private val missedPongTimeoutMs: Long = 6000L
) {
    private var heartbeatJob: Job? = null
    private var lastSentPingTime: Long = 0L
    private var lastReceivedPongTime: Long = System.currentTimeMillis()

    private val _latencyMs = MutableStateFlow<Long>(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    /**
     * Starts the periodic 3-second Ping loop.
     */
    fun start() {
        stop()
        lastReceivedPongTime = System.currentTimeMillis()
        heartbeatJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()

                // Check for missed pong timeout (6 seconds)
                if (lastSentPingTime > 0 && (now - lastReceivedPongTime) > missedPongTimeoutMs) {
                    onMissedHeartbeatTimeout()
                    break
                }

                lastSentPingTime = now
                val ping = NetworkPacket.Ping(
                    clientTimestamp = now,
                    stateVersionId = getStateVersionId()
                )
                try {
                    sendPacket(ping)
                } catch (e: Exception) {
                    onMissedHeartbeatTimeout()
                    break
                }

                delay(pingIntervalMs)
            }
        }
    }

    /**
     * Called when a Pong response packet arrives from the server.
     */
    fun handlePong(pong: NetworkPacket.Pong) {
        val now = System.currentTimeMillis()
        lastReceivedPongTime = now
        val rtt = (now - pong.clientTimestamp).coerceAtLeast(0L)
        _latencyMs.value = rtt
    }

    /**
     * Stops the heartbeat loop.
     */
    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        lastSentPingTime = 0L
    }
}
