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

package org.jitsi.nlj.transform.node.incoming

import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.every
import io.mockk.mockk
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.resources.node.onOutput
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.util.BufferPool
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpReportBlock
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.RtcpSrPacketBuilder
import org.jitsi.rtp.rtcp.SenderInfoBuilder
import org.jitsi.rtp.rtcp.SenderInfoParser
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacketBuilder

class RtcpTerminationTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val rtcpEventNotifier: RtcpEventNotifier = mockk()
    private val rtcpTermination = RtcpTermination(rtcpEventNotifier, StdoutLogger())

    val nackPacket = RtcpFbNackPacketBuilder().build()

    private var numRetrievedBuffers = 0
    private val returnedBuffers = mutableListOf<ByteArray>()
    private val outputPackets = mutableListOf<PacketInfo>()

    init {
        BufferPool.getBuffer = { size ->
            numRetrievedBuffers++
            ByteArray(size)
        }
        BufferPool.returnBuffer = { array ->
            returnedBuffers.add(array)
        }
        rtcpTermination.onOutput { packetInfo ->
            outputPackets.add(packetInfo)
        }
        // Force an access of RtcpSrPacket's report blocks, since it's lazy, to better
        // simulate real conditions
        every { rtcpEventNotifier.notifyRtcpReceived(any<RtcpPacket>(), any()) } answers {
            (args[0] as? RtcpSrPacket)?.reportBlocks
        }

        context("Receiving an SR packet") {
            val senderInfo = SenderInfoBuilder(
                ntpTimestampMsw = 0xDEADBEEF,
                ntpTimestampLsw = 0xBEEFDEAD,
                rtpTimestamp = 12345L,
                sendersPacketCount = 42,
                sendersOctetCount = 4242
            )
            context("without any receiver report blocks") {
                val srPacket = RtcpSrPacketBuilder(
                    RtcpHeaderBuilder(
                        senderSsrc = 12345L
                    ),
                    senderInfo
                ).build()
                val compoundRtcpPacket = createCompoundRtcp(srPacket)

                rtcpTermination.processPacket(PacketInfo(compoundRtcpPacket))

                should("be forwarded intact") {
                    outputPackets shouldHaveSize 1
                    with(outputPackets[0].packet as RtcpSrPacket) {
                        length shouldBe (RtcpHeader.SIZE_BYTES + SenderInfoParser.SIZE_BYTES)
                        reportCount shouldBe 0
                        reportBlocks.size shouldBe 0
                        returnedBuffers shouldHaveSize 0
                    }
                }
            }
            context("with a receiver report block") {
                val reportBlock = RtcpReportBlock(
                    ssrc = 12345,
                    fractionLost = 42,
                    cumulativePacketsLost = 4242,
                    seqNumCycles = 1,
                    seqNum = 42,
                    interarrivalJitter = 4242,
                    lastSrTimestamp = 23456,
                    delaySinceLastSr = 34567
                )

                val srPacket = RtcpSrPacketBuilder(
                    RtcpHeaderBuilder(
                        senderSsrc = 12345L
                    ),
                    senderInfo,
                    mutableListOf(reportBlock)
                ).build()

                val compoundRtcpPacket = createCompoundRtcp(srPacket)

                rtcpTermination.processPacket(PacketInfo(compoundRtcpPacket))
                should("remove all of the report blocks") {
                    outputPackets shouldHaveSize 1
                    with(outputPackets[0].packet as RtcpSrPacket) {
                        length shouldBe (RtcpHeader.SIZE_BYTES + SenderInfoParser.SIZE_BYTES)
                        reportCount shouldBe 0
                        reportBlocks.size shouldBe 0
                    }
                }
                should("return the original buffer to the pool") {
                    returnedBuffers shouldHaveSize 1
                    returnedBuffers[0] shouldBe compoundRtcpPacket.buffer
                }
            }
        }
    }

    // Create a compound RTCP packet with another arbitrary RTCP packet alongside the SR
    private fun createCompoundRtcp(srPacket: RtcpSrPacket): CompoundRtcpPacket {
        return CompoundRtcpPacket(
            nackPacket.buffer + srPacket.buffer,
            srPacket.offset,
            srPacket.length + nackPacket.length
        )
    }
}
