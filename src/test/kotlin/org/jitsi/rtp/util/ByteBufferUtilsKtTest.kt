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

import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec

internal class ByteBufferUtilsKtTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        context("byteBufferOf(Any)") {
            should("work correctly with values that can be converted to byte") {
                val buf = byteBufferOf(0x00, 0x01, 0xFF, 0xFE)
                buf.get(0) shouldBe 0x00.toByte()
                buf.get(1) shouldBe 0x01.toByte()
                buf.get(2) shouldBe 0xFF.toByte()
                buf.get(3) shouldBe 0xFE.toByte()
            }
        }
    }
}
