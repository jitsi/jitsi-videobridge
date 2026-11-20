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
package org.jitsi.nlj.util

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

/** Unit tests for WindowedMinFilter,
 * based on WebRTC rtc_base/numerics/windowed_min_filter_unittest.cc in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 */
class WindowedMinFilterTest : FreeSpec() {
    init {
        "EmptyFilterReturnZero" {
            val filter = WindowedMinFilter(windowLength = 3, defaultValue = Duration.ZERO)
            filter.getMin() shouldBe Duration.ZERO
        }

        "EmptyFilterReturnEmptyString" {
            val filter = WindowedMinFilter(windowLength = 3, defaultValue = "")
            filter.getMin() shouldBe ""
        }

        "GetMinReturnsMin" {
            val filter = WindowedMinFilter(windowLength = 3, defaultValue = 0)
            filter.insert(30)
            filter.getMin() shouldBe 30
            filter.insert(20)
            filter.getMin() shouldBe 20
            filter.insert(10)
            filter.getMin() shouldBe 10
        }

        "GetMinReturnsMinNotSortedInput" {
            val filter = WindowedMinFilter(windowLength = 4, defaultValue = 0)
            filter.insert(0)
            filter.insert(30)
            filter.getMin() shouldBe 0
            filter.insert(10)
            filter.getMin() shouldBe 0
            filter.insert(40)
            filter.getMin() shouldBe 0
            filter.insert(40)
            filter.getMin() shouldBe 10
        }

        "GetMinReturnsMinWithStringsNotSorted" {
            val filter = WindowedMinFilter(windowLength = 3, defaultValue = "")
            filter.insert("bbb")
            filter.getMin() shouldBe "bbb"
            filter.insert("ccc")
            filter.getMin() shouldBe "bbb"
            filter.insert("aaa")
            filter.getMin() shouldBe "aaa"
        }

        "GetMinReturnsMinInWindow" {
            val filter = WindowedMinFilter(windowLength = 3, defaultValue = 0)
            filter.insert(10)
            filter.insert(20)
            filter.insert(30)
            filter.getMin() shouldBe 10
            filter.insert(40)
            filter.getMin() shouldBe 20
            filter.insert(50)
            filter.getMin() shouldBe 30
        }

        "RestartAfterReset" {
            val filter = WindowedMinFilter(windowLength = 3, defaultValue = 0)
            filter.insert(10)
            filter.insert(20)
            filter.getMin() shouldBe 10
            filter.reset()
            filter.getMin() shouldBe 0
            filter.insert(30)
            filter.getMin() shouldBe 30
        }
    }
}
