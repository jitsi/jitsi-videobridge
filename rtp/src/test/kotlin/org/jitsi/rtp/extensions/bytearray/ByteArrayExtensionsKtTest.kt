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

package org.jitsi.rtp.extensions.bytearray

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.data.forAll
import io.kotest.data.row
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.jitsi.test_helpers.matchers.haveSameContentAs

class ByteArrayExtensionsKtTest : ShouldSpec() {
    init {
        context("ByteArray.getShort/putShort") {
            should("parse the short correctly") {
                forAll(
                    row(byteArrayOf(0x00, 0x00), 0.toShort()),
                    row(byteArrayOf(0xFF, 0xFF), 65535.toShort())
                ) { buf, expectedShort ->
                    buf.getShort(0) shouldBe expectedShort

                    val array = ByteArray(2)
                    array.putShort(0, expectedShort)
                    array should haveSameContentAs(buf)
                }
            }
        }
        context("ByteArray.getInt/putInt") {
            should("parse the short correctly") {
                forAll(
                    row(byteArrayOf(0x00, 0x00, 0x00, 0x00), 0.toInt()),
                    row(byteArrayOf(0xFF, 0xFF, 0xFF, 0xFF), 4294967295.toInt())
                ) { buf, expectedInt ->
                    buf.getInt(0) shouldBe expectedInt

                    val array = ByteArray(4)
                    array.putInt(0, expectedInt)
                    array should haveSameContentAs(buf)
                }
            }
        }
    }
}
