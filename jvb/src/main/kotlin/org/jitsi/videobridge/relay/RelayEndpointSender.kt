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

import org.jitsi.nlj.DebugStateMode
import org.jitsi.nlj.Features
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpSender
import org.jitsi.nlj.RtpSenderImpl
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtcp.RtcpListener
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.StreamInformationStore
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.videobridge.TransportConfig
import org.jitsi.videobridge.metrics.QueueMetrics
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer
import org.jitsi.videobridge.transport.ice.IceTransport
import org.jitsi.videobridge.util.TaskPools
import org.json.simple.JSONObject
import java.time.Instant

/**
 * An object that sends media from a single local endpoint to a single remote relay.
 */
class RelayEndpointSender(
    val relay: Relay,
    val id: String,
    parentLogger: Logger,
    diagnosticContext: DiagnosticContext
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
        "${relay.id}-$id",
        rtcpEventNotifier,
        TaskPools.CPU_POOL,
        TaskPools.SCHEDULED_POOL,
        streamInformationStore,
        logger,
        diagnosticContext
    ).apply {
        onOutgoingPacket(object : PacketHandler {
            override fun processPacket(packetInfo: PacketInfo) {
                packetInfo.addEvent(SRTP_QUEUE_ENTRY_EVENT)
                outgoingSrtpPacketQueue.add(packetInfo)
            }
        })
    }

    /**
     * The queue we put outgoing SRTP packets onto so they can be sent
     * out via the Relay's [IceTransport] on an IO thread.
     */
    private val outgoingSrtpPacketQueue = PacketInfoQueue(
        "${javaClass.simpleName}-outgoing-packet-queue",
        TaskPools.IO_POOL,
        { packet -> relay.doSendSrtp(packet) },
        TransportConfig.queueSize
    ).apply {
        setErrorHandler(queueErrorCounter)
    }

    private var expired = false

    fun addPayloadType(payloadType: PayloadType) = streamInformationStore.addRtpPayloadType(payloadType)
    fun addRtpExtension(rtpExtension: RtpExtension) = streamInformationStore.addRtpExtensionMapping(rtpExtension)

    fun setExtmapAllowMixed(allow: Boolean) = streamInformationStore.setExtmapAllowMixed(allow)

    fun setSrtpInformation(srtpTransformers: SrtpTransformers) {
        rtpSender.setSrtpTransformers(srtpTransformers)
    }

    fun sendPacket(packetInfo: PacketInfo) = rtpSender.processPacket(packetInfo)

    fun setFeature(feature: Features, enabled: Boolean) {
        rtpSender.setFeature(feature, enabled)
    }

    fun getOutgoingStats() = rtpSender.getPacketStreamStats()

    fun getDebugState(mode: DebugStateMode) = JSONObject().apply {
        this["expired"] = expired
        this["id"] = id
        this["sender"] = rtpSender.debugState(mode)
        this["stream_information_store"] = streamInformationStore.debugState(mode)
    }

    fun expire() {
        if (expired) {
            return
        }
        expired = true

        try {
            updateStatsOnExpire()
            rtpSender.stop()
            logger.cdebug { getDebugState(DebugStateMode.FULL).toJSONString() }
            rtpSender.tearDown()
        } catch (t: Throwable) {
            logger.error("Exception while expiring: ", t)
        }
        outgoingSrtpPacketQueue.close()
    }

    private fun updateStatsOnExpire() {
        val relayStats = relay.statistics
        val outgoingStats = rtpSender.getPacketStreamStats()

        relayStats.apply {
            bytesSent.getAndAdd(outgoingStats.bytes)
            packetsSent.getAndAdd(outgoingStats.packets)
        }
    }

    companion object {
        private val droppedPacketsMetric = VideobridgeMetricsContainer.instance.registerCounter(
            "relay_endpoint_srtp_send_queue_dropped_packets",
            "Number of packets dropped out of the Relay SRTP send queue."
        )

        private val exceptionsMetric = VideobridgeMetricsContainer.instance.registerCounter(
            "relay_endpoint_srtp_send_queue_exceptions",
            "Number of exceptions from the Relay SRTP send queue."
        )

        /** Count the number of dropped packets and exceptions. */
        @JvmField
        val queueErrorCounter = object : CountingErrorHandler() {
            override fun packetDropped() = super.packetDropped().also {
                droppedPacketsMetric.inc()
                QueueMetrics.droppedPackets.inc()
            }

            override fun packetHandlingFailed(t: Throwable?) = super.packetHandlingFailed(t).also {
                exceptionsMetric.inc()
                QueueMetrics.exceptions.inc()
            }
        }

        private const val SRTP_QUEUE_ENTRY_EVENT = "Entered RelayEndpointSender SRTP sender outgoing queue"
    }
}
