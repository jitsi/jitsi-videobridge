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

import org.jitsi.nlj.EncodingLivenessConfig
import org.jitsi.nlj.EncodingLivenessTracker
import org.jitsi.nlj.Event
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.findRtpSource
import org.jitsi.nlj.rtp.RtpExtensionType.VLA
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.kbps
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.VlaExtension
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import java.time.Clock
import kotlin.math.min

/**
 * A node which reads the Video Layers Allocation (VLA) RTP header extension and updates the media sources. It sets
 * the target bitrates, resolutions and frame rates of the layers the allocation lists, and which encodings the
 * sender is sending, see [EncodingLivenessTracker.onSignaled]. An allocation lists the active layers of every RTP
 * stream of the source. A stream with no active layers, or beyond the number of streams the allocation covers, is
 * not being sent.
 */
class VlaReaderNode(
    streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger = LoggerImpl(VlaReaderNode::class.simpleName),
    private val clock: Clock = Clock.systemUTC()
) : ObserverNode("Video Layers Allocation reader") {
    private val logger = createChildLogger(parentLogger)
    private var vlaExtId: Int? = null
    private var mediaSourceDescs: Array<MediaSourceDesc> = arrayOf()

    /** Whether to tell the encodings' liveness trackers what the allocation says, see [EncodingLivenessConfig]. */
    private val trustSignaling = EncodingLivenessConfig.trustSignaling

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

    override fun observe(packetInfo: PacketInfo) {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        vlaExtId?.let {
            rtpPacket.getHeaderExtension(it)?.let { ext ->
                val vla = try {
                    VlaExtension.parse(ext)
                } catch (e: Exception) {
                    logger.warn("Failed to parse VLA extension", e)
                    return
                }

                val sourceDesc = mediaSourceDescs.findRtpSource(rtpPacket)

                logger.debug { "Found VLA=$vla for sourceDesc=$sourceDesc" }

                /* A sender puts the allocation on the packets most likely to be retransmitted. An allocation on a
                 * packet of an older frame than its encoding's current frame is stale, regardless of which encodings
                 * it names. It could list an encoding the sender has since turned off. The encoding liveness node,
                 * ahead of this node, has recorded the packet on its encoding, so the encoding knows. Like that node,
                 * this node reads only packets of an encoding's primary SSRC. */
                val encoding = sourceDesc?.rtpEncodings?.find { it.primarySSRC == rtpPacket.ssrc } ?: return
                if (encoding.liveness.isOfOlderFrame(rtpPacket.sequenceNumber, rtpPacket.timestamp)) {
                    logger.cdebug { "Ignoring a stale VLA on a reordered or retransmitted packet: $rtpPacket" }
                    return
                }

                /* An allocation with no streams at all says nothing about which are sent: the packet carrying it
                 * shows that at least its own is. The format defines an empty allocation as no layer active, but a
                 * sender which puts it on a packet is sending that packet's stream, so the allocation is not
                 * believed. */
                if (trustSignaling && sourceDesc != null && vla.isNotEmpty()) {
                    val nowMs = clock.millis()
                    sourceDesc.rtpEncodings.forEachIndexed { streamIdx, rtpEncoding ->
                        val active = vla.getOrNull(streamIdx)?.spatialLayers?.isNotEmpty() == true
                        rtpEncoding.liveness.onSignaled(active, nowMs)
                    }
                }

                vla.forEachIndexed { streamIdx, stream ->
                    /* The allocation may cover more streams than the source is known to have. */
                    val rtpEncoding = sourceDesc?.rtpEncodings?.getOrNull(streamIdx)
                    stream.spatialLayers.forEach { spatialLayer ->
                        val maxTl = spatialLayer.targetBitratesKbps.size - 1

                        spatialLayer.targetBitratesKbps.forEachIndexed { tlIdx, targetBitrateKbps ->
                            rtpEncoding?.layers?.find {
                                // With VP8 simulcast all layers have sid -1
                                (it.sid == spatialLayer.id || it.sid == -1) && it.tid == tlIdx
                            }?.let { layer ->
                                logger.debug {
                                    "Setting target bitrate for rtpEncoding=$rtpEncoding layer=$layer to " +
                                        "${targetBitrateKbps.kbps} (res=${spatialLayer.res})"
                                }
                                layer.targetBitrate = targetBitrateKbps.kbps
                                spatialLayer.res?.let { res ->
                                    // Treat the lesser of width and height as the height
                                    // in order to handle portrait-mode video correctly
                                    val minDimension = min(res.height, res.width)
                                    if (layer.height > 0 && layer.height != minDimension) {
                                        logger.info {
                                            "Updating layer height for source ${sourceDesc.sourceName} " +
                                                "encoding ${rtpEncoding.primarySSRC} layer ${layer.indexString()} " +
                                                "from ${layer.height} to $minDimension"
                                        }
                                    }
                                    layer.height = minDimension
                                    /* Presume 2:1 frame rate ratios for temporal layers */
                                    val framerateFraction = 1.0 / (1 shl (maxTl - tlIdx))
                                    layer.frameRate = res.maxFramerate.toDouble() * framerateFraction
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun trace(f: () -> Unit) {}
}
