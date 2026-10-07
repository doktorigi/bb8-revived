package com.doktorigi.bb8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogicTest {
    @Test fun rollPacket() {
        val p = Protocol.roll(0x80, 270).map { it.toInt() and 0xff }
        assertEquals(listOf(0xff, 0xff, 0x02, 0x30), p.take(4))
        assertEquals(6, p[5]) // 5 data bytes + checksum
        assertEquals(listOf(0x80, 0x01, 0x0e, 0x01, 0x00), p.slice(6..10))
        assertEquals(p.slice(2 until p.size - 1).sum().inv() and 0xff, p.last())
        // same payload as a roll captured from Sphero Edu: ff ff 02 30 47 06 18 00 b4 01 00 b3
        assertEquals(listOf(0x02, 0x30), Protocol.roll(0x18, 0xb4).map { it.toInt() and 0xff }.slice(2..3))
        assertEquals(listOf(0x06, 0x18, 0x00, 0xb4, 0x01, 0x00), Protocol.roll(0x18, 0xb4).map { it.toInt() and 0xff }.slice(5..10))
        assertEquals(Protocol.roll(10, 359).slice(7..8), Protocol.roll(10, -1).slice(7..8))
    }

    @Test fun cueTrack() {
        assertEquals(3_723_000L, parseTime("1:02:03"))
        assertEquals(90_000L, parseTime("1:30"))
        assertNull(parseTime("abc"))
        assertNull(parseTime(""))
        assertEquals(listOf(Cue(5000, "scared"), Cue(10000, "happy")),
            parseCues("# comment\n0:10 happy\nbad line\n0:05 Scared # loud\n"))
        assertEquals("1:02:03", formatTime(3_723_000))
    }
}
