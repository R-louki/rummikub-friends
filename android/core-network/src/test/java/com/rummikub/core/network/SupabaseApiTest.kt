package com.rummikub.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SupabaseApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(requests: MutableList<HttpRequestData> = mutableListOf(), handler: (HttpRequestData) -> Pair<HttpStatusCode, String>): SupabaseApi {
        val engine = MockEngine { request ->
            requests += request
            val (status, body) = handler(request)
            respond(body, status, jsonHeaders)
        }
        return SupabaseApi("https://p.supabase.co/", "PUBLISHABLE", HttpClient(engine))
    }

    private fun HttpRequestData.bodyText() = (body as TextContent).text

    @Test
    fun `google sign in exchanges the id token and parses the session`() = runBlocking {
        val sent = mutableListOf<HttpRequestData>()
        val api = api(sent) {
            HttpStatusCode.OK to """{"access_token":"AT","refresh_token":"RT","expires_in":3600,"token_type":"bearer","user":{"id":"u-1","email":"x"}}"""
        }
        val session = api.signInWithGoogle("GOOGLE-ID-TOKEN")
        assertEquals("AT", session.accessToken)
        assertEquals("RT", session.refreshToken)
        assertEquals("u-1", session.user?.id)
        val req = sent.single()
        assertEquals("https://p.supabase.co/auth/v1/token?grant_type=id_token", req.url.toString())
        assertEquals("PUBLISHABLE", req.headers["apikey"])
        assertTrue(req.bodyText().contains("\"provider\":\"google\""))
        assertTrue(req.bodyText().contains("GOOGLE-ID-TOKEN"))
    }

    @Test
    fun `requests carry the player's access token and the publishable key`() = runBlocking {
        val sent = mutableListOf<HttpRequestData>()
        val api = api(sent) { HttpStatusCode.OK to """[{"user_id":"u-1","pseudo":"Sami"}]""" }
        api.accessToken = "USER-JWT"
        val profile = api.profile("u-1")
        assertEquals("Sami", profile?.pseudo)
        assertEquals("Bearer USER-JWT", sent.single().headers[HttpHeaders.Authorization])
        assertEquals("PUBLISHABLE", sent.single().headers["apikey"])
    }

    @Test
    fun `pseudo availability reads the rpc answer`() = runBlocking {
        assertTrue(api { HttpStatusCode.OK to "true" }.pseudoAvailable("Sami"))
        assertFalse(api { HttpStatusCode.OK to "false" }.pseudoAvailable("Sami"))
    }

    @Test
    fun `creating a profile maps a duplicate or invalid pseudo to dedicated errors`() = runBlocking {
        api { HttpStatusCode.Created to "" }.createProfile("u-1", " Sami ")

        try {
            api { HttpStatusCode.Conflict to """{"code":"23505","message":"duplicate key"}""" }.createProfile("u-1", "Sami")
            fail("expected PseudoTakenException")
        } catch (e: PseudoTakenException) { /* expected */ }

        try {
            api { HttpStatusCode.BadRequest to """{"code":"23514","message":"check constraint"}""" }.createProfile("u-1", "x")
            fail("expected PseudoInvalidException")
        } catch (e: PseudoInvalidException) { /* expected */ }
    }

    @Test
    fun `friends are classified as accepted, incoming or outgoing with the other player's pseudo`() = runBlocking {
        val api = api { req ->
            when {
                req.url.encodedPath.endsWith("/friendships") -> HttpStatusCode.OK to
                    """[{"id":1,"requester":"me","addressee":"a","status":"accepted"},
                        {"id":2,"requester":"b","addressee":"me","status":"pending"},
                        {"id":3,"requester":"me","addressee":"c","status":"pending"}]"""
                else -> HttpStatusCode.OK to
                    """[{"user_id":"a","pseudo":"Yasmine"},{"user_id":"b","pseudo":"Karim"},{"user_id":"c","pseudo":"Nour"}]"""
            }
        }
        val friends = api.friends("me")
        assertEquals(listOf(FriendStatus.ACCEPTED, FriendStatus.INCOMING_REQUEST, FriendStatus.OUTGOING_REQUEST), friends.map { it.status })
        assertEquals(listOf("Yasmine", "Karim", "Nour"), friends.map { it.pseudo })
        assertEquals(listOf("a", "b", "c"), friends.map { it.userId })
    }

    @Test
    fun `no friends means no second request`() = runBlocking {
        val sent = mutableListOf<HttpRequestData>()
        val friends = api(sent) { HttpStatusCode.OK to "[]" }.friends("me")
        assertTrue(friends.isEmpty())
        assertEquals(1, sent.size)
    }

    @Test
    fun `friend request answers are mapped`() = runBlocking {
        val cases = mapOf(
            "\"sent\"" to FriendRequestResult.SENT,
            "\"accepted\"" to FriendRequestResult.ACCEPTED,
            "\"already_friends\"" to FriendRequestResult.ALREADY_FRIENDS,
            "\"already_sent\"" to FriendRequestResult.ALREADY_SENT,
            "\"self\"" to FriendRequestResult.SELF,
            "\"not_found\"" to FriendRequestResult.NOT_FOUND
        )
        for ((body, expected) in cases) {
            assertEquals(expected, api { HttpStatusCode.OK to body }.sendFriendRequest("x"))
        }
    }

    @Test
    fun `accepting and removing a friend use the right verbs`() = runBlocking {
        val sent = mutableListOf<HttpRequestData>()
        val api = api(sent) { HttpStatusCode.NoContent to "" }
        api.acceptFriend(7)
        api.removeFriend(7)
        assertEquals(HttpMethod.Patch, sent[0].method)
        assertEquals(HttpMethod.Delete, sent[1].method)
        assertTrue(sent[0].url.toString().endsWith("friendships?id=eq.7"))
        assertTrue(sent[0].bodyText().contains("accepted"))
    }

    @Test
    fun `history embeds the players of each game`() = runBlocking {
        val api = api {
            HttpStatusCode.OK to """[{"id":"g1","room_code":"AB12CD","round_number":2,"finished_at":"2026-11-15T10:00:00+00:00",
              "winner_name":"Sami","vs_computer":true,
              "game_players":[{"pseudo":"Sami","user_id":"u1","is_bot":false,"is_winner":true,"remaining_tiles":0,"round_delta":30,"total_score":50},
                              {"pseudo":"Sami (ordi)","user_id":null,"is_bot":true,"is_winner":false,"remaining_tiles":6,"round_delta":-30,"total_score":-50}]}]"""
        }
        val games = api.history()
        assertEquals(1, games.size)
        assertEquals(2, games[0].players.size)
        assertTrue(games[0].vsComputer)
        assertEquals(50, games[0].players[0].totalScore)
        assertNull(games[0].players[1].userId)
    }

    @Test
    fun `avatar is read with the profile and sent with the friends`() = runBlocking {
        val api = api { req ->
            when {
                req.url.encodedPath.endsWith("/friendships") -> HttpStatusCode.OK to
                    """[{"id":1,"requester":"me","addressee":"a","status":"accepted"}]"""
                else -> HttpStatusCode.OK to """[{"user_id":"a","pseudo":"Yasmine","avatar":"emoji:🦊"}]"""
            }
        }
        assertEquals("emoji:🦊", api.friends("me").single().avatar)
        assertEquals("emoji:🦊", api.profile("a")?.avatar)
    }

    @Test
    fun `avatar update and photo upload go to the right endpoints`() = runBlocking {
        val sent = mutableListOf<HttpRequestData>()
        val api = api(sent) { HttpStatusCode.OK to "{}" }
        api.accessToken = "JWT"
        api.updateAvatar("u-1", "emoji:🐼")
        api.updateAvatar("u-1", null)
        val url = api.uploadAvatar("u-1", byteArrayOf(1, 2, 3))

        assertEquals(HttpMethod.Patch, sent[0].method)
        assertTrue(sent[0].bodyText().contains("emoji:"))
        assertTrue(sent[1].bodyText().contains("null"))
        assertEquals(HttpMethod.Post, sent[2].method)
        assertTrue(sent[2].url.toString().endsWith("/storage/v1/object/avatars/u-1/avatar.jpg"))
        assertEquals("true", sent[2].headers["x-upsert"])
        assertEquals("Bearer JWT", sent[2].headers[HttpHeaders.Authorization])
        assertTrue(url.startsWith("https://p.supabase.co/storage/v1/object/public/avatars/u-1/avatar.jpg?v="))
    }

    @Test
    fun `last seen of the friends is read from the presence table`() = runBlocking {
        val sent = mutableListOf<HttpRequestData>()
        val api = api(sent) {
            HttpStatusCode.OK to """[{"user_id":"a","last_seen":"2026-11-15T10:00:00+00:00"},{"user_id":"b","last_seen":"2026-11-14T09:00:00+00:00"}]"""
        }
        val seen = api.lastSeen(listOf("a", "b"))
        assertEquals("2026-11-15T10:00:00+00:00", seen["a"])
        assertEquals(2, seen.size)
        assertTrue(sent.single().url.toString().contains("presence?select=user_id,last_seen&user_id=in.(a,b)"))
        assertTrue(api { HttpStatusCode.OK to "[]" }.lastSeen(emptyList()).isEmpty())
    }

    @Test
    fun `a failed call raises SupabaseException with the status`() = runBlocking {
        try {
            api { HttpStatusCode.Unauthorized to """{"message":"JWT expired"}""" }.history()
            fail("expected SupabaseException")
        } catch (e: SupabaseException) {
            assertEquals(401, e.status)
        }
    }
}
