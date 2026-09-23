/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.SourceEncoding
import org.jitsi.nlj.indexEncodingsBySsrc
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import java.time.Clock

/**
 * Records each media packet on the liveness tracker of its encoding, so that the encoding knows whether it is being
 * sent; see [org.jitsi.nlj.EncodingLivenessTracker]. This node runs after padding termination, so every packet it
 * sees is media. A packet with no known layer is still evidence that its encoding is being sent, so the node does
 * not need the layer lookup. Only packets of an encoding's primary SSRC count. A packet of a secondary SSRC, such as
 * FEC, has a sequence number and timestamp space of its own, and is not the encoding being sent.
 */
class EncodingLivenessNode(
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) : ObserverNode("Encoding liveness") {
    private val logger = createChildLogger(parentLogger)

    /** The encoding each media SSRC of the media sources belongs to, for one lookup per packet. */
    @Volatile
    private var encodingsBySsrc: Map<Long, SourceEncoding> = emptyMap()

    override fun observe(packetInfo: PacketInfo) {
        val packet = packetInfo.packetAs<VideoRtpPacket>()
        val now = clock.millis()
        val encoding = encodingsBySsrc[packet.ssrc]?.encoding ?: return
        if (packet.ssrc == encoding.primarySSRC) {
            encoding.liveness.onPacketReceived(now, packet.sequenceNumber, packet.timestamp)
        }
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaSourcesEvent -> {
                encodingsBySsrc = event.mediaSourceDescs.indexEncodingsBySsrc()
                logger.cdebug { "Encoding liveness node got media sources:\n${event.mediaSourceDescs.joinToString()}" }
            }
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
