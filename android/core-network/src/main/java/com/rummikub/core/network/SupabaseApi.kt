package com.rummikub.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ==========================================
// DATA
// ==========================================

@Serializable
data class AuthUser(val id: String)

@Serializable
data class AuthSession(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    val user: AuthUser? = null
)

@Serializable
data class Profile(
    @SerialName("user_id") val userId: String,
    val pseudo: String,
    /** "emoji:🦊" (preset) or "url:https://..." (uploaded photo); null = initials. */
    val avatar: String? = null
)

@Serializable
private data class FriendshipRow(
    val id: Long,
    val requester: String,
    val addressee: String,
    val status: String
)

enum class FriendStatus { ACCEPTED, INCOMING_REQUEST, OUTGOING_REQUEST }

/** A friend, or a pending request, as shown in the friends screen. */
data class Friend(
    val friendshipId: Long,
    val userId: String,
    val pseudo: String,
    val status: FriendStatus,
    val avatar: String? = null
)

@Serializable
data class HistoryPlayer(
    val pseudo: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("is_bot") val isBot: Boolean = false,
    @SerialName("is_winner") val isWinner: Boolean = false,
    @SerialName("remaining_tiles") val remainingTiles: Int = 0,
    @SerialName("round_delta") val roundDelta: Int = 0,
    @SerialName("total_score") val totalScore: Int = 0
)

@Serializable
data class HistoryGame(
    val id: String,
    @SerialName("room_code") val roomCode: String,
    @SerialName("round_number") val roundNumber: Int,
    @SerialName("finished_at") val finishedAt: String,
    @SerialName("winner_name") val winnerName: String,
    @SerialName("vs_computer") val vsComputer: Boolean = false,
    @SerialName("game_players") val players: List<HistoryPlayer> = emptyList()
)

/** Result of "add a friend". */
enum class FriendRequestResult { SENT, ACCEPTED, ALREADY_FRIENDS, ALREADY_SENT, NOT_FOUND, SELF }

class SupabaseException(val status: Int, val body: String) : Exception("Supabase HTTP $status: $body")
class PseudoTakenException : Exception("Ce pseudo est déjà pris")
class PseudoInvalidException : Exception("Pseudo invalide")

// ==========================================
// API
// ==========================================

/**
 * Minimal Supabase client: Google ID-token sign-in, profile (unique pseudo), friends and game history.
 * Every table is protected by Row Level Security, so with the publishable key and the player's own
 * access token the phone can only see and change what belongs to that player.
 */
class SupabaseApi(
    baseUrl: String,
    private val publishableKey: String,
    private val client: HttpClient = HttpClient(OkHttp)
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    /** The signed-in player's access token (set by the account manager). */
    @Volatile var accessToken: String? = null

    private fun io.ktor.client.request.HttpRequestBuilder.common() {
        header("apikey", publishableKey)
        header(HttpHeaders.Authorization, "Bearer ${accessToken ?: publishableKey}")
    }

    private suspend fun HttpResponse.ok(): String {
        val text = bodyAsText()
        if (!status.isSuccess()) throw SupabaseException(status.value, text)
        return text
    }

    // ---------- authentication ----------

    suspend fun signInWithGoogle(idToken: String): AuthSession {
        val body = buildJsonObject {
            put("provider", "google")
            put("id_token", idToken)
        }.toString()
        val text = client.post("$base/auth/v1/token?grant_type=id_token") {
            header("apikey", publishableKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.ok()
        return json.decodeFromString(AuthSession.serializer(), text)
    }

    suspend fun refresh(refreshToken: String): AuthSession {
        val body = buildJsonObject { put("refresh_token", refreshToken) }.toString()
        val text = client.post("$base/auth/v1/token?grant_type=refresh_token") {
            header("apikey", publishableKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.ok()
        return json.decodeFromString(AuthSession.serializer(), text)
    }

    // ---------- profile ----------

    suspend fun profile(userId: String): Profile? {
        val text = client.get("$base/rest/v1/profiles?user_id=eq.$userId&select=user_id,pseudo,avatar") { common() }.ok()
        return json.decodeFromString(ListSerializer(Profile.serializer()), text).firstOrNull()
    }

    suspend fun pseudoAvailable(pseudo: String): Boolean {
        val body = buildJsonObject { put("p", pseudo) }.toString()
        val text = client.post("$base/rest/v1/rpc/pseudo_available") {
            common()
            contentType(ContentType.Application.Json)
            setBody(body)
        }.ok()
        return text.trim() == "true"
    }

    /** Creates the profile with a unique pseudo (3-16 letters, digits, space . _ -). */
    suspend fun createProfile(userId: String, pseudo: String, avatar: String? = null) {
        val body = buildJsonObject {
            put("user_id", userId)
            put("pseudo", pseudo.trim())
            if (avatar != null) put("avatar", avatar)
        }.toString()
        val response = client.post("$base/rest/v1/profiles") {
            common()
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (response.status.isSuccess()) return
        val text = response.bodyAsText()
        when {
            response.status.value == 409 || text.contains("23505") -> throw PseudoTakenException()
            response.status.value == 400 || text.contains("23514") -> throw PseudoInvalidException()
            else -> throw SupabaseException(response.status.value, text)
        }
    }

    /** Sets (or clears with null) the avatar of the signed-in player. */
    suspend fun updateAvatar(userId: String, avatar: String?) {
        val body = buildJsonObject {
            if (avatar == null) put("avatar", JsonNull) else put("avatar", avatar)
        }.toString()
        client.patch("$base/rest/v1/profiles?user_id=eq.$userId") {
            common()
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.ok()
    }

    /**
     * Uploads the profile photo (a small square JPEG) into the player's own folder of the public
     * "avatars" bucket and returns its public URL, with a version suffix so caches refresh.
     */
    suspend fun uploadAvatar(userId: String, jpeg: ByteArray): String {
        client.post("$base/storage/v1/object/avatars/$userId/avatar.jpg") {
            common()
            header("x-upsert", "true")
            contentType(ContentType.Image.JPEG)
            setBody(jpeg)
        }.ok()
        return "$base/storage/v1/object/public/avatars/$userId/avatar.jpg?v=${System.currentTimeMillis()}"
    }

    // ---------- friends ----------

    suspend fun friends(myId: String): List<Friend> {
        val text = client.get("$base/rest/v1/friendships?select=id,requester,addressee,status&order=created_at.desc") { common() }.ok()
        val rows = json.decodeFromString(ListSerializer(FriendshipRow.serializer()), text)
        if (rows.isEmpty()) return emptyList()

        val otherIds = rows.map { if (it.requester == myId) it.addressee else it.requester }.distinct()
        val list = otherIds.joinToString(",")
        val profText = client.get("$base/rest/v1/profiles?user_id=in.($list)&select=user_id,pseudo,avatar") { common() }.ok()
        val profiles = json.decodeFromString(ListSerializer(Profile.serializer()), profText).associateBy { it.userId }

        return rows.map { r ->
            val other = if (r.requester == myId) r.addressee else r.requester
            Friend(
                friendshipId = r.id,
                userId = other,
                pseudo = profiles[other]?.pseudo ?: "Joueur",
                status = when {
                    r.status == "accepted" -> FriendStatus.ACCEPTED
                    r.requester == myId -> FriendStatus.OUTGOING_REQUEST
                    else -> FriendStatus.INCOMING_REQUEST
                },
                avatar = profiles[other]?.avatar
            )
        }
    }

    suspend fun sendFriendRequest(pseudo: String): FriendRequestResult {
        val body = buildJsonObject { put("target_pseudo", pseudo) }.toString()
        val text = client.post("$base/rest/v1/rpc/send_friend_request") {
            common()
            contentType(ContentType.Application.Json)
            setBody(body)
        }.ok()
        return when (json.decodeFromString(JsonPrimitive.serializer(), text).content) {
            "sent" -> FriendRequestResult.SENT
            "accepted" -> FriendRequestResult.ACCEPTED
            "already_friends" -> FriendRequestResult.ALREADY_FRIENDS
            "already_sent" -> FriendRequestResult.ALREADY_SENT
            "self" -> FriendRequestResult.SELF
            else -> FriendRequestResult.NOT_FOUND
        }
    }

    suspend fun acceptFriend(friendshipId: Long) {
        client.patch("$base/rest/v1/friendships?id=eq.$friendshipId") {
            common()
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"accepted"}""")
        }.ok()
    }

    suspend fun removeFriend(friendshipId: Long) {
        client.delete("$base/rest/v1/friendships?id=eq.$friendshipId") { common() }.ok()
    }

    // ---------- history ----------

    suspend fun history(limit: Int = 50): List<HistoryGame> {
        val select = "id,room_code,round_number,finished_at,winner_name,vs_computer," +
            "game_players(pseudo,user_id,is_bot,is_winner,remaining_tiles,round_delta,total_score)"
        val text = client.get("$base/rest/v1/game_history?select=$select&order=finished_at.desc&limit=$limit") { common() }.ok()
        return json.decodeFromString(ListSerializer(HistoryGame.serializer()), text)
    }
}
