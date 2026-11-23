/*
 * Copyright @ 2019 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.nlj.rtp.scream.test

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/** Pseudo-random number generator,
 * based on WebRTC rtc_base/random.{h,cc} in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 *
 * The tests use this and not a generator from the standard library so that the simulated network behaves here
 * exactly as it does in the tests of WebRTC.
 */
class WebRtcRandom(seed: ULong) {
    private var state: ULong = seed

    init {
        require(seed != 0uL)
    }

    // Uniformly distributed pseudo-random number in the interval [0, t].
    fun rand(t: UInt): UInt {
        // Casting the output to 32 bits will give an almost uniform number.
        // Pr[x=0] = (2^32-1) / (2^64-1)
        // Pr[x=k] = 2^32 / (2^64-1) for k!=0
        // Uniform would be Pr[x=k] = 2^32 / 2^64 for all 32-bit integers k.
        val x = nextOutput().toUInt()
        // If x / 2^32 is uniform on [0,1), then x / 2^32 * (t+1) is uniform on
        // the interval [0,t+1), so the integer part is uniform on [0,t].
        var result = x.toULong() * (t.toULong() + 1uL)
        result = result shr 32
        return result.toUInt()
    }

    // Uniformly distributed pseudo-random number in the interval [low, high].
    fun rand(low: UInt, high: UInt): UInt {
        require(low <= high)
        return rand(high - low) + low
    }

    // Return pseudo-random number in the interval [0.0, 1.0).
    fun randDouble(): Double {
        var result = (nextOutput() - 1uL).toDouble()
        result = result / kMaxULongAsDouble
        return result
    }

    // Normal Distribution.
    fun gaussian(mean: Double, standardDeviation: Double): Double {
        // Creating a Normal distribution variable from two independent uniform
        // variables based on the Box-Muller transform, which is defined on the
        // interval (0, 1]. Note that we rely on NextOutput to generate integers
        // in the range [1, 2^64-1]. Normally this behavior is a bit frustrating,
        // but here it is exactly what we need.
        val u1 = nextOutput().toDouble() / kMaxULongAsDouble
        val u2 = nextOutput().toDouble() / kMaxULongAsDouble
        return mean + standardDeviation * sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
    }

    // Exponential Distribution.
    fun exponential(lambda: Double): Double {
        val uniform = randDouble()
        return -ln(uniform) / lambda
    }

    // Outputs a nonzero 64-bit random number using Xorshift algorithm.
    // https://en.wikipedia.org/wiki/Xorshift
    private fun nextOutput(): ULong {
        state = state xor (state shr 12)
        state = state xor (state shl 25)
        state = state xor (state shr 27)
        check(state != 0uL)
        return state * 2685821657736338717uL
    }

    companion object {
        private val kMaxULongAsDouble = ULong.MAX_VALUE.toDouble()
    }
}
