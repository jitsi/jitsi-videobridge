package org.jitsi.nlj.util

import java.time.Duration

fun Int.milliseconds(): Duration = Duration.ofMillis(this.toLong())
fun Int.ms(): Duration = Duration.ofMillis(this.toLong())

fun Int.minutes(): Duration = Duration.ofMinutes(this.toLong())
fun Int.mins(): Duration = Duration.ofMinutes(this.toLong())

fun Duration.toDoubleMillis(): Double {
    val sec = this.seconds
    val nano = this.nano
    return sec * 1e3 + nano * 1e-6
}
