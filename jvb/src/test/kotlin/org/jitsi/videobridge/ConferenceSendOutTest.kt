/*
 * Copyright @ 2026 - Present, 8x8, Inc.
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
package org.jitsi.videobridge

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jitsi.ConfigTest
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jxmpp.jid.impl.JidCreate

/**
 * Tests for the way [Conference.handleIncomingPacket] distributes a packet to the endpoints that want it.
 */
class ConferenceSendOutTest : ConfigTest() {
    private val videobridge = mockk<Videobridge>(relaxed = true)

    init {
        val name = JidCreate.entityBareFrom("roomName@somedomain.com")

        context("When one endpoint's wants() throws") {
            should("still send the packet to the other endpoints that want it") {
                with(Conference(videobridge, "id", name, null, false)) {
                    try {
                        val conference = this
                        fun endpoint(id: String, wants: () -> Boolean) = mockk<Endpoint>(relaxed = true) {
                            every { this@mockk.id } returns id
                            every { this@mockk.conference } returns conference
                            every { wants(any()) } answers { wants() }
                        }
                        // The order in which the conference iterates over its endpoints is not defined. In any
                        // order, a failure that stops the loop makes at least one healthy endpoint lose the packet:
                        // either the one before it, which waits to be sent to, or the ones after it.
                        val healthy = (1..3).map { endpoint("healthy-$it") { true } }
                        val failing = endpoint("failing") { throw IndexOutOfBoundsException("Index 8 out of bounds") }
                        addEndpoints((healthy + failing).toSet())

                        handleIncomingPacket(PacketInfo(VideoRtpPacket(ByteArray(100), 0, 100)))

                        healthy.forEach { verify(exactly = 1) { it.send(any()) } }
                        verify(exactly = 0) { failing.send(any()) }
                    } finally {
                        expire()
                    }
                }
            }
        }
    }
}
