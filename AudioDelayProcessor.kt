package com.romane.tvplayer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Décale le son par rapport à l'image, réglable en direct (sans coupure).
 *
 * - délai positif : le son est retardé (on insère du silence)
 * - délai négatif : le son est avancé (on retire un peu de son)
 *
 * Ne fonctionne que sur du PCM 16 bits. Si le son est envoyé tel quel à un
 * ampli ou une barre de son (passthrough Dolby/DTS), le processeur est ignoré.
 */
@OptIn(UnstableApi::class)
class AudioDelayProcessor : BaseAudioProcessor() {

    /** Délai souhaité en millisecondes, modifiable depuis le thread principal. */
    @Volatile
    var targetDelayMs: Int = 0

    private var appliedDelayMs = 0
    private var pendingSilenceBytes = 0
    private var pendingSkipBytes = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            inputAudioFormat
        } else {
            AudioFormat.NOT_SET
        }
    }

    override fun onFlush() {
        // Après un changement de flux ou une avance/retour, le délai est ré-appliqué.
        appliedDelayMs = targetDelayMs
        pendingSilenceBytes = 0
        pendingSkipBytes = 0
        val bytes = bytesFor(targetDelayMs)
        if (targetDelayMs > 0) {
            pendingSilenceBytes = bytes
        } else if (targetDelayMs < 0) {
            pendingSkipBytes = bytes
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val target = targetDelayMs
        if (target != appliedDelayMs) {
            val deltaBytes = bytesFor(target - appliedDelayMs)
            val signedDelta = if (target > appliedDelayMs) deltaBytes else -deltaBytes
            val net = pendingSilenceBytes - pendingSkipBytes + signedDelta
            if (net >= 0) {
                pendingSilenceBytes = net
                pendingSkipBytes = 0
            } else {
                pendingSilenceBytes = 0
                pendingSkipBytes = -net
            }
            appliedDelayMs = target
        }

        var remaining = inputBuffer.remaining()
        if (pendingSkipBytes > 0 && remaining > 0) {
            val skip = minOf(pendingSkipBytes, remaining)
            inputBuffer.position(inputBuffer.position() + skip)
            pendingSkipBytes -= skip
            remaining -= skip
        }

        val silence = pendingSilenceBytes
        val out = replaceOutputBuffer(silence + remaining)
        if (silence > 0) {
            out.put(ByteArray(silence))
            pendingSilenceBytes = 0
        }
        out.put(inputBuffer)
        out.flip()
    }

    /** Nombre d'octets (alignés sur des trames complètes) pour une durée en ms. */
    private fun bytesFor(ms: Int): Int {
        val format = inputAudioFormat
        if (format.sampleRate <= 0 || format.bytesPerFrame <= 0) return 0
        val frames = (abs(ms).toLong() * format.sampleRate / 1000L).toInt()
        return frames * format.bytesPerFrame
    }
}
