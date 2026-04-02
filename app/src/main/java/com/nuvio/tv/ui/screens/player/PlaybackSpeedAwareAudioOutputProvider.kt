package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.util.concurrent.CopyOnWriteArraySet

internal class PlaybackSpeedAwareAudioOutputProvider(
    audioOutputProvider: AudioOutputProvider
) : ForwardingAudioOutputProvider(audioOutputProvider) {

    private val listeners = CopyOnWriteArraySet<AudioOutputProvider.Listener>()

    @Volatile
    private var playbackSpeed: Float = 1f

    @Volatile
    private var forcePcmForCurrentEac3Session: Boolean = false

    fun updatePlaybackSpeed(speed: Float, selectedAudioIsEac3: Boolean = false) {
        val normalizedSpeed = speed.takeIf { it > 0f } ?: 1f
        val wasForcingPcm = forcePcmForCurrentEac3Session
        if (selectedAudioIsEac3 && normalizedSpeed != 1f) {
            forcePcmForCurrentEac3Session = true
        }
        playbackSpeed = normalizedSpeed
        val isForcingPcm = forcePcmForCurrentEac3Session
        if (wasForcingPcm != isForcingPcm) {
            listeners.forEach(AudioOutputProvider.Listener::onFormatSupportChanged)
        }
    }

    override fun addListener(listener: AudioOutputProvider.Listener) {
        listeners += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: AudioOutputProvider.Listener) {
        listeners -= listener
        super.removeListener(listener)
    }

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        val support = super.getFormatSupport(formatConfig)
        if (!shouldForcePcm(formatConfig.format) || support.supportLevel != AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY) {
            return support
        }
        return AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(AudioOutputProvider.FORMAT_UNSUPPORTED)
            .build()
    }

    private fun shouldForcePcm(format: Format): Boolean {
        if (!forcePcmForCurrentEac3Session) {
            return false
        }
        return when (format.sampleMimeType) {
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC -> true
            else -> format.codecs?.contains("ec-3", ignoreCase = true) == true
        }
    }
}
