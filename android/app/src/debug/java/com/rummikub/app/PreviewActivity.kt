package com.rummikub.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rummikub.app.ui.account.FriendsDialog
import com.rummikub.app.ui.components.NetworkIndicator
import com.rummikub.app.ui.lobby.LobbyScreen
import com.rummikub.app.ui.lobby.WaitingRoomScreen
import com.rummikub.core.model.GameConfig
import com.rummikub.core.model.PlayerSummary
import com.rummikub.core.model.Tile
import com.rummikub.core.model.TileColor
import androidx.compose.foundation.layout.height
import com.rummikub.core.network.ConnectionStatus
import com.rummikub.core.network.Friend
import com.rummikub.core.network.FriendStatus
import java.time.Instant
import com.rummikub.app.ui.theme.RummikubTheme
import com.rummikub.app.ui.victory.FinalStanding
import com.rummikub.app.ui.victory.PlayerEndScore
import com.rummikub.app.ui.victory.VictoryScreen

/**
 * DEBUG BUILDS ONLY. Shows one screen with fake data so a design can be checked on the emulator
 * without playing a game:
 *
 *   adb shell am start -n com.rummikub.app/.PreviewActivity --es screen victory
 */
class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val screen = intent.getStringExtra("screen") ?: "victory"
        setContent { RummikubTheme { Preview(screen) } }
    }
}

private val sampleScores = listOf(
    PlayerEndScore("p1", "Sami", true, 0, 40, 40, avatar = "emoji:🦊"),
    PlayerEndScore("p2", "Yasmine", false, 5, -22, -22, avatar = "emoji:🐼"),
    PlayerEndScore("p3", "Karim (ordi)", false, 3, -18, -18, hadJokerPenalty = false, avatar = "emoji:🤖")
)

private val sampleFriends = listOf(
    Friend(1, "u-karim", "Karim", FriendStatus.ACCEPTED, "emoji:🦁"),
    Friend(2, "u-nour", "Nour", FriendStatus.ACCEPTED, "emoji:🦋"),
    Friend(3, "u-yas", "Yasmine", FriendStatus.ACCEPTED, "emoji:🐼"),
    Friend(4, "u-lea", "Léa", FriendStatus.ACCEPTED, null),
    Friend(5, "u-new", "Anis", FriendStatus.INCOMING_REQUEST, "emoji:🐯")
)
private fun ago(seconds: Long) = Instant.now().minusSeconds(seconds).toString()
private val sampleSeen = mapOf("u-nour" to ago(7 * 60), "u-yas" to ago(3 * 3600), "u-lea" to ago(2 * 86400))
private val samplePlayers = listOf(
    PlayerSummary(id = "p1", name = "Sami", isHost = true, isConnected = true, avatar = "emoji:🦊", tileCount = 0, hasMadeInitialMeld = false),
    PlayerSummary(id = "p2", name = "Yasmine", isHost = false, isConnected = true, avatar = "emoji:🐼", tileCount = 0, hasMadeInitialMeld = false)
)

@Composable
private fun Preview(screen: String) {
    when (screen) {
        "waiting", "waiting_guest" -> WaitingRoomScreen(
            roomCode = "BW9C52", players = samplePlayers, isHost = true, gameConfig = GameConfig.STANDARD,
            onStartGame = {}, onLeaveRoom = {}, connectionStatus = ConnectionStatus.Connected, latencyMs = 74,
            inviteFriends = if (screen == "waiting") sampleFriends else emptyList(),
            onlineFriendIds = setOf("u-karim"), lastSeen = sampleSeen, invitedIds = setOf(),
            signedIn = screen == "waiting", onInviteFriend = {}
        )
        "rack", "rack_folded" -> {
            val tiles = (1..14).map { Tile(id = "t$it", color = TileColor.values()[it % 4], number = (it % 13) + 1) }
            androidx.compose.foundation.layout.Box(Modifier.background(Color(0xFF0C2B18)).padding(top = 120.dp)) {
                com.rummikub.app.ui.rack.PlayerRackView(
                    rack = com.rummikub.core.model.PlayerRack(playerId = "p1", tiles = tiles), layoutEpoch = 0, collapsed = screen == "rack_folded", onToggleCollapsed = {}, isMyTurn = true,
                    selectedTileIds = emptySet(), onTileTapped = {}, onTileDroppedOnRack = {}, onRackTapped = {},
                    modifier = Modifier.height(92.dp)
                )
            }
        }
        "crop" -> {
            val bmp = android.graphics.Bitmap.createBitmap(800, 600, android.graphics.Bitmap.Config.ARGB_8888).also {
                val c = android.graphics.Canvas(it)
                c.drawColor(android.graphics.Color.rgb(40, 120, 200))
                val p = android.graphics.Paint().apply { color = android.graphics.Color.YELLOW; textSize = 120f }
                c.drawCircle(400f, 300f, 150f, p); c.drawText("PHOTO", 220f, 560f, p)
            }
            com.rummikub.app.ui.account.AvatarCropDialog(bitmap = bmp, onConfirm = { _, _, _ -> }, onCancel = {})
        }
        "lobby" -> LobbyScreen(
            connectionStatus = ConnectionStatus.Connected, onCreateRoom = { _, _ -> }, onJoinRoom = { _, _ -> },
            onCancelConnecting = {}, latencyMs = 74, accountPseudo = "Sami", accountAvatar = "emoji:🦊", pendingFriendRequests = 1
        )
        "friends" -> FriendsDialog(
            friends = sampleFriends, onlineIds = setOf("u-karim"), lastSeen = sampleSeen,
            onAdd = {}, onAccept = {}, onRemove = {}, onRefresh = {}, onDismiss = {}
        )
        "network" -> Column(Modifier.background(Color(0xFF0C2B18)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NetworkIndicator(ConnectionStatus.Connected, 45)
            NetworkIndicator(ConnectionStatus.Connected, 130)
            NetworkIndicator(ConnectionStatus.Connected, 260)
            NetworkIndicator(ConnectionStatus.Connected, 700)
            NetworkIndicator(ConnectionStatus.Reconnecting(2, 1000), 0)
            NetworkIndicator(ConnectionStatus.Disconnected, 0)
        }
        "victory" -> VictoryScreen(
            scores = sampleScores, winnerName = "Sami", isMeWinner = true, roundNumber = 2, isHost = true,
            onNextRound = {}, onEndGame = {}, onLeave = {}
        )
        "victory_guest" -> VictoryScreen(
            scores = sampleScores, winnerName = "Sami", isMeWinner = false, roundNumber = 2, isHost = false,
            onNextRound = {}, onEndGame = {}, onLeave = {}
        )
        "victory_final" -> VictoryScreen(
            scores = emptyList(), winnerName = "Sami", isMeWinner = true, roundNumber = 3, isHost = true,
            finalStandings = listOf(
                FinalStanding("Sami", 78, 2, "emoji:🦊"),
                FinalStanding("Yasmine", -12, 1, "emoji:🐼"),
                FinalStanding("Karim (ordi)", -66, 0, "emoji:🤖")
            ),
            roundsPlayed = 3, onNextRound = {}, onEndGame = {}, onLeave = {}
        )
    }
}
