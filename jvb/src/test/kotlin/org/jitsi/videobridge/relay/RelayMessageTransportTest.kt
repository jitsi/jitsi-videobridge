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
package org.jitsi.videobridge.relay

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.datachannel.DataChannel
import org.jitsi.videobridge.message.ConnectionStats
import org.jitsi.videobridge.message.ForwardedSourcesMessage

class RelayMessageTransportTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        context("Messages that the data channel does not accept") {
            val transport = RelayMessageTransport(mockk(relaxed = true), mockk(relaxed = true), createLogger())
            // The transport keeps only a weak reference to the data channel.
            val dataChannel = mockk<DataChannel>(relaxed = true) {
                every { isReady } returns true
                every { sendString(any()) } returns true
            }
            transport.setDataChannel(dataChannel)

            should("not be reported when the data channel accepts every message") {
                transport.sendMessage(ConnectionStats(1000))
                transport.droppedMessagesSummary() shouldBe null
                transport.debugState["sent_counts"]["ConnectionStats"].asLong() shouldBe 1
            }
            should("be counted by type, and not as sent") {
                transport.sendMessage(ConnectionStats(1000))
                every { dataChannel.sendString(any()) } returns false
                repeat(3) { transport.sendMessage(ConnectionStats(1000)) }
                transport.sendMessage(ForwardedSourcesMessage(emptyList()))

                transport.droppedMessagesSummary() shouldBe
                    "The data channel did not accept 4 messages, by type: {ConnectionStats=3, ForwardedSourcesMessage=1}"
                val debugState = transport.debugState
                debugState["data_channel_dropped_counts"]["ConnectionStats"].asLong() shouldBe 3
                debugState["data_channel_dropped_counts"]["ForwardedSourcesMessage"].asLong() shouldBe 1
                debugState["sent_counts"]["ConnectionStats"].asLong() shouldBe 1
                debugState["sent_counts"].has("ForwardedSourcesMessage") shouldBe false
            }
        }
    }
}
