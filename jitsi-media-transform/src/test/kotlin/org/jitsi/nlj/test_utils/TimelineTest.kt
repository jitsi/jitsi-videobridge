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

import org.jitsi.test.time.FakeClock
import java.time.Duration
import java.time.Instant

internal class TimelineTest(private val clock: FakeClock) {
    private val tasks = mutableListOf<() -> Unit>()

    fun time(time: Int) {
        tasks.add { clock.setTime(Instant.ofEpochMilli(time.toLong())) }
    }

    fun run(block: () -> Unit) {
        tasks.add(block)
    }

    fun elapse(duration: Duration) {
        tasks.add { clock.elapse(duration) }
    }

    fun run() {
        tasks.forEach { it.invoke() }
    }
}

internal fun timeline(clock: FakeClock, block: TimelineTest.() -> Unit): TimelineTest =
    TimelineTest(clock).apply(block)
