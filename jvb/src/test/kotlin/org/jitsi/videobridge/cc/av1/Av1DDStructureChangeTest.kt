/*
 * Copyright @ 2026 - Present, 8x8 Inc
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
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.cc.RtpState
import org.jitsi.videobridge.cc.accept
import org.junit.Assert
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * A sender changes its scalability structure (for example L3T3_KEY to L1T3, as lib-jitsi-meet does when the
 * sender constraints change). The first packet of the new keyframe, which carries the new structure, arrives
 * after a later packet of the same keyframe. So the frame is not seen as a keyframe, but the frames after it
 * are parsed with the new, smaller structure, while the quality filter still forwards a decode target of the
 * old structure.
 */
class Av1DDStructureChangeTest {
    private val logger: Logger = LoggerImpl(javaClass.name)

    private var seq = 1000
    private var ts = 90000L
    private var frameNumber = 0
    private var pictureCount = 0

    private fun receivedTime(): Instant = baseTime + Duration.ofMillis(pictureCount * 100L / 3)

    private fun packet(
        structure: Av1TemplateDependencyStructure,
        templateIndex: Int,
        startOfFrame: Boolean,
        endOfFrame: Boolean,
        marker: Boolean,
        attachStructure: Boolean,
        /** The structure the bridge knows when it parses this packet. */
        knownStructure: Av1TemplateDependencyStructure?,
        seqNum: Int = seq,
        frameNum: Int = frameNumber
    ): PacketInfo {
        val buffer = packetTemplate.clone()
        val rtpPacket = RtpPacket(buffer, 0, buffer.size)
        rtpPacket.ssrc = SSRC
        rtpPacket.sequenceNumber = seqNum
        rtpPacket.timestamp = ts
        rtpPacket.isMarked = marker
        val dd = Av1DependencyDescriptorHeaderExtension(
            startOfFrame = startOfFrame,
            endOfFrame = endOfFrame,
            frameDependencyTemplateId = (templateIndex + structure.templateIdOffset) % 64,
            frameNumber = frameNum,
            newTemplateDependencyStructure = if (attachStructure) structure else null,
            activeDecodeTargetsBitmask = null,
            customDtis = null,
            customFdiffs = null,
            customChains = null,
            structure = structure
        )
        val ext = rtpPacket.addHeaderExtension(EXT_ID, dd.encodedLength)
        dd.write(ext)
        rtpPacket.encodeHeaderExtensions()
        val av1Packet = Av1DDPacket(rtpPacket, EXT_ID, knownStructure, logger)
        av1Packet.encodingId = 0
        return PacketInfo(av1Packet).also { it.receivedTime = receivedTime() }
    }

    /** Send one picture of three single-packet frames (S0, S1, S2) of the L3T3_KEY structure. */
    private fun sendKsvcPicture(
        context: Av1DDAdaptiveSourceProjectionContext,
        templates: List<Int>,
        attachStructure: Boolean,
        target: Int
    ): List<Boolean> {
        val result = templates.mapIndexed { i, t ->
            val p = packet(
                ksvc,
                t,
                startOfFrame = true,
                endOfFrame = true,
                marker = i == templates.size - 1,
                attachStructure = attachStructure && i == 0,
                knownStructure = ksvc
            )
            seq = RtpUtils.applySequenceNumberDelta(seq, 1)
            frameNumber = RtpUtils.applySequenceNumberDelta(frameNumber, 1)
            context.accept(p, target)
        }
        endPicture()
        return result
    }

    /** Send one single-packet frame of the L1T3 structure. */
    private fun sendL1t3Frame(context: Av1DDAdaptiveSourceProjectionContext, template: Int, target: Int): Boolean {
        val p = packet(
            l1t3,
            template,
            startOfFrame = true,
            endOfFrame = true,
            marker = true,
            attachStructure = template == 0,
            knownStructure = l1t3
        )
        seq = RtpUtils.applySequenceNumberDelta(seq, 1)
        frameNumber = RtpUtils.applySequenceNumberDelta(frameNumber, 1)
        endPicture()
        return context.accept(p, target)
    }

    private fun endPicture() {
        ts = RtpUtils.applyTimestampDelta(ts, 3000)
        pictureCount++
    }

    private fun newContext() = Av1DDAdaptiveSourceProjectionContext(
        DiagnosticContext(),
        RtpState(SSRC, 10000, 1000000),
        null,
        logger
    )

    /**
     * Forward the top decode target of L3T3_KEY, then change to L1T3 with a reordered keyframe.
     * @param newTarget the target index that the allocation gives after the structure change.
     */
    private fun runStructureShrink(newTarget: Int) {
        val context = newContext()
        val oldTarget = getIndex(0, 8)

        // L3T3_KEY: a keyframe picture, then delta pictures, forwarded at DT 8.
        Assert.assertEquals(listOf(true, true, true), sendKsvcPicture(context, listOf(0, 5, 10), true, oldTarget))
        repeat(12) {
            val templates = listOf(listOf(1, 6, 11), listOf(3, 8, 13), listOf(2, 7, 12), listOf(4, 9, 14))[it % 4]
            // K-SVC: at DT 8 (S2T2) only the S2 frames depend on nothing but S2.
            Assert.assertEquals(listOf(false, false, true), sendKsvcPicture(context, templates, false, oldTarget))
        }

        // The sender changes to L1T3 and sends a keyframe of two packets. The second packet arrives first.
        // The bridge does not know the new structure yet, so this packet has no frame info.
        val keyframeSeq = seq
        val keyframeNumber = frameNumber
        val second = packet(
            l1t3,
            0,
            startOfFrame = false,
            endOfFrame = true,
            marker = true,
            attachStructure = false,
            knownStructure = ksvc,
            seqNum = RtpUtils.applySequenceNumberDelta(keyframeSeq, 1)
        )
        Assert.assertNull(second.packetAs<Av1DDPacket>().frameInfo)
        Assert.assertFalse(context.accept(second, oldTarget))
        // The first packet, with the new structure, arrives late (reordered or retransmitted).
        val first = packet(
            l1t3, 0, startOfFrame = true, endOfFrame = false, marker = false,
            attachStructure = true, knownStructure = ksvc, seqNum = keyframeSeq, frameNum = keyframeNumber
        )
        Assert.assertTrue(first.packetAs<Av1DDPacket>().isKeyframe)
        // The frame is not new, so the quality filter does not see this keyframe.
        Assert.assertFalse(context.accept(first, newTarget))
        seq = RtpUtils.applySequenceNumberDelta(keyframeSeq, 2)
        frameNumber = RtpUtils.applySequenceNumberDelta(frameNumber, 1)
        endPicture()

        // Delta frames of the new structure. The deployed code throws IndexOutOfBoundsException here
        // (Av1DDQualityFilter.doAcceptFrame: frameInfo.dti[currentDt], with currentDt 8 and 3 DTIs).
        repeat(8) {
            Assert.assertFalse(sendL1t3Frame(context, listOf(1, 3, 2, 4)[it % 4], newTarget))
        }
        // Nothing is forwarded, so the context must ask for a keyframe.
        Assert.assertTrue("A keyframe must be requested", context.needsKeyframe())

        // The sender answers with a keyframe, in order this time. Forwarding resumes.
        Assert.assertTrue(sendL1t3Frame(context, 0, newTarget))
        Assert.assertTrue(sendL1t3Frame(context, 1, newTarget))
    }

    @Test
    fun structureShrinkWithReorderedKeyframe() {
        // The allocation follows the structure change: the late structure packet sets layeringChanged, and
        // BitrateController updates the allocation before the packet reaches this context.
        runStructureShrink(newTarget = getIndex(0, 2))
    }

    companion object {
        private const val SSRC = 0xcafebabeL
        private const val EXT_ID = 11
        private val baseTime: Instant = Instant.ofEpochMilli(1577836800000L)

        private fun parseStructure(hex: String, offset: Int): Av1TemplateDependencyStructure {
            val dd = parseHexBinary(hex)
            return Av1DependencyDescriptorHeaderExtension.parse(dd, 0, dd.size, null).structure
                .withTemplateIdOffset(offset)
        }

        /** L3T3_KEY, 9 decode targets, 15 templates, at template ID offset 0. */
        private val ksvc = parseStructure(
            "8f008581e81485214eaaaaa8000600004000100002aa80a8000600004000100002a000a80006000040" +
                "0016d549241b5524906d54923157e001974ca864330e222396eca8655304224390eca87753013f00b3027f016704ff02cf",
            0
        )

        /** L1T3, 3 decode targets, 5 templates, at template ID offset 15 (after the previous structure). */
        private val l1t3 = parseStructure("800001800214eaa860414d141020842701df010d", 15)

        private val packetTemplate = parseHexBinary(
            "80" + "29" + "0000" + "00000000" + "cafebabe" + "0000000000000000000000"
        )
    }
}
