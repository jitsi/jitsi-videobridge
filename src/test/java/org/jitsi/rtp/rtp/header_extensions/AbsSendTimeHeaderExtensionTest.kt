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

package org.jitsi.rtp.rtp.header_extensions

import io.kotlintest.data.forall
import io.kotlintest.should
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.tables.row
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.util.byteBufferOf
import org.jitsi.test_helpers.matchers.haveSameContentAs
import java.nio.ByteBuffer

class AbsSendTimeHeaderExtensionTest : ShouldSpec() {

    init {
        "Creating an AbsSendTimeHeaderExtension" {
            "from values" {
                forall(
                    row(-4849508876619215140, byteBufferOf(0xFD, 0x85, 0xED)),
                    row(-2701414392983929778, byteBufferOf(0xFC, 0x10, 0x75)),
                    row(5157639288397355688, byteBufferOf(0xE1, 0x96, 0xE4)),
                    row(9097771050361383908, byteBufferOf(0xA9, 0x72, 0x0E)),
                    row(-7756641245299364717, byteBufferOf(0xFE, 0xCD, 0x74)),
                    row(601512607291034270, byteBufferOf(0x7D, 0x2A, 0x04)),
                    row(-4958796559658684375, byteBufferOf(0xFD, 0x5D, 0x82)),
                    row(-4421090035510677421, byteBufferOf(0xFD, 0xF5, 0x11)),
                    row(-5591303715162993432, byteBufferOf(0xFF, 0x59, 0x19)),
                    row(-3528501572568025269, byteBufferOf(0xFD, 0xBA, 0x58))
                ) { timestamp: Long, expectedData: ByteBuffer ->
                    val abs = AbsSendTimeHeaderExtension(1, timestamp)
                    val buf = ByteBuffer.allocate(abs.sizeBytes)
                    abs.serializeToAs(abs.type, buf)
                    buf.subBuffer(1) should haveSameContentAs(expectedData)
                }
            }
        }
    }

}