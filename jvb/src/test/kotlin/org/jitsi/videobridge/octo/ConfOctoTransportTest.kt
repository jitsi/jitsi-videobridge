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

package org.jitsi.videobridge.octo

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.transport.octo.BridgeOctoTransport
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.SSRCInfoPacketExtension
import org.jxmpp.jid.impl.JidCreate
import kotlin.random.Random
import kotlin.system.measureTimeMillis

class ConfOctoTransportTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf
    private val bridgeOctoTransportMock: BridgeOctoTransport = mockk {
        every { relayId } returns "relayId"
    }
    private val octoRelayService: OctoRelayService = mockk {
        every { bridgeOctoTransport } returns bridgeOctoTransportMock
    }
    init {
        mockkStatic("org.jitsi.videobridge.octo.OctoRelayServiceProviderKt")
        every { singleton() } returns mockk {
            every { get() } returns octoRelayService
        }
    }

    private val conference: Conference = mockk(relaxed = true) {
        every { gid } returns 123L
        every { logger } returns LoggerImpl("test")
        every { tentacle } returns mockk(relaxed = true)
        every { getEndpoint(any()) } returns mockk<OctoEndpoint>(relaxed = true)
    }
    private val confOctoTransport = ConfOctoTransport(conference)

    init {
        context("setSources") {
            val audioSources = mutableListOf<SourcePacketExtension>()
            val videoSources = mutableListOf<SourcePacketExtension>()
            repeat(500) {
                val epId = generateEpId()
                audioSources += generateSourcePacketExtension(epId)
                videoSources += generateSourcePacketExtension(epId)
            }

            val time = measureTimeMillis {
                confOctoTransport.setSources(
                    audioSources,
                    videoSources,
                    emptyList()
                )
            }
            println("Took $time ms")
        }
    }
}

private val charPool: List<Char> = ('a'..'z') + ('0'..'9')
private fun generateEpId(): String {
    return (1..12)
        .map { _ -> Random.nextInt(0, charPool.size) }
        .map(charPool::get)
        .joinToString("")
}

private fun generateSourcePacketExtension(epId: String, numSources: Int = 1): SourcePacketExtension {
    return SourcePacketExtension().apply {
        repeat(numSources) {
            val ssrc = Random.nextLong()
            this.ssrc = ssrc
            addChildExtension(
                SSRCInfoPacketExtension().apply {
                    owner = JidCreate.from("confname@confdomain.net/$epId")
                }
            )
        }
    }
}

fun main() {
    repeat(10) {
        val bridgeOctoTransportMock: BridgeOctoTransport = mockk {
            every { relayId } returns "relayId"
        }
        val octoRelayService: OctoRelayService = mockk {
            every { bridgeOctoTransport } returns bridgeOctoTransportMock
        }

        mockkStatic("org.jitsi.videobridge.octo.OctoRelayServiceProviderKt")
        every { singleton() } returns mockk {
            every { get() } returns octoRelayService
        }

        val conference: Conference = mockk(relaxed = true) {
            every { gid } returns 123L
            every { logger } returns LoggerImpl("test")
            every { tentacle } returns mockk(relaxed = true)
            every { getEndpoint(any()) } returns mockk<OctoEndpoint>(relaxed = true)
        }
        val confOctoTransport = ConfOctoTransport(conference)

        val audioSources = mutableListOf<SourcePacketExtension>()
        val videoSources = mutableListOf<SourcePacketExtension>()
        repeat(500) {
            val epId = generateEpId()
            audioSources += generateSourcePacketExtension(epId)
            videoSources += generateSourcePacketExtension(epId)
        }

        val time = measureTimeMillis {
            confOctoTransport.setSources(
                audioSources,
                videoSources,
                emptyList()
            )
        }
        println("Took $time ms")
    }
}
