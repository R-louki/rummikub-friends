package com.rummikub.core.network

import com.rummikub.core.model.GameConfig
import com.rummikub.core.model.NetworkPacket
import com.rummikub.core.model.Tile
import com.rummikub.core.model.TileColor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkLogicTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    @Test
    fun `exponential backoff calculates monotonic increasing delays bounded by maxDelay`() {
        val backoff = ExponentialBackoff(
            initialDelayMs = 500L,
            multiplier = 1.5,
            maxDelayMs = 5000L,
            jitterRatio = 0.0 // Zero jitter for deterministic check
        )

        assertEquals(500L, backoff.getDelay(1))
        assertEquals(750L, backoff.getDelay(2))
        assertEquals(1125L, backoff.getDelay(3))

        // Large attempts must be capped at 5000ms
        assertEquals(5000L, backoff.getDelay(15))
    }

    @Test
    fun `network packet serialization and deserialization roundtrip`() {
        val originalPacket: NetworkPacket = NetworkPacket.RoomJoinedResponse(
            roomCode = "RUM123",
            playerId = "P_001",
            sessionToken = "token_xyz",
            isHost = true,
            config = GameConfig(turnDurationSeconds = 45),
            players = emptyList()
        )

        val encoded = json.encodeToString(NetworkPacket.serializer(), originalPacket)
        assertTrue(encoded.contains("\"type\":\"room_joined_res\""))
        assertTrue(encoded.contains("\"roomCode\":\"RUM123\""))

        val decoded = json.decodeFromString<NetworkPacket>(encoded)
        assertTrue(decoded is NetworkPacket.RoomJoinedResponse)
        val response = decoded as NetworkPacket.RoomJoinedResponse
        assertEquals("RUM123", response.roomCode)
        assertEquals(45, response.config.turnDurationSeconds)
    }

    @Test
    fun `heartbeat manager measures rtt correctly upon pong`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        var lastSentPing: NetworkPacket.Ping? = null
        val heartbeat = HeartbeatManager(
            scope = scope,
            sendPacket = { ping -> lastSentPing = ping },
            onMissedHeartbeatTimeout = {},
            getStateVersionId = { 42L }
        )

        // Simulate receiving a pong 85ms later
        val clientTime = System.currentTimeMillis() - 85L
        val pong = NetworkPacket.Pong(
            clientTimestamp = clientTime,
            serverTimestamp = clientTime + 40L,
            serverStateVersionId = 42L
        )

        heartbeat.handlePong(pong)
        assertTrue("Latency should be approximately >= 80ms", heartbeat.latencyMs.value >= 80L)
    }
}
