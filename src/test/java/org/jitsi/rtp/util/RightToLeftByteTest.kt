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

import io.kotlintest.IsolationMode
import io.kotlintest.data.forall
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.tables.row

internal class RightToLeftByteTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        val data = 0x0F.toByte()
        "Getting bits" {
            should("work correctly using right-to-left indicies") {
                forall(
                    row(0, true),
                    row(1, true),
                    row(2, true),
                    row(3, true),
                    row(4, false),
                    row(5, false),
                    row(6, false),
                    row(7, false)
                ) { bitIndex, value ->
                    RightToLeftByteUtils.getBitAsBool(data, bitIndex) shouldBe value
                }
            }
        }
        "Setting bits" {
            should("work correctly using right-to-left indicies") {
                var changedData = data
                changedData = RightToLeftByteUtils.putBit(changedData, 0, false)
                changedData = RightToLeftByteUtils.putBit(changedData, 1, false)
                changedData = RightToLeftByteUtils.putBit(changedData, 2, false)
                changedData = RightToLeftByteUtils.putBit(changedData, 3, false)
                changedData = RightToLeftByteUtils.putBit(changedData, 4, true)
                changedData = RightToLeftByteUtils.putBit(changedData, 5, true)
                changedData = RightToLeftByteUtils.putBit(changedData, 6, true)
                changedData = RightToLeftByteUtils.putBit(changedData, 7, true)

                forall(
                    row(0, false),
                    row(1, false),
                    row(2, false),
                    row(3, false),
                    row(4, true),
                    row(5, true),
                    row(6, true),
                    row(7, true)
                ) { bitIndex, value ->
                    RightToLeftByteUtils.getBitAsBool(changedData, bitIndex) shouldBe value
                }
            }
        }
    }
}
