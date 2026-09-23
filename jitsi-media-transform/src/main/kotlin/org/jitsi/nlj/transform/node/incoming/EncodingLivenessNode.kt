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
import org.jitsi.nlj.rtcp.KeyframeModeDetector
import org.jitsi.nlj.rtp.ParsedVideoPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Records each media packet on the liveness tracker of its encoding, so that the encoding knows whether it is being
 * sent; see [org.jitsi.nlj.EncodingLivenessTracker]. This node runs after padding termination, so every packet it
 * sees is media. A packet with no known layer is still evidence that its encoding is being sent, so the node does
 * not need the layer lookup. Only packets of an encoding's primary SSRC count. A packet of a secondary SSRC, such as
 * FEC, has a sequence number and timestamp space of its own, and is not the encoding being sent.
 *
 * The node also tells the [KeyframeModeDetector] about each keyframe which starts arriving, and about each packet
 * ahead of recording it, so that the detector can read the encodings' last packet times as of an observation's
 * deadline.
 */
class EncodingLivenessNode(
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) : ObserverNode("Encoding liveness") {
    private val logger = createChildLogger(parentLogger)

    /** The encoding each media SSRC of the media sources belongs to, for one lookup per packet. */
    @Volatile
    private var encodingsBySsrc: Map<Long, SourceEncoding> = emptyMap()

    /** Told when a keyframe starts arriving on each SSRC, to learn how the sender answers keyframe requests. */
    @Volatile
    private var keyframeModeDetector: KeyframeModeDetector? = null

    /**
     * The RTP timestamp of the most recent keyframe seen on each SSRC. VP9 marks every spatial layer's packets of a
     * keyframe, and a keyframe may span many packets, so this is how one keyframe is told from the next.
     */
    private val lastKeyframeTimestamps = ConcurrentHashMap<Long, Long>()

    override fun observe(packetInfo: PacketInfo) {
        val packet = packetInfo.packetAs<VideoRtpPacket>()
        val now = clock.millis()

        /* Before the packet is recorded on its encoding: the detector closes an observation which is due, and reads
         * the encodings' last packet times as of then. */
        val detector = keyframeModeDetector
        detector?.onPacketObserved(now)

        val encoding = encodingsBySsrc[packet.ssrc]?.encoding ?: return
        if (packet.ssrc == encoding.primarySSRC) {
            encoding.liveness.onPacketReceived(now, packet.sequenceNumber, packet.timestamp)
        }

        if (detector != null) {
            val isKeyframe = (packet as? ParsedVideoPacket)?.isKeyframe ?: false
            if (isKeyframe && lastKeyframeTimestamps[packet.ssrc] != packet.timestamp) {
                /* The first packet of this keyframe; only it writes the map, on the ingress path. */
                lastKeyframeTimestamps[packet.ssrc] = packet.timestamp
                detector.onKeyframeObserved(packet.ssrc, now)
            }
        }
    }

    /** Sets the [KeyframeModeDetector], which this node tells about the keyframes arriving on the sources' SSRCs. */
    fun setKeyframeModeDetector(detector: KeyframeModeDetector) {
        keyframeModeDetector = detector
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaSourcesEvent -> {
                encodingsBySsrc = event.mediaSourceDescs.indexEncodingsBySsrc()
                lastKeyframeTimestamps.keys.retainAll(encodingsBySsrc.values.map { it.encoding.primarySSRC }.toSet())
                logger.cdebug { "Encoding liveness node got media sources:\n${event.mediaSourceDescs.joinToString()}" }
            }
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
