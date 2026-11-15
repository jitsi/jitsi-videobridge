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
package org.jitsi.videobridge.util

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jitsi.utils.MediaType
import org.jitsi.xmpp.extensions.jingle.ParameterPacketExtension
import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension
import org.jitsi.xmpp.extensions.jingle.RtcpFbPacketExtension

class PayloadTypeUtilTest : ShouldSpec() {
    /** A payload type as signaled, with the given "type subtype" rtcp-fb entries. */
    private fun signaled(name: String, id: Int, vararg rtcpFb: String) = PayloadTypePacketExtension().apply {
        this.name = name
        setId(id)
        clockrate = 90000
        channels = 1
        if (name == "rtx") {
            addParameter(ParameterPacketExtension("apt", "100"))
        }
        rtcpFb.forEach {
            addRtcpFeedbackType(
                RtcpFbPacketExtension().apply {
                    feedbackType = it.substringBefore(' ')
                    if (it.contains(' ')) {
                        feedbackSubtype = it.substringAfter(' ')
                    }
                }
            )
        }
    }

    private fun feedbackOf(name: String, mediaType: MediaType, vararg rtcpFb: String): Set<String> =
        PayloadTypeUtil.create(signaled(name, 100, *rtcpFb), mediaType).shouldNotBeNull().rtcpFeedbackSet.toSet()

    init {
        context("RFC 8888 feedback") {
            should("be recorded for every kind of payload type it is signaled for") {
                val all = arrayOf("ack ccfb", "nack", "nack pli", "ccm fir", "transport-cc")

                feedbackOf("VP8", MediaType.VIDEO, *all) shouldBe all.toSet()
                feedbackOf("red", MediaType.VIDEO, *all) shouldBe all.toSet()
                // These keep only this kind of feedback, as before they kept none.
                feedbackOf("rtx", MediaType.VIDEO, *all) shouldBe setOf("ack ccfb")
                feedbackOf("opus", MediaType.AUDIO, *all) shouldBe setOf("ack ccfb")
                feedbackOf("red", MediaType.AUDIO, *all) shouldBe setOf("ack ccfb")
                feedbackOf("telephone-event", MediaType.AUDIO, *all) shouldBe setOf("ack ccfb")
                feedbackOf("ulpfec", MediaType.VIDEO, *all) shouldBe setOf("ack ccfb")
                feedbackOf("CN", MediaType.AUDIO, *all) shouldBe setOf("ack ccfb")
            }
            should("not be recorded when it is not signaled") {
                feedbackOf("VP8", MediaType.VIDEO, "nack", "transport-cc") shouldBe setOf("nack", "transport-cc")
                feedbackOf("rtx", MediaType.VIDEO, "nack", "transport-cc") shouldBe emptySet()
                feedbackOf("opus", MediaType.AUDIO, "transport-cc") shouldBe emptySet()
            }
        }
    }
}
