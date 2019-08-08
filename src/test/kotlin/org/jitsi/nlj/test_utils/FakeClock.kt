package org.jitsi.nlj.test_utils

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A fake [Clock] whose time is advanced manually
 */
internal abstract class FakeClock(
    private val debug: Boolean = false
) : Clock() {
    private var now = Instant.ofEpochMilli(0)

    private fun log(str: String) {
        if (debug) {
            println(str)
        }
    }

    override fun instant(): Instant {
        return now
    }

    fun elapse(duration: Duration) {
        log("elapsing $duration")
        now = now.plus(duration)
    }

    fun setTime(instant: Instant) {
        log("clock setting time to $instant")
        now = instant
    }
}