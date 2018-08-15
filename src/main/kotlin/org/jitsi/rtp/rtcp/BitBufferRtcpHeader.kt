/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.rtp.rtcp

import java.nio.ByteBuffer

//internal class BitBufferRtcpHeader : RtcpHeader {
//    private val buf: ByteBuffer
//    override var version: Int
//        get() = RtcpHeaderUtils.getVersion(buf)
//        set(version) = RtcpHeaderUtils.setVersion(buf, version)
//
//    override var hasPadding: Boolean
//        get() = RtcpHeaderUtils.hasPadding(buf)
//        set(hasPadding) = RtcpHeaderUtils.setPadding(buf, hasPadding)
//
//    override var reportCount: Int
//        get() = RtcpHeaderUtils.getReportCount(buf)
//        set(reportCount) = RtcpHeaderUtils.setReportCount(buf, reportCount)
//
//    override var payloadType: Int
//        get() = RtcpHeaderUtils.getPayloadType(buf)
//        set(payloadType) = RtcpHeaderUtils.setPayloadType(buf, payloadType)
//
//    override var length: Int
//        get() = RtcpHeaderUtils.getLength(buf)
//        set(length) = RtcpHeaderUtils.setLength(buf, length)
//
//    override var senderSsrc: Long
//        get() = RtcpHeaderUtils.getSenderSsrc(buf)
//        set(senderSsrc) = RtcpHeaderUtils.setSenderSsrc(buf, senderSsrc)
//
//    constructor(buf: ByteBuffer) : super() {
//        this.buf = buf
//    }
//
//    constructor(
//        version: Int = 2,
//        hasPadding: Boolean = false,
//        reportCount: Int = 0,
//        payloadType: Int = 0,
//        length: Int = 0,
//        senderSsrc: Long = 0
//    ) : super() {
//        this.buf = ByteBuffer.allocate(8)
//        this.version = version
//        this.hasPadding = hasPadding
//        this.reportCount = reportCount
//        this.payloadType = payloadType
//        this.length = length
//        this.senderSsrc = senderSsrc
//    }
//
//    // These are deprecated
//    companion object Create {
//        fun fromBuffer(buf: ByteBuffer): RtcpHeader = BitBufferRtcpHeader(buf)
//        fun fromValues(receiver: BitBufferRtcpHeader.() -> Unit): BitBufferRtcpHeader = BitBufferRtcpHeader().apply(receiver)
//    }
//}
