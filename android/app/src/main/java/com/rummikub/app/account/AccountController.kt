package com.rummikub.app.account

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.rummikub.app.AppConfig
import com.rummikub.core.network.AuthSession
import com.rummikub.core.network.Friend
import com.rummikub.core.network.FriendRequestResult
import com.rummikub.core.network.GameNetworkRepository
import com.rummikub.core.network.HistoryGame
import com.rummikub.core.network.PseudoInvalidException
import com.rummikub.core.network.PseudoTakenException
import com.rummikub.core.network.SupabaseApi
import com.rummikub.core.network.SupabaseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class AuthState {
    /** Restoring a saved session. */
    object Loading : AuthState()
    object SignedOut : AuthState()
    object SigningIn : AuthState()
    /** Logged in with Google but no pseudo chosen yet. */
    data class NeedsPseudo(val userId: String) : AuthState()
    data class SignedIn(val userId: String, val pseudo: String, val avatar: String? = null) : AuthState()
    /** Accounts are not configured in this build: anonymous play as before. */
    object Guest : AuthState()
}

enum class PseudoCheck { EMPTY, INVALID, TAKEN, AVAILABLE, ERROR }

/**
 * Everything about the player's account: Google sign-in, saved session with automatic token refresh,
 * unique pseudo, friends and game history. Talks to Supabase; the game server then recognises the
 * phone through the same access token.
 */
class AccountController(
    context: Context,
    private val api: SupabaseApi,
    private val repo: GameNetworkRepository,
    private val scope: CoroutineScope
) {
    private val prefs = context.applicationContext.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val tokenLock = Mutex()

    private val _auth = MutableStateFlow<AuthState>(if (AppConfig.accountsEnabled) AuthState.Loading else AuthState.Guest)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _lastSeen = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Friend user id to the ISO date of his last connection. */
    val lastSeen: StateFlow<Map<String, String>> = _lastSeen.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryGame>>(emptyList())
    val history: StateFlow<List<HistoryGame>> = _history.asStateFlow()

    private val _historyLoading = MutableStateFlow(false)
    val historyLoading: StateFlow<Boolean> = _historyLoading.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val myUserId: String?
        get() = when (val a = _auth.value) {
            is AuthState.SignedIn -> a.userId
            is AuthState.NeedsPseudo -> a.userId
            else -> null
        }

    val myPseudo: String? get() = (_auth.value as? AuthState.SignedIn)?.pseudo
    val myAvatar: String? get() = (_auth.value as? AuthState.SignedIn)?.avatar

    init {
        if (AppConfig.accountsEnabled) {
            repo.accessTokenProvider = { freshAccessToken() }
            scope.launch { restoreSession() }
        }
    }

    // ==========================================
    // SESSION
    // ==========================================

    private fun saveSession(session: AuthSession) {
        prefs.edit()
            .putString("access", session.accessToken)
            .putString("refresh", session.refreshToken)
            .putLong("expires_at", System.currentTimeMillis() + session.expiresIn * 1000L)
            .apply()
        api.accessToken = session.accessToken
    }

    private fun clearSession() {
        prefs.edit().clear().apply()
        api.accessToken = null
    }

    private suspend fun restoreSession() {
        val refresh = prefs.getString("refresh", null)
        if (refresh == null) {
            _auth.value = AuthState.SignedOut
            return
        }
        try {
            val session = api.refresh(refresh)
            saveSession(session)
            onSignedIn(session.user?.id ?: throw IllegalStateException("no user"))
        } catch (e: SupabaseException) {
            // The saved session is no longer valid (revoked, expired): sign in again
            clearSession()
            _auth.value = AuthState.SignedOut
        } catch (e: Exception) {
            _auth.value = AuthState.SignedOut
            _messages.tryEmit("Impossible de joindre le service de connexion. Vérifiez votre connexion.")
        }
    }

    /** A valid access token, refreshed when it is about to expire. Used by the game connection. */
    suspend fun freshAccessToken(): String? = tokenLock.withLock {
        val access = prefs.getString("access", null)
        val expiresAt = prefs.getLong("expires_at", 0L)
        if (access != null && expiresAt - System.currentTimeMillis() > 60_000L) return@withLock access
        val refresh = prefs.getString("refresh", null) ?: return@withLock access
        try {
            val session = api.refresh(refresh)
            saveSession(session)
            session.accessToken
        } catch (_: Exception) {
            access
        }
    }

    private suspend fun onSignedIn(userId: String) {
        val profile = try {
            api.profile(userId)
        } catch (e: Exception) {
            _auth.value = AuthState.SignedOut
            _messages.tryEmit("Impossible de charger votre profil. Réessayez.")
            return
        }
        _auth.value = if (profile == null) AuthState.NeedsPseudo(userId) else AuthState.SignedIn(userId, profile.pseudo, profile.avatar)
        // Keep a connection open: friends see us online and invitations arrive
        scope.launch { repo.ensureLobbyConnection() }
        if (profile != null) refreshFriends()
    }

    // ==========================================
    // GOOGLE SIGN-IN
    // ==========================================

    suspend fun signInWithGoogle(activity: Activity) {
        if (!AppConfig.accountsEnabled) return
        _auth.value = AuthState.SigningIn
        try {
            val manager = CredentialManager.create(activity)
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(GetSignInWithGoogleOption.Builder(AppConfig.GOOGLE_WEB_CLIENT_ID).build())
                .build()
            val result = manager.getCredential(activity, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val session = api.signInWithGoogle(idToken)
                saveSession(session)
                onSignedIn(session.user?.id ?: throw IllegalStateException("no user"))
            } else {
                fail("Type d'identifiant Google inattendu.")
            }
        } catch (e: GetCredentialCancellationException) {
            fail("Connexion annulée.")
        } catch (e: NoCredentialException) {
            fail("Aucun compte Google disponible sur ce téléphone. Ajoutez-en un dans les réglages Android.")
        } catch (e: GetCredentialException) {
            fail("Connexion Google impossible : ${e.message ?: e.type}")
        } catch (e: SupabaseException) {
            fail("Le service de connexion a refusé le compte Google (code ${e.status}). La configuration Google dans Supabase est peut-être incomplète.")
        } catch (e: Exception) {
            fail("Connexion impossible : ${e.message}")
        }
    }

    private fun fail(message: String) {
        _auth.value = AuthState.SignedOut
        _messages.tryEmit(message)
    }

    suspend fun signOut() {
        clearSession()
        _friends.value = emptyList()
        _history.value = emptyList()
        repo.accessTokenProvider = if (AppConfig.accountsEnabled) { { freshAccessToken() } } else null
        repo.stopLobbyConnection()
        _auth.value = if (AppConfig.accountsEnabled) AuthState.SignedOut else AuthState.Guest
    }

    // ==========================================
    // PSEUDO
    // ==========================================

    private val pseudoRegex = Regex("^[A-Za-z0-9_.À-ÿ -]{3,16}$")

    fun isPseudoValid(p: String): Boolean = p == p.trim() && pseudoRegex.matches(p)

    suspend fun checkPseudo(raw: String): PseudoCheck {
        val p = raw
        if (p.isBlank()) return PseudoCheck.EMPTY
        if (!isPseudoValid(p)) return PseudoCheck.INVALID
        return try {
            if (api.pseudoAvailable(p)) PseudoCheck.AVAILABLE else PseudoCheck.TAKEN
        } catch (_: Exception) {
            PseudoCheck.ERROR
        }
    }

    /** Creates the profile; the pseudo must be unique among all players. */
    suspend fun choosePseudo(raw: String): Boolean {
        val userId = myUserId ?: return false
        val pseudo = raw.trim()
        // A random preset avatar to start with; it can be changed (or replaced by a photo) in the lobby
        val avatar = "emoji:" + AvatarPresets.emojis.random()
        return try {
            api.createProfile(userId, pseudo, avatar)
            _auth.value = AuthState.SignedIn(userId, pseudo, avatar)
            // Tell the game server the pseudo now exists
            scope.launch { repo.authenticate() }
            refreshFriends()
            true
        } catch (e: PseudoTakenException) {
            _messages.tryEmit("Ce pseudo est déjà pris, choisissez-en un autre.")
            false
        } catch (e: PseudoInvalidException) {
            _messages.tryEmit("Pseudo invalide : 3 à 16 caractères (lettres, chiffres, espace . _ -).")
            false
        } catch (e: Exception) {
            _messages.tryEmit("Impossible d'enregistrer le pseudo. Réessayez.")
            false
        }
    }

    // ==========================================
    // AVATAR
    // ==========================================

    private suspend fun applyAvatar(avatar: String?) {
        val current = _auth.value as? AuthState.SignedIn ?: return
        try {
            api.updateAvatar(current.userId, avatar)
            _auth.value = current.copy(avatar = avatar)
            // Tell the game server: tables created from now on show the new avatar
            scope.launch { repo.authenticate() }
        } catch (_: Exception) {
            _messages.tryEmit("Impossible de changer l'avatar. Réessayez.")
        }
    }

    suspend fun setEmojiAvatar(emoji: String) = applyAvatar("emoji:$emoji")

    suspend fun removeAvatar() = applyAvatar(null)

    /** Uploads an already cropped JPEG as the profile photo. */
    suspend fun setPhotoAvatarBytes(jpeg: ByteArray) {
        val current = _auth.value as? AuthState.SignedIn ?: return
        try {
            val url = api.uploadAvatar(current.userId, jpeg)
            applyAvatar("url:$url")
        } catch (_: Exception) {
            _messages.tryEmit("Envoi de la photo impossible. Réessayez.")
        }
    }

    /** Uploads a photo picked in the gallery (cropped to a small square) and uses it as avatar. */
    suspend fun setPhotoAvatar(context: Context, uri: android.net.Uri) {
        val current = _auth.value as? AuthState.SignedIn ?: return
        val jpeg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ImageUtils.squareJpeg(context, uri) }
        if (jpeg == null) {
            _messages.tryEmit("Cette image ne peut pas être lue.")
            return
        }
        try {
            val url = api.uploadAvatar(current.userId, jpeg)
            applyAvatar("url:$url")
        } catch (_: Exception) {
            _messages.tryEmit("Envoi de la photo impossible. Réessayez.")
        }
    }

    // ==========================================
    // FRIENDS
    // ==========================================

    suspend fun refreshFriends() {
        val me = myUserId ?: return
        try {
            val list = api.friends(me)
            _friends.value = list
            _lastSeen.value = api.lastSeen(list.filter { it.status == com.rummikub.core.network.FriendStatus.ACCEPTED }.map { it.userId })
        } catch (_: Exception) {
            // keep the previous list
        }
        repo.requestFriendsPresence()
    }

    suspend fun addFriend(pseudo: String) {
        val target = pseudo.trim()
        if (target.isEmpty()) return
        try {
            val message = when (api.sendFriendRequest(target)) {
                FriendRequestResult.SENT -> "Demande envoyée à $target."
                FriendRequestResult.ACCEPTED -> "Vous êtes maintenant amis avec $target."
                FriendRequestResult.ALREADY_FRIENDS -> "$target est déjà votre ami."
                FriendRequestResult.ALREADY_SENT -> "Une demande est déjà en attente pour $target."
                FriendRequestResult.SELF -> "C'est votre propre pseudo."
                FriendRequestResult.NOT_FOUND -> "Aucun joueur nommé « $target »."
            }
            _messages.tryEmit(message)
            refreshFriends()
        } catch (_: Exception) {
            _messages.tryEmit("Impossible d'envoyer la demande. Réessayez.")
        }
    }

    suspend fun acceptFriend(friend: Friend) {
        try {
            api.acceptFriend(friend.friendshipId)
            _messages.tryEmit("Vous êtes maintenant amis avec ${friend.pseudo}.")
        } catch (_: Exception) {
            _messages.tryEmit("Impossible d'accepter la demande.")
        }
        refreshFriends()
    }

    suspend fun removeFriend(friend: Friend) {
        try {
            api.removeFriend(friend.friendshipId)
        } catch (_: Exception) {
            _messages.tryEmit("Action impossible. Réessayez.")
        }
        refreshFriends()
    }

    // ==========================================
    // HISTORY
    // ==========================================

    suspend fun loadHistory() {
        _historyLoading.value = true
        try {
            _history.value = api.history()
        } catch (_: Exception) {
            _messages.tryEmit("Impossible de charger l'historique.")
        } finally {
            _historyLoading.value = false
        }
    }
}
