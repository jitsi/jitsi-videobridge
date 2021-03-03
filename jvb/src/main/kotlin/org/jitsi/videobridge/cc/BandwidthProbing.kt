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

package org.jitsi.videobridge.cc

import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.utils.concurrent.PeriodicRunnable
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.videobridge.cc.allocation.BitrateControllerStatusSnapshot
import org.jitsi.videobridge.cc.config.BandwidthProbingConfig
import org.json.simple.JSONObject
import java.util.function.Supplier
import kotlin.random.Random

class BandwidthProbing(
    private val probingDataSender: ProbingDataSender,
    private val statusSnapshotSupplier: Supplier<BitrateControllerStatusSnapshot>
) : PeriodicRunnable(config.paddingPeriodMs), BandwidthEstimator.Listener {

    /**
     * The sequence number to use if probing with the JVB's SSRC.
     */
    private var seqNum = Random.nextInt(0xFFFF)

    /**
     * The RTP timestamp to use if probing with the JVB's SSRC.
     */
    private var ts: Long = Random.nextLong().and(0xFFFFFFFFL)

    /**
     * Whether or not probing is currently enabled
     */
    var enabled = false

    /**
     * The number of bytes left over from one run of probing to the next.  This
     * avoids accumulated rounding errors causing us to under-shoot the probing
     * bandwidth, and also handles the use when the number of bytes we want to
     * send is less than the size of an RTP header.
     */
    private var bytesLeftOver = 0

    private var latestBwe: Long = -1

    var diagnosticsContext: DiagnosticContext? = null

    override fun bandwidthEstimationChanged(newValue: Bandwidth) {
        latestBwe = newValue.bps.toLong()
    }

    override fun run() {
        super.run()
        if (!enabled) {
            return
        }

        // We calculate how much to probe for based on the total target bps
        // (what we're able to reach), the total ideal bps (what we want to
        // be able to reach) and the total current bps (what we currently send).
        val bitrateControllerStatus = statusSnapshotSupplier.get()

        // How much padding do we need?
        val totalNeededBps = bitrateControllerStatus.currentIdealBps - bitrateControllerStatus.currentTargetBps
        if (totalNeededBps < 1) {
            // Don't need to send any probing
            bytesLeftOver = 0
            return
        }

        val latestBweCopy = latestBwe
        if (bitrateControllerStatus.currentIdealBps <= latestBweCopy) {
            // it seems like the ideal bps fits in the bandwidth estimation,
            // let's update the bitrate controller.
            // TODO(brian): this trigger for a bitratecontroller update seems awkward and may now be obsolete
            //  since i now update it every time we get an updated estimate from bandwidth estimator
//             dest.getBitrateController().update(bweBps);
            return
        }

        // How much padding can we afford?
        val maxPaddingBps = latestBweCopy - bitrateControllerStatus.currentTargetBps
        val paddingBps = totalNeededBps.coerceAtMost(maxPaddingBps)

        var timeSeriesPoint: DiagnosticContext.TimeSeriesPoint? = null
        val newBytesNeeded = (config.paddingPeriodMs * paddingBps / 1000.0 / 8.0)
        val bytesNeeded = newBytesNeeded + bytesLeftOver

        if (timeSeriesLogger.isTraceEnabled) {
            diagnosticsContext?.let { diagnosticsContext ->
                timeSeriesPoint = diagnosticsContext
                    .makeTimeSeriesPoint("sent_padding")
                    .addField("padding_bps", paddingBps)
                    .addField("total_ideal_bps", bitrateControllerStatus.currentIdealBps)
                    .addField("total_target_bps", bitrateControllerStatus.currentTargetBps)
                    .addField("needed_bps", totalNeededBps)
                    .addField("max_padding_bps", maxPaddingBps)
                    .addField("bwe_bps", latestBweCopy)
                    .addField("bytes_needed", bytesNeeded)
                    .addField("prev_bytes_left_over", bytesLeftOver)
            }
        }

        if (bytesNeeded >= 1) {
            val bytesSent = probingDataSender.sendProbing(bitrateControllerStatus.activeSsrcs, bytesNeeded.toInt())
            bytesLeftOver = (bytesNeeded - bytesSent).coerceAtLeast(0.0).toInt()
            timeSeriesPoint?.addField("bytes_sent", bytesSent)?.addField("new_bytes_left_over", bytesLeftOver)
        } else {
            bytesLeftOver = bytesNeeded.coerceAtLeast(0.0).toInt()
        }

        if (timeSeriesLogger.isTraceEnabled) {
            timeSeriesLogger?.trace(timeSeriesPoint)
        }
    }

    fun getDebugState(): JSONObject = JSONObject().apply {
        put("seqNum", seqNum)
        put("ts", ts)
        put("enabled", enabled)
        put("latestBwe", latestBwe)
    }

    companion object {
        private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(BandwidthProbing::class.java)
        private val config = BandwidthProbingConfig()
    }

    interface ProbingDataSender {
        /**
         * Sends a specific number of bytes with a specific set of SSRCs.
         * @param mediaSsrcs the SSRCs
         * @param numBytes the number of probing bytes we want to send
         * @return the number of bytes of probing data actually sent
         */
        fun sendProbing(mediaSsrcs: Collection<Long>, numBytes: Int): Int
    }
}
