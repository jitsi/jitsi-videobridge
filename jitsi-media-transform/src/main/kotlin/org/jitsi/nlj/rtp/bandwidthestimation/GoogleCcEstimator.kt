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
package org.jitsi.nlj.rtp.bandwidthestimation

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bps
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.RemoteBitrateEstimatorAbsSendTime
import org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation.SendSideBandwidthEstimation
import java.time.Duration
import java.time.Instant
import kotlin.properties.Delegates

class GoogleCcEstimator(diagnosticContext: DiagnosticContext, parentLogger: Logger) :
    BandwidthEstimator(diagnosticContext) {
    override val algorithmName = "Google CC"

    /* TODO: Use configuration service to set this default value. */
    override var initBw: Bandwidth = BandwidthEstimatorConfig.initBw
    /* TODO: observable which sets the components' values if we're in initial state. */

    override var minBw: Bandwidth by Delegates.observable(BandwidthEstimatorConfig.minBw) {
            _, _, newValue ->
        bitrateEstimatorAbsSendTime.setMinBitrate(newValue.bps.toInt())
        sendSideBandwidthEstimation.setMinMaxBitrate(newValue.bps.toInt(), maxBw.bps.toInt())
    }

    override var maxBw: Bandwidth by Delegates.observable(BandwidthEstimatorConfig.maxBw) {
            _, _, newValue ->
        sendSideBandwidthEstimation.setMinMaxBitrate(minBw.bps.toInt(), newValue.bps.toInt())
    }

    private val logger = createChildLogger(parentLogger)

    /**
     * Implements the delay-based part of Google CC.
     */
    private val bitrateEstimatorAbsSendTime = RemoteBitrateEstimatorAbsSendTime(diagnosticContext, logger).also {
        it.setMinBitrate(minBw.bps.toInt())
    }

    /**
     * Implements the loss-based part of Google CC.
     */
    private val sendSideBandwidthEstimation =
        SendSideBandwidthEstimation(diagnosticContext, initBw.bps.toLong(), logger).also {
            it.setMinMaxBitrate(minBw.bps.toInt(), maxBw.bps.toInt())
        }

    override fun doProcessPacketArrival(
        now: Instant,
        sendTime: Instant?,
        recvTime: Instant?,
        seq: Int,
        size: DataSize,
        ecn: Byte,
        previouslyReportedLost: Boolean
    ) {
        if (sendTime != null && recvTime != null) {
            bitrateEstimatorAbsSendTime.incomingPacketInfo(
                now.toEpochMilli(),
                sendTime.toEpochMilli(),
                recvTime.toEpochMilli(),
                size.bytes.toInt()
            )
        }
        sendSideBandwidthEstimation.updateReceiverEstimate(bitrateEstimatorAbsSendTime.latestEstimate)
        sendSideBandwidthEstimation.reportPacketArrived(now.toEpochMilli(), previouslyReportedLost)
    }

    override fun doProcessPacketLoss(now: Instant, sendTime: Instant?, seq: Int) {
        sendSideBandwidthEstimation.reportPacketLost(now.toEpochMilli())
    }

    override fun doFeedbackComplete(now: Instant) {
        /* TODO: rate-limit how often we call updateEstimate? */
        sendSideBandwidthEstimation.updateEstimate(now.toEpochMilli())
        reportBandwidthEstimate(now, sendSideBandwidthEstimation.latestEstimate.bps)
    }

    override fun doRttUpdate(now: Instant, newRtt: Duration) {
        bitrateEstimatorAbsSendTime.onRttUpdate(now.toEpochMilli(), newRtt.toMillis())
        sendSideBandwidthEstimation.onRttUpdate(newRtt)
    }

    override fun getCurrentBw(now: Instant): Bandwidth {
        return sendSideBandwidthEstimation.latestEstimate.bps
    }

    override fun getStats(now: Instant): StatisticsSnapshot = StatisticsSnapshot(
        "GoogleCcEstimator",
        getCurrentBw(now)
    ).apply {
        bitrateEstimatorAbsSendTime.statistics?.run {
            addNumber("delayBasedEstimatorOffset", offset)
            addNumber("delayBasedEstimatorThreshold", threshold)
            addNumber("delayBasedEstimatorHypothesis", hypothesis.value)
        }
        addNumber("latestDelayBasedEstimate", sendSideBandwidthEstimation.latestREMB)
        addNumber("latestLossFraction", sendSideBandwidthEstimation.latestFractionLoss / 256.0)
        with(sendSideBandwidthEstimation.statistics) {
            update(now.toEpochMilli())
            addNumber("lossDegradedMs", lossDegradedMs)
            addNumber("lossFreeMs", lossFreeMs)
            addNumber("lossLimitedMs", lossLimitedMs)
        }
    }

    override fun reset() {
        initBw = BandwidthEstimatorConfig.initBw
        minBw = BandwidthEstimatorConfig.minBw
        maxBw = BandwidthEstimatorConfig.maxBw

        bitrateEstimatorAbsSendTime.reset()
        sendSideBandwidthEstimation.reset(initBw.bps)

        sendSideBandwidthEstimation.setMinMaxBitrate(minBw.bps.toInt(), maxBw.bps.toInt())
    }

    companion object {
        /* Default config settings to use when the classic Google CC estimator engine is used. */
        val defaultRateTrackerWindowSize: Duration by config {
            "jmt.bwe.estimator.GoogleCc.default-window-size".from(JitsiConfig.newConfig)
        }
        val defaultRateTrackerBucketSize: Duration by config {
            "jmt.bwe.estimator.GoogleCc.default-bucket-size".from(JitsiConfig.newConfig)
        }
        val defaultInitialIgnoreBwePeriod: Duration by config {
            "jmt.bwe.estimator.GoogleCc.default-initial-ignore-bwe-period".from(JitsiConfig.newConfig)
        }
    }
}
