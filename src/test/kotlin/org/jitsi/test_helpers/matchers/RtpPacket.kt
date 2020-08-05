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
package org.jitsi.test_helpers.matchers

import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.ByteArrayBuffer

fun RtpPacket.getPayload(): ByteArrayBuffer {
    return UnparsedPacket(buffer, payloadOffset, payloadLength)
}

fun RtpPacket.getFixedHeaderAsBAB(): ByteArrayBuffer {
    return UnparsedPacket(buffer, offset, RtpHeader.FIXED_HEADER_SIZE_BYTES)
}

fun haveSamePayload(expected: RtpPacket) = object : Matcher<RtpPacket> {
    override fun test(value: RtpPacket): MatcherResult {
        val valuePayload = value.getPayload()
        val expectedPayload = expected.getPayload()

        return MatcherResult(
            valuePayload.hasSameContentAs(expectedPayload),
            "\n${valuePayload.toHex()}\nwas supposed to be:\n${expectedPayload.toHex()}",
            "\n${valuePayload.toHex()}\nshould not have equaled \n${expectedPayload.toHex()}"
        )
    }
}

fun haveSameFixedHeader(expected: RtpPacket) = object : Matcher<RtpPacket> {
    override fun test(value: RtpPacket): MatcherResult {
        val valueHeader = value.getFixedHeaderAsBAB()
        val expectedHeader = expected.getFixedHeaderAsBAB()

        return MatcherResult(
            valueHeader.hasSameContentAs(expectedHeader),
            "\n${valueHeader.toHex()}\nwas supposed to be:\n${expectedHeader.toHex()}",

            "\n${valueHeader.toHex()}\nshould not have equaled \n${expectedHeader.toHex()}"
        )
    }
}
