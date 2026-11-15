package com.rummikub.app.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rummikub.app.account.PseudoCheck
import com.rummikub.app.ui.theme.FeltDarkGreen
import com.rummikub.app.ui.theme.WoodBorder
import com.rummikub.app.ui.theme.WoodDarkBrown
import com.rummikub.core.network.Friend
import com.rummikub.core.network.FriendStatus
import com.rummikub.core.network.HistoryGame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Gold = Color(0xFFFFD54F)
private val Green = Color(0xFF81C784)

// ==========================================
// LOGIN
// ==========================================

@Composable
fun LoginScreen(
    isSigningIn: Boolean,
    onSignInWithGoogle: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF134226), FeltDarkGreen, Color(0xFF06140B)))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text("RUMMIKUB", fontSize = 38.sp, fontWeight = FontWeight.Black, color = Gold, letterSpacing = 4.sp)
            Text("FRIENDS & LIVE VOICE", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), letterSpacing = 3.sp)
            Spacer(Modifier.height(4.dp))

            Card(
                modifier = Modifier
                    .width(420.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(2.dp, WoodBorder, RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isSigningIn) {
                        CircularProgressIndicator(color = Gold, strokeWidth = 3.dp)
                        Text("Connexion en cours...", fontSize = 13.sp, color = Color.White)
                    } else {
                        Button(
                            onClick = onSignInWithGoogle,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                        ) {
                            Text("G", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color(0xFF4285F4))
                            Spacer(Modifier.width(10.dp))
                            Text("Se connecter avec Google", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3C4043))
                        }
                        Text(
                            text = "Votre compte Google sert uniquement à vous identifier. Vous choisirez ensuite un pseudo visible par vos amis.",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingScreen(message: String) {
    Box(modifier = Modifier.fillMaxSize().background(FeltDarkGreen), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = Gold, strokeWidth = 3.dp)
            Text(message, fontSize = 13.sp, color = Color.White)
        }
    }
}

// ==========================================
// PSEUDO
// ==========================================

@Composable
fun PseudoScreen(
    check: suspend (String) -> PseudoCheck,
    confirm: suspend (String) -> Boolean,
    onSignOut: () -> Unit
) {
    var pseudo by remember { mutableStateOf("") }
    var result by remember { mutableStateOf(PseudoCheck.EMPTY) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Check availability shortly after the player stops typing
    LaunchedEffect(pseudo) {
        result = PseudoCheck.EMPTY
        if (pseudo.isNotBlank()) {
            delay(450)
            result = check(pseudo)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF134226), FeltDarkGreen, Color(0xFF06140B)))),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(2.dp, WoodBorder, RoundedCornerShape(18.dp)),
            colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choisissez votre pseudo", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Gold)
                Text(
                    "C'est le nom que verront vos amis et vos adversaires. Il doit être unique : personne d'autre ne pourra le prendre.",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.75f)
                )
                OutlinedTextField(
                    value = pseudo,
                    onValueChange = { if (it.length <= 16) pseudo = it },
                    label = { Text("Pseudo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Gold,
                        unfocusedBorderColor = Color(0xFF8D6E63)
                    )
                )
                val (text, color) = when (result) {
                    PseudoCheck.AVAILABLE -> "✔ Disponible" to Green
                    PseudoCheck.TAKEN -> "✖ Ce pseudo est déjà pris" to Color(0xFFFF8A80)
                    PseudoCheck.INVALID -> "3 à 16 caractères : lettres, chiffres, espace . _ -" to Color(0xFFFFB74D)
                    PseudoCheck.ERROR -> "Vérification impossible, réessayez" to Color(0xFFFFB74D)
                    PseudoCheck.EMPTY -> (if (pseudo.isBlank()) "3 à 16 caractères" else "Vérification...") to Color.White.copy(alpha = 0.6f)
                }
                Text(text, fontSize = 12.sp, color = color)

                Button(
                    onClick = {
                        saving = true
                        scope.launch {
                            confirm(pseudo)
                            saving = false
                        }
                    },
                    enabled = result == PseudoCheck.AVAILABLE && !saving,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text(if (saving) "Enregistrement..." else "Valider ce pseudo", fontWeight = FontWeight.Bold, color = Color.White)
                }
                TextButton(onClick = onSignOut, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Changer de compte Google", fontSize = 11.sp, color = Color(0xFFFFCC80))
                }
            }
        }
    }
}

// ==========================================
// FRIENDS
// ==========================================

@Composable
private fun OnlineDot(online: Boolean) {
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(if (online) Color(0xFF00E676) else Color(0xFF616161))
    )
}

@Composable
fun FriendsDialog(
    friends: List<Friend>,
    onlineIds: Set<String>,
    onAdd: suspend (String) -> Unit,
    onAccept: suspend (Friend) -> Unit,
    onRemove: suspend (Friend) -> Unit,
    onRefresh: suspend () -> Unit,
    onDismiss: () -> Unit
) {
    var newFriend by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Keep the list and the "online" dots fresh while the dialog is open
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(8_000)
        }
    }

    val incoming = friends.filter { it.status == FriendStatus.INCOMING_REQUEST }
    val accepted = friends.filter { it.status == FriendStatus.ACCEPTED }.sortedByDescending { it.userId in onlineIds }
    val outgoing = friends.filter { it.status == FriendStatus.OUTGOING_REQUEST }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.width(480.dp).heightIn(max = 340.dp).clip(RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("👥 Mes amis", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Gold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) { Text("✕", color = Color.White, fontSize = 14.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newFriend,
                        onValueChange = { newFriend = it },
                        label = { Text("Pseudo de votre ami") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Gold, unfocusedBorderColor = Color(0xFF8D6E63)
                        )
                    )
                    Button(
                        onClick = {
                            val p = newFriend
                            newFriend = ""
                            scope.launch { onAdd(p) }
                        },
                        enabled = newFriend.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) { Text("Ajouter", fontSize = 12.sp, color = Color.White) }
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (incoming.isNotEmpty()) {
                        item { Text("Demandes reçues", fontSize = 11.sp, color = Gold, fontWeight = FontWeight.Bold) }
                        items(incoming, key = { it.friendshipId }) { f ->
                            FriendRow(f.pseudo, online = null, avatar = f.avatar) {
                                Button(
                                    onClick = { scope.launch { onAccept(f) } },
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                ) { Text("Accepter", fontSize = 10.sp, color = Color.White) }
                                TextButton(onClick = { scope.launch { onRemove(f) } }) { Text("Refuser", fontSize = 10.sp, color = Color(0xFFFF8A80)) }
                            }
                        }
                    }
                    item { Text("Amis (${accepted.size})", fontSize = 11.sp, color = Gold, fontWeight = FontWeight.Bold) }
                    if (accepted.isEmpty()) {
                        item { Text("Pas encore d'ami : ajoutez un pseudo ci-dessus.", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f)) }
                    }
                    items(accepted, key = { it.friendshipId }) { f ->
                        FriendRow(f.pseudo, online = f.userId in onlineIds, avatar = f.avatar) {
                            TextButton(onClick = { scope.launch { onRemove(f) } }) { Text("Retirer", fontSize = 10.sp, color = Color(0xFFFF8A80)) }
                        }
                    }
                    if (outgoing.isNotEmpty()) {
                        item { Text("Demandes envoyées", fontSize = 11.sp, color = Gold, fontWeight = FontWeight.Bold) }
                        items(outgoing, key = { it.friendshipId }) { f ->
                            FriendRow(f.pseudo, online = null, avatar = f.avatar) {
                                Text("en attente", fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
                                TextButton(onClick = { scope.launch { onRemove(f) } }) { Text("Annuler", fontSize = 10.sp, color = Color(0xFFFF8A80)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendRow(name: String, online: Boolean?, avatar: String?, actions: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x22FFFFFF))
            .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarCircle(avatar, name, 30.dp)
        if (online != null) OnlineDot(online)
        Text(name, modifier = Modifier.weight(1f), fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** Friends you can invite from the waiting room: only accepted friends, and only those online. */
@Composable
fun FriendInvitePanel(
    friends: List<Friend>,
    onlineIds: Set<String>,
    onInvite: (Friend) -> Unit
) {
    val accepted = friends.filter { it.status == FriendStatus.ACCEPTED }
    if (accepted.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Inviter un ami :", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        LazyColumn(modifier = Modifier.heightIn(max = 90.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(accepted.sortedByDescending { it.userId in onlineIds }, key = { it.friendshipId }) { f ->
                val online = f.userId in onlineIds
                FriendRow(f.pseudo, online = online, avatar = f.avatar) {
                    Button(
                        onClick = { onInvite(f) },
                        enabled = online,
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))
                    ) { Text(if (online) "Inviter" else "hors ligne", fontSize = 10.sp, color = Color.White) }
                }
            }
        }
    }
}

// ==========================================
// APP UPDATE
// ==========================================

@Composable
fun UpdateDialog(
    versionName: String,
    notes: String,
    sizeMb: Int,
    progress: Float?,
    message: String?,
    onUpdate: () -> Unit,
    onLater: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (progress == null) onLater() },
        title = { Text("Mise à jour disponible") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Version $versionName" + if (sizeMb > 0) " ($sizeMb Mo)" else "")
                if (notes.isNotBlank()) Text(notes.take(300), fontSize = 12.sp)
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("Téléchargement ${(progress * 100).toInt()} %", fontSize = 12.sp)
                }
                if (message != null) Text(message, fontSize = 12.sp, color = Color(0xFFE65100))
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate, enabled = progress == null) {
                Text(if (progress != null) "Téléchargement..." else "Mettre à jour")
            }
        },
        dismissButton = {
            if (progress == null) TextButton(onClick = onLater) { Text("Plus tard") }
        }
    )
}

// ==========================================
// INVITATION RECEIVED
// ==========================================

@Composable
fun InviteDialog(fromPseudo: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Invitation à jouer") },
        text = { Text("$fromPseudo vous invite à rejoindre sa table de Rummikub.") },
        confirmButton = { TextButton(onClick = onAccept) { Text("Rejoindre") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Plus tard") } }
    )
}

// ==========================================
// HISTORY
// ==========================================

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")

private fun formatDate(iso: String): String = try {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(dateFormat)
} catch (_: Exception) {
    iso.take(16).replace('T', ' ')
}

@Composable
fun HistoryDialog(
    history: List<HistoryGame>,
    loading: Boolean,
    myUserId: String?,
    onDismiss: () -> Unit
) {
    val myRows = history.mapNotNull { g -> g.players.firstOrNull { it.userId == myUserId } }
    val wins = myRows.count { it.isWinner }
    val points = myRows.sumOf { it.roundDelta }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.width(500.dp).heightIn(max = 340.dp).clip(RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("📜 Historique des parties", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Gold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) { Text("✕", color = Color.White, fontSize = 14.sp) }
                }
                if (history.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x33FFD54F)).padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text("${history.size} manches", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("🏆 $wins victoires", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("${if (points > 0) "+" else ""}$points pts", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = if (points >= 0) Color(0xFF69F0AE) else Color(0xFFFF8A80))
                    }
                }
                when {
                    loading && history.isEmpty() -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Gold, strokeWidth = 3.dp)
                    }
                    history.isEmpty() -> Text("Aucune partie terminée pour le moment.", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(history, key = { it.id }) { game ->
                            Column(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF)).padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    "${formatDate(game.finishedAt)} · ${if (game.vsComputer) "contre l'ordinateur" else "entre amis"} · manche ${game.roundNumber}",
                                    fontSize = 10.sp, color = Color.White.copy(alpha = 0.65f)
                                )
                                game.players.sortedByDescending { it.totalScore }.forEach { p ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            (if (p.isWinner) "🏆 " else "") + p.pseudo + if (p.userId == myUserId && myUserId != null) " (moi)" else "",
                                            fontSize = 12.sp,
                                            fontWeight = if (p.userId == myUserId) FontWeight.Bold else FontWeight.Normal,
                                            color = if (p.isWinner) Gold else Color.White
                                        )
                                        Text(
                                            "${if (p.roundDelta > 0) "+" else ""}${p.roundDelta}  (cumul ${p.totalScore})",
                                            fontSize = 11.sp,
                                            color = if (p.roundDelta >= 0) Color(0xFF69F0AE) else Color(0xFFFF8A80)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
