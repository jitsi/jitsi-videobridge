package org.jitsi.nlj.util

import java.time.Duration
import kotlin.math.round

fun Duration.toDouble(): Double {
    return this.seconds.toDouble() + this.nano.toDouble() * 1e-9
}

fun Duration.toDoubleMillis(): Double {
    val sec = this.seconds
    val nano = this.nano
    return sec * 1e3 + nano * 1e-6
}

fun durationOfDoubleSeconds(duration: Double): Duration {
    return Duration.ofNanos(round(duration * 1e9).toLong())
}

val minDuration = Duration.ofSeconds(Long.MIN_VALUE, 0)

val maxDuration = Duration.ofSeconds(Long.MAX_VALUE, 999_999_999)

fun Duration.isFinite() = this != minDuration && this != maxDuration

operator fun Duration.times(other: Double): Duration = durationOfDoubleSeconds((toDouble() * other))
operator fun Double.times(other: Duration): Duration = durationOfDoubleSeconds(other.toDouble() * this)

operator fun Duration.div(other: Double): Duration = durationOfDoubleSeconds(toDouble() / other)

operator fun Duration.div(other: Long): Duration = this.dividedBy(other)

operator fun Duration.unaryMinus(): Duration = this.negated()

fun <T> Iterable<T>.sumOf(selector: (T) -> Duration): Duration {
    var sum: Duration = Duration.ZERO
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

/**
 * Returns the maximum of two [Duration]s
 */
fun max(a: Duration, b: Duration): Duration {
    return if (a >= b) a else b
}

/**
 * Returns the minimum of two [Duration]s
 */
fun min(a: Duration, b: Duration): Duration {
    return if (a <= b) a else b
}

/**
 * Ensures that this value lies in the specified range [minimumValue]..[maximumValue].
 *
 * @return this value if it's in the range, or [minimumValue] if this value is less than [minimumValue],
 * or [maximumValue] if this value is greater than [maximumValue].
 *
 * @sample samples.comparisons.ComparableOps.coerceIn
 */
fun Duration.coerceIn(minimumValue: Duration, maximumValue: Duration): Duration {
    if (minimumValue > maximumValue) {
        throw IllegalArgumentException(
            "Cannot coerce value to an empty range:  maximum $maximumValue is less than minimum $minimumValue."
        )
    }
    if (this < minimumValue) {
        return minimumValue
    }
    if (this > maximumValue) {
        return maximumValue
    }
    return this
}
