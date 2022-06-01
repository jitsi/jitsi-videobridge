/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.findRtpLayerDesc
import java.util.concurrent.atomic.AtomicInteger

/**
 * Set video packets' quality layer info
 */
class VideoQualityLayerLookup(
    parentLogger: Logger
) : TransformerNode("Video quality layer lookup") {
    private val logger = createChildLogger(parentLogger)
    private var sources: Array<MediaSourceDesc> = arrayOf()
    private val numPacketsDroppedNoEncoding = AtomicInteger()

    /* TODO: combine this with VideoBitrateCalculator? They both do findRtpLayerDesc. */
    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val videoPacket = packetInfo.packetAs<VideoRtpPacket>()
        val encodingDesc = sources.findRtpLayerDesc(videoPacket) ?: run {
            logger.warn(
                "Unable to find encoding matching packet! packet=$videoPacket; " +
                    "sources=${sources.joinToString(separator = "\n")}"
            )
            numPacketsDroppedNoEncoding.incrementAndGet()
            return null
        }
        videoPacket.qualityIndex = encodingDesc.index

        return packetInfo
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaSourcesEvent -> {
                sources = event.mediaSourceDescs
            }
        }
        super.handleEvent(event)
    }

    override fun trace(f: () -> Unit) = f.invoke()

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_packets_dropped_no_encoding", numPacketsDroppedNoEncoding.get())
        }
    }
}
