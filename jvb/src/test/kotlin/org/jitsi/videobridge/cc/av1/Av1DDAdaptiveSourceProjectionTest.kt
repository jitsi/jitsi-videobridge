/*
 * Copyright @ 2019 - Present, 8x8 Inc
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
package org.jitsi.videobridge.cc.av1

import jakarta.xml.bind.DatatypeConverter.parseHexBinary
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket
import org.jitsi.nlj.rtp.codec.av1.Av1DDRtpLayerDesc.Companion.getIndex
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorHeaderExtension
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorReader
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure
import org.jitsi.rtp.rtp.header_extensions.DTI
import org.jitsi.rtp.rtp.header_extensions.FrameInfo
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.cc.RtpState
import org.junit.Assert
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.xml.bind.DatatypeConverter

class Av1DDAdaptiveSourceProjectionTest {
    private val logger: Logger = LoggerImpl(javaClass.name)

    @Test
    fun singlePacketProjectionTest() {
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "singlePacketProjectionTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Av1DDAdaptiveSourceProjectionContext(
            diagnosticContext,
            initialState, logger
        )
        val generator = ScalableAv1PacketGenerator(1)
        val packetInfo = generator.nextPacket()
        val packet = packetInfo.packetAs<Av1DDPacket>()
        val packetIndices = packet.frameInfo!!.dti.withIndex()
            .filter { (_, dti) -> dti != DTI.NOT_PRESENT }
            .map { (i, _) -> getIndex(0, i) }
        val targetIndex = getIndex(eid = 0, dt = 0)
        Assert.assertTrue(
            context.accept(packetInfo, packetIndices, targetIndex)
        )
        context.rewriteRtp(packetInfo)
        Assert.assertEquals(10001, packet.sequenceNumber)
        Assert.assertEquals(1003000, packet.timestamp)
        Assert.assertEquals(0, packet.frameNumber)
        Assert.assertEquals(0, packet.frameInfo?.spatialId)
        Assert.assertEquals(0, packet.frameInfo?.temporalId)
    }

    private fun runInOrderTest(
        generator: Av1PacketGenerator,
        targetIndex: Int,
        expectAccept: (FrameInfo) -> Boolean
    ) {
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = Thread.currentThread().stackTrace[2].methodName
        val initialState = RtpState(1, 10000, 1000000)
        val context = Av1DDAdaptiveSourceProjectionContext(
            diagnosticContext,
            initialState, logger
        )
        var expectedSeq = 10001
        var expectedTs: Long = 1003000
        var expectedFrameNumber = 0
        for (i in 0..99999) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Av1DDPacket>()
            val frameInfo = packet.frameInfo
            val packetIndices = frameInfo!!.dti.withIndex()
                .filter { (_, dti) -> dti != DTI.NOT_PRESENT }
                .map { (i, _) -> getIndex(0, i) }
            val accepted = context.accept(
                packetInfo,
                packetIndices, targetIndex
            )
            val endOfFrame = packet.isEndOfFrame
            val endOfPicture = packet.isMarked // Save this before rewriteRtp
            if (expectAccept(frameInfo)) {
                Assert.assertTrue(accepted)
                context.rewriteRtp(packetInfo)
                Assert.assertEquals(expectedSeq, packet.sequenceNumber)
                Assert.assertEquals(expectedTs, packet.timestamp)
                Assert.assertEquals(expectedFrameNumber, packet.frameNumber)
                expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
            } else {
                Assert.assertFalse(accepted)
            }
            if (endOfFrame) {
                expectedFrameNumber = RtpUtils.applySequenceNumberDelta(expectedFrameNumber, 1)
            }
            if (endOfPicture) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
            }
        }
    }

    @Test
    fun simpleNonScalableTest() {
        val generator = NonScalableAv1PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            true
        }
    }

    @Test
    fun simpleTemporalProjectionTest() {
        val generator = TemporallyScaledPacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            true
        }
    }

    @Test
    fun filteredTemporalProjectionTest() {
        val generator = TemporallyScaledPacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.temporalId == 0
        }
    }
}

private open class Av1PacketGenerator(
    val packetsPerFrame: Int,
    val keyframeTemplates: Array<Int>,
    val normalTemplates: Array<Int>,
    val framesPerTimestamp: Int, /* Equivalent to number of layers */
    templateDdHex: String,
    val allKeyframesGetStructure: Boolean = false
) {
    var packetOfFrame = 0
        private set
    private var frameOfPicture = 0

    private var seq = 0
    private var ts: Long = 0L
    var ssrc: Long = 0xcafebabeL
    private var frameNumber = 0
    private var keyframePicture = false
    private var keyframeRequested = false
    private var pictureCount = 0
    private var receivedTime = baseReceivedTime
    private var templateIdx = 0
    private var packetCount = 0
    private var octetCount = 0

    private val structure: Av1TemplateDependencyStructure

    init {
        val dd = parseHexBinary(templateDdHex)
        structure = Av1DependencyDescriptorReader(dd, 0, dd.size).parse(null).structure
    }

    fun reset() {
        val useRandom = true // switch off to ease debugging
        val seed = System.currentTimeMillis()
        val random = Random(seed)
        seq = if (useRandom) random.nextInt() % 0x10000 else 0
        ts = if (useRandom) random.nextLong() % 0x100000000L else 0
        frameNumber = 0
        packetOfFrame = 0
        frameOfPicture = 0
        keyframePicture = true
        keyframeRequested = false
        ssrc = 0xcafebabeL
        pictureCount = 0
        receivedTime = baseReceivedTime
        templateIdx = 0
        packetCount = 0
        octetCount = 0
    }

    fun nextPacket(): PacketInfo {
        val startOfFrame = packetOfFrame == 0
        val endOfFrame = packetOfFrame == packetsPerFrame - 1
        val startOfPicture = startOfFrame && frameOfPicture == 0
        val endOfPicture = endOfFrame && frameOfPicture == framesPerTimestamp - 1

        val templateId = (if (keyframePicture) keyframeTemplates[templateIdx] else normalTemplates[templateIdx]) +
            structure.templateIdOffset

        val buffer = packetTemplate.clone()
        val rtpPacket = RtpPacket(buffer, 0, buffer.size)
        rtpPacket.ssrc = ssrc
        rtpPacket.sequenceNumber = seq
        rtpPacket.timestamp = ts
        rtpPacket.isMarked = endOfPicture

        val dd = Av1DependencyDescriptorHeaderExtension(
            startOfFrame = startOfFrame,
            endOfFrame = endOfFrame,
            frameDependencyTemplateId = templateId,
            frameNumber = frameNumber,
            newTemplateDependencyStructure =
            if (keyframePicture && startOfFrame && (startOfPicture || allKeyframesGetStructure))
                structure else null,
            activeDecodeTargetsBitmask = null,
            customDtis = null,
            customFdiffs = null,
            customChains = null,
            structure = structure
        )

        val ext = rtpPacket.addHeaderExtension(AV1_DD_HEADER_EXTENSION_ID, dd.encodedLength)
        dd.write(ext)
        rtpPacket.encodeHeaderExtensions()

        val av1Packet = Av1DDPacket(rtpPacket, AV1_DD_HEADER_EXTENSION_ID, structure)

        val info = PacketInfo(av1Packet)
        info.receivedTime = receivedTime

        seq = RtpUtils.applySequenceNumberDelta(seq, 1)
        packetCount++
        octetCount += av1Packet.length

        if (endOfFrame) {
            packetOfFrame = 0
            if (endOfPicture) {
                frameOfPicture = 0
            } else {
                frameOfPicture++
            }
            frameNumber = RtpUtils.applySequenceNumberDelta(frameNumber, 1)
        } else {
            packetOfFrame++
        }

        if (endOfPicture) {
            ts = RtpUtils.applyTimestampDelta(ts, 3000)
            templateIdx++
            if (keyframeRequested) {
                keyframePicture = true
                templateIdx = 0
            } else if (keyframePicture) {
                if (templateIdx >= keyframeTemplates.size) {
                    keyframePicture = false
                }
            }
            keyframeRequested = false
            if (templateIdx >= normalTemplates.size) {
                templateIdx = 0
            }
            pictureCount++
            receivedTime = baseReceivedTime + Duration.ofMillis(pictureCount * 100L / 3)
        }

        return info
    }

    fun requestKeyframe() {
        keyframeRequested = true
    }

    init {
        reset()
    }

    companion object {
        val baseReceivedTime: Instant = Instant.ofEpochMilli(1577836800000L) /* 2020-01-01 00:00:00 UTC */

        const val AV1_DD_HEADER_EXTENSION_ID = 11

        private val packetTemplate = DatatypeConverter.parseHexBinary( /* RTP Header */
            "80" + /* V, P, X, CC */
                "29" + /* M, PT */
                "0000" + /* Seq */
                "00000000" + /* TS */
                "cafebabe" + /* SSRC */
                // Header extension will be added dynamically
                // Dummy payload data
                "0000000000000000000000"
        )
    }
}

private class NonScalableAv1PacketGenerator(
    packetsPerFrame: Int
) :
    Av1PacketGenerator(
        packetsPerFrame,
        arrayOf(0),
        arrayOf(1),
        1,
        "80000180003a410180ef808680"
    )

/** Generate a temporally-scaled series of AV1 frames, with a single keyframe at the start. */
private class TemporallyScaledPacketGenerator(packetsPerFrame: Int) : Av1PacketGenerator(
    packetsPerFrame,
    arrayOf(0),
    arrayOf(1, 3, 2, 4),
    1,
    "800001800214eaa860414d141020842701df010d"
)

private class ScalableAv1PacketGenerator(
    packetsPerFrame: Int
) :
    Av1PacketGenerator(
        packetsPerFrame,
        arrayOf(1, 6, 11),
        arrayOf(0, 5, 10, 3, 8, 13, 2, 7, 12, 4, 9, 14),
        3,
        "d0013481e81485214eafffaaaa863cf0430c10c302afc0aaa0063c00430010c002a000a800060000" +
            "40001d954926e082b04a0941b820ac1282503157f974000ca864330e222222eca8655304224230ec" +
            "a87753013f00b3027f016704ff02cf"
    )
