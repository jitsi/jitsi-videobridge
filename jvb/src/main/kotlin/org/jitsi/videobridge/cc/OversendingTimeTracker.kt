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

package org.jitsi.videobridge.cc

import org.jitsi.nlj.util.NEVER
import java.time.Clock
import java.time.Duration
import java.time.Instant

class OversendingTimeTracker(
    private val clock: Clock = Clock.systemUTC()
) {
    var totalOversendingTime: Duration = Duration.ofMillis(0)
        private set
    private var mostRecentStartOversendingTimestamp: Instant = NEVER
    private var currentlyOversending: Boolean = false

    fun startedOversending() {
        if (!currentlyOversending) {
            currentlyOversending = true
            mostRecentStartOversendingTimestamp = clock.instant()
        }
    }

    fun stoppedOversending() {
        if (currentlyOversending) {
            currentlyOversending = false
            totalOversendingTime += Duration.between(mostRecentStartOversendingTimestamp, clock.instant())
        }
    }
}
