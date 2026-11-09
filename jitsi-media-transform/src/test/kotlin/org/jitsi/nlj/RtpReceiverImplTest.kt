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

package org.jitsi.nlj

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.jitsi.config.withNewConfig
import org.jitsi.nlj.format.OpusPayloadType
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.resources.srtp_samples.SrtpSample
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.test_utils.matchers.ByteArrayBuffer.haveSameContentAs
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.utils.MediaType
import java.util.concurrent.Executors

/**
 * Tests the receive pipeline end to end: an encrypted RTP packet goes in, and the packet handler at the end of the
 * pipeline gets the decrypted packet, or does not get it when the pipeline drops it.
 */
class RtpReceiverImplTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val executor = Executors.newSingleThreadExecutor()
    private val backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
    private val streamInformationStore = StreamInformationStoreImpl().apply {
        addRtpPayloadType(OpusPayloadType(SrtpSample.rtpPayloadType))
    }
    private val forwarded = mutableListOf<PacketInfo>()
    private var receiver: RtpReceiverImpl? = null

    private fun createReceiver() = RtpReceiverImpl(
        id = "test",
        rtcpEventNotifier = RtcpEventNotifier(),
        executor = executor,
        backgroundExecutor = backgroundExecutor,
        streamInformationStore = streamInformationStore,
        eventHandler = object : TransceiverEventHandler {},
        parentLogger = StdoutLogger()
    ).apply {
        setSrtpTransformers(
            SrtpUtil.initializeTransformer(
                SrtpSample.srtpProfileInformation,
                SrtpSample.keyingMaterial.array(),
                SrtpSample.tlsRole,
                cryptex = false,
                StdoutLogger()
            )
        )
        packetHandler = object : PacketHandler {
            override fun processPacket(packetInfo: PacketInfo) {
                forwarded.add(packetInfo)
            }
        }
        receiver = this
    }

    /**
     * Runs the sample packet through the pipeline synchronously ([RtpReceiverImpl.processPacket] rather than
     * [RtpReceiverImpl.enqueuePacket]), so the assertions that follow do not race the receiver's queue thread.
     */
    private fun RtpReceiverImpl.receive() = processPacket(PacketInfo(SrtpSample.incomingEncryptedRtpPacket.clone()))

    override suspend fun afterSpec(spec: io.kotest.core.spec.Spec) {
        receiver?.stop()
        executor.shutdownNow()
        backgroundExecutor.shutdownNow()
    }

    init {
        context("An RTP packet with a signaled SSRC") {
            streamInformationStore.addReceiveSsrc(SrtpSample.rtpSsrc, MediaType.AUDIO)
            val receiver = createReceiver()
            receiver.receive()

            should("be decrypted and forwarded") {
                forwarded.size shouldBe 1
                forwarded[0].packet should haveSameContentAs(SrtpSample.expectedDecryptedRtpPacket)
            }
        }

        context("An RTP packet with an SSRC that was not signaled") {
            context("with the default config") {
                val receiver = createReceiver()
                receiver.receive()

                should("be dropped") {
                    forwarded.size shouldBe 0
                }
            }

            context("with the filter disabled") {
                withNewConfig("jmt.transceiver.recv.drop-unsignaled-ssrcs = false") {
                    val receiver = createReceiver()
                    receiver.receive()

                    should("be forwarded") {
                        forwarded.size shouldBe 1
                    }
                }
            }
        }
    }
}
