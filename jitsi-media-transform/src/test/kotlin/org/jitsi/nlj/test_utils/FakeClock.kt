/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.test_utils

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * A fake [Clock] whose time is advanced manually
 */
internal class FakeClock(
    private val debug: Boolean = false,
    private val zone_: ZoneId = ZoneId.systemDefault()
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

    override fun getZone(): ZoneId {
        return zone_
    }

    override fun withZone(zone: ZoneId?): Clock {
        if (zone_ == zone)
            return this
        return FakeClock(zone_ = zone!!)
    }
}
