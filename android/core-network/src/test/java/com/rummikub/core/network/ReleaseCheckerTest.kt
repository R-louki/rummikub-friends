package com.rummikub.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCheckerTest {

    private fun checker(status: HttpStatusCode, body: String) = ReleaseChecker(
        "owner/app",
        HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) })
    )

    @Test
    fun `version comparison is numeric, not alphabetical`() {
        assertTrue(ReleaseChecker.isNewer("1.2.9", "1.2.10"))
        assertTrue(ReleaseChecker.isNewer("1.2.4", "v1.3.0"))
        assertTrue(ReleaseChecker.isNewer("1.9", "2.0.0"))
        assertFalse(ReleaseChecker.isNewer("1.2.4", "1.2.4"))
        assertFalse(ReleaseChecker.isNewer("1.2.4", "1.2.3"))
        assertFalse(ReleaseChecker.isNewer("2.0.0", "1.99.99"))
        assertFalse(ReleaseChecker.isNewer("1.2.4", "garbage"))
    }

    @Test
    fun `latest release with an apk is parsed`() = runBlocking {
        val info = checker(
            HttpStatusCode.OK,
            """{"tag_name":"v1.3.0","body":"  Nouveautés : amis  ","draft":false,"prerelease":false,
                "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":10},
                          {"name":"Rummikub-Friends-1.3.0.apk","browser_download_url":"https://x/app.apk","size":28000000}]}"""
        ).latest()
        assertEquals("1.3.0", info?.versionName)
        assertEquals("https://x/app.apk", info?.apkUrl)
        assertEquals(28000000L, info?.sizeBytes)
        assertEquals("Nouveautés : amis", info?.notes)
    }

    @Test
    fun `no release, no apk, draft or prerelease means no update`() = runBlocking {
        assertNull(checker(HttpStatusCode.NotFound, """{"message":"Not Found"}""").latest())
        assertNull(checker(HttpStatusCode.OK, """{"tag_name":"v2.0.0","assets":[]}""").latest())
        assertNull(checker(HttpStatusCode.OK,
            """{"tag_name":"v2.0.0","prerelease":true,"assets":[{"name":"a.apk","browser_download_url":"u"}]}""").latest())
        assertNull(checker(HttpStatusCode.InternalServerError, "oops").latest())
    }
}
