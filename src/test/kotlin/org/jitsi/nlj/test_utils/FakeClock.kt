package org.jitsi.nlj.test_utils

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A fake [Clock] whose time is advanced manually
 */
internal abstract class FakeClock : Clock() {
    private var now = Instant.ofEpochMilli(0)

    override fun instant(): Instant {
        return now
    }

    fun elapse(duration: Duration) {
        now = now.plus(duration)
    }

    fun setTime(instant: Instant) {
        println("clock setting time to $instant")
        now = instant
    }
}