/*
 * Copyright @ 2020 - present 8x8, Inc.
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
package org.jitsi.nlj.node

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jitsi.config.useNewConfig
import org.jitsi.nlj.rtp.audioPacketBytes
import org.jitsi.nlj.transform.node.PacketLossConfig
import org.jitsi.nlj.transform.node.PacketLossNode
import org.jitsi.rtp.rtp.RtpPacket

class PacketLossTest : ShouldSpec() {

    private inline fun withNewConfig(config: String, block: () -> Unit) {
        useNewConfig("new-${this::class.simpleName}", config, true, block)
    }

    init {
        context("Burst loss") {
            withNewConfig("""
                prefix.uniform-rate=0
                prefix.burst-size=3
                prefix.burst-interval=100"""
            ) {
                val config = PacketLossConfig("prefix")

                config.uniformRate shouldBe 0
                config.burstSize shouldBe 3
                config.burstInterval shouldBe 100

                val node = PacketLossNode(config)

                // The first interval starts at 100
                node.processPackets(List(99) { rtpPacket }) shouldHaveSize 99
                node.processPackets(List(3) { rtpPacket }) shouldHaveSize 0
                node.processPackets(List(97) { rtpPacket }) shouldHaveSize 97
                node.processPackets(List(3) { rtpPacket }) shouldHaveSize 0
            }
        }
    }
}

private val rtpPacket = RtpPacket(audioPacketBytes, 0, audioPacketBytes.size)
