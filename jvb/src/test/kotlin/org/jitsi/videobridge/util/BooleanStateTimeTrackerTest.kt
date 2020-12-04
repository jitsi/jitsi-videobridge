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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.test.time.FakeClock
import org.jitsi.utils.secs
import java.time.Duration

class BooleanStateTimeTrackerTest : ShouldSpec() {
    private val clock = FakeClock()
    private val oversendingTracker = BooleanStateTimeTracker(clock = clock)

    init {
        context("BooleanStateTimeTracker") {
            should("start with a time of 0") {
                oversendingTracker.totalTimeOn() shouldBe Duration.ofSeconds(0)
                oversendingTracker.totalTimeOff() shouldBe Duration.ofSeconds(0)
            }
            context("when the state changes") {
                oversendingTracker.on()
                should("increment the time in the 'on' state") {
                    clock.elapse(5.secs)
                    oversendingTracker.totalTimeOn() shouldBe 5.secs
                    oversendingTracker.totalTimeOff() shouldBe 0.secs
                    clock.elapse(5.secs)
                    oversendingTracker.totalTimeOn() shouldBe 10.secs
                    oversendingTracker.totalTimeOff() shouldBe 0.secs
                }
                context("and then changes again") {
                    clock.elapse(5.secs)
                    oversendingTracker.off()
                    should("have the correct time") {
                        oversendingTracker.totalTimeOn() shouldBe 15.secs
                        oversendingTracker.totalTimeOff() shouldBe 0.secs
                    }
                    context("and time elapses") {
                        clock.elapse(5.secs)
                        should("not increase the time") {
                            oversendingTracker.totalTimeOn() shouldBe 15.secs
                            oversendingTracker.totalTimeOff() shouldBe 5.secs
                        }
                    }
                }
            }
        }
    }
}
