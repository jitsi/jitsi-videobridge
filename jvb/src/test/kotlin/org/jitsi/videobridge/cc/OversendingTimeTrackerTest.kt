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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.test.time.FakeClock
import org.jitsi.utils.secs
import java.time.Duration

class OversendingTimeTrackerTest : ShouldSpec() {
    private val clock = FakeClock()
    private val oversendingTracker = OversendingTimeTracker(clock)

    init {
        context("OversendingTimeTracker") {
            should("start with a time of 0") {
                oversendingTracker.totalOversendingTime() shouldBe Duration.ofSeconds(0)
            }
            context("when oversending starts") {
                oversendingTracker.startedOversending()
                should("increment the oversending time") {
                    clock.elapse(5.secs)
                    oversendingTracker.totalOversendingTime() shouldBe 5.secs
                    clock.elapse(5.secs)
                    oversendingTracker.totalOversendingTime() shouldBe 10.secs
                }
                context("and then stops") {
                    clock.elapse(5.secs)
                    oversendingTracker.stoppedOversending()
                    should("have the correct time") {
                        oversendingTracker.totalOversendingTime() shouldBe 15.secs
                    }
                    context("and time elapses") {
                        clock.elapse(5.secs)
                        should("not increase the time") {
                            oversendingTracker.totalOversendingTime() shouldBe 15.secs
                        }
                    }
                }
            }
        }
    }
}
