/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.util.DataSize
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Transport CC engine invoking GoogCc NetworkController.
 */
class GoogCcTransportCcEngine(
    parentLogger: Logger,
    diagnosticContext: DiagnosticContext,
    val clock: Clock = Clock.systemUTC(),
) : TransportCcEngine() {
    private val logger = createChildLogger(parentLogger)

    private val feedbackAdapter = TransportFeedbackAdapter(logger)
    private val networkController = GoogCcNetworkController(
        NetworkControllerConfig(logger, diagnosticContext),
        GoogCcConfig(
            // TODO configure non-default fields here.
        )
    ).apply {
        val update = onNetworkAvailability(NetworkAvailability(atTime = clock.instant(), networkAvailable = true))
        processUpdate(update) // Does this make sense to do during init?
    }

    private val listeners = LinkedList<BandwidthListener>()

    override fun onRttUpdate(rtt: Duration) {
        val update =
            networkController.onRoundTripTimeUpdate(
                RoundTripTimeUpdate(receiveTime = clock.instant(), roundTripTime = rtt)
            )
        processUpdate(update)
    }

    override fun rtcpPacketReceived(rtcpPacket: RtcpPacket, receivedTime: Instant?) {
        if (rtcpPacket is RtcpFbTccPacket) {
            val now = clock.instant()
            val feedback = feedbackAdapter.processTransportFeedback(rtcpPacket, now)
            if (feedback != null) {
                val update = networkController.onTransportPacketsFeedback(feedback)
                processUpdate(update)
            }
        }
        // TODO: handle other RTCP packets that the network controller wants
    }

    override fun mediaPacketSent(tccSeqNum: Int, length: DataSize) {
        val now = clock.instant()
        val sentPacketInfo = SentPacketInfo(
            packetId = tccSeqNum,
            sendTime = now,
            info = PacketInfo(
                // TODO I think these should always be true when tccSeqNum is defined?
                includedInAllocation = true,
                includedInFeedback = true,
                packetSizeBytes = length.bytes.toLong()
            )
        )
        val sentPacket = feedbackAdapter.processSentPacket(sentPacketInfo)
        if (sentPacket != null) {
            val update = networkController.onSentPacket(sentPacket)
            processUpdate(update)
        }
    }

    override fun getStatistics(): StatisticsSnapshot {
        return StatisticsSnapshot()
    }

    override fun addBandwidthListener(listener: BandwidthListener) {
        listeners.add(listener)
    }

    override fun removeBandwidthListener(listener: BandwidthListener) {
        listeners.remove(listener)
    }

    private fun processUpdate(update: NetworkControlUpdate) {
        update.targetRate?.let { targetRate ->
            listeners.forEach { it.bandwidthEstimationChanged(targetRate.targetRate) }
        }
        // TOOD: other fields of update
    }

    class StatisticsSnapshot : TransportCcEngine.StatisticsSnapshot() {
        // TODO
        override fun toJson(): Map<*, *> {
            return OrderedJsonObject()
        }
    }
}
