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

package org.jitsi.nlj.rtp.codec.vp9

import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.rtp.ParsedVideoPacket
import org.jitsi.rtp.extensions.bytearray.hashCodeOfSegment
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.logging2.cwarn
import org.jitsi_modified.impl.neomedia.codec.video.vp9.DePacketizer

/**
 * If this [Vp9Packet] instance is being created via a clone,
 * we've already parsed the packet itself and determined whether
 * or not its a keyframe and what its spatial layer index is,
 * so the constructor allows passing in those values if
 * they're already known.  If they're null, this instance
 * will do the parsing itself.
 */
class Vp9Packet private constructor (
    buffer: ByteArray,
    offset: Int,
    length: Int,
    isKeyframe: Boolean?,
    isStartOfFrame: Boolean?,
    isEndOfFrame: Boolean?,
    encodingIndex: Int?,
    pictureId: Int?,
    TL0PICIDX: Int?
) : ParsedVideoPacket(buffer, offset, length, encodingIndex) {

    constructor(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ) : this(
        buffer, offset, length,
        isKeyframe = null,
        isStartOfFrame = null,
        isEndOfFrame = null,
        encodingIndex = null,
        pictureId = null,
        TL0PICIDX = null
    )

    override val isKeyframe: Boolean =
        isKeyframe ?: DePacketizer.VP9PayloadDescriptor.isKeyFrame(this.buffer, payloadOffset, payloadLength)

    override val isStartOfFrame: Boolean =
        isStartOfFrame ?: DePacketizer.VP9PayloadDescriptor.isStartOfFrame(buffer, payloadOffset, payloadLength)

    override val isEndOfFrame: Boolean =
        isEndOfFrame ?: DePacketizer.VP9PayloadDescriptor.isEndOfFrame(buffer, payloadOffset, payloadLength)

    override val layerId: Int
        get() = if (hasLayerIndices) RtpLayerDesc.getIndex(0, spatialLayerIndex, temporalLayerIndex) else super.layerId

    /** End of VP9 picture is the marker bit. Note frame/picture distinction. */
    /* TODO: not sure this should be the override from [ParsedVideoPacket] */
    val isEndOfPicture: Boolean
        /** This uses [get] rather than initialization because [isMarked] is a var. */
        get() = isMarked

    val hasLayerIndices =
        DePacketizer.VP9PayloadDescriptor.hasLayerIndices(buffer, payloadOffset, payloadLength)

    val hasPictureId =
        DePacketizer.VP9PayloadDescriptor.hasPictureId(buffer, payloadOffset, payloadLength)

    val hasExtendedPictureId =
        DePacketizer.VP9PayloadDescriptor.hasExtendedPictureId(buffer, payloadOffset, payloadLength)

    val hasScalabilityStructure =
        DePacketizer.VP9PayloadDescriptor.hasScalabilityStructure(buffer, payloadOffset, payloadLength)

    val isUpperLevelReference =
        DePacketizer.VP9PayloadDescriptor.isUpperLevelReference(buffer, payloadOffset, payloadLength)

    val isInterPicturePredicted =
        DePacketizer.VP9PayloadDescriptor.isInterPicturePredicted(buffer, payloadOffset, payloadLength)

    private var _TL0PICIDX =
        TL0PICIDX ?: DePacketizer.VP9PayloadDescriptor.getTL0PICIDX(buffer, payloadOffset, payloadLength)

    var TL0PICIDX: Int
        get() = _TL0PICIDX
        set(newValue) {
            if (newValue != -1 && !DePacketizer.VP9PayloadDescriptor.setTL0PICIDX(
                    buffer, payloadOffset, payloadLength, newValue
                )
            ) {
                logger.cwarn { "Failed to set the TL0PICIDX of a VP9 packet." }
            }
            _TL0PICIDX = newValue
        }

    val hasTL0PICIDX: Boolean
        get() = _TL0PICIDX != -1

    private var _pictureId =
        pictureId ?: DePacketizer.VP9PayloadDescriptor.getPictureId(buffer, payloadOffset, payloadLength)

    var pictureId: Int
        get() = _pictureId
        set(newValue) {
            if (!DePacketizer.VP9PayloadDescriptor.setExtendedPictureId(
                    buffer, payloadOffset, payloadLength, newValue
                )
            ) {
                logger.cwarn { "Failed to set the picture id of a VP9 packet." }
            }
            _pictureId = newValue
        }

    /* TODO: avoid recomputing these on clone */

    val temporalLayerIndex: Int =
        DePacketizer.VP9PayloadDescriptor.getTemporalLayerIndex(buffer, payloadOffset, payloadLength)

    val spatialLayerIndex: Int =
        DePacketizer.VP9PayloadDescriptor.getSpatialLayerIndex(buffer, payloadOffset, payloadLength)

    val effectiveTemporalLayerIndex: Int =
        if (hasLayerIndices) temporalLayerIndex else 0

    val effectiveSpatialLayerIndex: Int =
        if (hasLayerIndices) spatialLayerIndex else 0

    val isSwitchingUpPoint: Boolean =
        DePacketizer.VP9PayloadDescriptor.isSwitchingUpPoint(buffer, payloadOffset, payloadLength)

    val usesInterLayerDependency: Boolean =
        DePacketizer.VP9PayloadDescriptor.usesInterLayerDependency(buffer, payloadOffset, payloadLength)

    fun getScalabilityStructure(
        eid: Int = 0,
        baseFrameRate: Double = 30.0
    ) = Companion.getScalabilityStructure(buffer, payloadOffset, payloadLength, ssrc, eid, baseFrameRate)

    val scalabilityStructureNumSpatial: Int
        get() {
            val off =
                DePacketizer.VP9PayloadDescriptor.getScalabilityStructureOffset(buffer, payloadOffset, payloadLength)
            if (off == -1) {
                return -1
            }
            val ssHeader = buffer[off].toInt()

            return ((ssHeader and 0xE0) shr 5) + 1
        }

    /**
     * For [Vp9Packet] the payload excludes the VP9 Payload Descriptor.
     */
    override val payloadVerification: String
        get() {
            val rtpPayloadLength = payloadLength
            val rtpPayloadOffset = payloadOffset
            val vp9pdSize = DePacketizer.VP9PayloadDescriptor.getSize(buffer, rtpPayloadOffset, rtpPayloadLength)
            val vp9PayloadLength = rtpPayloadLength - vp9pdSize
            val hashCode = buffer.hashCodeOfSegment(rtpPayloadOffset + vp9pdSize, rtpPayloadOffset + rtpPayloadLength)
            return "type=VP9Packet len=$vp9PayloadLength hashCode=$hashCode"
        }

    override fun toString(): String {
        return super.toString() + ", SID=$spatialLayerIndex, TID=$temporalLayerIndex"
    }

    override fun clone(): Vp9Packet {
        return Vp9Packet(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length,
            isKeyframe = isKeyframe,
            isStartOfFrame = isStartOfFrame,
            isEndOfFrame = isEndOfFrame,
            encodingIndex = qualityIndex,
            pictureId = pictureId,
            TL0PICIDX = TL0PICIDX
        )
    }

    companion object {
        private val logger = createLogger()

        private val Y_BIT = 1 shl 4
        private val G_BIT = 1 shl 3

        private val MAX_NUM_TLAYERS = 8

        /* In theory this would fit better in vp9.DePacketizer, but I don't feel like translating this code
         * to Java, or translating that file to Kotlin.
         */
        fun getScalabilityStructure(
            buffer: ByteArray,
            payloadOffset: Int,
            payloadLength: Int,
            ssrc: Long,
            eid: Int,
            baseFrameRate: Double
        ): RtpEncodingDesc? {
            /*
             * VP9 Scalability structure:
             *
             *      +-+-+-+-+-+-+-+-+
             * V:   | N_S |Y|G|-|-|-|
             *      +-+-+-+-+-+-+-+-+              -\
             * Y:   |     WIDTH     | (OPTIONAL)    .
             *      +               +               .
             *      |               | (OPTIONAL)    .
             *      +-+-+-+-+-+-+-+-+               . - N_S + 1 times
             *      |     HEIGHT    | (OPTIONAL)    .
             *      +               +               .
             *      |               | (OPTIONAL)    .
             *      +-+-+-+-+-+-+-+-+              -/
             * G:   |      N_G      | (OPTIONAL)
             *      +-+-+-+-+-+-+-+-+                            -\
             * N_G: | TID |U| R |-|-| (OPTIONAL)                 .
             *      +-+-+-+-+-+-+-+-+              -\            . - N_G times
             *      |    P_DIFF     | (OPTIONAL)    . - R times  .
             *      +-+-+-+-+-+-+-+-+              -/            -/
             */

            var off = DePacketizer.VP9PayloadDescriptor.getScalabilityStructureOffset(
                buffer, payloadOffset, payloadLength
            )
            if (off == -1) {
                return null
            }
            val ssHeader = buffer[off].toInt()

            val numSpatial = ((ssHeader and 0xE0) shr 5) + 1
            val hasResolution = (ssHeader and Y_BIT) != 0
            val hasPictureGroup = (ssHeader and G_BIT) != 0

            off++

            val heights = if (hasResolution) {
                Array(numSpatial) {
                    off += 2 // We only record height, not width

                    val height = ((buffer[off].toInt() and 0xff) shl 8) or
                        (buffer[off + 1].toInt() and 0xff)
                    off += 2
                    height
                }
            } else {
                Array(numSpatial) { RtpLayerDesc.NO_HEIGHT }
            }

            val tlCounts = Array(MAX_NUM_TLAYERS) { 0 }
            val groupSize = if (hasPictureGroup) {
                val groupSize = buffer[off].toInt()
                off++

                for (i in 0 until groupSize) {
                    val descByte = buffer[off].toInt()
                    val tid = (descByte and 0xE0) shr 5
                    val numRefs = (descByte and 0x0C) shr 2
                    off++

                    tlCounts[tid]++

                    /* TODO: do something with U, R, P_DIFF? */
                    for (j in 0 until numRefs) {
                        off++
                    }
                }
                groupSize
            } else {
                tlCounts[0] = 1
                1
            }

            val numTemporal = tlCounts.indexOfLast { it > 0 } + 1

            for (t in 1 until numTemporal) {
                /* Sum up frames per picture group */
                tlCounts[t] += tlCounts[t - 1]
            }

            val layers = ArrayList<RtpLayerDesc>()

            for (s in 0 until numSpatial) {
                for (t in 0 until numTemporal) {
                    val dependencies = ArrayList<RtpLayerDesc>()
                    val softDependencies = ArrayList<RtpLayerDesc>()
                    if (s > 0) {
                        /* Because of K-SVC, spatial layer dependencies are soft */
                        layers.find { it.sid == s - 1 && it.tid == t }?.let { softDependencies.add(it) }
                    }
                    if (t > 0) {
                        layers.find { it.sid == s && it.tid == t - 1 }?.let { dependencies.add(it) }
                    }
                    val layerDesc = RtpLayerDesc(
                        eid = eid,
                        tid = t,
                        sid = s,
                        height = heights[s],
                        frameRate = if (hasPictureGroup) {
                            baseFrameRate * tlCounts[t] / groupSize
                        } else {
                            RtpLayerDesc.NO_FRAME_RATE
                        },
                        dependencyLayers = dependencies.toArray(arrayOf<RtpLayerDesc>()),
                        softDependencyLayers = softDependencies.toArray(arrayOf<RtpLayerDesc>())
                    )
                    layers.add(layerDesc)
                }
            }

            return RtpEncodingDesc(ssrc, layers.toArray(arrayOf()))
        }
    }
}
