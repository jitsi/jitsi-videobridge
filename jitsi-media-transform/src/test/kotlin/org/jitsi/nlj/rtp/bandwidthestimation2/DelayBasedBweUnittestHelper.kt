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
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.instantOfEpochMicro
import org.jitsi.nlj.util.toEpochMicro
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.ms
import org.jitsi.utils.time.FakeClock
import java.lang.Long.max
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Helper classes for the unit tests for Delay-Based BWE,
 * based on WebRTC modules/congestion_controller/goog_cc/delay_based_bwe_unittest_helper.{h,cc} in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 *
 */

class TestBitrateObserver {
    var updated = false
        private set
    var latestBitrate = 0L
        private set

    fun onReceivedBitrateChanged(bitrate: Long) {
        latestBitrate = bitrate
        updated = true
    }

    fun reset() {
        updated = false
    }
}

class RtpStream(
    val fps: Int,
    var bitrateBps: Int
) {
    init {
        require(fps > 0)
    }

    var nextRtpTime: Long = 0L
        private set

    // Generates a new frame for this stream. If called too soon after the
    // previous frame, no frame will be generated. The frame is split into
    // packets.
    fun generateFrame(timeNowUs: Long, packets: MutableList<PacketResult>): Long {
        if (timeNowUs < nextRtpTime) {
            return nextRtpTime
        }
        val bitsPerFrame = (bitrateBps + fps / 2) / fps
        val nPackets = ((bitsPerFrame + 4 * kMtu) / (8 * kMtu)).coerceAtLeast(1)
        val payloadSize = (bitsPerFrame + 4 * nPackets) / (8 * nPackets)
        repeat(nPackets) {
            val packet = PacketResult()
            packet.sentPacket.sendTime = instantOfEpochMicro(timeNowUs + kSendSideOffsetUs)
            packet.sentPacket.size = payloadSize.bytes
            packets.add(packet)
        }
        nextRtpTime = timeNowUs + (1000000 + fps / 2) / fps
        return nextRtpTime
    }

    companion object {
        const val kSendSideOffsetUs = 1000000
        const val kMtu = 1200
    }
}

class StreamGenerator(
    private var capacity: Int,
    timeNow: Long
) {
    /** All streams being transmitted on this simulated channel. */
    private val streams = ArrayList<RtpStream>()

    private var prevArrivalTimeUs = timeNow

    /** Add a new stream */
    fun addStream(stream: RtpStream) {
        streams.add(stream)
    }

    /** Set the link capacity */
    fun setCapacityBps(capacityBps: Int) {
        require(capacityBps > 0)
        capacity = capacityBps
    }

    /** Divides `bitrate_bps` among all streams. The allocated bitrate per stream
     is decided by the initial allocation ratios. */
    fun setBitrateBps(bitrateBps: Long) {
        check(streams.isNotEmpty())
        var totalBitrateBefore = 0
        for (stream in streams) {
            totalBitrateBefore += stream.bitrateBps
        }
        var bitrateBefore = 0L
        var totalBitrateAfter = 0L
        for (stream in streams) {
            bitrateBefore += stream.bitrateBps
            val bitrateAfter: Long =
                (bitrateBefore * bitrateBps + totalBitrateBefore / 2) /
                    totalBitrateBefore
            stream.bitrateBps = (bitrateAfter - totalBitrateAfter).toInt()
            totalBitrateAfter += stream.bitrateBps
        }
        check(bitrateBefore == totalBitrateBefore.toLong())
        check(totalBitrateAfter == bitrateBps)
    }

    // TODO(holmer): Break out the channel simulation part from this class to make
    //  it possible to simulate different types of channels.
    fun generateFrame(packets: MutableList<PacketResult>, timeNowUs: Long): Long {
        check(packets.isEmpty())
        check(capacity > 0)
        var it = streams.minByOrNull { it.nextRtpTime }
        it!!.generateFrame(timeNowUs, packets)
        for (packet in packets) {
            val capacityBpus = capacity / 1000
            val requiredNetworkTimeUs =
                (8 * 1000 * packet.sentPacket.size.bytes.toLong() + capacityBpus / 2) /
                    capacityBpus
            val prevArrivalTimeUs =
                max(
                    timeNowUs + requiredNetworkTimeUs,
                    prevArrivalTimeUs + requiredNetworkTimeUs
                )
            packet.receiveTime = instantOfEpochMicro(prevArrivalTimeUs)
        }
        it = streams.minByOrNull { it.nextRtpTime }
        return max(it!!.nextRtpTime, timeNowUs)
    }
}

class OneDelayBasedBweTest(parentLogger: Logger, diagnosticContext: DiagnosticContext) {
    val clock = FakeClock().also { it.setTime(instantOfEpochMicro(1000000)) }
    val bitrateObserver = TestBitrateObserver()
    val acknowledgedBitrateEstimator: AcknowledgedBitrateEstimatorInterface =
        AcknowledgedBitrateEstimatorInterface.create()
    val probeBitrateEstimator = ProbeBitrateEstimator()
    val bitrateEstimator = DelayBasedBwe(parentLogger, diagnosticContext)
    val streamGenerator = StreamGenerator(1e6.toInt(), clock.instant().toEpochMicro())

    val arrivalTimeOffsetMs: Long = 0L
    var firstUpdate: Boolean = true

    fun addDefaultStream() {
        streamGenerator.addStream(RtpStream(30, 3e5.toInt()))
    }

    // Helpers to insert a single packet into the delay-based BWE.
    fun incomingFeedback(arrivalTimeMs: Long, sendTimeMs: Long, payloadSize: Long) =
        incomingFeedback(arrivalTimeMs, sendTimeMs, payloadSize, PacedPacketInfo())

    fun incomingFeedback(arrivalTimeMs: Long, sendTimeMs: Long, payloadSize: Long, pacingInfo: PacedPacketInfo) {
        check(arrivalTimeMs + arrivalTimeOffsetMs >= 0)
        incomingFeedback(
            Instant.ofEpochMilli(arrivalTimeMs + arrivalTimeOffsetMs),
            Instant.ofEpochMilli(sendTimeMs),
            payloadSize,
            pacingInfo
        )
    }

    fun incomingFeedback(receiveTime: Instant, sendTime: Instant, payloadSize: Long, pacingInfo: PacedPacketInfo) {
        val packet = PacketResult()
        packet.receiveTime = receiveTime
        packet.sentPacket.sendTime = sendTime
        packet.sentPacket.size = payloadSize.bytes
        packet.sentPacket.pacingInfo = pacingInfo
        if (packet.sentPacket.pacingInfo.probeClusterId != PacedPacketInfo.kNotAProbe) {
            probeBitrateEstimator.handleProbeAndEstimateBitrate(packet)
        }

        val msg = TransportPacketsFeedback()
        msg.feedbackTime = clock.instant()
        msg.packetFeedbacks.add(packet)
        val result = bitrateEstimator.incomingPacketFeedbackVector(
            msg,
            acknowledgedBitrateEstimator.bitrate(),
            probeBitrateEstimator.fetchAndResetLastEstimatedBitrate(),
            false
        )
        if (result.updated) {
            bitrateObserver.onReceivedBitrateChanged(result.targetBitrate.bps)
        }
    }

    /** Generates a frame of packets belonging to a stream at a given bitrate and
     with a given ssrc. The stream is pushed through a very simple simulated
     network, and is then given to the receive-side bandwidth estimator.
     Returns true if an over-use was seen, false otherwise.
     The StreamGenerator::updated() should be used to check for any changes in
     target bitrate after the call to this function. */
    fun generateAndProcessFrame(ssrc: Long, bitrateBps: Long): Boolean {
        streamGenerator.setBitrateBps(bitrateBps)
        val packets = ArrayList<PacketResult>()

        val nextTimeUs = streamGenerator.generateFrame(packets, clock.instant().toEpochMicro())
        if (packets.isEmpty()) {
            return false
        }

        var overuse = false
        bitrateObserver.reset()
        clock.elapse(Duration.between(clock.instant(), packets.last().receiveTime))

        for (packet in packets) {
            check(packet.receiveTime.toEpochMilli() + arrivalTimeOffsetMs >= 0)
            packet.receiveTime += Duration.ofMillis(arrivalTimeOffsetMs)

            if (packet.sentPacket.pacingInfo.probeClusterId != PacedPacketInfo.kNotAProbe) {
                probeBitrateEstimator.handleProbeAndEstimateBitrate(packet)
            }
        }

        acknowledgedBitrateEstimator.incomingPacketFeedbackVector(packets)
        val msg = TransportPacketsFeedback()
        msg.packetFeedbacks = packets
        msg.feedbackTime = clock.instant()

        val result =
            bitrateEstimator.incomingPacketFeedbackVector(
                msg,
                acknowledgedBitrateEstimator.bitrate(),
                probeBitrateEstimator.fetchAndResetLastEstimatedBitrate(),
                false
            )
        if (result.updated) {
            bitrateObserver.onReceivedBitrateChanged(result.targetBitrate.bps)
            if (!firstUpdate && result.targetBitrate.bps < bitrateBps) {
                overuse = true
            }
            firstUpdate = false
        }

        clock.setTime(instantOfEpochMicro(nextTimeUs))
        return overuse
    }

    /** Run the bandwidth estimator with a stream of `number_of_frames` frames, or
     until it reaches `target_bitrate`.
     Can for instance be used to run the estimator for some time to get it
     into a steady state. */
    fun steadyStateRun(
        ssrc: Long,
        maxNumberOfFrames: Int,
        startBitrate: Long,
        minBitrate: Long,
        maxBitrate: Long,
        targetBitrate: Long
    ): Long {
        var bitrateBps = startBitrate
        var bitrateUpdateSeen = false
        for (i in 0 until maxNumberOfFrames) {
            // Produce `number_of_frames` frames and give them to the estimator.
            val overuse = generateAndProcessFrame(ssrc, bitrateBps)
            if (overuse) {
                check(bitrateObserver.latestBitrate <= maxBitrate)
                check(bitrateObserver.latestBitrate >= minBitrate)
                bitrateBps = bitrateObserver.latestBitrate
                bitrateUpdateSeen = true
            } else if (bitrateObserver.updated) {
                bitrateBps = bitrateObserver.latestBitrate
                bitrateObserver.reset()
            }
            if (bitrateUpdateSeen && bitrateBps >= targetBitrate) {
                break
            }
        }
        check(bitrateUpdateSeen)
        return bitrateBps
    }

    fun initialBehaviorTestHelper(expectedConvergeBitrate: Long) {
        val kFramerate = 50 // 50 fps to avoid rounding errors.
        val kFrameIntervalMs = 1000L / kFramerate
        val kPacingInfo = PacedPacketInfo(0, 5, 5000)
        var sendTimeMs = 0L
        check(bitrateEstimator.latestEstimate() == null)
        check(!bitrateObserver.updated)
        bitrateObserver.reset()
        clock.elapse(1000.ms)
        // Inserting packets for 5 seconds to get a valid estimate.
        for (i in 0 until 5 * kFramerate + 1 + kNumInitialPackets) {
            val pacingInfo = if (i < kInitialProbingPackets) {
                kPacingInfo
            } else {
                PacedPacketInfo()
            }
            if (i == kNumInitialPackets) {
                check(bitrateEstimator.latestEstimate() == null)
                check(!bitrateObserver.updated)
                bitrateObserver.reset()
            }
            incomingFeedback(clock.millis(), sendTimeMs, kMtu, pacingInfo)
            clock.elapse((1000 / kFramerate).ms)
            sendTimeMs = kFrameIntervalMs
        }
        val bitrate = bitrateEstimator.latestEstimate()
        check(bitrate != null)
        check(
            bitrate.bps in
                expectedConvergeBitrate - kAcceptedBitrateErrorBps..expectedConvergeBitrate + kAcceptedBitrateErrorBps
        )
        check(bitrateObserver.updated)
        check(bitrateObserver.latestBitrate == bitrate.bps)
    }

    fun testTimestampGroupingTestHelper() {
    }

    fun testWrappingHelper(silenceTimeS: Int) {
    }

    fun rateIncreaseReorderingTestHelper(expectedBitrate: Long) {
    }

    fun rateIncreaseRtpTimestampsTestHelper(expectedIterations: Int) {
    }

    fun capacityDropTestHelper(
        numberOfStreams: Int,
        wrapTimeStamp: Boolean,
        expectedBitrateDropDelta: Long,
        receiverClockOffsetChangeMs: Long
    ) {
    }

    companion object {
        const val kMtu = 1200L
        const val kAcceptedBitrateErrorBps = 50000

        // Number of packets needed before we have a valid estimate.
        const val kNumInitialPackets = 2
        const val kInitialProbingPackets = 5
    }
}
