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

package org.jitsi.nlj.module_tests

import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import org.jitsi.nlj.RtpReceiver
import org.jitsi.nlj.RtpReceiverImpl
import org.jitsi.nlj.TransceiverEventHandler
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.util.LocalSsrcAssociation
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.test_utils.SourceAssociation
import org.jitsi.test_utils.SrtpData
import org.jitsi.utils.logging2.Logger

class ReceiverFactory {
    companion object {
        fun createReceiver(
            executor: ExecutorService,
            backgroundExecutor: ScheduledExecutorService,
            srtpData: SrtpData,
            payloadTypes: List<PayloadType>,
            headerExtensions: List<RtpExtension>,
            ssrcAssociations: List<SourceAssociation>,
            rtcpSender: (RtcpPacket) -> Unit = {},
            logger: Logger = StdoutLogger()
        ): RtpReceiver {
            val streamInformationStore = StreamInformationStoreImpl()
            val receiver = RtpReceiverImpl(
                id = Random().nextLong().toString(),
                rtcpSender = rtcpSender,
                rtcpEventNotifier = RtcpEventNotifier(),
                executor = executor,
                backgroundExecutor = backgroundExecutor,
                streamInformationStore = streamInformationStore,
                eventHandler = object : TransceiverEventHandler {},
                parentLogger = logger
            )
            receiver.setSrtpTransformers(SrtpTransformerFactory.createSrtpTransformers(srtpData))

            payloadTypes.forEach {
                streamInformationStore.addRtpPayloadType(it)
            }
            headerExtensions.forEach {
                streamInformationStore.addRtpExtensionMapping(it)
            }
            ssrcAssociations.forEach {
                streamInformationStore.addSsrcAssociation(
                    LocalSsrcAssociation(it.primarySsrc, it.secondarySsrc, it.associationType)
                )
            }

            return receiver
        }
    }
}
