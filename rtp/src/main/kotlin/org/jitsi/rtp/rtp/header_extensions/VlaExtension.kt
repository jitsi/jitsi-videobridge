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
package org.jitsi.rtp.rtp.header_extensions

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.VlaExtension.Stream
import org.jitsi.rtp.util.BitReader

/**
 * A parser for the Video Layers Allocation RTP header extension.
 * https://webrtc.googlesource.com/src/+/refs/heads/main/docs/native-code/rtp-hdrext/video-layers-allocation00
 */
@SuppressFBWarnings("SF_SWITCH_NO_DEFAULT", justification = "False positive")
class VlaExtension {
    companion object {
        fun parse(ext: RtpPacket.HeaderExtension): ParsedVla {
            val empty = ext.dataLengthBytes == 1 && ext.buffer[ext.dataOffset] == 0.toByte()
            if (empty) {
                return emptyList()
            }

            val reader = BitReader(ext.buffer, ext.dataOffset, ext.dataLengthBytes)
            reader.skipBits(2) // RID
            val ns = reader.bits(2) + 1
            val slBm = reader.bits(4)
            val slBms = IntArray(4) { i -> if (i < ns) slBm else 0 }
            if (slBm == 0) {
                slBms[0] = reader.bits(4)
                if (ns > 1) {
                    slBms[1] = reader.bits(4)
                    if (ns > 2) {
                        slBms[2] = reader.bits(4)
                        if (ns > 3) {
                            slBms[3] = reader.bits(4)
                        }
                    }
                }
                if (ns == 1 || ns == 3) {
                    reader.skipBits(4)
                }
            }

            val slCount = slBms.sumOf { it.countOneBits() }
            val tlCountLenBytes = slCount / 4 + if (slCount % 4 != 0) 1 else 0

            val tlCountReader = reader.clone(tlCountLenBytes)
            reader.skipBits(tlCountLenBytes * 8)

            val streams = ArrayList<Stream>(ns)
            (0 until ns).forEach { streamIdx ->
                val spatialLayers = ArrayList<SpatialLayer>()
                val stream = Stream(streamIdx, spatialLayers)
                streams.add(stream)

                (0 until 4).forEach { slIdx ->
                    if ((slBms[streamIdx] and (1 shl slIdx)) != 0) {
                        val targetBitrates = buildList {
                            repeat(tlCountReader.bits(2) + 1) {
                                add(reader.leb128())
                            }
                        }
                        spatialLayers.add(
                            SpatialLayer(
                                slIdx,
                                targetBitrates,
                                null
                            )
                        )
                    }
                }
            }

            (0 until ns).forEach outer@{ streamIdx ->
                (0 until 4).forEach { slIdx ->
                    if ((slBms[streamIdx] and (1 shl slIdx)) != 0) {
                        if (reader.remainingBits() < 40) {
                            return@outer
                        }
                        val sl = streams[streamIdx].spatialLayers[slIdx]
                        sl.res = ResolutionAndFrameRate(
                            reader.bits(16) + 1,
                            reader.bits(16) + 1,
                            reader.bits(8)
                        )
                    }
                }
            }

            return streams
        }
    }

    data class ResolutionAndFrameRate(
        val width: Int,
        val height: Int,
        val maxFramerate: Int
    )

    data class SpatialLayer(
        val id: Int,
        // The target bitrates for each temporal layer in this spatial layer
        val targetBitratesKbps: List<Long>,
        var res: ResolutionAndFrameRate?
    ) {
        override fun toString(): String = "SpatialLayer(id=$id, targetBitratesKbps=$targetBitratesKbps, " +
            "width=${res?.width}, height=${res?.height}, maxFramerate=${res?.maxFramerate})"
    }

    data class Stream(
        val id: Int,
        val spatialLayers: List<SpatialLayer>
    )
}

typealias ParsedVla = List<Stream>
