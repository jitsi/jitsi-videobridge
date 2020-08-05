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

package org.jitsi.rtp.extensions.unsigned

import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec

internal class UnsignedKtTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        context("converting a byte to a positive int") {
            should("convert correctly") {
                val byteSizedInts = listOf(0, 1, 63, 127, 128, 244, 255)
                byteSizedInts.forEach { byteSizedInt ->
                    val byte = byteSizedInt.toByte()
                    val convertedInt = byte.toPositiveInt()
                    convertedInt shouldBe byteSizedInt
                }
            }
        }
        context("converting a short to a positive int") {
            val shortSizedInts = listOf(0, 1, 63, 127, 65530, 65535)
            shortSizedInts.forEach { shortSizedInt ->
                val short = shortSizedInt.toShort()
                val convertedInt = short.toPositiveInt()
                convertedInt shouldBe shortSizedInt
            }
        }
        context("converting an int to a positive long") {
            val intSizedLongs = listOf(0, 1, 63, 127, 2294967195, 4294967295)
            intSizedLongs.forEach { intSizedLong ->
                val int = intSizedLong.toInt()
                val convertedLong = int.toPositiveLong()
                convertedLong shouldBe intSizedLong
            }
        }
    }
}
