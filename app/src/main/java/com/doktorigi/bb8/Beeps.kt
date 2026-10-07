package com.doktorigi.bb8

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Synthesised droid chatter (no recorded sound effects): strings of short sine sweeps whose shape sets the mood. */
object Beeps {
    private const val RATE = 22050
    @Volatile var enabled = true

    fun play(mood: String) {
        if (!enabled) return
        thread {
            val pcm = render(mood)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size * 1000L / RATE + 100)
            track.release()
        }
    }

    private fun render(mood: String): ShortArray {
        val r = Random
        // (start Hz, end Hz, ms) per chirp
        val chirps: List<Triple<Double, Double, Int>> = when (mood) {
            "happy" -> List(r.nextInt(4, 7)) { val f = r.nextDouble(900.0, 1800.0); Triple(f, f * 1.6, r.nextInt(50, 90)) }
            "excited" -> List(r.nextInt(7, 11)) { Triple(r.nextDouble(1200.0, 2600.0), r.nextDouble(1200.0, 2600.0), r.nextInt(30, 60)) }
            "sad" -> List(2) { i -> Triple(900.0 - i * 200, 400.0 - i * 100, 350) }
            "scared" -> List(14) { i -> if (i % 2 == 0) Triple(2400.0, 1700.0, 35) else Triple(1700.0, 2400.0, 35) }
            "question" -> listOf(Triple(700.0, 800.0, 120), Triple(800.0, 1900.0, 220))
            else -> List(r.nextInt(3, 6)) { Triple(r.nextDouble(600.0, 2000.0), r.nextDouble(600.0, 2000.0), r.nextInt(60, 140)) }
        }
        val gap = RATE * 25 / 1000
        val out = ShortArray(chirps.sumOf { RATE * it.third / 1000 + gap })
        var i = 0
        for ((f0, f1, ms) in chirps) {
            val n = RATE * ms / 1000
            var phase = 0.0
            for (k in 0 until n) {
                phase += 2 * PI * (f0 + (f1 - f0) * k / n) / RATE
                val env = minOf(1.0, minOf(k, n - k) / (RATE * 0.005)) // 5 ms fade in/out avoids clicks
                out[i++] = (sin(phase) * env * 0.5 * Short.MAX_VALUE).toInt().toShort()
            }
            i += gap
        }
        return out
    }
}
