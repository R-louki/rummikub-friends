package com.rummikub.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusSdpTunerTest {
    private val sdp = listOf(
        "v=0",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111 63",
        "a=rtpmap:111 opus/48000/2",
        "a=fmtp:111 minptime=10;useinbandfec=1",
        "a=rtpmap:63 red/48000/2"
    ).joinToString("\r\n")

    @Test
    fun `raises the bitrate and keeps the existing parameters`() {
        val tuned = OpusSdpTuner.tune(sdp)
        val fmtp = tuned.lines().first { it.startsWith("a=fmtp:111") }
        assertTrue(fmtp.contains("minptime=10"))
        assertTrue(fmtp.contains("maxaveragebitrate=64000"))
        assertTrue(fmtp.contains("stereo=0"))
        assertTrue(fmtp.contains("usedtx=0"))
        // useinbandfec stays present exactly once
        assertEquals(1, Regex("useinbandfec").findAll(fmtp).count())
    }

    @Test
    fun `adds the parameters line when the codec has none`() {
        val bare = "m=audio 9 RTP/AVP 111\r\na=rtpmap:111 opus/48000/2\r\na=sendrecv"
        val tuned = OpusSdpTuner.tune(bare)
        val lines = tuned.split("\r\n")
        assertEquals("a=rtpmap:111 opus/48000/2", lines[1])
        assertTrue(lines[2].startsWith("a=fmtp:111 maxaveragebitrate=64000"))
        assertEquals("a=sendrecv", lines[3])
    }

    @Test
    fun `leaves a description without opus untouched`() {
        val other = "v=0\r\nm=audio 9 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000"
        assertEquals(other, OpusSdpTuner.tune(other))
    }
}
