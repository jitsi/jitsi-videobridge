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

package org.jitsi.nlj.rtp.codec.av1

import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.codec.VideoCodecParser
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

class Av1Parser(
    sources: Array<MediaSourceDesc>,
    parentLogger: Logger
) : VideoCodecParser(sources) {
    private val logger = createChildLogger(parentLogger)

    /** Encodings we've actually seen.  Used to clear out inferred-from-signaling encoding information. */
    private val ssrcsSeen = HashSet<Long>()

    override fun parse(packetInfo: PacketInfo) {
        val av1packet = packetInfo.packetAs<Av1packet>()

        ssrcsSeen.add(av1packet.ssrc)
    }
}
