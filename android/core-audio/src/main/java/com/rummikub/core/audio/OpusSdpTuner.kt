package com.rummikub.core.audio

/**
 * Raises the quality of the voice codec in a WebRTC session description (SDP):
 * mono speech at 64 kbps with error correction, instead of the default ~32 kbps.
 * Pure text processing, so it can be unit tested.
 */
object OpusSdpTuner {
    private val wanted = linkedMapOf(
        "maxaveragebitrate" to "64000",
        "stereo" to "0",
        "sprop-stereo" to "0",
        "useinbandfec" to "1",
        "usedtx" to "0",
        "maxplaybackrate" to "48000"
    )

    fun tune(sdp: String): String {
        val lineBreak = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(lineBreak).toMutableList()
        val rtpmap = Regex("^a=rtpmap:([0-9]+) opus/48000/2")
        val payload = lines.firstNotNullOfOrNull { rtpmap.find(it)?.groupValues?.get(1) } ?: return sdp

        val fmtpPrefix = "a=fmtp:$payload "
        val fmtpIndex = lines.indexOfFirst { it.startsWith(fmtpPrefix) }
        if (fmtpIndex >= 0) {
            val params = linkedMapOf<String, String>()
            lines[fmtpIndex].removePrefix(fmtpPrefix).split(';').filter { it.isNotBlank() }.forEach { p ->
                val kv = p.split('=', limit = 2)
                params[kv[0].trim()] = kv.getOrElse(1) { "" }.trim()
            }
            params.putAll(wanted)
            lines[fmtpIndex] = fmtpPrefix + params.entries.joinToString(";") { (k, v) -> "$k=$v" }
        } else {
            val mapIndex = lines.indexOfFirst { rtpmap.containsMatchIn(it) }
            lines.add(mapIndex + 1, fmtpPrefix + wanted.entries.joinToString(";") { (k, v) -> "$k=$v" })
        }
        return lines.joinToString(lineBreak)
    }
}
