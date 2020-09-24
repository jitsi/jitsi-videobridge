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

package org.jitsi.nlj.util

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.utils.ms
import org.jitsi.utils.secs

class RateUtilsKtTest : ShouldSpec() {

    init {
        context("atRate") {
            should("work correctly") {
                1.megabytes atRate 1.mbps shouldBe 8.secs
            }
        }
        context("in") {
            should("work correctly") {
                val size = howMuchCanISendAtRate(1.mbps).`in`(8.secs)
                size shouldBe 1.megabytes
            }
            should("work correctly for fractional durations") {
                val size = howMuchCanISendAtRate(1.mbps).`in`(800.ms)
                size shouldBe 100.kilobytes
            }
        }
    }
}
