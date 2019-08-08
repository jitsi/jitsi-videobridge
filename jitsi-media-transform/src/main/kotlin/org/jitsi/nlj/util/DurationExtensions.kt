package org.jitsi.nlj.util

import java.time.Duration

fun Int.milliseconds(): Duration = Duration.ofMillis(this.toLong())
