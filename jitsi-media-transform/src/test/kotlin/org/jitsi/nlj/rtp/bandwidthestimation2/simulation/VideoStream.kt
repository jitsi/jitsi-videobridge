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
// This file uses WebRTC's naming style for enums
@file:Suppress("ktlint:standard:enum-entry-name-case")

package org.jitsi.nlj.rtp.bandwidthestimation2.simulation

import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType

/** Test scenario video streams,
 * based on WebRTC test/scenario/video_stream.{h,cc} in
 * WebRTC tag branch-heads/6422 (Chromium 125).
 *
 * Only those features used by GoogCcNetworkControllerTest are implemented.
 */

class SendVideoStream(
    val sender: CallClient,
    val config: VideoStreamConfig,
    val sendTransport: NetworkNodeTransport,
    val matcher: Any
) {
    fun start() {
        TODO()
    }
}

class ReceiveVideoStream(
    val receiver: CallClient,
    val config: VideoStreamConfig,
    sendStream: SendVideoStream,
    chosenStream: Long,
    feedbackTransport: NetworkNodeTransport,
    matcher: Any
) {
    fun start() {
        TODO()
    }
}

class VideoStreamPair(
    sender: CallClient,
    receiver: CallClient,
    val config: VideoStreamConfig
) {
    val matcher = Any() // TODO
    val send = SendVideoStream(sender, config, sender.transport, matcher)
    val receive = ReceiveVideoStream(receiver, config, send, 0, receiver.transport, matcher)
}

private enum class ID {
    kTransportSequenceNumberExtensionId,
    kAbsSendTimeExtensionId,
    kVideoContentTypeExtensionId,
    kVideoRotationRtpExtensionId;

    val id
        get() = ordinal.toByte()
}

fun getVideoRtpExtensions(config: VideoStreamConfig): List<RtpExtension> {
    val res = mutableListOf(
        RtpExtension(ID.kVideoContentTypeExtensionId.id, RtpExtensionType.VIDEO_CONTENT_TYPE),
        RtpExtension(ID.kVideoRotationRtpExtensionId.id, RtpExtensionType.VIDEO_ORIENTATION)
    )
    if (config.stream.packetFeedback) {
        res.add(RtpExtension(ID.kTransportSequenceNumberExtensionId.id, RtpExtensionType.TRANSPORT_CC))
    }
    if (config.stream.absSendTime) {
        res.add(RtpExtension(ID.kAbsSendTimeExtensionId.id, RtpExtensionType.ABS_SEND_TIME))
    }
    return res
}
