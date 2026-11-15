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

package org.jitsi.nlj.rtp.pacing

import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.FreeSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.rtp.bandwidthestimation2.BitrateProberConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.PacedPacketInfo
import org.jitsi.nlj.rtp.bandwidthestimation2.PacerConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.ProbeClusterConfig
import org.jitsi.nlj.rtp.scream.dataSentAt
import org.jitsi.nlj.rtp.scream.rateOf
import org.jitsi.nlj.rtp.scream.ratioTo
import org.jitsi.nlj.rtp.scream.scaledBy
import org.jitsi.nlj.rtp.scream.timeBetween
import org.jitsi.nlj.rtp.scream.timeToSend
import org.jitsi.nlj.rtp.scream.wholeBytes
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.bytesPerSec
import org.jitsi.nlj.util.kbps
import org.jitsi.utils.NEVER
import org.jitsi.utils.isFinite
import org.jitsi.utils.isInfinite
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.micros
import org.jitsi.utils.ms
import org.jitsi.utils.roundTo
import org.jitsi.utils.roundedMillis
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import org.jitsi.utils.toMicros
import org.jitsi.utils.toRoundedEpochMilli
import org.jitsi.utils.toRoundedMillis
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

private val kFirstClusterRate = 900.kbps
private val kSecondClusterRate = 1800.kbps

// The error stems from truncating the time interval of probe packets to integer
// values. This results in probing slightly higher than the target bitrate.
// For 1.8 Mbps, this comes to be about 120 kbps with 1200 probe packets.
private val kProbingErrorMargin = 150.kbps

private const val kPaceMultiplier = 2.5f

private const val kAudioSsrc = 12345L
private const val kVideoSsrc = 234565L

private val kTargetRate = 800.kbps

/** The size of an RTP header without extensions, which the packets of the tests in WebRTC have. */
private const val kHeadersSize = 12

private fun buildPacket(
    type: RtpPacketMediaType,
    ssrc: Long,
    sequenceNumber: Int,
    captureTimeMs: Long,
    payloadSize: Int,
    // Jitsi local: WebRTC sets these with set_is_key_frame and set_first_packet_of_frame after BuildPacket.
    isKeyFrame: Boolean = false,
    isFirstPacketOfFrame: Boolean = false
) = RtpPacketToSend(
    packetType = type,
    ssrc = ssrc,
    sequenceNumber = sequenceNumber,
    headersSize = kHeadersSize,
    payloadSize = payloadSize,
    isKeyFrame = isKeyFrame,
    isFirstPacketOfFrame = isFirstPacketOfFrame,
    captureTime = Instant.ofEpochMilli(captureTimeMs)
)

/** Jitsi local: the packet that `RtpPacketToSend(nullptr)` and `SetPadding(paddingSize)` build in WebRTC. */
private fun buildPaddingPacket(paddingSize: Int) = RtpPacketToSend(
    packetType = RtpPacketMediaType.kPadding,
    ssrc = 0,
    sequenceNumber = 0,
    headersSize = kHeadersSize,
    payloadSize = 0,
    paddingSize = paddingSize
)

/**
 * Jitsi local: replaces CreateTestFieldTrials. The default of max_probe_delay is 10 ms in WebRTC and 20 ms in
 * BitrateProberConfig, so it is always set here.
 */
private fun createTestFieldTrials(
    // WebRTC-Pacer-PadInSilence/Enabled/
    padInSilence: Boolean = false,
    // WebRTC-Pacer-BlockAudio/Enabled/
    blockAudio: Boolean = false,
    // WebRTC-Pacer-FastRetransmissions/Enabled/
    fastRetransmissions: Boolean = false,
    // WebRTC-RetransmitFlushedPackets/Enabled/
    retransmitFlushedPackets: Boolean = false,
    // WebRTC-Bwe-ProbingBehavior/max_probe_delay
    maxProbeDelay: Duration = 10.ms
) = PacingController.FieldTrials(
    padInSilence = padInSilence,
    blockAudio = blockAudio,
    fastRetransmissions = fastRetransmissions,
    retransmitFlushedPackets = retransmitFlushedPackets,
    proberConfig = BitrateProberConfig(maxProbeDelay = maxProbeDelay)
)

/** Jitsi local: EXPECT_NEAR. It compares its arguments as doubles. */
private fun expectNear(val1: Number, val2: Number, absError: Number) {
    withClue("The difference between $val1 and $val2") {
        abs(val1.toDouble() - val2.toDouble()) shouldBeLessThanOrEqualTo absError.toDouble()
    }
}

private class MediaStream(
    private val clock: FakeClock,
    private val type: RtpPacketMediaType,
    private val ssrc: Long,
    private val packetSize: Int
) {
    fun buildNextPacket(): RtpPacketToSend = buildPacket(type, ssrc, seqNum++, clock.roundedMillis(), packetSize)

    fun buildNextPacket(size: Int): RtpPacketToSend = buildPacket(type, ssrc, seqNum++, clock.roundedMillis(), size)

    private var seqNum = 1000
}

/*
 * Jitsi local: a replacement for the parts of gmock that the tests in WebRTC use. It has the semantics of gmock:
 *
 * - The expectations of a function are searched from the newest to the oldest. The first expectation that matches
 *   the arguments, is not retired, and has all its sequence prerequisites satisfied handles the call.
 * - A call fails if the expectation that handles it has already reached its maximum call count.
 * - A call fails if the function has expectations and no expectation handles the call.
 * - A call to a function with no expectations runs the default action. gmock allows this for a plain mock and for
 *   a NiceMock, and only warns for a plain mock, so Mock replaces both.
 * - Without times(), the expected call count is the number of willOnce() actions. It is a minimum if there is a
 *   willRepeatedly() action. It is 1 if there are no actions.
 * - The willOnce() actions run in order, then the willRepeatedly() action, or the default action if there is none.
 * - An expectation that handles a call retires the expectations before it in its sequence.
 * - verifyAndClearExpectations() verifies the minimum call counts. afterTest also verifies them, because gmock
 *   verifies a mock when the mock is destroyed.
 *
 * A failure throws an AssertionError immediately. gmock records the failure and continues the test.
 */

/** The state that the mocks and the expectations of the running test share. */
private class MockState {
    /** The sequence that MockFunction.expectCall adds a new expectation to. It is set while an [InSequence] is open. */
    var implicitSequence: Sequence? = null

    /** The mocks of the running test. afterTest verifies them. */
    val mocks = mutableListOf<Mock>()
}

private val mockState = MockState()

/** The file and line of the test that set an expectation, for failure messages. */
private fun callSite(): String {
    val testClassName = PacingControllerTest::class.java.name
    val frame = Throwable().stackTrace.firstOrNull { it.className.substringBefore('$') == testClassName }
    return frame?.let { "${it.fileName}:${it.lineNumber}" } ?: "an unknown place"
}

private fun anyNumber() = 0..Int.MAX_VALUE

/** The gmock wildcard `_` in the arguments of an expectation. */
private fun <T> any(): T? = null

private class Sequence {
    private var last: Expectation<*, *>? = null

    fun add(expectation: Expectation<*, *>) {
        last?.let { expectation.prerequisites.add(it) }
        last = expectation
    }
}

/** gmock InSequence. Expectations that are set while an instance is open form a sequence in the order they are set. */
private class InSequence : AutoCloseable {
    private val sequenceCreated = mockState.implicitSequence == null

    init {
        if (sequenceCreated) {
            mockState.implicitSequence = Sequence()
        }
    }

    override fun close() {
        if (sequenceCreated) {
            mockState.implicitSequence = null
        }
    }
}

private class Expectation<A, R>(
    private val function: MockFunction<A, R>,
    private val matcher: (A) -> Boolean
) {
    private val source = callSite()
    private var cardinality: IntRange? = null
    private val onceActions = mutableListOf<(A) -> R>()
    private var repeatedAction: ((A) -> R)? = null
    val prerequisites = mutableListOf<Expectation<*, *>>()
    private var callCount = 0
    private var retired = false

    fun times(n: Int): Expectation<A, R> = times(n..n)

    fun times(range: IntRange): Expectation<A, R> = apply { cardinality = range }

    fun willOnce(action: (A) -> R): Expectation<A, R> = apply { onceActions.add(action) }

    fun willRepeatedly(action: (A) -> R): Expectation<A, R> = apply { repeatedAction = action }

    fun inSequence(sequence: Sequence): Expectation<A, R> = apply { sequence.add(this) }

    private val bounds: IntRange
        get() = cardinality ?: when {
            repeatedAction != null -> onceActions.size..Int.MAX_VALUE
            onceActions.isEmpty() -> 1..1
            else -> onceActions.size..onceActions.size
        }

    private fun isSatisfied() = callCount >= bounds.first

    private fun isSaturated() = callCount >= bounds.last

    private fun allPrerequisitesAreSatisfied(): Boolean =
        prerequisites.all { it.isSatisfied() && it.allPrerequisitesAreSatisfied() }

    private fun retireAllPrerequisites() {
        for (prerequisite in prerequisites) {
            if (!prerequisite.retired) {
                prerequisite.retired = true
                prerequisite.retireAllPrerequisites()
            }
        }
    }

    fun shouldHandle(args: A): Boolean = !retired && allPrerequisitesAreSatisfied() && matcher(args)

    fun perform(args: A): R {
        if (isSaturated()) {
            callCount++
            throw AssertionError(
                "${function.name}($args) was called more times than expected. ${describeCallCount()}"
            )
        }
        callCount++
        retireAllPrerequisites()
        val action = onceActions.getOrNull(callCount - 1) ?: repeatedAction
        return if (action != null) action(args) else function.defaultResult
    }

    /** Returns a failure message if the call count is below the minimum. */
    fun verify(): String? = if (isSatisfied()) null else describeCallCount()

    private fun describeCallCount(): String {
        val expected = when {
            bounds.last == Int.MAX_VALUE -> "at least ${bounds.first} times"
            bounds.first == bounds.last -> "${bounds.first} times"
            else -> "between ${bounds.first} and ${bounds.last} times"
        }
        return "The expectation of ${function.name} set at $source: expected $expected, called $callCount times."
    }
}

private class MockFunction<A, R>(val name: String, val defaultResult: R) {
    private val expectations = mutableListOf<Expectation<A, R>>()

    fun expectCall(matcher: (A) -> Boolean = { true }): Expectation<A, R> {
        val expectation = Expectation(this, matcher)
        expectations.add(expectation)
        mockState.implicitSequence?.add(expectation)
        return expectation
    }

    fun call(args: A): R {
        if (expectations.isEmpty()) {
            return defaultResult
        }
        val expectation = expectations.lastOrNull { it.shouldHandle(args) }
            ?: throw AssertionError("Unexpected call of $name($args): no expectation handles it.")
        return expectation.perform(args)
    }

    fun verifyAndClearExpectations(): List<String> {
        val failures = expectations.mapNotNull { it.verify() }
        expectations.clear()
        return failures
    }
}

private abstract class Mock {
    private val functions = mutableListOf<MockFunction<*, *>>()

    init {
        mockState.mocks.add(this)
    }

    protected fun <A, R> mockFunction(name: String, defaultResult: R): MockFunction<A, R> =
        MockFunction<A, R>(name, defaultResult).also { functions.add(it) }

    fun verifyAndClearExpectations() {
        val failures = functions.flatMap { it.verifyAndClearExpectations() }
        if (failures.isNotEmpty()) {
            throw AssertionError(failures.joinToString("\n"))
        }
    }
}

/** The arguments of the mock method SendPacket of MockPacingControllerCallback. */
private data class SendPacketArgs(
    val ssrc: Long,
    val sequenceNumber: Int,
    val captureTimestamp: Long,
    val retransmission: Boolean,
    val padding: Boolean
)

// Mock callback proxy, where both new and old api redirects to common mock
// methods that focus on core aspects.
private class MockPacingControllerCallback : Mock(), PacingController.PacketSender {
    override fun sendPacket(packet: RtpPacketToSend, clusterInfo: PacedPacketInfo) {
        sendPacket.call(
            SendPacketArgs(
                packet.ssrc,
                packet.sequenceNumber,
                packet.captureTime.toRoundedEpochMilli(),
                packet.packetType == RtpPacketMediaType.kRetransmission,
                packet.packetType == RtpPacketMediaType.kPadding
            )
        )
    }

    override fun generatePadding(size: DataSize): List<RtpPacketToSend> {
        val ret = mutableListOf<RtpPacketToSend>()
        val paddingSize = sendPadding.call(size.wholeBytes().toInt())
        if (paddingSize > 0) {
            ret.add(
                RtpPacketToSend(
                    packetType = RtpPacketMediaType.kPadding,
                    ssrc = 0,
                    sequenceNumber = 0,
                    headersSize = kHeadersSize,
                    payloadSize = paddingSize
                )
            )
        }
        return ret
    }

    override fun fetchFec(): List<RtpPacketToSend> = fetchFec.call(Unit)

    override fun onAbortedRetransmissions(ssrc: Long, sequenceNumbers: List<Int>) =
        onAbortedRetransmissions.call(Pair(ssrc, sequenceNumbers))

    override fun getRtxSsrcForMedia(ssrc: Long): Long? = getRtxSsrcForMedia.call(ssrc)

    override fun onBatchComplete() = onBatchComplete.call(Unit)

    private val sendPacket = mockFunction<SendPacketArgs, Unit>("SendPacket", Unit)
    private val fetchFec = mockFunction<Unit, List<RtpPacketToSend>>("FetchFec", emptyList())
    private val sendPadding = mockFunction<Int, Int>("SendPadding", 0)
    private val onAbortedRetransmissions = mockFunction<Pair<Long, List<Int>>, Unit>("OnAbortedRetransmissions", Unit)
    private val getRtxSsrcForMedia = mockFunction<Long, Long?>("GetRtxSsrcForMedia", null)
    private val onBatchComplete = mockFunction<Unit, Unit>("OnBatchComplete", Unit)

    /** Jitsi local: EXPECT_CALL for SendPacket. An argument that is [any] matches every value. */
    fun expectSendPacket(
        ssrc: Long? = any(),
        sequenceNumber: Int? = any(),
        captureTimestamp: Long? = any(),
        retransmission: Boolean? = any(),
        padding: Boolean? = any()
    ): Expectation<SendPacketArgs, Unit> = sendPacket.expectCall {
        (ssrc == null || it.ssrc == ssrc) &&
            (sequenceNumber == null || it.sequenceNumber == sequenceNumber) &&
            (captureTimestamp == null || it.captureTimestamp == captureTimestamp) &&
            (retransmission == null || it.retransmission == retransmission) &&
            (padding == null || it.padding == padding)
    }

    fun expectFetchFec(): Expectation<Unit, List<RtpPacketToSend>> = fetchFec.expectCall()

    fun expectSendPadding(targetSize: Int? = any()): Expectation<Int, Int> =
        sendPadding.expectCall { targetSize == null || it == targetSize }

    fun expectGetRtxSsrcForMedia(ssrc: Long? = any()): Expectation<Long, Long?> =
        getRtxSsrcForMedia.expectCall { ssrc == null || it == ssrc }
}

// Mock callback implementing the raw api.
private class MockPacketSender : Mock(), PacingController.PacketSender {
    override fun sendPacket(packet: RtpPacketToSend, clusterInfo: PacedPacketInfo) =
        sendPacket.call(Pair(packet, clusterInfo))

    override fun fetchFec(): List<RtpPacketToSend> = fetchFec.call(Unit)

    override fun generatePadding(size: DataSize): List<RtpPacketToSend> = generatePadding.call(size)

    override fun onAbortedRetransmissions(ssrc: Long, sequenceNumbers: List<Int>) =
        onAbortedRetransmissions.call(Pair(ssrc, sequenceNumbers))

    override fun getRtxSsrcForMedia(ssrc: Long): Long? = getRtxSsrcForMedia.call(ssrc)

    override fun onBatchComplete() = onBatchComplete.call(Unit)

    private val sendPacket = mockFunction<Pair<RtpPacketToSend, PacedPacketInfo>, Unit>("SendPacket", Unit)
    private val fetchFec = mockFunction<Unit, List<RtpPacketToSend>>("FetchFec", emptyList())
    private val generatePadding = mockFunction<DataSize, List<RtpPacketToSend>>("GeneratePadding", emptyList())
    private val onAbortedRetransmissions = mockFunction<Pair<Long, List<Int>>, Unit>("OnAbortedRetransmissions", Unit)
    private val getRtxSsrcForMedia = mockFunction<Long, Long?>("GetRtxSsrcForMedia", null)
    private val onBatchComplete = mockFunction<Unit, Unit>("OnBatchComplete", Unit)

    /** Jitsi local: EXPECT_CALL for SendPacket. [matcher] replaces the gmock argument matchers. */
    fun expectSendPacket(
        matcher: (packet: RtpPacketToSend, clusterInfo: PacedPacketInfo) -> Boolean = { _, _ -> true }
    ): Expectation<Pair<RtpPacketToSend, PacedPacketInfo>, Unit> =
        sendPacket.expectCall { matcher(it.first, it.second) }

    fun expectGeneratePadding(targetSize: DataSize? = any()): Expectation<DataSize, List<RtpPacketToSend>> =
        generatePadding.expectCall { targetSize == null || it == targetSize }

    fun expectGetRtxSsrcForMedia(ssrc: Long? = any()): Expectation<Long, Long?> =
        getRtxSsrcForMedia.expectCall { ssrc == null || it == ssrc }

    fun expectOnBatchComplete(): Expectation<Unit, Unit> = onBatchComplete.expectCall()
}

private class PacingControllerPadding : PacingController.PacketSender {
    override fun sendPacket(packet: RtpPacketToSend, clusterInfo: PacedPacketInfo) {
        totalBytesSent += packet.payloadSize
    }

    override fun fetchFec(): List<RtpPacketToSend> = emptyList()

    override fun generatePadding(size: DataSize): List<RtpPacketToSend> {
        val numPackets = (size.wholeBytes() + kPaddingPacketSize - 1) / kPaddingPacketSize
        val packets = mutableListOf<RtpPacketToSend>()
        for (i in 0 until numPackets) {
            packets.add(buildPaddingPacket(kPaddingPacketSize))
            paddingSent += kPaddingPacketSize
        }
        return packets
    }

    override fun onAbortedRetransmissions(ssrc: Long, sequenceNumbers: List<Int>) {}

    override fun getRtxSsrcForMedia(ssrc: Long): Long? = null

    override fun onBatchComplete() {}

    var paddingSent = 0L
        private set
    var totalBytesSent = 0L
        private set

    companion object {
        const val kPaddingPacketSize = 224
    }
}

private class PacingControllerProbing : PacingController.PacketSender {
    // Controls if padding can be generated or not.
    // In real implementation, padding can only be generated after a sent
    // media packet, or if the sender support RTX.
    fun setCanGeneratePadding(canGenerate: Boolean) {
        canGeneratePadding = canGenerate
    }

    override fun sendPacket(packet: RtpPacketToSend, clusterInfo: PacedPacketInfo) {
        if (packet.packetType != RtpPacketMediaType.kPadding) {
            ++packetsSent
        } else {
            ++paddingPacketsSent
        }
        // Jitsi local: WebRTC copies the struct here. PacingController changes clusterInfo after the call, and
        // copy() omits probeClusterBytesSent.
        lastPacingInfo = clusterInfo.copy().also { it.probeClusterBytesSent = clusterInfo.probeClusterBytesSent }
    }

    override fun fetchFec(): List<RtpPacketToSend> = emptyList()

    override fun generatePadding(size: DataSize): List<RtpPacketToSend> {
        if (!canGeneratePadding) {
            return emptyList()
        }
        // From RTPSender:
        // Max in the RFC 3550 is 255 bytes, we limit it to be modulus 32 for SRTP.
        val kMaxPadding = 224.bytes

        val packets = mutableListOf<RtpPacketToSend>()
        var targetSize = size
        while (targetSize > DataSize.ZERO) {
            val paddingSize = minOf(kMaxPadding, targetSize)
            packets.add(buildPaddingPacket(paddingSize.wholeBytes().toInt()))
            paddingSent += paddingSize.wholeBytes().toInt()
            targetSize -= paddingSize
        }
        return packets
    }

    override fun onAbortedRetransmissions(ssrc: Long, sequenceNumbers: List<Int>) {}

    override fun getRtxSsrcForMedia(ssrc: Long): Long? = null

    override fun onBatchComplete() {}

    fun totalPacketsSent(): Int = packetsSent + paddingSent

    private var canGeneratePadding = true
    var packetsSent = 0
        private set
    var paddingPacketsSent = 0
        private set
    var paddingSent = 0
        private set
    var lastPacingInfo = PacedPacketInfo()
        private set
}

/** Unit tests for PacingController,
 * based on WebRTC modules/pacing/pacing_controller_unittest.cc in
 * WebRTC commit 42a497714596d8f726da4dae4fb4f133cbaa15df (main, 2026-11-15).
 *
 * Field trial settings are passed as PacingController.FieldTrials.
 */
class PacingControllerTest : FreeSpec() {
    /** Jitsi local: gtest creates a new fixture for each test, so each test runs in a new instance of the spec. */
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val logger = createLogger()

    private fun sendAndExpectPacket(
        pacer: PacingController,
        type: RtpPacketMediaType,
        ssrc: Long,
        sequenceNumber: Int,
        captureTimeMs: Long,
        size: Int
    ) {
        pacer.enqueuePacket(buildPacket(type, ssrc, sequenceNumber, captureTimeMs, size))

        callback.expectSendPacket(
            ssrc,
            sequenceNumber,
            captureTimeMs,
            type == RtpPacketMediaType.kRetransmission,
            false
        )
    }

    private fun advanceTimeUntil(time: Instant) {
        val now = clock.instant()
        clock.elapse(maxOf(Duration.ZERO, timeBetween(now, time)))
    }

    private fun consumeInitialBudget(pacer: PacingController) {
        val kSsrc = 54321L
        var sequenceNumber = 1234
        val captureTimeMs = clock.roundedMillis()
        val kPacketSize = 250

        pacer.oldestPacketEnqueueTime().isInfinite() shouldBe true

        // Due to the multiplicative factor we can send 5 packets during a send
        // interval. (network capacity * multiplier / (8 bits per byte *
        // (packet size * #send intervals per second)
        val packetsToSendPerInterval = (kTargetRate.bps * kPaceMultiplier / (8 * kPacketSize * 200)).toInt()
        for (i in 0 until packetsToSendPerInterval) {
            sendAndExpectPacket(
                pacer,
                RtpPacketMediaType.kVideo,
                kSsrc,
                sequenceNumber++,
                captureTimeMs,
                kPacketSize
            )
        }

        while (pacer.queueSizePackets() > 0) {
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
        }
    }

    private val clock = FakeClock().apply { setTime(Instant.ofEpochSecond(0, 123_456_000)) }

    private val audio = MediaStream(clock, type = RtpPacketMediaType.kAudio, ssrc = kAudioSsrc, packetSize = 100)
    private val video = MediaStream(clock, type = RtpPacketMediaType.kVideo, ssrc = kVideoSsrc, packetSize = 1000)

    private val callback = MockPacingControllerCallback()
    private val trials = createTestFieldTrials()

    /**
     * Jitsi local: gmock verifies the expectations of a mock when the mock is destroyed, at the end of the test.
     * This also closes an [InSequence] that is still open.
     */
    override suspend fun afterTest(testCase: TestCase, result: TestResult) {
        val testMocks = mockState.mocks.toList()
        mockState.mocks.clear()
        mockState.implicitSequence = null
        if (!result.isErrorOrFailure) {
            testMocks.forEach { it.verifyAndClearExpectations() }
        }
    }

    init {
        "DefaultNoPaddingInSilence" {
            val trials = createTestFieldTrials()
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), sendRate = kTargetRate, padRate = Bandwidth.ZERO))
            // Video packet to reset last send time and provide padding data.
            pacer.enqueuePacket(video.buildNextPacket())
            callback.expectSendPacket().times(1)
            clock.elapse(5.ms)
            pacer.processPackets()
            callback.expectSendPadding().times(0)
            // Waiting 500 ms should not trigger sending of padding.
            clock.elapse(500.ms)
            pacer.processPackets()
        }

        "PaddingInSilenceWithTrial" {
            val trials = createTestFieldTrials(padInSilence = true)
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), sendRate = kTargetRate, padRate = Bandwidth.ZERO))
            // Video packet to reset last send time and provide padding data.
            pacer.enqueuePacket(video.buildNextPacket())
            callback.expectSendPacket().times(2)
            clock.elapse(5.ms)
            pacer.processPackets()
            callback.expectSendPadding().willOnce { 1000 }
            // Waiting 500 ms should trigger sending of padding.
            clock.elapse(500.ms)
            pacer.processPackets()
        }

        "CongestionWindowAffectsAudioInTrial" {
            val trials = createTestFieldTrials(blockAudio = true)
            callback.expectSendPadding().times(0)
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), sendRate = 10000.kbps, padRate = Bandwidth.ZERO))
            // Video packet fills congestion window.
            pacer.enqueuePacket(video.buildNextPacket())
            callback.expectSendPacket().times(1)
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            pacer.setCongested(true)
            // Audio packet blocked due to congestion.
            pacer.enqueuePacket(audio.buildNextPacket())
            callback.expectSendPacket().times(0)
            // Forward time to where we send keep-alive.
            callback.expectSendPadding(1).times(2)
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            // Audio packet unblocked when congestion window clear.
            callback.verifyAndClearExpectations()
            pacer.setCongested(false)
            callback.expectSendPacket().times(1)
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
        }

        "DefaultCongestionWindowDoesNotAffectAudio" {
            callback.expectSendPadding().times(0)
            val trials = createTestFieldTrials()
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(clock.instant(), sendRate = 10000000.bps, padRate = Bandwidth.ZERO)
            )
            // Video packet fills congestion window.
            pacer.enqueuePacket(video.buildNextPacket())
            callback.expectSendPacket().times(1)
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            pacer.setCongested(true)
            // Audio not blocked due to congestion.
            pacer.enqueuePacket(audio.buildNextPacket())
            callback.expectSendPacket().times(1)
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
        }

        "BudgetAffectsAudioInTrial" {
            val trials = createTestFieldTrials(blockAudio = true)
            val pacer = PacingController(logger, clock, callback, trials)
            val kPacketSize = 1000
            val kProcessIntervalsPerSecond = 1000 / 5
            val pacingRate = (kPacketSize / 3 * 8 * kProcessIntervalsPerSecond).bps
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = pacingRate,
                    padRate = Bandwidth.ZERO,
                    timeWindow = Duration.ZERO
                )
            )
            // Video fills budget for following process periods.
            pacer.enqueuePacket(video.buildNextPacket(kPacketSize))
            callback.expectSendPacket().times(1)
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            // Audio packet blocked due to budget limit.
            pacer.enqueuePacket(audio.buildNextPacket())
            val waitStartTime = clock.instant()
            var waitEndTime = NEVER
            callback.expectSendPacket().willOnce { waitEndTime = clock.instant() }
            while (!waitEndTime.isFinite()) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
            val expectedWaitTime = timeToSend(kPacketSize.bytes, pacingRate)
            // Verify delay is near expectation, within timing margin.
            (Duration.between(waitStartTime, waitEndTime) - expectedWaitTime).abs() shouldBeLessThan
                PacingController.kMinSleepTime
        }

        "DefaultBudgetDoesNotAffectAudio" {
            val kPacketSize = 1000
            callback.expectSendPadding().times(0)
            val trials = createTestFieldTrials()
            val pacer = PacingController(logger, clock, callback, trials)
            val kProcessIntervalsPerSecond = 1000 / 5
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = (kPacketSize / 3 * 8 * kProcessIntervalsPerSecond).bps,
                    padRate = Bandwidth.ZERO
                )
            )
            // Video fills budget for following process periods.
            pacer.enqueuePacket(video.buildNextPacket(kPacketSize))
            callback.expectSendPacket().times(1)
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            // Audio packet not blocked due to budget limit.
            callback.expectSendPacket().times(1)
            pacer.enqueuePacket(audio.buildNextPacket())
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
        }

        "FirstSentPacketTimeIsSet" {
            val kStartTime = clock.instant()
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO
                )
            )

            // No packet sent.
            pacer.firstSentPacketTime() shouldBe null
            pacer.enqueuePacket(video.buildNextPacket())
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            pacer.firstSentPacketTime() shouldBe kStartTime
        }

        "QueueAndPacePacketsWithZeroBurstPeriod" {
            val kSsrc = 12345L
            var sequenceNumber = 1234
            val kPackeSize = 250.bytes
            val kSendInterval = 5.ms

            // Due to the multiplicative factor we can send 5 packets during a 5ms send
            // interval. (send interval * network capacity * multiplier / packet size)
            val kPacketsToSend = (
                dataSentAt(kTargetRate, kSendInterval).wholeBytes() * kPaceMultiplier / kPackeSize.wholeBytes()
                ).toInt()
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO,
                    timeWindow = Duration.ZERO
                )
            )

            for (i in 0 until kPacketsToSend) {
                sendAndExpectPacket(
                    pacer,
                    RtpPacketMediaType.kVideo,
                    kSsrc,
                    sequenceNumber++,
                    clock.roundedMillis(),
                    kPackeSize.wholeBytes().toInt()
                )
            }
            callback.expectSendPadding().times(0)

            // Enqueue one extra packet.
            val queuedPacketTimestamp = clock.roundedMillis()
            pacer.enqueuePacket(
                buildPacket(
                    RtpPacketMediaType.kVideo,
                    kSsrc,
                    sequenceNumber,
                    queuedPacketTimestamp,
                    kPackeSize.wholeBytes().toInt()
                )
            )
            pacer.queueSizePackets() shouldBe kPacketsToSend + 1

            // Send packets until the initial kPacketsToSend packets are done.
            val startTime = clock.instant()
            while (pacer.queueSizePackets() > 1) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
            Duration.between(startTime, clock.instant()) shouldBeLessThan kSendInterval

            // Proceed till last packet can be sent.
            callback.expectSendPacket(kSsrc, sequenceNumber, queuedPacketTimestamp, false, false).times(1)
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            Duration.between(startTime, clock.instant()) shouldBeGreaterThanOrEqualTo kSendInterval
            pacer.queueSizePackets() shouldBe 0
        }

        "PaceQueuedPackets" {
            val ssrc = 12345L
            var sequenceNumber = 1234
            val kPacketSize = 250
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO
                )
            )

            val packetsToSendPerBurstInterval = (
                dataSentAt(kTargetRate.scaledBy(kPaceMultiplier.toDouble()), PacerConfig.kDefaultTimeInterval)
                    .wholeBytes() / kPacketSize
                ).toInt()
            for (i in 0 until packetsToSendPerBurstInterval) {
                sendAndExpectPacket(
                    pacer,
                    RtpPacketMediaType.kVideo,
                    ssrc,
                    sequenceNumber++,
                    clock.roundedMillis(),
                    kPacketSize
                )
            }

            for (j in 0 until packetsToSendPerBurstInterval * 10) {
                pacer.enqueuePacket(
                    buildPacket(RtpPacketMediaType.kVideo, ssrc, sequenceNumber++, clock.roundedMillis(), kPacketSize)
                )
            }
            pacer.queueSizePackets() shouldBe packetsToSendPerBurstInterval + packetsToSendPerBurstInterval * 10

            while (pacer.queueSizePackets() > packetsToSendPerBurstInterval * 10) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
            pacer.queueSizePackets() shouldBe packetsToSendPerBurstInterval * 10
            callback.expectSendPadding().times(0)

            callback.expectSendPacket(ssrc, any(), any(), false, false).times(pacer.queueSizePackets())
            val expectedPaceTime = timeToSend(
                (pacer.queueSizePackets() * kPacketSize).bytes,
                kTargetRate.scaledBy(kPaceMultiplier.toDouble())
            )
            val startTime = clock.instant()
            while (pacer.queueSizePackets() > 0) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
            val actualPaceTime = Duration.between(startTime, clock.instant())
            (actualPaceTime - expectedPaceTime).abs() shouldBeLessThan PacingController.kMinSleepTime

            pacer.queueSizePackets() shouldBe 0
            advanceTimeUntil(pacer.nextSendTime())
            pacer.queueSizePackets() shouldBe 0
            pacer.processPackets()

            // Send some more packet, just show that we can..?
            for (i in 0 until packetsToSendPerBurstInterval) {
                sendAndExpectPacket(
                    pacer,
                    RtpPacketMediaType.kVideo,
                    ssrc,
                    sequenceNumber++,
                    clock.roundedMillis(),
                    250
                )
            }
            pacer.queueSizePackets() shouldBe packetsToSendPerBurstInterval
            for (i in 0 until packetsToSendPerBurstInterval) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
            pacer.queueSizePackets() shouldBe 0
        }

        "RepeatedRetransmissionsAllowed" {
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO
                )
            )

            // Send one packet, then two retransmissions of that packet.
            for (i in 0 until 3) {
                val ssrc = 333L
                val sequenceNumber = 444
                val bytes = 250
                val isRetransmission = (i != 0) // Original followed by retransmissions.
                sendAndExpectPacket(
                    pacer,
                    if (isRetransmission) RtpPacketMediaType.kRetransmission else RtpPacketMediaType.kVideo,
                    ssrc,
                    sequenceNumber,
                    clock.roundedMillis(),
                    bytes
                )
                clock.elapse(5.ms)
            }
            while (pacer.queueSizePackets() > 0) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
        }

        "CanQueuePacketsWithSameSequenceNumberOnDifferentSsrcs" {
            val ssrc = 12345L
            val sequenceNumber = 1234
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO
                )
            )

            sendAndExpectPacket(pacer, RtpPacketMediaType.kVideo, ssrc, sequenceNumber, clock.roundedMillis(), 250)

            // Expect packet on second ssrc to be queued and sent as well.
            sendAndExpectPacket(pacer, RtpPacketMediaType.kVideo, ssrc + 1, sequenceNumber, clock.roundedMillis(), 250)

            clock.elapse(1000.ms)
            while (pacer.queueSizePackets() > 0) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
        }

        "Padding" {
            val ssrc = 12345L
            var sequenceNumber = 1234
            val kPacketSize = 1000
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = kTargetRate
                )
            )

            val kPacketsToSend = 30
            for (i in 0 until kPacketsToSend) {
                sendAndExpectPacket(
                    pacer,
                    RtpPacketMediaType.kVideo,
                    ssrc,
                    sequenceNumber++,
                    clock.roundedMillis(),
                    kPacketSize
                )
            }

            val expectedBursts = floor(
                timeToSend(
                    (pacer.queueSizePackets() * kPacketSize).bytes,
                    kTargetRate.scaledBy(kPaceMultiplier.toDouble())
                ).ratioTo(PacerConfig.kDefaultTimeInterval)
            ).toInt()
            val expectedPaceTime = PacerConfig.kDefaultTimeInterval.multipliedBy((expectedBursts - 1).toLong())
            callback.expectSendPadding().times(0)
            // Only the media packets should be sent.
            val startTime = clock.instant()
            while (pacer.queueSizePackets() > 0) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
            val actualPaceTime = Duration.between(startTime, clock.instant())
            (actualPaceTime - expectedPaceTime).abs() shouldBeLessThanOrEqualTo PacerConfig.kDefaultTimeInterval

            // Pacing media happens at 2.5x, but padding was configured with 1.0x
            // factor. We have to wait until the padding debt is gone before we start
            // sending padding.
            val timeToPaddingDebtFree = expectedPaceTime.scaledBy(kPaceMultiplier.toDouble()) - actualPaceTime
            clock.elapse(timeToPaddingDebtFree - PacingController.kMinSleepTime)
            pacer.processPackets()

            // Send 10 padding packets.
            val kPaddingPacketsToSend = 10
            var paddingSent = DataSize.ZERO
            var packetsSent = 0
            var firstSendTime = NEVER
            var lastSendTime = NEVER

            callback.expectSendPadding().times(kPaddingPacketsToSend).willRepeatedly { targetSize ->
                ++packetsSent
                if (packetsSent < kPaddingPacketsToSend) {
                    // Don't count bytes of last packet, instead just
                    // use this as the time the last packet finished
                    // sending.
                    paddingSent += targetSize.bytes
                }
                if (firstSendTime.isInfinite()) {
                    firstSendTime = clock.instant()
                } else {
                    lastSendTime = clock.instant()
                }
                targetSize
            }
            callback.expectSendPacket(any(), any(), any(), false, true).times(kPaddingPacketsToSend)

            while (packetsSent < kPaddingPacketsToSend) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }

            // Verify rate of sent padding.
            val paddingDuration = timeBetween(firstSendTime, lastSendTime)
            val paddingRate = rateOf(paddingSent, paddingDuration)
            paddingRate shouldBe kTargetRate
        }

        "NoPaddingBeforeNormalPacket" {
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = kTargetRate
                )
            )

            callback.expectSendPadding().times(0)

            pacer.processPackets()
            advanceTimeUntil(pacer.nextSendTime())

            pacer.processPackets()
            advanceTimeUntil(pacer.nextSendTime())

            val ssrc = 12345L
            var sequenceNumber = 1234
            val captureTimeMs = 56789L

            sendAndExpectPacket(pacer, RtpPacketMediaType.kVideo, ssrc, sequenceNumber++, captureTimeMs, 250)
            var paddingSent = false
            callback.expectSendPadding().willOnce { padding ->
                paddingSent = true
                padding
            }
            callback.expectSendPacket(any(), any(), any(), any(), true).times(1)
            while (!paddingSent) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
        }

        "VerifyAverageBitrateVaryingMediaPayload" {
            val ssrc = 12345L
            var sequenceNumber = 1234
            val captureTimeMs = 56789L
            val kAveragingWindowLength = 10.secs
            val callback = PacingControllerPadding()
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setProbingEnabled(false)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = kTargetRate
                )
            )

            // Jitsi local: replaces rand().
            val random = Random(0)
            val startTime = clock.instant()
            var mediaBytes = 0L
            while (Duration.between(startTime, clock.instant()) < kAveragingWindowLength) {
                // Maybe add some new media packets corresponding to expected send rate.
                val randValue = random.nextInt(Int.MAX_VALUE)
                while (
                    mediaBytes < dataSentAt(kTargetRate, Duration.between(startTime, clock.instant())).wholeBytes()
                ) {
                    val mediaPayload = randValue % 400 + 800 // [400, 1200] bytes.
                    pacer.enqueuePacket(
                        buildPacket(RtpPacketMediaType.kVideo, ssrc, sequenceNumber++, captureTimeMs, mediaPayload)
                    )
                    mediaBytes += mediaPayload
                }
                advanceTimeUntil(minOf(clock.instant() + 20.ms, pacer.nextSendTime()))
                pacer.processPackets()
            }

            expectNear(
                kTargetRate.bps,
                rateOf(callback.totalBytesSent.bytes, kAveragingWindowLength).bps,
                kTargetRate.scaledBy(0.01).bps // 1% error marging
            )
        }

        "Priority" {
            val ssrcLowPriority = 12345L
            val ssrc = 12346L
            var sequenceNumber = 1234
            val captureTimeMs = 56789L
            val captureTimeMsLowPriority = 1234567L
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO
                )
            )

            consumeInitialBudget(pacer)

            // Expect normal and low priority to be queued and high to pass through.
            pacer.enqueuePacket(
                buildPacket(
                    RtpPacketMediaType.kVideo,
                    ssrcLowPriority,
                    sequenceNumber++,
                    captureTimeMsLowPriority,
                    250
                )
            )

            val packetsToSendPerInterval = (kTargetRate.bps * kPaceMultiplier / (8 * 250 * 200)).toInt()
            for (i in 0 until packetsToSendPerInterval) {
                pacer.enqueuePacket(
                    buildPacket(RtpPacketMediaType.kRetransmission, ssrc, sequenceNumber++, captureTimeMs, 250)
                )
            }
            pacer.enqueuePacket(buildPacket(RtpPacketMediaType.kAudio, ssrc, sequenceNumber++, captureTimeMs, 250))

            // Expect all high and normal priority to be sent out first.
            callback.expectSendPadding().times(0)
            val s = Sequence()
            callback.expectSendPacket(ssrc, any(), captureTimeMs, any(), any())
                .times(packetsToSendPerInterval + 1)
                .inSequence(s)
            callback.expectSendPacket(ssrcLowPriority, any(), captureTimeMsLowPriority, any(), any())
                .inSequence(s)

            while (pacer.queueSizePackets() > 0) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
        }

        "RetransmissionPriority" {
            val ssrc = 12345L
            var sequenceNumber = 1234
            val captureTimeMs = 45678L
            val captureTimeMsRetransmission = 56789L
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO
                )
            )

            val packetsToSendPerBurstInterval = (
                dataSentAt(kTargetRate.scaledBy(kPaceMultiplier.toDouble()), PacerConfig.kDefaultTimeInterval)
                    .wholeBytes() / 250
                ).toInt()
            pacer.processPackets()
            pacer.queueSizePackets() shouldBe 0

            // Alternate retransmissions and normal packets.
            for (i in 0 until packetsToSendPerBurstInterval) {
                pacer.enqueuePacket(buildPacket(RtpPacketMediaType.kVideo, ssrc, sequenceNumber++, captureTimeMs, 250))
                pacer.enqueuePacket(
                    buildPacket(
                        RtpPacketMediaType.kRetransmission,
                        ssrc,
                        sequenceNumber++,
                        captureTimeMsRetransmission,
                        250
                    )
                )
            }
            pacer.queueSizePackets() shouldBe 2 * packetsToSendPerBurstInterval

            // Expect all retransmissions to be sent out first despite having a later
            // capture time.
            callback.expectSendPadding().times(0)
            callback.expectSendPacket(any(), any(), any(), false, any()).times(0)
            callback.expectSendPacket(ssrc, any(), captureTimeMsRetransmission, true, any())
                .times(packetsToSendPerBurstInterval)

            while (pacer.queueSizePackets() > packetsToSendPerBurstInterval) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
            pacer.queueSizePackets() shouldBe packetsToSendPerBurstInterval

            // Expect the remaining (non-retransmission) packets to be sent.
            callback.expectSendPadding().times(0)
            callback.expectSendPacket(any(), any(), any(), true, any()).times(0)
            callback.expectSendPacket(ssrc, any(), captureTimeMs, false, any()).times(packetsToSendPerBurstInterval)

            while (pacer.queueSizePackets() > 0) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
            pacer.queueSizePackets() shouldBe 0
        }

        "HighPrioDoesntAffectBudget" {
            val kPacketSize = 250
            val ssrc = 12346L
            var sequenceNumber = 1234
            val captureTimeMs = 56789L
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO
                )
            )

            // As high prio packets doesn't affect the budget, we should be able to send
            // a high number of them at once.
            val kNumAudioPackets = 25
            for (i in 0 until kNumAudioPackets) {
                sendAndExpectPacket(
                    pacer,
                    RtpPacketMediaType.kAudio,
                    ssrc,
                    sequenceNumber++,
                    captureTimeMs,
                    kPacketSize
                )
            }
            pacer.processPackets()
            pacer.queueSizePackets() shouldBe 0
            // Low prio packets does affect the budget.
            val kPacketsToSendPerBurstInterval = (
                dataSentAt(kTargetRate.scaledBy(kPaceMultiplier.toDouble()), PacerConfig.kDefaultTimeInterval)
                    .wholeBytes() / kPacketSize
                ).toInt()
            for (i in 0 until kPacketsToSendPerBurstInterval) {
                sendAndExpectPacket(
                    pacer,
                    RtpPacketMediaType.kVideo,
                    ssrc,
                    sequenceNumber++,
                    clock.roundedMillis(),
                    kPacketSize
                )
            }

            // Send all packets and measure pace time.
            val startTime = clock.instant()
            pacer.nextSendTime() shouldBe clock.instant()
            while (pacer.queueSizePackets() > 0) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }

            // Measure pacing time.
            val pacingTime = Duration.between(startTime, clock.instant())
            // All packets sent in one burst since audio packets are not accounted for.
            val expectedPacingTime = Duration.ZERO
            expectNear(pacingTime.toMicros(), expectedPacingTime.toMicros(), PacingController.kMinSleepTime.toMicros())
        }

        "SendsOnlyPaddingWhenCongested" {
            val ssrc = 202020L
            var sequenceNumber = 1000
            val kPacketSize = 250
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO
                )
            )

            // Send an initial packet so we have a last send time.
            sendAndExpectPacket(
                pacer,
                RtpPacketMediaType.kVideo,
                ssrc,
                sequenceNumber++,
                clock.roundedMillis(),
                kPacketSize
            )
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            callback.verifyAndClearExpectations()

            // Set congested state, we should not send anything until the 500ms since
            // last send time limit for keep-alives is triggered.
            callback.expectSendPacket().times(0)
            callback.expectSendPadding().times(0)
            pacer.setCongested(true)
            var blockedPackets = 0
            var expectedTimeUntilPadding = 500L
            while (expectedTimeUntilPadding > 5) {
                pacer.enqueuePacket(
                    buildPacket(RtpPacketMediaType.kVideo, ssrc, sequenceNumber++, clock.roundedMillis(), kPacketSize)
                )
                blockedPackets++
                clock.elapse(5.ms)
                pacer.processPackets()
                expectedTimeUntilPadding -= 5
            }

            callback.verifyAndClearExpectations()
            callback.expectSendPadding(1).willOnce { 1 }
            callback.expectSendPacket(any(), any(), any(), any(), true).times(1)
            clock.elapse(5.ms)
            pacer.processPackets()
            pacer.queueSizePackets() shouldBe blockedPackets
        }

        "DoesNotAllowOveruseAfterCongestion" {
            val ssrc = 202020L
            var seqNum = 1000
            val size = 1000
            val nowMs = { clock.roundedMillis() }
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO,
                    timeWindow = Duration.ZERO
                )
            )
            callback.expectSendPadding().times(0)
            // The pacing rate is low enough that the budget should not allow two packets
            // to be sent in a row.
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = (400 * 8 * 1000 / 5).bps,
                    padRate = Bandwidth.ZERO,
                    timeWindow = Duration.ZERO
                )
            )
            // Not yet budget limited or congested, packet is sent.
            pacer.enqueuePacket(buildPacket(RtpPacketMediaType.kVideo, ssrc, seqNum++, nowMs(), size))
            callback.expectSendPacket().times(1)
            clock.elapse(5.ms)
            pacer.processPackets()
            // Packet blocked due to congestion.
            pacer.setCongested(true)
            pacer.enqueuePacket(buildPacket(RtpPacketMediaType.kVideo, ssrc, seqNum++, nowMs(), size))
            callback.expectSendPacket().times(0)
            clock.elapse(5.ms)
            pacer.processPackets()
            // Packet blocked due to congestion.
            pacer.enqueuePacket(buildPacket(RtpPacketMediaType.kVideo, ssrc, seqNum++, nowMs(), size))
            callback.expectSendPacket().times(0)
            clock.elapse(5.ms)
            pacer.processPackets()
            // Congestion removed and budget has recovered, packet is sent.
            pacer.enqueuePacket(buildPacket(RtpPacketMediaType.kVideo, ssrc, seqNum++, nowMs(), size))
            callback.expectSendPacket().times(1)
            clock.elapse(5.ms)
            pacer.setCongested(false)
            pacer.processPackets()
            // Should be blocked due to budget limitation as congestion has be removed.
            pacer.enqueuePacket(buildPacket(RtpPacketMediaType.kVideo, ssrc, seqNum++, nowMs(), size))
            callback.expectSendPacket().times(0)
            clock.elapse(5.ms)
            pacer.processPackets()
        }

        "CongestionPausesQueueTimeAging" {
            // Jitsi local: afterTest closes the sequence.
            InSequence()
            var seqNum = 1000
            val kPacketSize = 1000
            val pacer = PacingController(logger, clock, callback, trials)
            val kPacingRate = 500.kbps
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), sendRate = kPacingRate, padRate = Bandwidth.ZERO))

            // Send a first packet so seen_first_packet_ is set and keep-alive packets
            // work.
            callback.expectSendPacket()
            pacer.enqueuePacket(
                buildPacket(RtpPacketMediaType.kVideo, kVideoSsrc, seqNum++, clock.roundedMillis(), kPacketSize)
            )
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()

            // Enqueue 5 video packets (5000 bytes = 80 ms of data at 500 kbps).
            for (i in 0 until 5) {
                pacer.enqueuePacket(
                    buildPacket(RtpPacketMediaType.kVideo, kVideoSsrc, seqNum++, clock.roundedMillis(), kPacketSize)
                )
            }

            // Enter congested state.
            pacer.setCongested(true)

            // During 3 seconds of congestion:
            // 1. Keepalives are sent every 500 ms.
            // 2. Unpaced audio packets can still be enqueued and sent.
            // 3. Queued video packets are NOT aged.
            callback.expectSendPadding(1).times(6)
            for (i in 0 until 6) {
                clock.elapse(500.ms)
                pacer.processPackets()
            }

            // Audio packet is sent immediately even during congestion.
            callback.expectSendPacket(kAudioSsrc, any(), any(), any(), any())
            pacer.enqueuePacket(
                buildPacket(RtpPacketMediaType.kAudio, kAudioSsrc, seqNum++, clock.roundedMillis(), kPacketSize)
            )
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()

            // Now exit congestion.
            pacer.setCongested(false)

            // The expected queue time should NOT have aged by 3 seconds; it should
            // reflect the size of the remaining video packets (~80 ms at 500 kbps for
            // 5000 bytes).
            pacer.expectedQueueTime() shouldBeGreaterThan 50.ms
            pacer.expectedQueueTime() shouldBeLessThan 100.ms

            // The video packets should be paced out in burst intervals, not all at once
            // in a 1ms burst. 3 packets (48 ms) fit in the first burst interval (40 ms
            // limit), leaving 2 in queue.
            callback.expectSendPacket(kVideoSsrc, any(), any(), any(), any()).times(3)
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            pacer.queueSizePackets() shouldBe 2

            // Next packet should require time to drain budget (not sent immediately).
            pacer.nextSendTime() shouldBeGreaterThan clock.instant()

            // Drain remaining packets.
            callback.expectSendPacket(kVideoSsrc, any(), any(), any(), any()).times(2)
            while (pacer.queueSizePackets() > 0) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
        }

        "ExpectedQueueTimeIncreasesWhenPacketsEnqueuedWhileCongested" {
            var seqNum = 1000
            val kPacketSize = 1000
            val pacer = PacingController(logger, clock, callback, trials)
            val kPacingRate = 500.kbps
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), sendRate = kPacingRate, padRate = Bandwidth.ZERO))

            // Send a first packet to mark first packet seen.
            callback.expectSendPacket()
            pacer.enqueuePacket(
                buildPacket(RtpPacketMediaType.kVideo, kVideoSsrc, seqNum++, clock.roundedMillis(), kPacketSize)
            )
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()

            pacer.expectedQueueTime() shouldBe Duration.ZERO

            // Enter congested state with empty queue.
            pacer.setCongested(true)
            pacer.expectedQueueTime() shouldBe Duration.ZERO

            // Advance time during congestion; queue remains empty, expected queue time
            // stays zero.
            clock.elapse(2.secs)
            pacer.expectedQueueTime() shouldBe Duration.ZERO

            // Enqueue 1 packet (1000 bytes at 500 kbps = 16 ms) while congested.
            pacer.enqueuePacket(
                buildPacket(RtpPacketMediaType.kVideo, kVideoSsrc, seqNum++, clock.roundedMillis(), kPacketSize)
            )
            pacer.expectedQueueTime() shouldBe 16.ms

            // Advance time by 3 seconds; expected queue time should still be 16 ms (not
            // aged).
            clock.elapse(3.secs)
            pacer.expectedQueueTime() shouldBe 16.ms

            // Enqueue 2 more packets (total 3 packets = 3000 bytes at 500 kbps = 48 ms)
            // while congested.
            pacer.enqueuePacket(
                buildPacket(RtpPacketMediaType.kVideo, kVideoSsrc, seqNum++, clock.roundedMillis(), kPacketSize)
            )
            pacer.enqueuePacket(
                buildPacket(RtpPacketMediaType.kVideo, kVideoSsrc, seqNum++, clock.roundedMillis(), kPacketSize)
            )
            pacer.expectedQueueTime() shouldBe 48.ms

            // Clear congestion and verify expected queue time is still 48 ms.
            pacer.setCongested(false)
            pacer.expectedQueueTime() shouldBe 48.ms
        }

        "Pause" {
            val ssrcLowPriority = 12345L
            val ssrc = 12346L
            val ssrcHighPriority = 12347L
            var sequenceNumber = 1234
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO
                )
            )

            pacer.oldestPacketEnqueueTime().isInfinite() shouldBe true

            consumeInitialBudget(pacer)

            pacer.pause()

            val captureTimeMs = clock.roundedMillis()
            val packetsToSendPerInterval = (kTargetRate.bps * kPaceMultiplier / (8 * 250 * 200)).toInt()
            for (i in 0 until packetsToSendPerInterval) {
                pacer.enqueuePacket(
                    buildPacket(RtpPacketMediaType.kVideo, ssrcLowPriority, sequenceNumber++, captureTimeMs, 250)
                )
                pacer.enqueuePacket(
                    buildPacket(RtpPacketMediaType.kRetransmission, ssrc, sequenceNumber++, captureTimeMs, 250)
                )
                pacer.enqueuePacket(
                    buildPacket(RtpPacketMediaType.kAudio, ssrcHighPriority, sequenceNumber++, captureTimeMs, 250)
                )
            }
            clock.elapse(10000.ms)
            val secondCaptureTimeMs = clock.roundedMillis()
            for (i in 0 until packetsToSendPerInterval) {
                pacer.enqueuePacket(
                    buildPacket(RtpPacketMediaType.kVideo, ssrcLowPriority, sequenceNumber++, secondCaptureTimeMs, 250)
                )
                pacer.enqueuePacket(
                    buildPacket(RtpPacketMediaType.kRetransmission, ssrc, sequenceNumber++, secondCaptureTimeMs, 250)
                )
                pacer.enqueuePacket(
                    buildPacket(RtpPacketMediaType.kAudio, ssrcHighPriority, sequenceNumber++, secondCaptureTimeMs, 250)
                )
            }

            // Expect everything to be queued.
            pacer.oldestPacketEnqueueTime().toRoundedEpochMilli() shouldBe captureTimeMs

            // Process triggers keep-alive packet.
            callback.expectSendPadding().willOnce { padding -> padding }
            callback.expectSendPacket(any(), any(), any(), any(), true).times(1)
            pacer.processPackets()

            // Verify no packets sent for the rest of the paused process interval.
            val kProcessInterval = 5.ms
            var expectedTimeUntilSend = PacingController.kPausedProcessInterval
            callback.expectSendPadding().times(0)
            while (expectedTimeUntilSend >= kProcessInterval) {
                pacer.processPackets()
                clock.elapse(kProcessInterval)
                expectedTimeUntilSend -= kProcessInterval
            }

            // New keep-alive packet.
            callback.verifyAndClearExpectations()
            callback.expectSendPadding().willOnce { padding -> padding }
            callback.expectSendPacket(any(), any(), any(), any(), true).times(1)
            clock.elapse(kProcessInterval)
            pacer.processPackets()
            callback.verifyAndClearExpectations()

            // Expect high prio packets to come out first followed by normal
            // prio packets and low prio packets (all in capture order).
            InSequence().use {
                callback.expectSendPacket(ssrcHighPriority, any(), captureTimeMs, any(), any())
                    .times(packetsToSendPerInterval)
                callback.expectSendPacket(ssrcHighPriority, any(), secondCaptureTimeMs, any(), any())
                    .times(packetsToSendPerInterval)

                for (i in 0 until packetsToSendPerInterval) {
                    callback.expectSendPacket(ssrc, any(), captureTimeMs, any(), any()).times(1)
                }
                for (i in 0 until packetsToSendPerInterval) {
                    callback.expectSendPacket(ssrc, any(), secondCaptureTimeMs, any(), any()).times(1)
                }
                for (i in 0 until packetsToSendPerInterval) {
                    callback.expectSendPacket(ssrcLowPriority, any(), captureTimeMs, any(), any()).times(1)
                }
                for (i in 0 until packetsToSendPerInterval) {
                    callback.expectSendPacket(ssrcLowPriority, any(), secondCaptureTimeMs, any(), any()).times(1)
                }
            }
            pacer.resume()
            while (pacer.queueSizePackets() > 0) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }

            pacer.oldestPacketEnqueueTime().isInfinite() shouldBe true
        }

        "InactiveFromStart" {
            // Recreate the pacer without the inital time forwarding.
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setProbingEnabled(false)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = kTargetRate
                )
            )

            // No packets sent, there should be no keep-alives sent either.
            callback.expectSendPadding().times(0)
            callback.expectSendPacket().times(0)
            pacer.processPackets()

            val startTime = clock.instant()

            // Determine the margin need so we can advance to the last possible moment
            // that will not cause a process event.
            val timeMargin = PacingController.kMinSleepTime + 1.micros

            timeBetween(startTime, pacer.nextSendTime()) shouldBe PacingController.kPausedProcessInterval
            clock.elapse(PacingController.kPausedProcessInterval - timeMargin)
            pacer.processPackets()
            timeBetween(startTime, pacer.nextSendTime()) shouldBe PacingController.kPausedProcessInterval

            clock.elapse(timeMargin)
            pacer.processPackets()
            timeBetween(startTime, pacer.nextSendTime()) shouldBe
                PacingController.kPausedProcessInterval.multipliedBy(2)
        }

        "QueueTimeGrowsOverTime" {
            val ssrc = 12346L
            val sequenceNumber = 1234
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO
                )
            )
            pacer.oldestPacketEnqueueTime().isInfinite() shouldBe true

            pacer.setPacerConfig(PacerConfig.create(clock.instant(), (30000 * kPaceMultiplier).bps, Bandwidth.ZERO))
            sendAndExpectPacket(pacer, RtpPacketMediaType.kVideo, ssrc, sequenceNumber, clock.roundedMillis(), 1200)

            clock.elapse(500.ms)
            pacer.oldestPacketEnqueueTime().toRoundedEpochMilli() shouldBe clock.roundedMillis() - 500
            pacer.processPackets()
            pacer.oldestPacketEnqueueTime().isInfinite() shouldBe true
        }

        "ProbingWithInsertedPackets" {
            val kPacketSize = 1200
            val kInitialBitrateBps = 300000
            val ssrc = 12346L
            var sequenceNumber = 1234

            val packetSender = PacingControllerProbing()
            val pacer = PacingController(logger, clock, packetSender, trials)
            val probeClusters = listOf(
                ProbeClusterConfig(
                    atTime = clock.instant(),
                    targetDataRate = kFirstClusterRate,
                    targetDuration = 15.ms,
                    targetProbeCount = 5,
                    id = 0
                ),
                ProbeClusterConfig(
                    atTime = clock.instant(),
                    targetDataRate = kSecondClusterRate,
                    targetDuration = 15.ms,
                    targetProbeCount = 5,
                    id = 1
                )
            )
            pacer.createProbeClusters(probeClusters)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = (kInitialBitrateBps * kPaceMultiplier).bps,
                    padRate = Bandwidth.ZERO
                )
            )

            for (i in 0 until 10) {
                pacer.enqueuePacket(
                    buildPacket(RtpPacketMediaType.kVideo, ssrc, sequenceNumber++, clock.roundedMillis(), kPacketSize)
                )
            }

            var start = clock.roundedMillis()
            while (packetSender.packetsSent < 5) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
            var packetsSent = packetSender.packetsSent
            // Validate first cluster bitrate. Note that we have to account for number
            // of intervals and hence (packets_sent - 1) on the first cluster.
            expectNear(
                (packetsSent - 1).toLong() * kPacketSize * 8000 / (clock.roundedMillis() - start),
                kFirstClusterRate.bps,
                kProbingErrorMargin.bps
            )
            // Probing always starts with a small padding packet.
            packetSender.paddingSent shouldBe 1

            advanceTimeUntil(pacer.nextSendTime())
            start = clock.roundedMillis()
            while (packetSender.packetsSent < 10) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
            packetsSent = packetSender.packetsSent - packetsSent
            // Validate second cluster bitrate.
            expectNear(
                (packetsSent - 1).toLong() * kPacketSize * 8000 / (clock.roundedMillis() - start),
                kSecondClusterRate.bps,
                kProbingErrorMargin.bps
            )
        }

        "SkipsProbesWhenProcessIntervalTooLarge" {
            val kPacketSize = 1200
            val kInitialBitrateBps = 300000
            val ssrc = 12346L
            val kProbeClusterId = 3

            var sequenceNumber = 1234

            val packetSender = PacingControllerProbing()

            // WebRTC-Bwe-ProbingBehavior/max_probe_delay:2ms/
            val trials = createTestFieldTrials(maxProbeDelay = 2.ms)
            val pacer = PacingController(logger, clock, packetSender, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = (kInitialBitrateBps * kPaceMultiplier).bps,
                    padRate = kInitialBitrateBps.bps
                )
            )

            for (i in 0 until 10) {
                pacer.enqueuePacket(
                    buildPacket(RtpPacketMediaType.kVideo, ssrc, sequenceNumber++, clock.roundedMillis(), kPacketSize)
                )
            }
            while (pacer.queueSizePackets() > 0) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }

            // Probe at a very high rate.
            val probeClusters = listOf(
                ProbeClusterConfig(
                    atTime = clock.instant(),
                    targetDataRate = 10000.kbps, // 10 Mbps,
                    targetDuration = 15.ms,
                    targetProbeCount = 5,
                    id = kProbeClusterId
                )
            )
            pacer.createProbeClusters(probeClusters)

            // We need one packet to start the probe.
            pacer.enqueuePacket(
                buildPacket(RtpPacketMediaType.kVideo, ssrc, sequenceNumber++, clock.roundedMillis(), kPacketSize)
            )
            val packetsSentBeforeProbe = packetSender.packetsSent
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            packetSender.packetsSent shouldBe packetsSentBeforeProbe + 1

            // Figure out how long between probe packets.
            val startTime = clock.instant()
            advanceTimeUntil(pacer.nextSendTime())
            val timeBetweenProbes = Duration.between(startTime, clock.instant())
            // Advance that distance again + 1ms.
            clock.elapse(timeBetweenProbes)

            // Send second probe packet.
            pacer.enqueuePacket(
                buildPacket(RtpPacketMediaType.kVideo, ssrc, sequenceNumber++, clock.roundedMillis(), kPacketSize)
            )
            pacer.processPackets()
            packetSender.packetsSent shouldBe packetsSentBeforeProbe + 2
            val lastPacingInfo = packetSender.lastPacingInfo
            lastPacingInfo.probeClusterId shouldBe kProbeClusterId

            // We're exactly where we should be for the next probe.
            val probeTime = clock.instant()
            pacer.nextSendTime() shouldBe clock.instant()

            val probingConfig = trials.proberConfig
            probingConfig.maxProbeDelay shouldBeGreaterThan Duration.ZERO
            // Advance to within max probe delay, should still return same target.
            clock.elapse(probingConfig.maxProbeDelay)
            pacer.nextSendTime() shouldBe probeTime

            // Too high probe delay, drop it!
            clock.elapse(1.micros)

            val packetsSentBeforeTimeout = packetSender.totalPacketsSent()
            // Expected next process time is unchanged, but calling should not
            // generate new packets.
            pacer.nextSendTime() shouldBe probeTime
            pacer.processPackets()
            packetSender.totalPacketsSent() shouldBe packetsSentBeforeTimeout

            // Next packet sent is not part of probe.
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            val expectedProbeId = PacedPacketInfo.kNotAProbe
            packetSender.lastPacingInfo.probeClusterId shouldBe expectedProbeId
        }

        "ProbingWithPaddingSupport" {
            val kPacketSize = 1200
            val kInitialBitrateBps = 300000
            val ssrc = 12346L
            var sequenceNumber = 1234

            val packetSender = PacingControllerProbing()
            val pacer = PacingController(logger, clock, packetSender, trials)
            val probeClusters = listOf(
                ProbeClusterConfig(
                    atTime = clock.instant(),
                    targetDataRate = kFirstClusterRate,
                    targetDuration = 15.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            pacer.createProbeClusters(probeClusters)

            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = (kInitialBitrateBps * kPaceMultiplier).bps,
                    padRate = Bandwidth.ZERO
                )
            )

            for (i in 0 until 3) {
                pacer.enqueuePacket(
                    buildPacket(RtpPacketMediaType.kVideo, ssrc, sequenceNumber++, clock.roundedMillis(), kPacketSize)
                )
            }

            val start = clock.roundedMillis()
            var processCount = 0
            while (processCount < 5) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
                ++processCount
            }
            val packetsSent = packetSender.packetsSent
            val paddingSent = packetSender.paddingSent
            packetsSent shouldBeGreaterThan 0
            paddingSent shouldBeGreaterThan 0
            // Note that the number of intervals here for kPacketSize is
            // packets_sent due to padding in the same cluster.
            expectNear(
                (packetsSent.toLong() * kPacketSize * 8000 + paddingSent) / (clock.roundedMillis() - start),
                kFirstClusterRate.bps,
                kProbingErrorMargin.bps
            )
        }

        "PaddingPacketCanTriggerProbe" {
            val kInitialBitrateBps = 300000
            val packetSender = PacingControllerProbing()
            val pacer = PacingController(logger, clock, packetSender, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = (kInitialBitrateBps * kPaceMultiplier).bps,
                    padRate = 300.kbps
                )
            )

            pacer.enqueuePacket(
                buildPacket(
                    RtpPacketMediaType.kVideo,
                    ssrc = 123,
                    sequenceNumber = 1,
                    clock.roundedMillis(),
                    payloadSize = 50
                )
            )

            for (i in 0 until 5) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
                packetSender.lastPacingInfo.probeClusterId shouldBe PacedPacketInfo.kNotAProbe
            }
            packetSender.packetsSent shouldBeGreaterThan 0
            packetSender.paddingSent shouldBeGreaterThan 0

            val kProbeClusterId = 1
            val probeClusters = listOf(
                ProbeClusterConfig(
                    atTime = clock.instant(),
                    targetDataRate = 1000.kbps,
                    targetDuration = 15.ms,
                    targetProbeCount = 5,
                    id = kProbeClusterId
                )
            )
            pacer.createProbeClusters(probeClusters)
            var probePacketSeen = false
            for (i in 0 until 5) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
                if (packetSender.lastPacingInfo.probeClusterId == kProbeClusterId) {
                    probePacketSeen = true
                    break
                }
            }
            probePacketSeen shouldBe true
        }

        "CanProbeWithPaddingBeforeFirstMediaPacket" {
            // const size_t kPacketSize = 1200;
            val kInitialBitrateBps = 300000

            val packetSender = PacingControllerProbing()
            val pacer = PacingController(logger, clock, packetSender, trials)
            pacer.setAllowProbeWithoutMediaPacket(true)
            val probeClusters = listOf(
                ProbeClusterConfig(
                    atTime = clock.instant(),
                    targetDataRate = kFirstClusterRate,
                    targetDuration = 15.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            pacer.createProbeClusters(probeClusters)

            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = (kInitialBitrateBps * kPaceMultiplier).bps,
                    padRate = Bandwidth.ZERO
                )
            )

            val start = clock.instant()
            var nextProcess = pacer.nextSendTime()
            while (clock.instant() < start + 100.ms && nextProcess.isFinite()) {
                advanceTimeUntil(nextProcess)
                pacer.processPackets()
                nextProcess = pacer.nextSendTime()
            }
            packetSender.paddingPacketsSent shouldBeGreaterThan 5
        }

        "ProbeSentAfterSetAllowProbeWithoutMediaPacket" {
            val kInitialBitrateBps = 300000

            val packetSender = PacingControllerProbing()
            val pacer = PacingController(logger, clock, packetSender, trials)
            val probeClusters = listOf(
                ProbeClusterConfig(
                    atTime = clock.instant(),
                    targetDataRate = kFirstClusterRate,
                    targetDuration = 15.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            pacer.createProbeClusters(probeClusters)

            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = (kInitialBitrateBps * kPaceMultiplier).bps,
                    padRate = Bandwidth.ZERO
                )
            )

            pacer.setAllowProbeWithoutMediaPacket(true)

            val start = clock.instant()
            var nextProcess = pacer.nextSendTime()
            while (clock.instant() < start + 100.ms && nextProcess.isFinite()) {
                advanceTimeUntil(nextProcess)
                pacer.processPackets()
                nextProcess = pacer.nextSendTime()
            }
            packetSender.paddingPacketsSent shouldBeGreaterThan 5
        }

        "CanNotProbeWithPaddingIfGeneratePaddingFails" {
            // const size_t kPacketSize = 1200;
            val kInitialBitrateBps = 300000

            val packetSender = PacingControllerProbing()
            packetSender.setCanGeneratePadding(false)
            val pacer = PacingController(logger, clock, packetSender, trials)
            pacer.setAllowProbeWithoutMediaPacket(true)
            val probeClusters = listOf(
                ProbeClusterConfig(
                    atTime = clock.instant(),
                    targetDataRate = kFirstClusterRate,
                    targetDuration = 15.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            pacer.createProbeClusters(probeClusters)

            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = (kInitialBitrateBps * kPaceMultiplier).bps,
                    padRate = Bandwidth.ZERO
                )
            )

            val start = clock.instant()
            var processCount = 0
            var nextProcess = pacer.nextSendTime()
            while (clock.instant() < start + 100.ms && nextProcess.isFinite()) {
                advanceTimeUntil(nextProcess)
                pacer.processPackets()
                ++processCount
                nextProcess = pacer.nextSendTime()
            }

            processCount shouldBeLessThan 10
            packetSender.paddingPacketsSent shouldBe 0
        }

        "PaddingOveruse" {
            val ssrc = 12346L
            var sequenceNumber = 1234
            val kPacketSize = 1200
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO
                )
            )

            // Initially no padding rate.
            pacer.processPackets()
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), (60000 * kPaceMultiplier).bps, Bandwidth.ZERO))

            sendAndExpectPacket(
                pacer,
                RtpPacketMediaType.kVideo,
                ssrc,
                sequenceNumber++,
                clock.roundedMillis(),
                kPacketSize
            )
            pacer.processPackets()

            // Add 30kbit padding. When increasing budget, media budget will increase from
            // negative (overuse) while padding budget will increase from 0.
            clock.elapse(5.ms)
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), (60000 * kPaceMultiplier).bps, 30000.bps))

            sendAndExpectPacket(
                pacer,
                RtpPacketMediaType.kVideo,
                ssrc,
                sequenceNumber++,
                clock.roundedMillis(),
                kPacketSize
            )
            5.ms shouldBeLessThan pacer.expectedQueueTime()
            // Don't send padding if queue is non-empty, even if padding budget > 0.
            callback.expectSendPadding().times(0)
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
        }

        "ProvidesOnBatchCompleteToPacketSender" {
            val callback = MockPacketSender()
            val pacer = PacingController(logger, clock, callback, trials)
            callback.expectOnBatchComplete()
            pacer.processPackets()
        }

        "ProbeClusterId" {
            val callback = MockPacketSender()
            val ssrc = 12346L
            var sequenceNumber = 1234
            val kPacketSize = 1200

            val pacer = PacingController(logger, clock, callback, trials)
            pacer.createProbeClusters(
                listOf(
                    ProbeClusterConfig(
                        atTime = clock.instant(),
                        targetDataRate = kFirstClusterRate,
                        targetDuration = 15.ms,
                        targetProbeCount = 5,
                        id = 0
                    ),
                    ProbeClusterConfig(
                        atTime = clock.instant(),
                        targetDataRate = kSecondClusterRate,
                        targetDuration = 15.ms,
                        targetProbeCount = 5,
                        id = 1
                    )
                )
            )
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = kTargetRate
                )
            )
            pacer.setProbingEnabled(true)
            for (i in 0 until 10) {
                pacer.enqueuePacket(
                    buildPacket(RtpPacketMediaType.kVideo, ssrc, sequenceNumber++, clock.roundedMillis(), kPacketSize)
                )
            }

            // First probing cluster.
            callback.expectSendPacket { _, clusterInfo -> clusterInfo.probeClusterId == 0 }.times(5)

            for (i in 0 until 5) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }

            // Second probing cluster.
            callback.expectSendPacket { _, clusterInfo -> clusterInfo.probeClusterId == 1 }.times(5)

            for (i in 0 until 5) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }

            // Needed for the Field comparer below.
            val kNotAProbe = PacedPacketInfo.kNotAProbe
            // No more probing packets.
            callback.expectGeneratePadding().willOnce { paddingSize ->
                val paddingPackets = mutableListOf<RtpPacketToSend>()
                paddingPackets.add(
                    buildPacket(
                        RtpPacketMediaType.kPadding,
                        ssrc,
                        sequenceNumber++,
                        clock.roundedMillis(),
                        paddingSize.wholeBytes().toInt()
                    )
                )
                paddingPackets
            }
            var nonProbePacketSeen = false
            callback.expectSendPacket().willOnce { (_, clusterInfo) ->
                clusterInfo.probeClusterId shouldBe kNotAProbe
                nonProbePacketSeen = true
            }
            while (!nonProbePacketSeen) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
        }

        "OwnedPacketPrioritizedOnType" {
            val callback = MockPacketSender()
            var ssrc = 123L

            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO
                )
            )

            // Insert a packet of each type, from low to high priority. Since priority
            // is weighted higher than insert order, these should come out of the pacer
            // in backwards order with the exception of FEC and Video.

            for (type in listOf(
                RtpPacketMediaType.kPadding,
                RtpPacketMediaType.kForwardErrorCorrection,
                RtpPacketMediaType.kVideo,
                RtpPacketMediaType.kRetransmission,
                RtpPacketMediaType.kAudio
            )) {
                pacer.enqueuePacket(
                    buildPacket(type, ++ssrc, sequenceNumber = 123, clock.roundedMillis(), payloadSize = 150)
                )
            }

            // Jitsi local: afterTest closes the sequence.
            InSequence()
            callback.expectSendPacket { packet, _ -> packet.packetType == RtpPacketMediaType.kAudio }
            callback.expectSendPacket { packet, _ -> packet.packetType == RtpPacketMediaType.kRetransmission }

            // FEC and video actually have the same priority, so will come out in
            // insertion order.
            callback.expectSendPacket { packet, _ -> packet.packetType == RtpPacketMediaType.kForwardErrorCorrection }
            callback.expectSendPacket { packet, _ -> packet.packetType == RtpPacketMediaType.kVideo }

            callback.expectSendPacket { packet, _ -> packet.packetType == RtpPacketMediaType.kPadding }

            while (pacer.queueSizePackets() > 0) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
        }

        "SmallFirstProbePacket" {
            val callback = MockPacketSender()
            val pacer = PacingController(logger, clock, callback, trials)
            val probeClusters = listOf(
                ProbeClusterConfig(
                    atTime = clock.instant(),
                    targetDataRate = kFirstClusterRate,
                    targetDuration = 15.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            pacer.createProbeClusters(probeClusters)

            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    padRate = Bandwidth.ZERO
                )
            )

            // Add high prio media.
            pacer.enqueuePacket(audio.buildNextPacket(234))

            // Expect small padding packet to be requested.
            callback.expectGeneratePadding(1.bytes).willOnce {
                val paddingPackets = mutableListOf<RtpPacketToSend>()
                paddingPackets.add(
                    buildPacket(RtpPacketMediaType.kPadding, kAudioSsrc, 1, clock.roundedMillis(), 1)
                )
                paddingPackets
            }

            var packetsSent = 0
            var mediaSeen = false
            callback.expectSendPacket().times(anyNumber()).willRepeatedly { (packet, _) ->
                if (packetsSent == 0) {
                    packet.packetType shouldBe RtpPacketMediaType.kPadding
                } else {
                    if (packet.packetType == RtpPacketMediaType.kAudio) {
                        mediaSeen = true
                    }
                }
                packetsSent++
            }
            while (!mediaSeen) {
                pacer.processPackets()
                clock.elapse(5.ms)
            }
        }

        "TaskLate" {
            // Set a low send rate to more easily test timing issues.
            val kSendRate = 30.kbps
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), sendRate = kSendRate, Bandwidth.ZERO))

            // Add four packets of equal size and priority.
            pacer.enqueuePacket(video.buildNextPacket(1000))
            pacer.enqueuePacket(video.buildNextPacket(1000))
            pacer.enqueuePacket(video.buildNextPacket(1000))
            pacer.enqueuePacket(video.buildNextPacket(1000))

            // Process packets, only first should be sent.
            callback.expectSendPacket().times(1)
            pacer.processPackets()

            var nextSendTime = pacer.nextSendTime()
            // Determine time between packets (ca 62ms)
            val timeBetweenPackets = Duration.between(clock.instant(), nextSendTime)

            // Simulate a late process call, executed just before we allow sending the
            // fourth packet.
            val kOffset = 1.ms
            clock.elapse(timeBetweenPackets.multipliedBy(3) - kOffset)

            callback.expectSendPacket().times(2)
            pacer.processPackets()

            // Check that next scheduled send time is in ca 1ms.
            nextSendTime = pacer.nextSendTime()
            val timeLeft = Duration.between(clock.instant(), nextSendTime)
            timeLeft.roundTo(1.ms) shouldBe kOffset

            clock.elapse(timeLeft)
            callback.expectSendPacket()
            pacer.processPackets()
        }

        "NoProbingWhilePaused" {
            val ssrc = 12345L
            val sequenceNumber = 1234
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setProbingEnabled(true)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = kTargetRate.scaledBy(kPaceMultiplier.toDouble()),
                    Bandwidth.ZERO
                )
            )
            pacer.createProbeClusters(
                listOf(
                    ProbeClusterConfig(
                        atTime = clock.instant(),
                        targetDataRate = kFirstClusterRate,
                        targetDuration = 15.ms,
                        targetProbeCount = 5,
                        id = 0
                    ),
                    ProbeClusterConfig(
                        atTime = clock.instant(),
                        targetDataRate = kSecondClusterRate,
                        targetDuration = 15.ms,
                        targetProbeCount = 5,
                        id = 1
                    )
                )
            )

            // Send at least one packet so probing can initate.
            sendAndExpectPacket(pacer, RtpPacketMediaType.kVideo, ssrc, sequenceNumber, clock.roundedMillis(), 250)
            while (pacer.queueSizePackets() > 0) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }

            // Trigger probing.
            val probeClusters = listOf(
                ProbeClusterConfig(
                    atTime = clock.instant(),
                    targetDataRate = 10000.kbps, // 10 Mbps.
                    targetDuration = 15.ms,
                    targetProbeCount = 5,
                    id = 3
                )
            )
            pacer.createProbeClusters(probeClusters)

            // Time to next send time should be small.
            timeBetween(clock.instant(), pacer.nextSendTime()) shouldBeLessThan PacingController.kPausedProcessInterval

            // Pause pacer, time to next send time should now be the pause process
            // interval.
            pacer.pause()

            timeBetween(clock.instant(), pacer.nextSendTime()) shouldBe PacingController.kPausedProcessInterval
        }

        "AudioNotPacedEvenWhenAccountedFor" {
            val kSsrc = 12345L
            var sequenceNumber = 1234
            val kPacketSize = 123
            val pacer = PacingController(logger, clock, callback, trials)

            // Account for audio - so that audio packets can cause pushback on other
            // types such as video. Audio packet should still be immediated passed
            // through though ("WebRTC-Pacer-BlockAudio" needs to be enabled in order
            // to pace audio packets).
            pacer.setAccountForAudioPackets(true)

            // Set pacing rate to 1 packet/s, no padding.
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), rateOf(kPacketSize.bytes, 1.secs), Bandwidth.ZERO))

            // Add and send an audio packet.
            sendAndExpectPacket(
                pacer,
                RtpPacketMediaType.kAudio,
                kSsrc,
                sequenceNumber++,
                clock.roundedMillis(),
                kPacketSize
            )
            pacer.processPackets()

            // Advance time, add another audio packet and process. It should be sent
            // immediately.
            clock.elapse(5.ms)
            sendAndExpectPacket(
                pacer,
                RtpPacketMediaType.kAudio,
                kSsrc,
                sequenceNumber++,
                clock.roundedMillis(),
                kPacketSize
            )
            pacer.processPackets()
        }

        "PaddingResumesAfterSaturationEvenWithConcurrentAudio" {
            val kSsrc = 12345L
            val kPacingDataRate = 125.kbps
            val kPaddingDataRate = 100.kbps
            val kMaxBufferInTime = 500.ms
            val kPacketSize = 130.bytes
            val kAudioPacketInterval = 20.ms

            // In this test, we fist send a burst of video in order to saturate the
            // padding debt level.
            // We then proceed to send audio at a bitrate that is slightly lower than
            // the padding rate, meaning there will be a period with audio but no
            // padding sent while the debt is draining, then audio and padding will
            // be interlieved.

            // Verify both with and without accounting for audio.
            for (accountForAudio in listOf(false, true)) {
                var sequenceNumber = 1234
                val callback = MockPacketSender()
                callback.expectSendPacket().times(anyNumber())
                val pacer = PacingController(logger, clock, callback, trials)
                pacer.setAccountForAudioPackets(accountForAudio)

                // First, saturate the padding budget.
                pacer.setPacerConfig(PacerConfig.create(clock.instant(), kPacingDataRate, kPaddingDataRate))

                val kPaddingSaturationTime = timeToSend(
                    dataSentAt(kPaddingDataRate, kMaxBufferInTime),
                    kPacingDataRate - kPaddingDataRate
                )
                val kVideoToSend = dataSentAt(kPacingDataRate, kPaddingSaturationTime)
                val kVideoPacketSize = 1200.bytes
                var videoSent = DataSize.ZERO
                while (videoSent < kVideoToSend) {
                    pacer.enqueuePacket(
                        buildPacket(
                            RtpPacketMediaType.kVideo,
                            kSsrc,
                            sequenceNumber++,
                            clock.roundedMillis(),
                            kVideoPacketSize.wholeBytes().toInt()
                        )
                    )
                    videoSent += kVideoPacketSize
                }
                while (pacer.queueSizePackets() > 0) {
                    advanceTimeUntil(pacer.nextSendTime())
                    pacer.processPackets()
                }

                // Add a stream of audio packets at a rate slightly lower than the padding
                // rate, once the padding debt is paid off we expect padding to be
                // generated.
                pacer.setPacerConfig(PacerConfig.create(clock.instant(), kPacingDataRate, kPaddingDataRate))
                var paddingSeen = false
                callback.expectGeneratePadding().willOnce { paddingSize ->
                    paddingSeen = true
                    val paddingPackets = mutableListOf<RtpPacketToSend>()
                    paddingPackets.add(
                        buildPacket(
                            RtpPacketMediaType.kPadding,
                            kSsrc,
                            sequenceNumber++,
                            clock.roundedMillis(),
                            paddingSize.wholeBytes().toInt()
                        )
                    )
                    paddingPackets
                }

                val startTime = clock.instant()
                var lastAudioTime = startTime
                while (!paddingSeen) {
                    val now = clock.instant()
                    val nextSendTime = pacer.nextSendTime()
                    val sleepTime = timeBetween(now, minOf(nextSendTime, lastAudioTime + kAudioPacketInterval))
                    clock.elapse(sleepTime)
                    while (clock.instant() >= lastAudioTime + kAudioPacketInterval) {
                        pacer.enqueuePacket(
                            buildPacket(
                                RtpPacketMediaType.kAudio,
                                kSsrc,
                                sequenceNumber++,
                                clock.roundedMillis(),
                                kPacketSize.wholeBytes().toInt()
                            )
                        )
                        lastAudioTime += kAudioPacketInterval
                    }
                    pacer.processPackets()
                }

                // Verify how long it took to drain the padding debt. Allow 2% error margin.
                val kAudioDataRate = rateOf(kPacketSize, kAudioPacketInterval)
                val expectedDrainTime = if (accountForAudio) {
                    timeToSend(dataSentAt(kPaddingDataRate, kMaxBufferInTime), kPaddingDataRate - kAudioDataRate)
                } else {
                    kMaxBufferInTime
                }
                val actualDrainTime = Duration.between(startTime, clock.instant())
                withClue(" where account_for_audio = $accountForAudio") {
                    expectNear(
                        actualDrainTime.toRoundedMillis(),
                        expectedDrainTime.toRoundedMillis(),
                        expectedDrainTime.toRoundedMillis() * 0.02
                    )
                }
            }
        }

        "AccountsForAudioEnqueueTime" {
            val kSsrc = 12345L
            val kPacingDataRate = 125.kbps
            val kPaddingDataRate = Bandwidth.ZERO
            val kPacketSize = 130.bytes
            val kPacketPacingTime = timeToSend(kPacketSize, kPacingDataRate)
            var sequnceNumber = 1
            val pacer = PacingController(logger, clock, callback, trials)
            // Audio not paced, but still accounted for in budget.
            pacer.setAccountForAudioPackets(true)
            pacer.setPacerConfig(
                PacerConfig.create(clock.instant(), kPacingDataRate, kPaddingDataRate, timeWindow = Duration.ZERO)
            )

            // Enqueue two audio packets, advance clock to where one packet
            // should have drained the buffer already, has they been sent
            // immediately.
            sendAndExpectPacket(
                pacer,
                RtpPacketMediaType.kAudio,
                kSsrc,
                sequnceNumber++,
                clock.roundedMillis(),
                kPacketSize.wholeBytes().toInt()
            )
            sendAndExpectPacket(
                pacer,
                RtpPacketMediaType.kAudio,
                kSsrc,
                sequnceNumber++,
                clock.roundedMillis(),
                kPacketSize.wholeBytes().toInt()
            )
            clock.elapse(kPacketPacingTime)
            // Now process and make sure both packets were sent.
            pacer.processPackets()
            callback.verifyAndClearExpectations()

            // Add a video packet. I can't be sent until debt from audio
            // packets have been drained.
            pacer.enqueuePacket(
                buildPacket(
                    RtpPacketMediaType.kVideo,
                    kSsrc + 1,
                    sequnceNumber++,
                    clock.roundedMillis(),
                    kPacketSize.wholeBytes().toInt()
                )
            )
            timeBetween(clock.instant(), pacer.nextSendTime()) shouldBe kPacketPacingTime
        }

        "NextSendTimeAccountsForPadding" {
            val kSsrc = 12345L
            val kPacingDataRate = 125.kbps
            val kPacketSize = 130.bytes
            val kPacketPacingTime = timeToSend(kPacketSize, kPacingDataRate)
            var sequnceNumber = 1
            val pacer = PacingController(logger, clock, callback, trials)

            // Start with no padding.
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), kPacingDataRate, Bandwidth.ZERO))

            // Send a single packet.
            sendAndExpectPacket(
                pacer,
                RtpPacketMediaType.kVideo,
                kSsrc,
                sequnceNumber++,
                clock.roundedMillis(),
                kPacketSize.wholeBytes().toInt()
            )
            pacer.processPackets()
            callback.verifyAndClearExpectations()

            // With current conditions, no need to wake until next keep-alive.
            timeBetween(clock.instant(), pacer.nextSendTime()) shouldBe PacingController.kPausedProcessInterval

            // Enqueue a new packet, that can be sent immediately due to default burst
            // rate is 40ms.
            sendAndExpectPacket(
                pacer,
                RtpPacketMediaType.kVideo,
                kSsrc,
                sequnceNumber++,
                clock.roundedMillis(),
                kPacketSize.wholeBytes().toInt()
            )
            timeBetween(clock.instant(), pacer.nextSendTime()) shouldBe Duration.ZERO
            pacer.processPackets()
            callback.verifyAndClearExpectations()

            // With current conditions, again no need to wake until next keep-alive.
            timeBetween(clock.instant(), pacer.nextSendTime()) shouldBe PacingController.kPausedProcessInterval

            // Set a non-zero padding rate. Padding also can't be sent until
            // previous debt has cleared. Since padding was disabled before, there
            // currently is no padding debt.
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), kPacingDataRate, (kPacingDataRate.bps / 2).bps))
            pacer.queueSizePackets() shouldBe 0
            timeBetween(clock.instant(), pacer.nextSendTime()) shouldBeLessThan PacerConfig.kDefaultTimeInterval

            // Advance time, expect padding.
            callback.expectSendPadding().willOnce { kPacketSize.wholeBytes().toInt() }
            clock.elapse(timeBetween(clock.instant(), pacer.nextSendTime()))
            pacer.processPackets()
            callback.verifyAndClearExpectations()

            // Since padding rate is half of pacing rate, next time we can send
            // padding is double the packet pacing time.
            timeBetween(clock.instant(), pacer.nextSendTime()) shouldBeGreaterThan
                kPacketPacingTime.multipliedBy(2) - 50.micros
            timeBetween(clock.instant(), pacer.nextSendTime()) shouldBeLessThan
                kPacketPacingTime.multipliedBy(2) + 50.micros

            // Insert a packet to be sent, this take precedence again.
            pacer.enqueuePacket(
                buildPacket(
                    RtpPacketMediaType.kVideo,
                    kSsrc,
                    sequnceNumber++,
                    clock.roundedMillis(),
                    kPacketSize.wholeBytes().toInt()
                )
            )
            pacer.nextSendTime() shouldBe clock.instant()
        }

        "PaddingTargetAccountsForPaddingRate" {
            // Target size for a padding packet is 5ms * padding rate.
            val kPaddingTarget = 5.ms
            // Jitsi local: srand(0) is omitted. The test does not call rand().
            // Need to initialize PacingController after we initialize clock.
            val pacer = PacingController(logger, clock, callback, trials)

            val kSsrc = 12345L
            val kPacingDataRate = 125.kbps
            val kPacketSize = 130.bytes

            var sequnceNumber = 1

            // Start with pacing and padding rate equal.
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), kPacingDataRate, kPacingDataRate))

            // Send a single packet.
            sendAndExpectPacket(
                pacer,
                RtpPacketMediaType.kVideo,
                kSsrc,
                sequnceNumber++,
                clock.roundedMillis(),
                kPacketSize.wholeBytes().toInt()
            )
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            callback.verifyAndClearExpectations()

            val expectedPaddingTargetBytes = dataSentAt(kPacingDataRate, kPaddingTarget).wholeBytes().toInt()
            callback.expectSendPadding(expectedPaddingTargetBytes).willOnce { expectedPaddingTargetBytes }
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()

            // Half the padding rate - expect half the padding target.
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), kPacingDataRate, (kPacingDataRate.bps / 2).bps))
            callback.expectSendPadding(expectedPaddingTargetBytes / 2).willOnce { expectedPaddingTargetBytes / 2 }
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
        }

        "SendsFecPackets" {
            val kSsrc = 12345L
            val kFlexSsrc = 54321L
            val sequenceNumber = 1234
            val flexfecSequenceNumber = 4321
            val kPacketSize = 123
            val pacer = PacingController(logger, clock, callback, trials)

            // Set pacing rate to 1000 packet/s, no padding.
            pacer.setPacerConfig(
                PacerConfig.create(clock.instant(), rateOf((1000 * kPacketSize).bytes, 1.secs), Bandwidth.ZERO)
            )

            val now = clock.roundedMillis()
            pacer.enqueuePacket(buildPacket(RtpPacketMediaType.kVideo, kSsrc, sequenceNumber, now, kPacketSize))
            callback.expectSendPacket(kSsrc, sequenceNumber, now, false, false)
            callback.expectFetchFec().willOnce {
                callback.expectSendPacket(kFlexSsrc, flexfecSequenceNumber, now, false, false)
                callback.expectFetchFec()
                val fecPackets = mutableListOf<RtpPacketToSend>()
                fecPackets.add(
                    buildPacket(
                        RtpPacketMediaType.kForwardErrorCorrection,
                        kFlexSsrc,
                        flexfecSequenceNumber,
                        now,
                        kPacketSize
                    )
                )
                fecPackets
            }
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
        }

        "GapInPacingDoesntAccumulateBudget" {
            val kSsrc = 12345L
            var sequenceNumber = 1234
            val kPackeSize = 1000.bytes
            val kPacketSendTime = 25.ms
            val pacer = PacingController(logger, clock, callback, trials)

            pacer.setPacerConfig(
                PacerConfig.create(clock.instant(), rateOf(kPackeSize, kPacketSendTime), padRate = Bandwidth.ZERO)
            )

            // Send an initial packet.
            sendAndExpectPacket(
                pacer,
                RtpPacketMediaType.kVideo,
                kSsrc,
                sequenceNumber++,
                clock.roundedMillis(),
                kPackeSize.wholeBytes().toInt()
            )
            pacer.processPackets()
            callback.verifyAndClearExpectations()

            // Advance time kPacketSendTime past where the media debt should be 0.
            clock.elapse(kPacketSendTime.multipliedBy(2))

            // Enqueue three new packets. Expect only two to be sent one ProcessPackets()
            // since the default burst interval is 40ms.
            sendAndExpectPacket(
                pacer,
                RtpPacketMediaType.kVideo,
                kSsrc,
                sequenceNumber++,
                clock.roundedMillis(),
                kPackeSize.wholeBytes().toInt()
            )
            sendAndExpectPacket(
                pacer,
                RtpPacketMediaType.kVideo,
                kSsrc,
                sequenceNumber++,
                clock.roundedMillis(),
                kPackeSize.wholeBytes().toInt()
            )
            callback.expectSendPacket(kSsrc, sequenceNumber + 1, any(), any(), any()).times(0)
            pacer.enqueuePacket(
                buildPacket(
                    RtpPacketMediaType.kVideo,
                    kSsrc,
                    sequenceNumber + 1,
                    clock.roundedMillis(),
                    kPackeSize.wholeBytes().toInt()
                )
            )

            pacer.processPackets()
        }

        "HandlesSubMicrosecondSendIntervals" {
            val kPacketSize = 1.bytes
            val kPacketSendTime = 1.micros
            val pacer = PacingController(logger, clock, callback, trials)

            // Set pacing rate such that a packet is sent in 0.5us.
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = rateOf((2 * kPacketSize.wholeBytes()).bytes, kPacketSendTime),
                    padRate = Bandwidth.ZERO,
                    timeWindow = Duration.ZERO
                )
            )

            // Enqueue three packets, the first two should be sent immediately - the third
            // should cause a non-zero delta to the next process time.
            callback.expectSendPacket().times(2)
            for (i in 0 until 3) {
                pacer.enqueuePacket(
                    buildPacket(
                        RtpPacketMediaType.kVideo,
                        ssrc = 12345,
                        sequenceNumber = i,
                        clock.roundedMillis(),
                        kPacketSize.wholeBytes().toInt()
                    )
                )
            }
            pacer.processPackets()

            pacer.nextSendTime() shouldBeGreaterThan clock.instant()
        }

        "HandlesSubMicrosecondPaddingInterval" {
            val kPacketSize = 1.bytes
            val kPacketSendTime = 1.micros
            val pacer = PacingController(logger, clock, callback, trials)

            // Set both pacing and padding rates to 1 byte per 0.5us.
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = rateOf((2 * kPacketSize.wholeBytes()).bytes, kPacketSendTime),
                    padRate = rateOf((2 * kPacketSize.wholeBytes()).bytes, kPacketSendTime)
                )
            )

            // Enqueue and send one packet.
            callback.expectSendPacket()
            pacer.enqueuePacket(
                buildPacket(
                    RtpPacketMediaType.kVideo,
                    ssrc = 12345,
                    sequenceNumber = 1234,
                    clock.roundedMillis(),
                    kPacketSize.wholeBytes().toInt()
                )
            )
            pacer.processPackets()

            // The padding debt is now 1 byte, and the pacing time for that is lower than
            // the precision of a TimeStamp tick. Make sure the pacer still indicates a
            // non-zero sleep time is needed until the next process.
            pacer.nextSendTime() shouldBeGreaterThan clock.instant()
        }

        "SendsPacketsInBurstImmediately" {
            val kMaxDelay = 20.ms
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = 10000.bytesPerSec,
                    padRate = Bandwidth.ZERO,
                    timeWindow = kMaxDelay
                )
            )

            // Max allowed send burst size is 100000*20/1000) = 200byte
            pacer.enqueuePacket(video.buildNextPacket(100))
            pacer.enqueuePacket(video.buildNextPacket(100))
            pacer.enqueuePacket(video.buildNextPacket(100))
            pacer.processPackets()
            pacer.queueSizePackets() shouldBe 1
            pacer.nextSendTime() shouldBe clock.instant() + kMaxDelay

            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
            pacer.queueSizePackets() shouldBe 0
        }

        "SendsPacketsInBurstEvenIfNotEnqueedAtSameTime" {
            val kMaxDelay = 20.ms
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = 10000.bytesPerSec,
                    padRate = Bandwidth.ZERO,
                    timeWindow = kMaxDelay
                )
            )
            pacer.enqueuePacket(video.buildNextPacket(200))
            pacer.nextSendTime() shouldBe clock.instant()
            pacer.processPackets()
            clock.elapse(1.ms)
            pacer.enqueuePacket(video.buildNextPacket(200))
            pacer.nextSendTime() shouldBe clock.instant()
            pacer.processPackets()
            pacer.queueSizePackets() shouldBe 0
        }

        "RespectsTargetRateWhenSendingPacketsInBursts" {
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setAccountForAudioPackets(true)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = 1000.kbps,
                    padRate = Bandwidth.ZERO,
                    timeWindow = 20.ms
                )
            )
            val startTime = clock.instant()
            // Inject 100 packets, with size 1000bytes over 100ms.
            // Expect only 1Mbps / (8*1000) / 10 =  12 packets to be sent.
            // Packets are sent in burst. Each burst is then 3 packets * 1000bytes at
            // 1Mbits = 24ms long. Thus, expect 4 bursts.
            callback.expectSendPacket().times(12)
            var numberOfBursts = 0
            while (clock.instant() < startTime + 100.ms) {
                pacer.enqueuePacket(video.buildNextPacket(1000))
                pacer.enqueuePacket(video.buildNextPacket(1000))
                pacer.enqueuePacket(video.buildNextPacket(1000))
                pacer.enqueuePacket(video.buildNextPacket(1000))
                pacer.enqueuePacket(video.buildNextPacket(1000))
                if (pacer.nextSendTime() <= clock.instant()) {
                    pacer.processPackets()
                    ++numberOfBursts
                }
                clock.elapse(5.ms)
            }
            pacer.queueSizePackets() shouldBe 88
            numberOfBursts shouldBe 4
        }

        "MaxBurstSizeLimitedAtHighPacingRateWhenSendingPacketsInBursts" {
            val callback = MockPacketSender()
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(
                PacerConfig.create(
                    clock.instant(),
                    sendRate = 10_000.kbps,
                    padRate = Bandwidth.ZERO,
                    timeWindow = 100.ms
                )
            )

            var sentSizeInBurst = 0
            callback.expectSendPacket().willRepeatedly { (packet, _) ->
                sentSizeInBurst += packet.size()
            }

            // Enqueue 200 packets from a 200Kb encoded frame.
            for (i in 0 until 200) {
                pacer.enqueuePacket(video.buildNextPacket(1000))
            }

            while (pacer.queueSizePackets() > 70) {
                pacer.processPackets()
                expectNear(sentSizeInBurst, PacingController.kMaxBurstSize.wholeBytes(), 1000)
                sentSizeInBurst = 0
                val timeToNext = timeBetween(clock.instant(), pacer.nextSendTime())
                expectNear(timeToNext.toRoundedMillis(), 50, 2)
                clock.elapse(timeToNext)
            }
        }

        "RespectsQueueTimeLimit" {
            val kPacketSize = 100.bytes
            val kNominalPacingRate = 200.kbps
            val kPacketPacingTime = timeToSend(kPacketSize, kNominalPacingRate)
            val kQueueTimeLimit = 1000.ms

            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), kNominalPacingRate, padRate = Bandwidth.ZERO))
            pacer.setQueueTimeLimit(kQueueTimeLimit)

            // Fill pacer up to queue time limit.
            val kNumPackets = kQueueTimeLimit.ratioTo(kPacketPacingTime).toInt()
            for (i in 0 until kNumPackets) {
                pacer.enqueuePacket(video.buildNextPacket(kPacketSize.wholeBytes().toInt()))
            }
            pacer.expectedQueueTime() shouldBe kQueueTimeLimit
            pacer.pacingRate() shouldBe kNominalPacingRate

            // Double the amount of packets in the queue, the queue time limit should
            // effectively double the pacing rate in response.
            for (i in 0 until kNumPackets) {
                pacer.enqueuePacket(video.buildNextPacket(kPacketSize.wholeBytes().toInt()))
            }
            pacer.expectedQueueTime() shouldBe kQueueTimeLimit
            pacer.pacingRate() shouldBe (2 * kNominalPacingRate.bps).bps

            // Send all the packets, should take as long as the queue time limit.
            val startTime = clock.instant()
            while (pacer.queueSizePackets() > 0) {
                advanceTimeUntil(pacer.nextSendTime())
                pacer.processPackets()
            }
            Duration.between(startTime, clock.instant()) shouldBe kQueueTimeLimit

            // We're back in a normal state - pacing rate should be back to previous
            // levels.
            pacer.pacingRate() shouldBe kNominalPacingRate
        }

        "BudgetDoesNotAffectRetransmissionInsTrial" {
            val kPacketSize = 1000.bytes

            callback.expectSendPadding().times(0)
            val trials = createTestFieldTrials(fastRetransmissions = true)
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), kTargetRate, padRate = Bandwidth.ZERO))

            // Send a video packet so that we have a bit debt.
            pacer.enqueuePacket(
                buildPacket(
                    RtpPacketMediaType.kVideo,
                    kVideoSsrc,
                    sequenceNumber = 1,
                    captureTimeMs = 1,
                    kPacketSize.wholeBytes().toInt()
                )
            )
            callback.expectSendPacket()
            pacer.processPackets()
            pacer.nextSendTime() shouldBeGreaterThan clock.instant()

            // A retransmission packet should still be immediately processed.
            callback.expectSendPacket()
            pacer.enqueuePacket(
                buildPacket(
                    RtpPacketMediaType.kRetransmission,
                    kVideoSsrc,
                    sequenceNumber = 1,
                    captureTimeMs = 1,
                    kPacketSize.wholeBytes().toInt()
                )
            )
            pacer.processPackets()
        }

        "AbortsAfterReachingCircuitBreakLimit" {
            val kPacketSize = 1000.bytes

            callback.expectSendPadding().times(0)
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), kTargetRate, padRate = Bandwidth.ZERO))

            // Set the circuit breaker to abort after one iteration of the main
            // sending loop.
            pacer.setCircuitBreakerThreshold(1)
            callback.expectSendPacket().times(1)

            // Send two packets.
            pacer.enqueuePacket(
                buildPacket(
                    RtpPacketMediaType.kVideo,
                    kVideoSsrc,
                    sequenceNumber = 1,
                    captureTimeMs = 1,
                    kPacketSize.wholeBytes().toInt()
                )
            )
            pacer.enqueuePacket(
                buildPacket(
                    RtpPacketMediaType.kVideo,
                    kVideoSsrc,
                    sequenceNumber = 2,
                    captureTimeMs = 2,
                    kPacketSize.wholeBytes().toInt()
                )
            )

            // Advance time to way past where both should be eligible for sending.
            clock.elapse(1.secs)

            pacer.processPackets()
        }

        "DoesNotPadIfProcessThreadIsBorked" {
            val callback = PacingControllerPadding()
            val pacer = PacingController(logger, clock, callback, trials)

            // Set both pacing and padding rate to be non-zero.
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), kTargetRate, padRate = kTargetRate))

            // Add one packet to the queue, but do not send it yet.
            pacer.enqueuePacket(
                buildPacket(
                    RtpPacketMediaType.kVideo,
                    kVideoSsrc,
                    sequenceNumber = 1,
                    captureTimeMs = 1,
                    payloadSize = 1000
                )
            )

            // Advance time to waaay after the packet should have been sent.
            clock.elapse(42.secs)

            // `ProcessPackets()` should send the delayed packet, followed by a small
            // amount of missed padding.
            pacer.processPackets()

            // The max padding window is the max replay duration + the target padding
            // duration.
            val kMaxPadding = dataSentAt(
                kTargetRate,
                PacingController.kMaxPaddingReplayDuration + PacingController.kTargetPaddingDuration
            )

            callback.paddingSent shouldBeLessThanOrEqualTo kMaxPadding.wholeBytes()
        }

        "FlushesPacketsOnKeyFrames" {
            val kSsrc = 12345L
            val kRtxSsrc = 12346L

            val pacer = PacingController(logger, clock, callback, trials)
            callback.expectGetRtxSsrcForMedia(kSsrc).willRepeatedly { kRtxSsrc }
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), kTargetRate, Bandwidth.ZERO))

            // Enqueue a video packet and a retransmission of that video stream.
            pacer.enqueuePacket(
                buildPacket(RtpPacketMediaType.kVideo, kSsrc, sequenceNumber = 1, captureTimeMs = 1, payloadSize = 100)
            )
            pacer.enqueuePacket(
                buildPacket(
                    RtpPacketMediaType.kRetransmission,
                    kRtxSsrc,
                    sequenceNumber = 10,
                    captureTimeMs = 1,
                    payloadSize = 100
                )
            )
            pacer.queueSizePackets() shouldBe 2

            // Enqueue the first packet of a keyframe for said stream.
            val packet = buildPacket(
                RtpPacketMediaType.kVideo,
                kSsrc,
                sequenceNumber = 2,
                captureTimeMs = 2,
                payloadSize = 1000,
                isKeyFrame = true,
                isFirstPacketOfFrame = true
            )
            pacer.enqueuePacket(packet)

            // Only they new keyframe packet should be left in the queue.
            pacer.queueSizePackets() shouldBe 1

            callback.expectSendPacket(
                kSsrc,
                sequenceNumber = 2,
                captureTimestamp = 2,
                retransmission = false,
                padding = false
            )
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
        }

        "FlushedPacketsSentWithAllowSendingFalseWhenEnabled" {
            val kSsrc = 12345L
            val kRtxSsrc = 12346L

            val trials = createTestFieldTrials(retransmitFlushedPackets = true)
            val callback = MockPacketSender()
            callback.expectGetRtxSsrcForMedia(kSsrc).willRepeatedly { kRtxSsrc }
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), kTargetRate, Bandwidth.ZERO))

            // Enqueue two video packets.
            pacer.enqueuePacket(
                buildPacket(RtpPacketMediaType.kVideo, kSsrc, sequenceNumber = 1, captureTimeMs = 1, payloadSize = 100)
            )
            pacer.enqueuePacket(
                buildPacket(RtpPacketMediaType.kVideo, kSsrc, sequenceNumber = 2, captureTimeMs = 1, payloadSize = 100)
            )
            pacer.queueSizePackets() shouldBe 2

            // When keyframe is enqueued, the previous 2 packets should be flushed,
            // marked as allow_sending == false, and passed to callback.SendPacket.
            val flushedPackets = mutableListOf<RtpPacketToSend>()
            callback.expectSendPacket().willRepeatedly { (packet, _) ->
                flushedPackets.add(packet)
            }

            val keyframePacket = buildPacket(
                RtpPacketMediaType.kVideo,
                kSsrc,
                sequenceNumber = 3,
                captureTimeMs = 2,
                payloadSize = 1000,
                isKeyFrame = true,
                isFirstPacketOfFrame = true
            )
            pacer.enqueuePacket(keyframePacket)

            flushedPackets.map { Pair(it.allowSending, it.sequenceNumber) } shouldBe
                listOf(Pair(false, 1), Pair(false, 2))
            pacer.queueSizePackets() shouldBe 1

            // The remaining packet in queue should be the keyframe packet, which is
            // allowed to be sent.
            callback.expectSendPacket { packet, _ -> packet.allowSending && packet.sequenceNumber == 3 }
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
        }

        "FlushedPacketsDroppedWhenDisabled" {
            val kSsrc = 12345L
            val kRtxSsrc = 12346L

            val callback = MockPacketSender()
            callback.expectGetRtxSsrcForMedia(kSsrc).willRepeatedly { kRtxSsrc }
            val pacer = PacingController(logger, clock, callback, trials)
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), kTargetRate, Bandwidth.ZERO))

            // Enqueue two video packets.
            pacer.enqueuePacket(
                buildPacket(RtpPacketMediaType.kVideo, kSsrc, sequenceNumber = 1, captureTimeMs = 1, payloadSize = 100)
            )
            pacer.enqueuePacket(
                buildPacket(RtpPacketMediaType.kVideo, kSsrc, sequenceNumber = 2, captureTimeMs = 1, payloadSize = 100)
            )
            pacer.queueSizePackets() shouldBe 2

            // When keyframe is enqueued with trial disabled, flushed packets must NOT be
            // passed to callback.SendPacket (they are silently dropped).
            callback.expectSendPacket().times(0)

            val keyframePacket = buildPacket(
                RtpPacketMediaType.kVideo,
                kSsrc,
                sequenceNumber = 3,
                captureTimeMs = 2,
                payloadSize = 1000,
                isKeyFrame = true,
                isFirstPacketOfFrame = true
            )
            pacer.enqueuePacket(keyframePacket)

            pacer.queueSizePackets() shouldBe 1

            // The remaining packet in queue is the keyframe packet.
            callback.expectSendPacket { packet, _ -> packet.allowSending && packet.sequenceNumber == 3 }
            advanceTimeUntil(pacer.nextSendTime())
            pacer.processPackets()
        }

        "CanControlQueueSizeUsingTtl" {
            val kSsrc = 54321L
            var sequenceNumber = 1234

            val config = PacingController.Configuration(
                drainLargeQueues = false,
                packetQueueTtl = PacketQueueTTL(video = 500.ms)
            )
            val pacer = PacingController(logger, clock, callback, trials, config)
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), 100_000.bps, Bandwidth.ZERO))

            var sendTime = Instant.EPOCH
            for (i in 0 until 100) {
                // Enqueue a new audio and video frame every 33ms.
                if (Duration.between(sendTime, clock.instant()) > 33.ms) {
                    for (j in 0 until 3) {
                        val packet = buildPacket(
                            RtpPacketMediaType.kVideo,
                            kSsrc,
                            sequenceNumber = ++sequenceNumber,
                            captureTimeMs = 2,
                            payloadSize = 1000
                        )
                        pacer.enqueuePacket(packet)
                    }
                    val packet = buildPacket(
                        RtpPacketMediaType.kAudio,
                        kAudioSsrc,
                        sequenceNumber = ++sequenceNumber,
                        captureTimeMs = 2,
                        payloadSize = 100
                    )
                    pacer.enqueuePacket(packet)
                    sendTime = clock.instant()
                }

                timeBetween(pacer.oldestPacketEnqueueTime(), clock.instant()) shouldBeLessThanOrEqualTo 500.ms
                clock.elapse(timeBetween(clock.instant(), pacer.nextSendTime()))
                pacer.processPackets()
            }
        }

        // Jitsi local: WebRTC frees the packets that it removes from the queue, and has no test of that.
        "PacketsRemovedFromTheQueueAreGivenToOnPacketDropped" {
            val kRtxSsrc = 234566L
            val kOtherSsrc = 234567L
            val dropped = mutableListOf<RtpPacketToSend>()
            val sender = object : PacingController.PacketSender {
                override fun sendPacket(packet: RtpPacketToSend, clusterInfo: PacedPacketInfo) {}

                override fun fetchFec(): List<RtpPacketToSend> = emptyList()

                override fun generatePadding(size: DataSize): List<RtpPacketToSend> = emptyList()

                override fun getRtxSsrcForMedia(ssrc: Long): Long? = if (ssrc == kVideoSsrc) kRtxSsrc else null

                override fun onPacketDropped(packet: RtpPacketToSend) {
                    dropped.add(packet)
                }
            }
            val pacer = PacingController(logger, clock, sender, trials)
            pacer.setPacerConfig(PacerConfig.create(clock.instant(), sendRate = kTargetRate, padRate = Bandwidth.ZERO))

            pacer.enqueuePacket(buildPacket(RtpPacketMediaType.kVideo, kVideoSsrc, 1, 0, 100))
            pacer.enqueuePacket(buildPacket(RtpPacketMediaType.kVideo, kVideoSsrc, 2, 0, 100))
            pacer.enqueuePacket(buildPacket(RtpPacketMediaType.kRetransmission, kRtxSsrc, 10, 0, 100))
            pacer.enqueuePacket(buildPacket(RtpPacketMediaType.kVideo, kOtherSsrc, 20, 0, 100))
            dropped.map { it.sequenceNumber } shouldBe emptyList()

            // The first packet of a keyframe removes the queued packets of its SSRC and of the RTX SSRC.
            pacer.enqueuePacket(
                buildPacket(
                    RtpPacketMediaType.kVideo,
                    kVideoSsrc,
                    3,
                    0,
                    100,
                    isKeyFrame = true,
                    isFirstPacketOfFrame = true
                )
            )
            dropped.map { it.sequenceNumber } shouldBe listOf(1, 2, 10)
            pacer.queueSizePackets() shouldBe 2

            pacer.removePacketsForSsrc(kOtherSsrc)
            dropped.map { it.sequenceNumber } shouldBe listOf(1, 2, 10, 20)
            pacer.queueSizePackets() shouldBe 1
        }
    }
}
