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

package org.jitsi.nlj.transform.node.incoming

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jitsi.config.withNewConfig
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.resources.node.onOutput
import org.jitsi.nlj.rtp.audioPacketBytes
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.LoggerImpl
import java.util.function.Supplier
import java.util.logging.Level

class ReceiveSsrcFilterTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val streamInformationStore = StreamInformationStoreImpl()

    // Debug level, so the test runs the debug log path for a repeated SSRC too.
    private val filter = ReceiveSsrcFilter(streamInformationStore, StdoutLogger("test", Level.ALL))
    private val forwarded = mutableListOf<PacketInfo>()

    init {
        filter.onOutput { forwarded.add(it) }

        context("A packet with an SSRC that was signaled") {
            streamInformationStore.addReceiveSsrc(SSRC_A, MediaType.AUDIO)
            filter.processPacket(packetInfo(SSRC_A))

            should("be forwarded") {
                forwarded.size shouldBe 1
                filter.numPacketsDropped() shouldBe 0
            }
        }

        context("A packet with an SSRC that was not signaled") {
            context("when the endpoint signaled no SSRC at all") {
                filter.processPacket(packetInfo(SSRC_A))

                should("be dropped") {
                    forwarded.size shouldBe 0
                    filter.numPacketsDropped() shouldBe 1
                    filter.statsJson()["num_packets_dropped_unsignaled_ssrc"].asInt() shouldBe 1
                }
            }
            context("when the endpoint signaled other SSRCs") {
                streamInformationStore.addReceiveSsrc(SSRC_A, MediaType.AUDIO)
                filter.processPacket(packetInfo(SSRC_B))
                filter.processPacket(packetInfo(SSRC_B))
                filter.processPacket(packetInfo(SSRC_A))

                should("be dropped, while the packets with a signaled SSRC are forwarded") {
                    forwarded.map { it.packetAs<RtpPacket>().ssrc } shouldBe listOf(SSRC_A)
                    filter.numPacketsDropped() shouldBe 2
                }
            }
            context("when the SSRC is signaled later") {
                filter.processPacket(packetInfo(SSRC_A))
                streamInformationStore.addReceiveSsrc(SSRC_A, MediaType.AUDIO)
                filter.processPacket(packetInfo(SSRC_A))

                should("be dropped before, and forwarded after, the SSRC is signaled") {
                    forwarded.size shouldBe 1
                    filter.numPacketsDropped() shouldBe 1
                }
            }
        }

        context("The log") {
            should("warn once for each SSRC, and not for each packet") {
                val recordingLogger = WarningRecordingLogger()
                val filter = ReceiveSsrcFilter(streamInformationStore, recordingLogger)
                filter.processPacket(packetInfo(SSRC_A))
                filter.processPacket(packetInfo(SSRC_A))
                filter.processPacket(packetInfo(SSRC_B))
                filter.processPacket(packetInfo(SSRC_A))

                recordingLogger.warnings.size shouldBe 2
                recordingLogger.warnings[0] shouldContain "SSRC $SSRC_A"
                recordingLogger.warnings[1] shouldContain "SSRC $SSRC_B"
            }
        }

        context("The config") {
            should("enable the filter by default") {
                ReceiveSsrcFilter.enabled shouldBe true
            }
            should("allow the filter to be disabled") {
                withNewConfig("jmt.transceiver.recv.drop-unsignaled-ssrcs = false") {
                    ReceiveSsrcFilter.enabled shouldBe false
                }
            }
        }
    }

    private fun ReceiveSsrcFilter.numPacketsDropped() = getNodeStats().getNumber("num_packets_dropped_unsignaled_ssrc")

    companion object {
        private const val SSRC_A = 1234L
        private const val SSRC_B = 5678L

        private fun packetInfo(ssrc: Long) = PacketInfo(RtpPacket(audioPacketBytes).apply { this.ssrc = ssrc })
    }
}

/** Records the warnings. The filter logs through a child logger, so the child is this logger too. */
private class WarningRecordingLogger : LoggerImpl("ReceiveSsrcFilterTest") {
    val warnings = mutableListOf<String>()

    override fun createChildLogger(name: String) = this
    override fun createChildLogger(name: String, context: MutableMap<String, String>) = this

    override fun warn(msg: Any?) {
        warnings.add("$msg")
    }

    override fun warn(msg: Supplier<String>) {
        warnings.add(msg.get())
    }

    override fun warn(msg: Any?, t: Throwable?) {
        warnings.add("$msg: $t")
    }
}
