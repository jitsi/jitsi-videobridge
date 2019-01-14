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

package org.jitsi.rtp.rtcp

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.clone
import org.jitsi.rtp.extensions.compareToFromBeginning
import org.jitsi.rtp.util.byteBufferOf
import toUInt
import java.nio.ByteBuffer

/**
 * Returns [num] but aligned to the next [alignment] amount
 */
fun align(num: Int, alignment: Int = 4): Int {
    var aligned = num
    while (aligned % alignment != 0) {
        aligned++
    }
    return aligned
}

internal class SdesChunkTest : ShouldSpec() {
    init {
        "Creating an SDES chunk" {
            val ssrc = 3828749302
            val cname = "user@domain.com"
            val sdesItem = CnameSdesItem(cname)
            "from a buffer" {
                val sdesChunkBuf = ByteBuffer.allocate(align(4 + sdesItem.size + SdesItem.EMPTY_ITEM.size))
                sdesChunkBuf.putInt(ssrc.toUInt())
                sdesChunkBuf.put(sdesItem.getBuffer())
                sdesChunkBuf.put(SdesItem.EMPTY_ITEM.getBuffer())
                sdesChunkBuf.rewind()
                val originalBuf = sdesChunkBuf.clone()

                val sdesChunk = SdesChunk(sdesChunkBuf)
                should("parse all fields correctly") {
                    sdesChunk.ssrc shouldBe ssrc
                    // The empty item should not be present in the SDES items
                    sdesChunk.sdesItems.size shouldBe 1
                    sdesChunk.sdesItems[0] shouldBe sdesItem
                }

                should("have the correct size") {
                    // We already calculated the expected size (which includes space for the empty SDES item and
                    //  padding) when allocating the original buffer
                    sdesChunk.size shouldBe sdesChunkBuf.limit()
                }

                should("not have changed the buffer position") {
                    sdesChunkBuf.position() shouldBe 0
                }

                "and then serializing it" {
                    val buf = sdesChunk.getBuffer()
                    should("write all the data correctly") {
                        buf.compareToFromBeginning(originalBuf) shouldBe 0
                    }
                }
            }
        }
    }
}
