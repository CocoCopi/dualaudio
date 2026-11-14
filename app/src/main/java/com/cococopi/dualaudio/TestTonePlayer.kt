package com.cococopi.dualaudio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a short two-note chime through the media path. If the phone is
 * currently mirroring media to two outputs, the chime is heard twice.
 */
class TestTonePlayer {

    @Volatile
    private var playing = false

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    val isPlaying: Boolean
        get() = playing

    fun play(onFinished: () -> Unit) {
        stop()
        playing = true
        val worker = Thread {
            val sampleRate = 44_100
            val durationSeconds = 1.9
            val totalSamples = (sampleRate * durationSeconds).toInt()
            val buffer = ShortArray(totalSamples * 2)

            for (i in 0 until totalSamples) {
                val t = i.toDouble() / sampleRate
                val frequency = when {
                    t < 0.8 -> 440.0
                    t < 0.95 -> 0.0
                    else -> 660.0
                }
                val envelope = when {
                    t < 0.03 -> t / 0.03
                    t > durationSeconds - 0.08 -> ((durationSeconds - t) / 0.08).coerceIn(0.0, 1.0)
                    else -> 1.0
                }
                val value = if (frequency == 0.0) {
                    0.0
                } else {
                    sin(2.0 * PI * frequency * t) * 0.28 * envelope
                }
                val sample = (value * Short.MAX_VALUE).toInt().toShort()
                buffer[i * 2] = sample
                buffer[i * 2 + 1] = sample
            }

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track = audioTrack
            try {
                audioTrack.write(buffer, 0, buffer.size)
                audioTrack.play()
                Thread.sleep((durationSeconds * 1000).toLong())
            } catch (_: InterruptedException) {
                // Stopped by the user.
            } catch (_: Throwable) {
                // Audio may be unavailable while a call is active.
            } finally {
                runCatching { audioTrack.stop() }
                runCatching { audioTrack.release() }
                if (track === audioTrack) track = null
                playing = false
                onFinished()
            }
        }
        thread = worker
        worker.isDaemon = true
        worker.start()
    }

    fun stop() {
        playing = false
        thread?.interrupt()
        thread = null
        val current = track
        track = null
        if (current != null) {
            runCatching { current.pause() }
            runCatching { current.flush() }
            runCatching { current.stop() }
            runCatching { current.release() }
        }
    }
}
