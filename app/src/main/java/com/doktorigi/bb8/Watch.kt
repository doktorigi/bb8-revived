package com.doktorigi.bb8

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

data class Cue(val atMs: Long, val move: String)

/** Lines like "1:02:03 happy" or "12:30 scared"; '#' starts a comment; anything unparseable is skipped. */
fun parseCues(text: String): List<Cue> = text.lines().mapNotNull { line ->
    val parts = line.substringBefore('#').trim().split(Regex("\\s+"))
    if (parts.size < 2) null else parseTime(parts[0])?.let { Cue(it, parts[1].lowercase()) }
}.sortedBy { it.atMs }

/** "h:mm:ss", "m:ss" or "s" → milliseconds; null if it isn't a time. */
fun parseTime(s: String): Long? {
    val nums = s.split(':').map { it.toLongOrNull() ?: return null }
    if (nums.size > 3) return null
    return nums.fold(0L) { acc, n -> acc * 60 + n } * 1000
}

fun formatTime(ms: Long): String { val s = ms / 1000; return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }

/**
 * Watch With Me: BB-8 sits beside you and reacts to the movie.
 * The original app synced by recognising one movie's soundtrack; this works with any movie:
 * a movie clock you sync by hand fires saved cues, and the mic reacts to loud moments live.
 */
class Watch(private val moves: Moves, private val onTick: (Long) -> Unit) {
    @Volatile var running = false
        private set
    @Volatile private var startedAt = 0L // elapsedRealtime at movie time 0
    @Volatile var cues: List<Cue> = emptyList()
    @Volatile var micOn = true
    /** How far above the room's running average (dB) counts as a big moment. Rooms and TVs differ: tune it. */
    @Volatile var sensitivityDb = 15.0

    val movieMs get() = SystemClock.elapsedRealtime() - startedAt

    fun start(fromMs: Long) {
        startedAt = SystemClock.elapsedRealtime() - fromMs
        if (running) return
        running = true
        thread(name = "watch") { loop() }
    }
    fun nudge(ms: Long) { startedAt -= ms }
    fun stop() { running = false }

    @SuppressLint("MissingPermission") // MainActivity grants RECORD_AUDIO before start() when the mic is on
    private fun loop() {
        val rate = 16_000
        val buf = ShortArray(rate / 10) // 100 ms windows, so the loop ticks at 10 Hz
        val rec = if (!micOn) null else AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, buf.size * 4).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        rec?.startRecording()
        val cues = cues
        var next = 0
        var lastMs = Long.MIN_VALUE
        var baseline = Double.NaN
        var calmUntil = 0L
        var lastReaction = SystemClock.elapsedRealtime()
        fun react(move: String) {
            moves.play(move)
            lastReaction = SystemClock.elapsedRealtime()
            calmUntil = lastReaction + 5000 // also keeps the mic from reacting to BB-8's own beeps
        }
        try {
            while (running) {
                val now = movieMs
                if (lastMs == Long.MIN_VALUE || now < lastMs) next = cues.indexOfFirst { it.atMs >= now }.let { if (it < 0) cues.size else it }
                while (next < cues.size && cues[next].atMs <= now) {
                    if (now - cues[next].atMs < 2000) react(cues[next].move) // skip cues we jumped past
                    next++
                }
                val n = rec?.read(buf, 0, buf.size) ?: 0
                if (n > 0) {
                    var sum = 0.0
                    for (k in 0 until n) sum += buf[k].toDouble() * buf[k]
                    val db = 20 * log10(sqrt(sum / n) + 1)
                    baseline = if (baseline.isNaN()) db else baseline + (db - baseline) * 0.01 // ~10 s running average
                    if (SystemClock.elapsedRealtime() > calmUntil && db > baseline + sensitivityDb)
                        react(if (db > baseline + sensitivityDb + 8) "scared" else "excited")
                } else Thread.sleep(100)
                if (SystemClock.elapsedRealtime() - lastReaction > 45_000) react("look") // fidget in quiet scenes
                lastMs = now
                onTick(now)
            }
        } finally {
            rec?.stop()
            rec?.release()
        }
    }
}
