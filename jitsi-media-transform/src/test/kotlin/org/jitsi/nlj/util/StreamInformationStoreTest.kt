/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.nlj.util

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.format.OpusPayloadType
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.format.Vp8PayloadType

class StreamInformationStoreTest : ShouldSpec() {
    private fun opus(vararg rtcpFb: String) = OpusPayloadType(111, rtcpFeedbackSet = rtcpFb.toSet())
    private fun vp8(vararg rtcpFb: String) = Vp8PayloadType(100, emptyMap(), rtcpFb.toSet())
    private fun rtx(vararg rtcpFb: String) = RtxPayloadType(96, mapOf("apt" to "100"), rtcpFb.toSet())

    init {
        context("RFC 8888 feedback") {
            should("be supported when it is signaled for every payload type") {
                val store = StreamInformationStoreImpl()
                store.supportsCcfb shouldBe false

                store.addRtpPayloadType(opus("ack ccfb"))
                store.addRtpPayloadType(vp8("ack ccfb", "nack", "nack pli", "ccm fir"))
                store.addRtpPayloadType(rtx("ack ccfb"))

                store.supportsCcfb shouldBe true
                store.supportsTcc shouldBe false
            }
            should("not be supported when it is signaled for only some payload types") {
                // Signaled for the video payload types, but not for audio.
                val store = StreamInformationStoreImpl()
                store.addRtpPayloadType(opus("transport-cc"))
                store.addRtpPayloadType(vp8("ack ccfb", "transport-cc"))
                store.addRtpPayloadType(rtx("ack ccfb"))

                store.supportsCcfb shouldBe false
                // Transport-cc feedback is then used, as it is signaled.
                store.supportsTcc shouldBe true
            }
            should("not be supported when it is not signaled") {
                val store = StreamInformationStoreImpl()
                store.addRtpPayloadType(opus("transport-cc"))
                store.addRtpPayloadType(vp8("transport-cc"))

                store.supportsCcfb shouldBe false
                store.supportsTcc shouldBe true
            }
            should("follow changes of the payload types") {
                val store = StreamInformationStoreImpl()
                var notified = false
                store.onRtpPayloadTypesChanged { notified = store.supportsCcfb }

                store.addRtpPayloadType(vp8("ack ccfb"))
                store.supportsCcfb shouldBe true
                // Handlers see the new value.
                notified shouldBe true

                store.clearRtpPayloadTypes()
                store.supportsCcfb shouldBe false
                notified shouldBe false

                store.addRtpPayloadType(vp8("transport-cc"))
                store.supportsCcfb shouldBe false
            }
        }
    }
}
