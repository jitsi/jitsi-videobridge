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

package org.jitsi.videobridge.relay

import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpSender
import org.jitsi.nlj.RtpSenderImpl
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtcp.RtcpListener
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.util.StreamInformationStore
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.util.TaskPools
import org.json.simple.JSONObject
import java.time.Instant

/**
 * An object that sends media from a single local endpoint to a single remote relay.
 */
class RelayEndpointSender(
    val relay: Relay,
    val id: String,
    diagnosticContext: DiagnosticContext,
    parentLogger: Logger
) {
    private val logger = createChildLogger(parentLogger)

    private val streamInformationStore: StreamInformationStore = StreamInformationStoreImpl()

    val rtcpEventNotifier = RtcpEventNotifier().apply {
        addRtcpEventListener(
            object : RtcpListener {
                override fun rtcpPacketReceived(packet: RtcpPacket, receivedTime: Instant?) {
                    throw IllegalStateException("got rtcpPacketReceived callback from a sender")
                }
                override fun rtcpPacketSent(packet: RtcpPacket) {
                    relay.rtcpPacketSent(packet, id)
                }
            },
            external = true
        )
    }

    private val rtpSender: RtpSender = RtpSenderImpl(
        id,
        rtcpEventNotifier,
        TaskPools.CPU_POOL,
        TaskPools.SCHEDULED_POOL,
        streamInformationStore,
        logger,
        diagnosticContext
    ).apply {
        onOutgoingPacket(object : PacketHandler {
            override fun processPacket(packetInfo: PacketInfo) {
                relay.handleOutgoingPacket(packetInfo)
            }
        })
    }

    private var expired = false

    fun addPayloadType(payloadType: PayloadType) = streamInformationStore.addRtpPayloadType(payloadType)
    fun addRtpExtension(rtpExtension: RtpExtension) = streamInformationStore.addRtpExtensionMapping(rtpExtension)

    fun setSrtpInformation(srtpTransformers: SrtpTransformers) {
        rtpSender.setSrtpTransformers(srtpTransformers)
    }

    fun sendPacket(packetInfo: PacketInfo) = rtpSender.processPacket(packetInfo)

    fun getNodeStats(): NodeStatsBlock {
        return NodeStatsBlock("Remote Endpoint $id").apply {
            addBlock(streamInformationStore.getNodeStats())
            addBlock(rtpSender.getNodeStats())
        }
    }

    fun getDebugState(): JSONObject {
        val debugState = JSONObject()
        debugState["expired"] = expired

        val block = getNodeStats()
        debugState[block.name] = block.toJson()

        return debugState
    }

    fun expire() {
        if (expired) {
            return
        }
        expired = true

        try {
            updateStatsOnExpire()
            rtpSender.stop()
            logger.cdebug { getNodeStats().prettyPrint(0) }
            rtpSender.tearDown()
        } catch (t: Throwable) {
            logger.error("Exception while expiring: ", t)
        }
    }

    private fun updateStatsOnExpire() {
        val relayStats = relay.statistics
        val outgoingStats = rtpSender.getPacketStreamStats()

        relayStats.apply {
            bytesSent.getAndAdd(outgoingStats.bytes)
            packetsSent.getAndAdd(outgoingStats.packets)
        }
    }
}
