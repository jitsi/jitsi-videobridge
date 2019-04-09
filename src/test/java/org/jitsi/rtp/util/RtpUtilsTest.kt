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

package org.jitsi.rtp.util

import io.kotlintest.data.forall
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.tables.row

class RtpUtilsTest : ShouldSpec() {

    init {
        "getSequenceNumberDelta" {
            should("work correctly") {
                forall(
                    row(1, 10, -9),
                    row(10, 1, 9),
                    row(1, 65530, 7),
                    row(65530, 1, -7),
                    row(1234, 1234, 0)
                ) { a, b, expected ->
                    RtpUtils.getSequenceNumberDelta(a, b) shouldBe expected
                }
            }
        }
        "isNewerThan" {
            should("work correctly") {
                forall(
                    row(2, 1, true),
                    row(2, 65530, true),
                    row(2, 4, false)
                ) { a, b, expected ->
                    a isNewerThan b shouldBe expected
                }
            }
        }
        "isOlderThan" {
            should("work correctly") {
                forall(
                    row(2, 1, false),
                    row(2, 65530, false),
                    row(2, 4, true)
                ) { a, b, expected ->
                    a isOlderThan b shouldBe expected
                }
            }
        }
    }
}