/*
 * Copyright @ 2024-Present 8x8, Inc
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
package org.jitsi.rtp.extensions

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.rtp.rtp.RtpPacket.HeaderExtension
import org.jitsi.rtp.rtp.header_extensions.ParsedVla
import org.jitsi.rtp.rtp.header_extensions.VlaExtension
import org.jitsi.rtp.rtp.header_extensions.VlaExtension.ResolutionAndFrameRate
import org.jitsi.rtp.rtp.header_extensions.VlaExtension.SpatialLayer
import org.jitsi.rtp.rtp.header_extensions.VlaExtension.Stream

@Suppress("ktlint:standard:no-multi-spaces")
class VlaExtensionTest : ShouldSpec() {
    init {
        context("Empty") {
            parse(0x00) shouldBe emptyList()
        }
        context("VP8 single stream (with resolution)") {
            parse(
                0b0000_0001,
                0b1000_0000.toByte(),
                0b0101_0000,
                0b0111_1000,
                0b1100_1000.toByte(),
                0b0000_0001,
                0b0000_0001,
                0b0011_1111,
                0b0000_0000,
                0b1011_0011.toByte(),
                0b0010_0001
            ) shouldBe listOf(
                Stream(
                    0,
                    listOf(
                        SpatialLayer(
                            0,
                            listOf(80, 120, 200),
                            ResolutionAndFrameRate(320, 180, 33)
                        )
                    )
                )
            )
        }
        context("VP8 single stream (without resolution)") {
            parse(
                0b0000_0001,
                0b1000_0000.toByte(),
                0b0101_0000,
                0b0111_1000,
                0b1100_1000.toByte(),
                0b0000_0001
            ) shouldBe listOf(
                Stream(
                    0,
                    listOf(
                        SpatialLayer(
                            0,
                            listOf(80, 120, 200),
                            null
                        )
                    )
                )
            )
        }
        context("VP8 simulcast stream (with resolutions)") {
            parse(
                0b0010_0001,
                0b1010_1000.toByte(),
                0b0011_1100,
                0b0101_1010,
                0b1001_0110.toByte(),
                0b0000_0001,
                0b1100_1000.toByte(),
                0b0000_0001,
                0b1010_1100.toByte(),
                0b0000_0010,
                0b1111_0100.toByte(),
                0b0000_0011,
                0b1110_1110.toByte(),
                0b0000_0011,
                0b1110_0110.toByte(),
                0b0000_0101,
                0b1101_0100.toByte(),
                0b0000_1001,
                0b0000_0001,
                0b0011_1111,
                0b0000_0000,
                0b1011_0011.toByte(),
                0b0001_1111,
                0b0000_0010,
                0b0111_1111,
                0b0000_0001,
                0b0110_0111,
                0b0001_1111,
                0b0000_0100,
                0b1111_1111.toByte(),
                0b0000_0010,
                0b1100_1111.toByte(),
                0b0001_1111
            ) shouldBe listOf(
                Stream(
                    0,
                    listOf(
                        SpatialLayer(
                            0,
                            listOf(60, 90, 150),
                            ResolutionAndFrameRate(320, 180, 31)
                        )
                    )
                ),
                Stream(
                    1,
                    listOf(
                        SpatialLayer(
                            0,
                            listOf(200, 300, 500),
                            ResolutionAndFrameRate(640, 360, 31)
                        )
                    )
                ),
                Stream(
                    2,
                    listOf(
                        SpatialLayer(
                            0,
                            listOf(494, 742, 1236),
                            ResolutionAndFrameRate(1280, 720, 31)
                        )
                    )
                )
            )
        }
        context("VP8 simulcast stream (without resolutions)") {
            parse(
                0b1010_0001.toByte(),
                0b1010_1000.toByte(),
                0b1001_0101.toByte(),
                0b0000_0010,
                0b1001_1111.toByte(),
                0b0000_0011,
                0b1011_0100.toByte(),
                0b0000_0101,
                0b1000_1101.toByte(),
                0b0000_1001,
                0b1101_0011.toByte(),
                0b0000_1101,
                0b1110_0000.toByte(),
                0b0001_0110,
                0b1101_0000.toByte(),
                0b0000_1111,
                0b1011_1000.toByte(),
                0b0001_0111,
                0b1000_1000.toByte(),
                0b0010_0111
            ) shouldBe listOf(
                Stream(
                    0,
                    listOf(
                        SpatialLayer(
                            0,
                            listOf(277, 415, 692),
                            null
                        )
                    )
                ),
                Stream(
                    1,
                    listOf(
                        SpatialLayer(
                            0,
                            listOf(1165, 1747, 2912),
                            null
                        )
                    )
                ),
                Stream(
                    2,
                    listOf(
                        SpatialLayer(
                            0,
                            listOf(2000, 3000, 5000),
                            null
                        )
                    )
                )
            )
        }
        context("VP9 SVC (with resolutions)") {
            parse(
                0b0000_0111,
                0b1010_1000.toByte(),
                0b0100_1101,
                0b0110_0100,
                0b1000_1110.toByte(),
                0b0000_0001,
                0b1101_0111.toByte(),
                0b0000_0001,
                0b1001_0111.toByte(),
                0b0000_0010,
                0b1000_1101.toByte(),
                0b0000_0011,
                0b1101_0110.toByte(),
                0b0000_0010,
                0b1011_1101.toByte(),
                0b0000_0011,
                0b1111_1001.toByte(),
                0b0000_0100,
                0b0000_0001,
                0b0011_1111,
                0b0000_0000,
                0b1011_0011.toByte(),
                0b0010_0000,
                0b0000_0010,
                0b0111_1111,
                0b0000_0001,
                0b0110_0111,
                0b0010_0000,
                0b0000_0100,
                0b1111_1111.toByte(),
                0b0000_0010,
                0b1100_1111.toByte(),
                0b0010_0000
            ) shouldBe listOf(
                Stream(
                    0,
                    listOf(
                        SpatialLayer(
                            0,
                            listOf(77, 100, 142),
                            ResolutionAndFrameRate(320, 180, 32)
                        ),
                        SpatialLayer(
                            1,
                            listOf(215, 279, 397),
                            ResolutionAndFrameRate(640, 360, 32)
                        ),
                        SpatialLayer(
                            2,
                            listOf(342, 445, 633),
                            ResolutionAndFrameRate(1280, 720, 32)
                        )
                    )
                )
            )
        }
        context("VP9 SVC (without resolutions or TLs)") {
            parse(
                0b0000_0111,
                0b0000_0000,
                0b1001_0110.toByte(),
                0b0000_0001,
                0b1111_0100.toByte(),
                0b0000_0011,
                0b1010_1010.toByte(),
                0b0000_1011
            ) shouldBe listOf(
                Stream(
                    0,
                    listOf(
                        SpatialLayer(
                            0,
                            listOf(150),
                            null
                        ),
                        SpatialLayer(
                            1,
                            listOf(500),
                            null
                        ),
                        SpatialLayer(
                            2,
                            listOf(1450),
                            null
                        )
                    )
                )
            )
        }
        context("Invalid VLAs") {
            context("Resolution incomplete") {
                // Cuts short with not enough bytes to contain the resolution. We succeed with no resolution.
                parse(
                    0b0000_0001,
                    0b1000_0000.toByte(), // #tls
                    0b0101_0000,          // targetBitrate 1
                    0b0111_1000,          // targetBitrate 2
                    0b1100_1000.toByte(), // targetBitrate 3
                    0b0000_0001,          // targetBitrate 3
                    0b0000_0001,          // width
                    0b0011_1111,          // width
                    0b0000_0000,          // height
                    0b1011_0011.toByte(), // height
                    // 0b0010_0001        // maxFramerate
                ) shouldBe listOf(
                    Stream(
                        0,
                        listOf(
                            SpatialLayer(
                                0,
                                listOf(80, 120, 200),
                                null
                            )
                        )
                    )
                )
            }
            context("Invalid leb128") {
                // Cuts short in the middle of one of the leb128 encoding of one of the target bitrates.
                shouldThrow<IllegalStateException> {
                    parse(
                        0b0000_0001,
                        0b1000_0000.toByte(), // #tls
                        0b0101_0000,          // targetBitrate 1
                        0b0111_1000,          // targetBitrate 2
                        0b1100_1000.toByte(), // targetBitrate 3
                        // 0b0000_0001,       // targetBitrate 3
                    )
                }
            }
            context("Missing target bitrates") {
                // Does not contain the expected number of target bitrates
                shouldThrow<IllegalStateException> {
                    parse(
                        0b0000_0001,          // 1 spatial layer
                        0b1000_0000.toByte(), // 3 temporal layers
                        0b0101_0000,          // targetBitrate 1
                        0b1100_1000.toByte(), // targetBitrate 2
                        0b0000_0001,          // targetBitrate 2
                        // 0b0111_1000,       // targetBitrate 3
                    )
                }
            }
            context("Missing temporal layer counts") {
                // Does not contain the expected temporal layer counts
                shouldThrow<IllegalStateException> {
                    parse(
                        0b0001_0000,          // spatial layer bitmask in the next byte, 2 streams
                        0b1111_1111.toByte(), // 4 spatial layers for each stream
                        0b0000_0000.toByte(), // 1 temporal layer for each spatial layer of stream 1, more must follow
                    )
                }
            }
        }
    }
}

private fun parse(vararg bytes: Byte): ParsedVla = VlaExtension.parse(RawHeaderExtension(bytes))

@SuppressFBWarnings("CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE")
class RawHeaderExtension(override val buffer: ByteArray) : HeaderExtension {
    override val dataOffset: Int = 0
    override var id: Int = 0
    override val dataLengthBytes: Int = buffer.size
    override val totalLengthBytes: Int = buffer.size
}
