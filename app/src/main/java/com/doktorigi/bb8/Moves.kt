package com.doktorigi.bb8

import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.random.Random

/**
 * BB-8's personality. Its head is magnetic and passive, so all emotion comes from body turns,
 * short bursts that rock the head, lights, and the phone's beeps. Every move ends facing where it started.
 */
class Moves(private val bb: BB8) {
    private val exec = Executors.newSingleThreadExecutor()
    private var current: Future<*>? = null
    @Volatile var patrolSpeed = 60

    private val tricks: Map<String, () -> Unit> = linkedMapOf(
        "hello" to { Beeps.play("talk"); burst(60, 200); wiggle(20, 2, 150) },
        "yes" to { Beeps.play("happy"); repeat(2) { burst(70, 180) } },
        "no" to { Beeps.play("talk"); wiggle(35, 3, 180) },
        "look" to {
            Beeps.play("question")
            val h = bb.heading
            bb.roll(0, h + 70); pause(900)
            bb.roll(0, h - 70); pause(900)
            bb.roll(0, h); pause(300)
        },
        "spin" to { Beeps.play("excited"); spin(2) },
        "happy" to { Beeps.play("happy"); flash(0x00ff00, 0xffff00, 0x00ff00); spin(1); burst(60, 150); bb.color(bb.baseColor) },
        "excited" to { Beeps.play("excited"); repeat(3) { burst(90, 150) }; flash(0xff6600, 0xffffff, 0xff6600, 0xffffff); bb.color(bb.baseColor) },
        "sad" to { bb.color(0x001040); Beeps.play("sad"); val h = bb.heading; bb.roll(0, h + 25); pause(1500); bb.roll(0, h); pause(300); bb.color(bb.baseColor) },
        "scared" to { bb.color(0xff0000); Beeps.play("scared"); wiggle(12, 8, 70); pause(400); bb.color(bb.baseColor) },
        "dance" to {
            Beeps.play("happy")
            repeat(2) { wiggle(45, 2, 200); spin(1, 100) }
            flash(0xff0000, 0xff8800, 0xffff00, 0x00ff00, 0x0000ff, 0x8800ff)
            bb.color(bb.baseColor)
        },
        "forward" to { val h = bb.heading; bb.roll(80, h); pause(1500); bb.roll(0, h) },
        "back" to { val h = bb.heading; bb.roll(80, h + 180); pause(1500); bb.roll(0, h) },
        "left" to { bb.roll(0, bb.heading - 90); pause(300) },
        "right" to { bb.roll(0, bb.heading + 90); pause(300) },
    )
    val names get() = tricks.keys.toList()

    fun play(name: String): Boolean {
        val t = tricks[name] ?: return false
        start(t)
        return true
    }

    fun startPatrol() = start {
        // ponytail: blind random walk; add Sphero collision detection (DID 02 CID 12 + response notifications) if it keeps hitting walls
        while (true) {
            val h = bb.heading + Random.nextInt(-120, 121)
            bb.roll(0, h); pause(400)
            bb.roll(patrolSpeed, h); pause(Random.nextLong(1200, 2800))
            bb.roll(0, h); pause(600)
            if (Random.nextInt(4) == 0) tricks.getValue("look")()
        }
    }

    fun stop() { current?.cancel(true); bb.stop() }

    @Synchronized private fun start(job: () -> Unit) {
        current?.cancel(true)
        current = exec.submit {
            try { job() } catch (_: InterruptedException) { bb.stop() } // replaced by a newer move
        }
    }

    private fun pause(ms: Long) = Thread.sleep(ms)
    /** Roll then brake: the head rocks forward and back like a nod. */
    private fun burst(speed: Int, ms: Long) { val h = bb.heading; bb.roll(speed, h); pause(ms); bb.roll(0, h); pause(250) }
    private fun wiggle(deg: Int, times: Int, ms: Long) {
        val h = bb.heading
        repeat(times) { bb.roll(0, h + deg); pause(ms); bb.roll(0, h - deg); pause(ms) }
        bb.roll(0, h)
    }
    private fun spin(turns: Int, ms: Long = 120) { val h = bb.heading; repeat(turns * 3) { bb.roll(0, h + (it + 1) * 120); pause(ms) } }
    private fun flash(vararg colors: Int) = colors.forEach { bb.color(it); pause(150) }
}
