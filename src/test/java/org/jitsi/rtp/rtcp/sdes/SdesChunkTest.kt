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

package org.jitsi.rtp.rtcp.sdes

import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

internal class SdesChunkTest : ShouldSpec() {

    private val sampleCnameString = "Hello, world"
    private val sampleCnameData = ByteBuffer.wrap(sampleCnameString.toByteArray(StandardCharsets.US_ASCII))
    val sampleCnameSdesItem = CnameSdesItem(sampleCnameString)
    val sampleCnameSdesBuf = ByteBuffer.allocate(2 + sampleCnameData.limit()).apply {
        put(SdesItemType.CNAME.value.toByte())
        put(sampleCnameData.limit().toByte())
        put(sampleCnameData.duplicate())
    }.flip() as ByteBuffer

    val sampleSdesChunkBuf = ByteBuffer.allocate(1024).apply {
        putInt(12345L.toInt())
        put(sampleCnameSdesItem.getBuffer())
        put(EmptySdesItem.getBuffer())
        put(0x00)
    }.flip() as ByteBuffer

    val sampleSdesChunkMultipleItemsBuf = ByteBuffer.allocate(1024).apply {
        putInt(12345L.toInt())
        put(sampleCnameSdesItem.getBuffer())
        put(sampleCnameSdesItem.getBuffer())
        put(EmptySdesItem.getBuffer())
        put(0x00); put(0x00); put(0x00)
    }.flip() as ByteBuffer

    init {
        "Creating an SDES chunk" {
            "from a buffer which has padding" {
                val sdesChunk = SdesChunk.fromBuffer(sampleSdesChunkBuf)
                should("parse all the fields correctly") {
                    sdesChunk.ssrc shouldBe 12345L
                    sdesChunk.sdesItems shouldHaveSize(1)
                    sdesChunk.sdesItems[0] shouldBe sampleCnameSdesItem
                    sdesChunk.sizeBytes shouldBe 20
                }
                should("leave the buffer's position after the parsed data (including the padding)") {
                    sampleSdesChunkBuf.position() shouldBe sampleSdesChunkBuf.limit()
                }
            }
            "from a buffer with multiple SDES items" {
                val sdesChunk = SdesChunk.fromBuffer(sampleSdesChunkMultipleItemsBuf)
                should("parse all items correctly") {
                    sdesChunk.sdesItems shouldHaveSize(2)
                    sdesChunk.sdesItems[0] shouldBe sampleCnameSdesItem
                    sdesChunk.sdesItems[1] shouldBe sampleCnameSdesItem
                    sdesChunk.sizeBytes shouldBe 36
                }
                should("leave the buffer's position after the parsed data (including the padding)") {
                    sampleSdesChunkBuf.position() shouldBe sampleSdesChunkBuf.limit()
                }
            }
            "from values" {
                val sdesChunk = SdesChunk(12345L, listOf(sampleCnameSdesItem))
                should("set the values correctly") {
                    sdesChunk.ssrc shouldBe 12345L
                    sdesChunk.sdesItems shouldHaveSize(1)
                    sdesChunk.sdesItems[0] shouldBe sampleCnameSdesItem
                    sdesChunk.sizeBytes shouldBe 20
                }
            }
        }
    }
}
