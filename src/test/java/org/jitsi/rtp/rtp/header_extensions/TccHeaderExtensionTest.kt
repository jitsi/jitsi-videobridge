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

import io.kotlintest.should
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.shouldBe
import org.jitsi.rtp.extensions.plus
import org.jitsi.rtp.util.byteBufferOf
import org.jitsi.test_helpers.matchers.haveSameContentAs
import java.nio.ByteBuffer

class TccHeaderExtensionTest : ShouldSpec() {

    val tccSeqNumData = byteBufferOf(0x00, 0x42)
    val tccExtensionInfo = OneByteExtensionInfo(
        5,
        tccSeqNumData,
        byteBufferOf(idLengthByte(5, 1)) + tccSeqNumData
    )

    val unparsedTccExtension = UnparsedHeaderExtension.fromBuffer(tccExtensionInfo.expectedOneByteBuffer, tccExtensionInfo.type)

    init {
        "Creating a TCC header extension" {
            "from an UnparsedHeaderExtension" {
                val tccExt = TccHeaderExtension.fromUnparsed(unparsedTccExtension)
                should("parse the values correctly") {
                    tccExt.tccSeqNum shouldBe 0x42
                    tccExt.type shouldBe HeaderExtensionType.ONE_BYTE_HEADER_EXT
                }
                "and then serializing it" {
                    "as a one byte extension" {
                        val buf = ByteBuffer.allocate(tccExt.sizeBytesAs(HeaderExtensionType.ONE_BYTE_HEADER_EXT))
                        tccExt.serializeToAs(HeaderExtensionType.ONE_BYTE_HEADER_EXT, buf)
                        should("write the data correctly") {
                            buf should haveSameContentAs(tccExtensionInfo.expectedOneByteBuffer)
                        }
                    }
                }
            }
        }
    }
}