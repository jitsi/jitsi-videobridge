/*
 * Copyright @ 2026 - present 8x8, Inc.
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

package org.jitsi.nlj.util

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock

class BitrateTrackerTest : ShouldSpec() {
    private val clock = FakeClock()
    private val tracker = BitrateTracker(5.secs, 100.ms, clock)

    init {
        context("A single burst") {
            // 500 kbit, which is 100 kbps once spread over the 5 second window.
            tracker.update(500_000.bits, clock.millis())
            clock.elapse(100.ms)

            should("read as a high rate while the window has not filled up") {
                // getRate divides by the time since the stream started, not by the window.
                tracker.getRate(clock.millis()) shouldBe 5.mbps
            }
            should("read as its average over the window with getRateOverFullWindow") {
                tracker.getRateOverFullWindow(clock.millis()) shouldBe 100.kbps
            }
            context("Once the window has filled up") {
                clock.elapse(4900.ms)
                should("agree with getRate") {
                    tracker.getRate(clock.millis()) shouldBe 100.kbps
                    tracker.getRateOverFullWindow(clock.millis()) shouldBe 100.kbps
                }
            }
            context("Once the burst has aged out of the window") {
                clock.elapse(10.secs)
                should("read as zero") {
                    tracker.getRateOverFullWindow(clock.millis()) shouldBe 0.bps
                }
            }
        }
    }
}
