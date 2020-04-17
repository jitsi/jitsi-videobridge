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
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.stats.PacketStreamStats
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsSnapshot
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.videobridge.octo.config.OctoConfig.Config
import org.jitsi.videobridge.util.TaskPools
import java.util.concurrent.ConcurrentHashMap

/**
 * An [RtpSender] for all data we want to send out over the Octo link(s).
 */
class OctoRtpSender(
    readOnlyStreamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger
) : RtpSender() {
    private val logger = createChildLogger(parentLogger)

    /**
     * The queues which pass packets to be sent, indexed by source endpoint ID
     */
    private val outgoingPacketQueues: MutableMap<String, PacketInfoQueue> = ConcurrentHashMap()

    /**
     * A handler for packets to be sent out onto the network
     */
    private var outgoingPacketHandler: PacketHandler? = null

    /**
     * The [KeyframeRequester] used for all remote Octo endpoints
     */
    private val keyframeRequester = KeyframeRequester(readOnlyStreamInformationStore, logger).apply {
        attach(object : ConsumerNode("Octo sender termination node") {
            override fun consume(packetInfo: PacketInfo) {
                outgoingPacketHandler?.processPacket(packetInfo) ?: packetDiscarded(packetInfo)
            }

            override fun trace(f: () -> Unit) = f.invoke()
        })
    }

    /**
     * Add a handler to handle outgoing Octo packets
     */
    override fun onOutgoingPacket(handler: PacketHandler) {
        outgoingPacketHandler = handler
    }

    override fun doProcessPacket(packetInfo: PacketInfo) {
        packetInfo.endpointId?.let { epId ->
            val queue = outgoingPacketQueues.computeIfAbsent(epId) {
                PacketInfoQueue(
                    "octo-tentacle-outgoing-packet-queue",
                    TaskPools.IO_POOL,
                    this::doSend,
                    Config.sendQueueSize()
                ).apply {
                    setErrorHandler(queueErrorCounter)
                }
            }
            queue.add(packetInfo)
        } ?: run {
            // Some packets may not have the source endpoint ID set, these are
            // packets that originate within the bridge itself (right now
            // this only happens for RTCP).  We don't queue these packets,
            // but instead send them directly
            doSend(packetInfo)
        }
    }

    private fun doSend(packetInfo: PacketInfo): Boolean {
        outgoingPacketHandler?.processPacket(packetInfo)
        return true
    }

    override fun requestKeyframe(mediaSsrc: Long?) {
        keyframeRequester.requestKeyframe(mediaSsrc)
    }

    fun endpointExpired(epId: String) {
        outgoingPacketQueues.remove(epId)
    }

    override fun sendProbing(mediaSsrc: Long, numBytes: Int): Int = 0

    override fun setSrtpTransformers(srtpTransformers: SrtpTransformers) {}

    override fun stop() {
        // TODO: we need a 'running' flag here so we can stop creating new
        // queue and close out the ones we have
    }

    override fun tearDown() {
        // TODO: close all queues here
    }

    override fun handleEvent(event: Event) {}

    override fun onRttUpdate(newRttMs: Double) {}

    override fun getPacketStreamStats(): PacketStreamStats.Snapshot =
        PacketStreamStats.Snapshot(0, 0, 0, 0)

    override fun getStreamStats(): OutgoingStatisticsSnapshot =
        OutgoingStatisticsSnapshot(mapOf())

    companion object {
        @JvmField
        val queueErrorCounter = CountingErrorHandler()
    }
}
