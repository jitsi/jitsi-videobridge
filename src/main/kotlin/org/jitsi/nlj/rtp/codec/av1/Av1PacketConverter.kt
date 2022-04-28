/*
 * Copyright @ 2023 - present 8x8, Inc.
 * Copyright @ 2023 - Vowel, Inc.
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

package org.jitsi.nlj.rtp.codec.av1

import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.rtp.codec.av1.dd.DependencyDescriptorReader
import org.jitsi.nlj.rtp.codec.av1.dd.FrameDependencyStructure
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.rtp.RtpPacket
import java.util.concurrent.ConcurrentHashMap

class Av1PacketConverter(val streamInformationStore: ReadOnlyStreamInformationStore) {
    private var ddExtId: Int? = null
    private var structures = ConcurrentHashMap<Long, FrameDependencyStructure>()

    init {
        streamInformationStore.onRtpExtensionMapping(RtpExtensionType.AV1_DEPENDENCY_DESCRIPTOR) {
            ddExtId = it
        }
    }

    fun parse(rtpPacket: RtpPacket): Av1packet {
        val ssrc = rtpPacket.ssrc
        val lastStructure = structures[ssrc]

        val extId = checkNotNull(ddExtId) { "missing dd ext id" }
        val ddExt = checkNotNull(rtpPacket.getHeaderExtension(extId)) { "missing dd ext from $rtpPacket" }
        val (descriptor, structure) = DependencyDescriptorReader(ddExt, lastStructure).parse()

        structures[ssrc] = structure

        return Av1packet(
            rtpPacket.buffer,
            rtpPacket.offset,
            rtpPacket.length,
            isKeyframe = descriptor.isKeyFrame(),
            isStartOfFrame = descriptor.firstPacketInFrame,
            isEndOfFrame = descriptor.lastPacketInFrame,
            frameNumber = descriptor.frameNumber,
            temporalLayerIndex = descriptor.frameDependencies!!.temporalId,
            spatialLayerIndex = descriptor.frameDependencies!!.spatialId
        )
    }
}
