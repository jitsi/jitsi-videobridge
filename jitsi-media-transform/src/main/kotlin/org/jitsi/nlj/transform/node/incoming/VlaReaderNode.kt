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
package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.Event
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.rtp.RtpExtensionType.VLA
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.VlaExtension
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger

/**
 * A node which reads the Video Layers Allocation (VLA) RTP header extension and updates the media sources (TODO).
 */
class VlaReaderNode(
    streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger = LoggerImpl(VlaReaderNode::class.simpleName)
) : ObserverNode("Video Layers Allocation reader") {
    private val logger = createChildLogger(parentLogger)
    private var vlaExtId: Int? = null
    private var mediaSourceDescs: Array<MediaSourceDesc> = arrayOf()

    init {
        streamInformationStore.onRtpExtensionMapping(VLA) {
            vlaExtId = it
            logger.debug("VLA extension ID set to $it")
        }
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaSourcesEvent -> {
                mediaSourceDescs = event.mediaSourceDescs.copyOf()
                logger.cdebug { "Media sources changed:\n${mediaSourceDescs.joinToString()}" }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun observe(packetInfo: PacketInfo) {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        vlaExtId?.let {
            rtpPacket.getHeaderExtension(it)?.let { ext ->
                try {
                    val vla = VlaExtension.parse(ext)
                    // TODO update media sources
                    logger.info(
                        "VLA extension found: $vla " +
                            "raw=${ext.buffer.toHexString(ext.dataOffset, ext.dataOffset + ext.dataLengthBytes)}"
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to parse VLA extension", e)
                }
            }
        }
    }

    override fun trace(f: () -> Unit) {}
}
