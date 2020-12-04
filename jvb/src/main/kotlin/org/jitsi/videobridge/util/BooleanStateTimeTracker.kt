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

package org.jitsi.videobridge.util

import java.time.Clock
import java.time.Duration

class BooleanStateTimeTracker(
    initialState: Boolean = false,
    private val clock: Clock = Clock.systemUTC()
) {
    private var totalTimeOn: Duration = Duration.ofMillis(0)
    private var totalTimeOff: Duration = Duration.ofMillis(0)
    var state: Boolean = initialState
        private set
    private var mostRecentStateChange = clock.instant()
    private val lock = Any()

    fun totalTimeOn(): Duration {
        synchronized(lock) {
            if (state) {
                return totalTimeOn + Duration.between(mostRecentStateChange, clock.instant())
            }
            return totalTimeOn
        }
    }

    fun totalTimeOff(): Duration {
        synchronized(lock) {
            if (!state) {
                return totalTimeOff + Duration.between(mostRecentStateChange, clock.instant())
            }
            return totalTimeOff
        }
    }

    fun setState(state: Boolean) = if (state) on() else off()

    fun on() {
        synchronized(lock) {
            if (!state) {
                state = true
                totalTimeOff += Duration.between(mostRecentStateChange, clock.instant())
                mostRecentStateChange = clock.instant()
            }
        }
    }

    fun off() {
        synchronized(lock) {
            if (state) {
                state = false
                totalTimeOn += Duration.between(mostRecentStateChange, clock.instant())
                mostRecentStateChange = clock.instant()
            }
        }
    }
}
