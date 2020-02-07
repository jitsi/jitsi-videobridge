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
import org.jitsi.nlj.SetLocalSsrcEvent
import org.jitsi.nlj.rtp.RtpExtensionType.ABS_SEND_TIME
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.rtp.bandwidthestimation.GoogleCcEstimator
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.bytes
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.observableWhenChanged
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacketBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.AbsSendTimeHeaderExtension
import org.jitsi.utils.LRUCache
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Estimates the available bandwidth for the incoming stream using the abs-send-time extension.
 */
class RemoteBandwidthEstimator(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger,
    diagnosticContext: DiagnosticContext = DiagnosticContext(),
    private val clock: Clock = Clock.systemUTC()
) : ObserverNode("Remote Bandwidth Estimator") {
    private val logger = createChildLogger(parentLogger)
    /**
     * The remote bandwidth estimation is enabled when REMB support is signaled, but TCC is not signaled.
     */
    private var enabled: Boolean by observableWhenChanged(false) {
        _, _, newValue -> logger.debug { "Setting enabled=$newValue." }
    }
    private var astExtId: Int? = null
    /**
     * We use the full [GoogleCcEstimator] here, but we don't notify it of packet loss, effectively using only the
     * delay-based part.
     */
    private val bwe: BandwidthEstimator by lazy { GoogleCcEstimator(diagnosticContext, logger) }
    private val ssrcs: MutableSet<Long> = LRUCache.lruSet(MAX_SSRCS, true /* accessOrder */)
    private var numRembsCreated = 0
    private var numPacketsWithoutAbsSendTime = 0
    private var localSsrc = 0L

    init {
        streamInformationStore.onRtpExtensionMapping(ABS_SEND_TIME) {
            astExtId = it
            logger.debug { "Setting abs-send-time extension ID to $astExtId" }
        }
        streamInformationStore.onRtpPayloadTypesChanged {
            enabled = streamInformationStore.supportsRemb && !streamInformationStore.supportsTcc
        }
    }

    companion object {
        private const val MAX_SSRCS: Int = 8
    }

    override fun handleEvent(event: Event) {
        if (event is SetLocalSsrcEvent && event.mediaType == MediaType.VIDEO) {
            localSsrc = event.ssrc
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()

    override fun observe(packetInfo: PacketInfo) {
        if (!enabled) return

        astExtId?.let {
            val rtpPacket = packetInfo.packetAs<RtpPacket>()
            rtpPacket.getHeaderExtension(it)?.let { ext ->
                bwe.processPacketArrival(
                    clock.instant(),
                    AbsSendTimeHeaderExtension.getTime(ext),
                    Instant.ofEpochMilli(packetInfo.receivedTime),
                    rtpPacket.sequenceNumber,
                    rtpPacket.length.bytes)
                ssrcs.add(rtpPacket.ssrc)
            }
        } ?: numPacketsWithoutAbsSendTime++
    }

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        addString("ast_ext_id", astExtId.toString())
        addBoolean("enabled", enabled)
        addNumber("num_rembs_created", numRembsCreated)
        addNumber("num_packets_without_ast", numPacketsWithoutAbsSendTime)
    }

    fun createRemb(): RtcpFbRembPacket? {
        // REMB based BWE is not configured.
        if (!enabled || astExtId == null) return null

        val currentBw = bwe.getCurrentBw(clock.instant())
        // The estimator does not yet have a valid value.
        if (currentBw < 0.bps) return null

        numRembsCreated++
        return RtcpFbRembPacketBuilder(
            rtcpHeader = RtcpHeaderBuilder(senderSsrc = localSsrc),
            brBps = currentBw.bps.toLong(),
            ssrcs = ssrcs.toList()).build()
    }

    fun onRttUpdate(newRttMs: Double) {
        bwe.onRttUpdate(clock.instant(), Duration.ofNanos((newRttMs * 1000_000).toLong()))
    }
}
