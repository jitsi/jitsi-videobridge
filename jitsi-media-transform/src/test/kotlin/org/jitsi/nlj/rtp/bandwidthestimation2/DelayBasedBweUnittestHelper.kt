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

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.util.bytes
import org.jitsi.utils.instantOfEpochMicro
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.ms
import org.jitsi.utils.roundedMillis
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import org.jitsi.utils.toEpochMicro
import org.jitsi.utils.toRoundedEpochMilli
import java.lang.Long.max
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Helper classes for the unit tests for Delay-Based BWE,
 * based on WebRTC modules/congestion_controller/goog_cc/delay_based_bwe_unittest_helper.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
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
    var bitrateBps: Long
) {
    init {
        require(fps > 0)
    }

    var nextRtpTime: Long = 0L
        private set

    // Generates a new frame for this stream. If called too soon after the
    // previous frame, no frame will be generated. The frame is split into
    // packets.
    fun generateFrame(timeNowUs: Long, nextSequenceNumber: Ref<Long>, packets: MutableList<PacketResult>): Long {
        if (timeNowUs < nextRtpTime) {
            return nextRtpTime
        }
        val bitsPerFrame = (bitrateBps + fps / 2) / fps
        val nPackets = ((bitsPerFrame + 4 * kMtu) / (8 * kMtu)).coerceAtLeast(1).toInt()
        val payloadSize = (bitsPerFrame + 4 * nPackets) / (8 * nPackets)
        repeat(nPackets) {
            val packet = PacketResult()
            packet.sentPacket.sendTime = instantOfEpochMicro(timeNowUs + kSendSideOffsetUs)
            packet.sentPacket.size = payloadSize.bytes
            packet.sentPacket.sequenceNumber = nextSequenceNumber.v++
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
    private var capacity: Long,
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
    fun setCapacityBps(capacityBps: Long) {
        require(capacityBps > 0)
        capacity = capacityBps
    }

    /** Divides `bitrate_bps` among all streams. The allocated bitrate per stream
     is decided by the initial allocation ratios. */
    fun setBitrateBps(bitrateBps: Long) {
        check(streams.isNotEmpty())
        var totalBitrateBefore = 0L
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
            stream.bitrateBps = bitrateAfter - totalBitrateAfter
            totalBitrateAfter += stream.bitrateBps
        }
        check(bitrateBefore == totalBitrateBefore)
        check(totalBitrateAfter == bitrateBps)
    }

    // TODO(holmer): Break out the channel simulation part from this class to make
    //  it possible to simulate different types of channels.
    fun generateFrame(packets: MutableList<PacketResult>, nextSequenceNumber: Ref<Long>, timeNowUs: Long): Long {
        check(packets.isEmpty())
        check(capacity > 0)
        var it = streams.minByOrNull { it.nextRtpTime }
        it!!.generateFrame(timeNowUs, nextSequenceNumber, packets)
        for (packet in packets) {
            val capacityBpus = capacity / 1000
            val requiredNetworkTimeUs =
                (8 * 1000 * packet.sentPacket.size.bytes.toLong() + capacityBpus / 2) /
                    capacityBpus
            prevArrivalTimeUs =
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
    val clock = FakeClock().also { it.setTime(instantOfEpochMicro(100000000)) }
    val bitrateObserver = TestBitrateObserver()
    val acknowledgedBitrateEstimator: AcknowledgedBitrateEstimatorInterface =
        AcknowledgedBitrateEstimatorInterface.create()
    val probeBitrateEstimator = ProbeBitrateEstimator(parentLogger, diagnosticContext)
    val bitrateEstimator = DelayBasedBwe(parentLogger, diagnosticContext)
    val streamGenerator = StreamGenerator(1e6.toLong(), clock.instant().toEpochMicro())

    var arrivalTimeOffsetMs: Long = 0L
    var nextSequenceNumber: Ref<Long> = Ref(0)
    var firstUpdate: Boolean = true

    fun addDefaultStream() {
        streamGenerator.addStream(RtpStream(30, 3e5.toLong()))
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
        packet.sentPacket.sequenceNumber = nextSequenceNumber.v++
        if (packet.sentPacket.pacingInfo.probeClusterId != PacedPacketInfo.kNotAProbe) {
            probeBitrateEstimator.handleProbeAndEstimateBitrate(packet)
        }

        val msg = TransportPacketsFeedback()
        msg.feedbackTime = clock.instant()
        msg.packetFeedbacks.add(packet)
        acknowledgedBitrateEstimator.incomingPacketFeedbackVector(msg.sortedByReceiveTime())
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

    /** Generates a frame of packets belonging to a stream at a given bitrate ~~and
     with a given ssrc~~. The stream is pushed through a very simple simulated
     network, and is then given to the receive-side bandwidth estimator.
     Returns true if an over-use was seen, false otherwise.
     The StreamGenerator::updated() should be used to check for any changes in
     target bitrate after the call to this function. */
    fun generateAndProcessFrame(bitrateBps: Long): Boolean {
        streamGenerator.setBitrateBps(bitrateBps)
        val packets = ArrayList<PacketResult>()

        val nextTimeUs = streamGenerator.generateFrame(packets, nextSequenceNumber, clock.instant().toEpochMicro())
        if (packets.isEmpty()) {
            return false
        }

        var overuse = false
        bitrateObserver.reset()
        clock.elapse(Duration.between(clock.instant(), packets.last().receiveTime))

        for (packet in packets) {
            check(packet.receiveTime.toRoundedEpochMilli() + arrivalTimeOffsetMs >= 0)
            packet.receiveTime += Duration.ofMillis(arrivalTimeOffsetMs)

            if (packet.sentPacket.pacingInfo.probeClusterId != PacedPacketInfo.kNotAProbe) {
                probeBitrateEstimator.handleProbeAndEstimateBitrate(packet)
            }
        }

        acknowledgedBitrateEstimator.incomingPacketFeedbackVector(packets)
        val msg = TransportPacketsFeedback()
        msg.packetFeedbacks = packets
        msg.feedbackTime = Instant.ofEpochMilli(clock.roundedMillis())

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
            val overuse = generateAndProcessFrame(bitrateBps)
            if (overuse) {
                bitrateObserver.latestBitrate shouldBeLessThanOrEqual maxBitrate
                bitrateObserver.latestBitrate shouldBeGreaterThanOrEqual minBitrate
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
        bitrateUpdateSeen shouldBe true
        return bitrateBps
    }

    fun initialBehaviorTestHelper(expectedConvergeBitrate: Long) {
        val kFramerate = 50 // 50 fps to avoid rounding errors.
        val kFrameIntervalMs = 1000L / kFramerate
        val kPacingInfo = PacedPacketInfo(0, 5, 5000)
        var sendTimeMs = 0L
        bitrateEstimator.latestEstimate() shouldBe null
        bitrateObserver.updated shouldBe false
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
                bitrateEstimator.latestEstimate() shouldBe null
                bitrateObserver.updated shouldBe false
                bitrateObserver.reset()
            }
            incomingFeedback(clock.roundedMillis(), sendTimeMs, kMtu, pacingInfo)
            clock.elapse((1000 / kFramerate).ms)
            sendTimeMs = kFrameIntervalMs
        }
        val bitrate = bitrateEstimator.latestEstimate()
        bitrate shouldNotBe null
        bitrate!!.bps shouldBeInRange (expectedConvergeBitrate plusOrMinus kAcceptedBitrateErrorBps)
        bitrateObserver.updated shouldBe true
        bitrateObserver.latestBitrate shouldBe bitrate.bps
    }

    fun rateIncreaseReorderingTestHelper(expectedBitrateBps: Long) {
        val kFramerate = 50 // fps to avoid rounding errors.
        val kFrameIntervalMs = 1000L / kFramerate
        val kPacingInfo = PacedPacketInfo(0, 5, 5000)
        var sendTimeMs = 0L
        // Inserting packets for five seconds to get a valid estimate.
        for (i in 0 until 5 * kFramerate + 1 + kNumInitialPackets) {
            // NOTE!!! If the following line is moved under the if case then this test
            //         wont work on windows realease bots.
            val pacingInfo = if (i < kInitialProbingPackets) kPacingInfo else PacedPacketInfo()

            // TODO(sprang): Remove this hack once the single stream estimator is gone,
            //  as it doesn't do anything in Process().
            if (i == kNumInitialPackets) {
                // Process after we have enough frames to get a valid input rate estimate.

                bitrateObserver.updated shouldBe false // No valid estimate.
            }
            incomingFeedback(clock.roundedMillis(), sendTimeMs, kMtu, pacingInfo)
            clock.elapse(kFrameIntervalMs.ms)
            sendTimeMs += kFrameIntervalMs
        }
        bitrateObserver.updated shouldBe true
        expectedBitrateBps shouldBeInRange (bitrateObserver.latestBitrate plusOrMinus kAcceptedBitrateErrorBps)

        repeat(10) {
            clock.elapse((2 * kFrameIntervalMs).ms)
            sendTimeMs += 2 * kFrameIntervalMs
            incomingFeedback(clock.roundedMillis(), sendTimeMs, 1000)
            incomingFeedback(clock.roundedMillis(), sendTimeMs - kFrameIntervalMs, 1000)
        }
        bitrateObserver.updated shouldBe true
        expectedBitrateBps shouldBeInRange (bitrateObserver.latestBitrate plusOrMinus kAcceptedBitrateErrorBps)
    }

    // Make sure we initially increase the bitrate as expected.
    fun rateIncreaseRtpTimestampsTestHelper(expectedIterations: Int) {
        // This threshold corresponds approximately to increasing linearly with
        // bitrate(i) = 1.04 * bitrate(i-1) + 1000
        // until bitrate(i) > 500000, with bitrate(1) ~= 30000.
        var bitrateBps = 30000L
        var iterations = 0
        addDefaultStream()
        // Feed the estimator with a stream of packets and verify that it reaches
        // 500 kbps at the expected time.
        while (bitrateBps < 5e5) {
            val overuse = generateAndProcessFrame(bitrateBps)
            if (overuse) {
                bitrateObserver.latestBitrate shouldBeGreaterThan bitrateBps
                bitrateBps = bitrateObserver.latestBitrate
                bitrateObserver.reset()
            } else if (bitrateObserver.updated) {
                bitrateBps = bitrateObserver.latestBitrate
                bitrateObserver.reset()
            }
            ++iterations
        }
        iterations shouldBe expectedIterations
    }

    fun capacityDropTestHelper(
        numberOfStreams: Int,
        wrapTimeStamp: Boolean,
        expectedBitrateDropDelta: Long,
        receiverClockOffsetChangeMs: Long
    ) {
        val kFramerate = 30
        val kStartBitrate = 900e3.toLong()
        val kMinExpectedBitrate = 800e3.toLong()
        val kMaxExpectedBitrate = 1100e3.toLong()
        val kInitialCapacityBps = 1000e3.toLong()
        val kReducedCapacityBps = 500e3.toLong()

        var steadyStateTime = 0
        if (numberOfStreams <= 1) {
            steadyStateTime = 10
            addDefaultStream()
        } else {
            steadyStateTime = 10 * numberOfStreams
            var bitrateSum = 0L
            val kBitrateDenom = numberOfStreams * (numberOfStreams - 1)
            for (i in 0 until numberOfStreams) {
                // First stream gets half available bitrate, while the rest share the
                // remaining half i.e.: 1/2 = Sum[n/(N*(N-1))] for n=1..N-1 (rounded up)
                var bitrate = kStartBitrate / 2
                if (i > 0) {
                    bitrate = (kStartBitrate * i + kBitrateDenom / 2) / kBitrateDenom
                }
                streamGenerator.addStream(RtpStream(kFramerate, bitrate))
                bitrateSum += bitrate
            }
            check(bitrateSum == kStartBitrate)
        }

        // Run in steady state to make the estimator converge.
        streamGenerator.setCapacityBps(kInitialCapacityBps)
        var bitrateBps = steadyStateRun(
            steadyStateTime * kFramerate,
            kStartBitrate,
            kMinExpectedBitrate,
            kMaxExpectedBitrate,
            kInitialCapacityBps
        )
        bitrateBps shouldBeInRange (kInitialCapacityBps plusOrMinus 180000)
        bitrateObserver.reset()

        // Add an offset to make sure the BWE can handle it.
        arrivalTimeOffsetMs += receiverClockOffsetChangeMs

        // Reduce the capacity and verify the decrease time.
        streamGenerator.setCapacityBps(kReducedCapacityBps)
        val overuseStartTime = clock.roundedMillis()
        var bitrateDropTime = -1L
        for (i in 0 until 100 * numberOfStreams) {
            generateAndProcessFrame(bitrateBps)
            if (bitrateDropTime == -1L &&
                bitrateObserver.latestBitrate <= kReducedCapacityBps
            ) {
                bitrateDropTime = clock.roundedMillis()
            }
            if (bitrateObserver.updated) {
                bitrateBps = bitrateObserver.latestBitrate
            }
        }

        bitrateDropTime - overuseStartTime shouldBeInRange (expectedBitrateDropDelta plusOrMinus 33)
    }

    fun testTimestampGroupingTestHelper() {
        val kFramerate = 50 // 50 fps to avoid rounding errors.
        val kFrameIntervalMs = 1000L / kFramerate
        var sendTimeMs = 0L
        // Initial set of frames to increase the bitrate. 6 seconds to have enough
        // time for the first estimate to be generated and for Process() to be called.
        for (i in 0 until 6 * kFramerate) {
            incomingFeedback(clock.roundedMillis(), sendTimeMs, 1000)

            clock.elapse(kFrameIntervalMs.ms)
            sendTimeMs += kFrameIntervalMs
        }
        bitrateObserver.updated shouldBe true
        bitrateObserver.latestBitrate shouldBeGreaterThanOrEqual 400000

        // Insert batches of frames which were sent very close in time. Also simulate
        // capacity over-use to see that we back off correctly.
        val kTimestampGroupLength = 15
        repeat(100) {
            repeat(kTimestampGroupLength) {
                // Insert `kTimestampGroupLength` frames with just 1 timestamp ticks in
                // between. Should be treated as part of the same group by the estimator.
                incomingFeedback(clock.roundedMillis(), sendTimeMs, 100)
                clock.elapse((kFrameIntervalMs / kTimestampGroupLength).ms)
                sendTimeMs += 1
            }
            // Increase time until next batch to simulate over-use.
            clock.elapse(10.ms)
            sendTimeMs += kFrameIntervalMs - kTimestampGroupLength
        }
        bitrateObserver.updated shouldBe true
        // Should have reduced the estimate.
        bitrateObserver.latestBitrate shouldBeLessThan 400000
    }

    fun testWrappingHelper(silenceTimeS: Int) {
        val kFramerate = 100
        val kFrameIntervalMs = 1000 / kFramerate
        var sendTimeMs = 0L

        repeat(3000) {
            incomingFeedback(clock.roundedMillis(), sendTimeMs, 1000)
            clock.elapse(kFrameIntervalMs.ms)
            sendTimeMs += kFrameIntervalMs
        }
        val bitrateBefore = bitrateEstimator.latestEstimate()

        clock.elapse(silenceTimeS.secs)

        repeat(24) {
            incomingFeedback(clock.roundedMillis(), sendTimeMs, 1000)
            clock.elapse((2 * kFrameIntervalMs).ms)
            sendTimeMs += kFrameIntervalMs
        }
        val bitrateAfter = bitrateEstimator.latestEstimate()
        bitrateAfter!! shouldBeLessThan bitrateBefore!!
    }

    companion object {
        const val kMtu = 1200L
        const val kAcceptedBitrateErrorBps = 50000L

        // Number of packets needed before we have a valid estimate.
        const val kNumInitialPackets = 2
        const val kInitialProbingPackets = 5
    }
}
