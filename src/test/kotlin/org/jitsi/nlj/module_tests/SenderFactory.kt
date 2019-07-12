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

import org.jitsi.nlj.RtpSender
import org.jitsi.nlj.RtpSenderImpl
import org.jitsi.nlj.SetLocalSsrcEvent
import org.jitsi.nlj.SsrcAssociationEvent
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.test_utils.SourceAssociation
import org.jitsi.test_utils.SrtpData
import org.jitsi.utils.MediaType
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

class SenderFactory {
    companion object {
        fun createSender(
            executor: ExecutorService,
            backgroundExecutor: ScheduledExecutorService,
            srtpData: SrtpData,
            payloadTypes: List<PayloadType>,
            headerExtensions: List<RtpExtension>,
            ssrcAssociations: List<SourceAssociation>
        ): RtpSender {
            val streamInformationStore = StreamInformationStoreImpl()
            val sender = RtpSenderImpl(
                Random().nextLong().toString(),
                null,
                RtcpEventNotifier(),
                executor,
                backgroundExecutor,
                streamInformationStore
            )
            sender.setSrtpTransformers(SrtpTransformerFactory.createSrtpTransformers(srtpData))

            payloadTypes.forEach {
                streamInformationStore.addRtpPayloadType(it)
            }
            headerExtensions.forEach {
                streamInformationStore.addRtpExtensionMapping(it)
            }
            ssrcAssociations.forEach {
                sender.handleEvent(SsrcAssociationEvent(it.primarySsrc, it.secondarySsrc, it.associationType))
            }

            // Set some dummy sender SSRCs so RTCP can be forwarded
            sender.handleEvent(SetLocalSsrcEvent(MediaType.VIDEO, 123))
            sender.handleEvent(SetLocalSsrcEvent(MediaType.AUDIO, 456))

            return sender
        }
    }
}