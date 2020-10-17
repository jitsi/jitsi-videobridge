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

package org.jitsi.videobridge.octo.config

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpSender
import org.jitsi.nlj.rtcp.KeyframeRequester
import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.stats.PacketStreamStats
import org.jitsi.nlj.transform.NodeStatsVisitor
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsSnapshot
import org.jitsi.nlj.util.OrderedJsonObject
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.bps
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.util.ByteBufferPool
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An [RtpSender] for all data we want to send out over the Octo link(s).
 */
class OctoRtpSender(
    readOnlyStreamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger
) : RtpSender() {
    private val logger = createChildLogger(parentLogger)

    private val running = AtomicBoolean(true)

    /**
     * A handler for packets to be sent out onto the network
     */
    private var outgoingPacketHandler: PacketHandler? = null

    private val outputPipelineTerminationNode = object : ConsumerNode("Octo sender termination node") {
        override fun consume(packetInfo: PacketInfo) {
            outgoingPacketHandler?.processPacket(packetInfo) ?: packetDiscarded(packetInfo)
        }

        override fun trace(f: () -> Unit) = f.invoke()
    }

    /**
     * The [KeyframeRequester] used for all remote Octo endpoints
     */
    private val keyframeRequester = KeyframeRequester(readOnlyStreamInformationStore, logger).apply {
        attach(outputPipelineTerminationNode)
    }

    /**
     * Add a handler to handle outgoing Octo packets
     */
    override fun onOutgoingPacket(handler: PacketHandler) {
        outgoingPacketHandler = handler
    }

    override fun doProcessPacket(packetInfo: PacketInfo) {
        if (running.get()) {
            outgoingPacketHandler?.processPacket(packetInfo)
        } else {
            ByteBufferPool.returnBuffer(packetInfo.packet.buffer)
        }
    }

    private fun doSend(packetInfo: PacketInfo): Boolean {
        outgoingPacketHandler?.processPacket(packetInfo)
        return true
    }

    override fun requestKeyframe(mediaSsrc: Long?) {
        keyframeRequester.requestKeyframe(mediaSsrc)
    }

    override fun sendProbing(mediaSsrcs: Collection<Long>, numBytes: Int): Int = 0

    override fun setSrtpTransformers(srtpTransformers: SrtpTransformers) {}

    override fun stop() {
        running.set(false)
    }

    override fun tearDown() {
    }

    override val bandwidthEstimator: BandwidthEstimator
        get() = TODO("Not implemented")

    override fun handleEvent(event: Event) {}

    override fun onRttUpdate(newRttMs: Double) {}

    override fun getPacketStreamStats(): PacketStreamStats.Snapshot = PacketStreamStats.Snapshot(0.bps, 0, 0, 0)

    override fun getTransportCcEngineStats(): TransportCcEngine.StatisticsSnapshot =
        TransportCcEngine.StatisticsSnapshot(0, 0, 0, 0, 0, 0)

    override fun getStreamStats(): OutgoingStatisticsSnapshot = OutgoingStatisticsSnapshot(mapOf())

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("Octo sender").apply {
        addBlock(super.getNodeStats())
        NodeStatsVisitor(this).reverseVisit(outputPipelineTerminationNode)
    }

    fun getDebugState(): OrderedJsonObject = OrderedJsonObject().apply {
        putAll(getNodeStats().toJson())
    }
}
