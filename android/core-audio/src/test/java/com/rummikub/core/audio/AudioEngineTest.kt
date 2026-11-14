package com.rummikub.core.audio

import com.rummikub.core.model.NetworkPacket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioEngineTest {

    @Test
    fun `audio ducking reduces music volume by 40 percent when player speaks`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val musicPlayer = FocusMusicPlayer(scope = scope)
        musicPlayer.setUserVolume(1.0f) // 100% volume

        val voiceChat = WebRtcVoiceChat(myPlayerId = "P1", scope = scope)
        val duckingManager = AudioDuckingManager(
            musicPlayer = musicPlayer,
            voiceChat = voiceChat,
            scope = scope,
            duckingAttenuationPercent = 0.40f,
            fadeOutDurationMs = 200L,
            fadeInDurationMs = 500L,
            releaseHangoverMs = 300L
        )

        assertFalse("Initially should not be ducked", duckingManager.isDucked.value)

        // Friend P2 starts speaking
        voiceChat.handleRemoteVoiceSignal(
            NetworkPacket.VoiceSignalPacket(
                fromPlayerId = "P2",
                signalType = NetworkPacket.VoiceSignalPacket.SignalType.VAD_SPEAKING_STATUS,
                isSpeaking = true
            )
        )

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Ducking should be active", duckingManager.isDucked.value)
        // With 100% user volume and 40% reduction, effective target is 0.60f
        assertEquals(0.60f, musicPlayer.effectiveVolume.value, 0.05f)

        // Friend P2 stops speaking
        voiceChat.handleRemoteVoiceSignal(
            NetworkPacket.VoiceSignalPacket(
                fromPlayerId = "P2",
                signalType = NetworkPacket.VoiceSignalPacket.SignalType.VAD_SPEAKING_STATUS,
                isSpeaking = false
            )
        )

        // Advance past hangover (300ms) + fade in (500ms)
        advanceTimeBy(350L)
        advanceTimeBy(550L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("Ducking should be released after hangover and fade-in", duckingManager.isDucked.value)
        assertEquals(1.0f, musicPlayer.effectiveVolume.value, 0.05f)
    }

    @Test
    fun `voice chat PTT mode activates speaking only when button is pressed`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val voiceChat = WebRtcVoiceChat(myPlayerId = "P1", scope = scope)
        voiceChat.setMode(VoiceChatMode.PUSH_TO_TALK)

        assertFalse(voiceChat.isLocalSpeaking.value)

        // Press PTT button
        voiceChat.setPushToTalkPressed(true)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue("Speaking should be active while PTT is pressed", voiceChat.isLocalSpeaking.value)
        assertTrue(voiceChat.speakingPlayers.value.contains("P1"))

        // Release PTT button
        voiceChat.setPushToTalkPressed(false)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse("Speaking should stop immediately when PTT is released", voiceChat.isLocalSpeaking.value)
        assertFalse(voiceChat.speakingPlayers.value.contains("P1"))
    }

    @Test
    fun `muting a friend removes them from speaking list and prevents ducking`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val musicPlayer = FocusMusicPlayer(scope = scope)
        musicPlayer.setUserVolume(0.80f)

        val voiceChat = WebRtcVoiceChat(myPlayerId = "P1", scope = scope)
        val duckingManager = AudioDuckingManager(
            musicPlayer = musicPlayer,
            voiceChat = voiceChat,
            scope = scope
        )

        // Mute friend P2
        voiceChat.togglePlayerMute("P2")
        assertTrue(voiceChat.mutedPlayers.value.contains("P2"))

        // P2 tries to speak
        voiceChat.handleRemoteVoiceSignal(
            NetworkPacket.VoiceSignalPacket(
                fromPlayerId = "P2",
                signalType = NetworkPacket.VoiceSignalPacket.SignalType.VAD_SPEAKING_STATUS,
                isSpeaking = true
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("Muted player should not be in speaking list", voiceChat.speakingPlayers.value.contains("P2"))
        assertFalse("Muted player speaking should not trigger ducking", duckingManager.isDucked.value)
    }

    @Test
    fun `vad energy detection activates speaking above threshold and releases after hangover`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val voiceChat = WebRtcVoiceChat(myPlayerId = "P1", scope = scope)

        // Audio energy below threshold (-55 dB < -45 dB) -> Silence
        voiceChat.updateLocalVadAudioEnergy(-55f)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(voiceChat.isLocalSpeaking.value)

        // Audio energy above threshold (-30 dB >= -45 dB) -> Speech detected
        voiceChat.updateLocalVadAudioEnergy(-30f)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue("Speech should be detected", voiceChat.isLocalSpeaking.value)

        // Speech ends (-60 dB)
        voiceChat.updateLocalVadAudioEnergy(-60f)
        advanceTimeBy(200L)
        // During hangover (400ms), still considered speaking to prevent word cutting
        assertTrue("Should still be speaking during hangover", voiceChat.isLocalSpeaking.value)

        advanceTimeBy(250L) // Total 450ms > 400ms hangover
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse("Should release speaking after hangover time", voiceChat.isLocalSpeaking.value)
    }
}
