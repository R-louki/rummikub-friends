package com.rummikub.core.model

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Base sealed class for all WebSocket network packets exchanged
 * between Android clients and the authoritative server.
 */
@Serializable
sealed class NetworkPacket {

    // ==========================================
    // 1. HEARTBEAT & LATENCY
    // ==========================================

    @Serializable
    @SerialName("ping")
    data class Ping(
        val clientTimestamp: Long,
        val stateVersionId: Long
    ) : NetworkPacket()

    @Serializable
    @SerialName("pong")
    data class Pong(
        val clientTimestamp: Long,
        val serverTimestamp: Long,
        val serverStateVersionId: Long
    ) : NetworkPacket()

    // ==========================================
    // 2. ROOM & LOBBY MANAGEMENT
    // ==========================================

    @Serializable
    @SerialName("create_room_req")
    data class CreateRoomRequest(
        val hostName: String,
        val config: GameConfig = GameConfig.STANDARD
    ) : NetworkPacket()

    @Serializable
    @SerialName("join_room_req")
    data class JoinRoomRequest(
        val roomCode: String,
        val playerName: String
    ) : NetworkPacket()

    @Serializable
    @SerialName("room_joined_res")
    data class RoomJoinedResponse(
        val roomCode: String,
        val playerId: String,
        val sessionToken: String,
        val isHost: Boolean,
        val config: GameConfig,
        val players: List<PlayerSummary>
    ) : NetworkPacket()

    @Serializable
    @SerialName("player_joined_evt")
    data class PlayerJoinedEvent(
        val player: PlayerSummary
    ) : NetworkPacket()

    @Serializable
    @SerialName("player_left_evt")
    data class PlayerLeftEvent(
        val playerId: String,
        val newHostId: String? = null
    ) : NetworkPacket()

    @Serializable
    @SerialName("update_config_req")
    data class UpdateGameConfigRequest(
        val roomCode: String,
        val config: GameConfig
    ) : NetworkPacket()

    @Serializable
    @SerialName("config_updated_evt")
    data class GameConfigUpdatedEvent(
        val config: GameConfig
    ) : NetworkPacket()

    @Serializable
    @SerialName("start_game_req")
    data class StartGameRequest(
        val roomCode: String
    ) : NetworkPacket()

    @Serializable
    @SerialName("game_started_evt")
    data class GameStartedEvent(
        val roomCode: String,
        val boardState: BoardState,
        val initialRack: PlayerRack,
        val players: List<PlayerSummary>
    ) : NetworkPacket()

    @Serializable
    @SerialName("game_over_evt")
    data class GameOverEvent(
        val roomCode: String,
        val winnerId: String,
        val winnerName: String,
        val scores: List<EndGamePlayerScore>
    ) : NetworkPacket()

    // ==========================================
    // 3. IN-TURN REAL-TIME EVENTS (THROTTLED)
    // ==========================================

    @Serializable
    @SerialName("tile_dragged_evt")
    data class TileDraggedEvent(
        val playerId: String,
        val tileId: String,
        val posX: Float,
        val posY: Float
    ) : NetworkPacket()

    @Serializable
    @SerialName("set_modified_evt")
    data class SetModifiedEvent(
        val playerId: String,
        val intermediateMelds: List<MeldSet>
    ) : NetworkPacket()

    // ==========================================
    // 4. TURN COMMIT, ROLLBACK & DRAW
    // ==========================================

    @Serializable
    @SerialName("end_turn_req")
    data class EndTurnRequest(
        val proposedMelds: List<MeldSet>,
        val remainingRackTileIds: List<String>
    ) : NetworkPacket()

    @Serializable
    @SerialName("turn_committed_evt")
    data class TurnCommittedEvent(
        val activePlayerId: String,
        val nextPlayerId: String,
        val boardState: BoardState,
        val updatedRack: PlayerRack? = null, // Sent only to active player
        val playerTilesCount: Map<String, Int>
    ) : NetworkPacket()

    @Serializable
    @SerialName("turn_rollback_evt")
    data class TurnRollbackEvent(
        val playerId: String,
        val reason: String,
        val restoredBoardState: BoardState,
        val restoredRack: PlayerRack? = null, // Sent only to active player
        val penaltyTiles: List<Tile> = emptyList(), // Sent only to active player
        val nextPlayerId: String,
        val playerTilesCount: Map<String, Int>
    ) : NetworkPacket()

    @Serializable
    @SerialName("draw_tile_req")
    data class DrawTileRequest(val playerId: String) : NetworkPacket()

    @Serializable
    @SerialName("tile_drawn_evt")
    data class TileDrawnEvent(
        val playerId: String,
        val drawnTile: Tile? = null, // Sent only to drawing player
        val newDrawPileCount: Int,
        val nextPlayerId: String,
        val playerTilesCount: Map<String, Int>
    ) : NetworkPacket()

    @Serializable
    @SerialName("manual_revert_turn_req")
    data class ManualRevertTurnRequest(val reason: String) : NetworkPacket()

    @Serializable
    @SerialName("manual_revert_turn_evt")
    data class ManualRevertTurnEvent(
        val revertedByPlayerId: String,
        val boardState: BoardState
    ) : NetworkPacket()

    // ==========================================
    // 5. RESILIENT RECONNECTION & STATE SYNC
    // ==========================================

    @Serializable
    @SerialName("reconnect_req")
    data class ReconnectRequest(
        val roomCode: String,
        val playerId: String,
        val sessionToken: String,
        val lastKnownVersionId: Long
    ) : NetworkPacket()

    @Serializable
    @SerialName("sync_delta_res")
    data class SyncDeltaResponse(
        val baseVersionId: Long,
        val targetVersionId: Long,
        val meldModifications: List<MeldSet>,
        val removedMeldIds: List<String>,
        val activePlayerId: String,
        val turnRemainingMillis: Long
    ) : NetworkPacket()

    @Serializable
    @SerialName("full_snapshot_res")
    data class FullSnapshotResponse(
        val roomCode: String,
        val boardState: BoardState,
        val playerRack: PlayerRack,
        val players: List<PlayerSummary>,
        val config: GameConfig
    ) : NetworkPacket()

    @Serializable
    @SerialName("player_disconnected_evt")
    data class PlayerDisconnectedEvent(
        val playerId: String,
        val gracePeriodSeconds: Int
    ) : NetworkPacket()

    @Serializable
    @SerialName("player_reconnected_evt")
    data class PlayerReconnectedEvent(
        val playerId: String
    ) : NetworkPacket()

    @Serializable
    @SerialName("grace_period_expired_evt")
    data class GracePeriodExpiredEvent(
        val disconnectedPlayerId: String,
        val nextPlayerId: String
    ) : NetworkPacket()

    // ==========================================
    // 6. WEBRTC LIVE VOICE SIGNALING & VAD
    // ==========================================

    @Serializable
    @SerialName("voice_signal")
    data class VoiceSignalPacket(
        val fromPlayerId: String,
        val toPlayerId: String? = null, // null for broadcast
        val type: SignalType,
        val sdp: String? = null,
        val candidate: String? = null,
        val isSpeaking: Boolean = false,
        val isMuted: Boolean = false
    ) : NetworkPacket() {
        enum class SignalType {
            OFFER, ANSWER, ICE_CANDIDATE, VAD_SPEAKING_STATUS, MUTE_STATUS
        }
    }

    // ==========================================
    // 7. ERROR PACKET
    // ==========================================

    @Serializable
    @SerialName("error")
    data class ErrorResponse(
        val code: String,
        val message: String
    ) : NetworkPacket()
}

/**
 * Lightweight summary of a player in a room.
 */
@Serializable
data class PlayerSummary(
    val id: String,
    val name: String,
    val isHost: Boolean,
    val isConnected: Boolean,
    val tileCount: Int,
    val hasMadeInitialMeld: Boolean,
    val isSpeaking: Boolean = false,
    val isMuted: Boolean = false
)

/**
 * Official score entry calculated at the end of a round.
 */
@Serializable
data class EndGamePlayerScore(
    val playerId: String,
    val playerName: String,
    val isWinner: Boolean,
    val remainingTileCount: Int,
    val scoreDelta: Int,
    val hadJokerPenalty: Boolean = false
)
