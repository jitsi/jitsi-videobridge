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
package org.jitsi.videobridge.xmpp

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import io.mockk.verifyOrder
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import org.jitsi.xmpp.mucclient.MucClient
import org.jitsi.xmpp.mucclient.MucClientConfiguration
import org.jitsi.xmpp.mucclient.MucClientManager
import org.jivesoftware.smack.packet.ExtensionElement

class XmppConnectionTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        beforeSpec { mockkConstructor(MucClientManager::class) }
        afterSpec { unmockkConstructor(MucClientManager::class) }

        context("Removing a MUC client") {
            val mucClient = mockk<MucClient>(relaxed = true)
            every { anyConstructed<MucClientManager>().getMucClient("shard1") } returns mucClient
            every { anyConstructed<MucClientManager>().removeMucClient("shard1") } returns true
            every { anyConstructed<MucClientManager>().getMucClient("unknown") } returns null
            every { anyConstructed<MucClientManager>().removeMucClient("unknown") } returns false
            val xmppConnection = XmppConnection()

            should("advertise shutting_down in its MUCs before it leaves them") {
                val ext = slot<ExtensionElement>()
                xmppConnection.removeMucClient(JsonNodeFactory.instance.objectNode().put("id", "shard1")) shouldBe true

                verifyOrder {
                    mucClient.setPresenceExtension(capture(ext))
                    anyConstructed<MucClientManager>().removeMucClient("shard1")
                }
                ext.captured.shouldBeInstanceOf<ColibriStatsExtension>()
                (ext.captured as ColibriStatsExtension).getValueAsString("shutting_down") shouldBe "true"
                // Only the removed client gets the presence, not all clients.
                verify(exactly = 0) { anyConstructed<MucClientManager>().setPresenceExtension(any()) }
            }
            should("remove the client even if the presence fails") {
                every { mucClient.setPresenceExtension(any()) } throws RuntimeException("test")
                xmppConnection.removeMucClient(JsonNodeFactory.instance.objectNode().put("id", "shard1")) shouldBe true
                verify(exactly = 1) { anyConstructed<MucClientManager>().removeMucClient("shard1") }
            }
            should("not advertise anything for an unknown client") {
                xmppConnection.removeMucClient(JsonNodeFactory.instance.objectNode().put("id", "unknown")) shouldBe
                    false
                verify(exactly = 0) { mucClient.setPresenceExtension(any()) }
            }
            should("advertise shutting_down before it replaces a client with a changed config") {
                every { mucClient.config } returns MucClientConfiguration("shard1").apply {
                    hostname = "old-host"
                    domain = "example.com"
                    username = "jvb"
                }
                every { anyConstructed<MucClientManager>().addMucClient(any()) } returns true
                val newConfig = JsonNodeFactory.instance.objectNode().apply {
                    put("id", "shard1")
                    put("hostname", "new-host")
                    put("domain", "example.com")
                    put("username", "jvb")
                    put("password", "pw")
                    put("muc_jids", "jvbbrewery@internal.example.com")
                    put("muc_nickname", "jvb1")
                }
                xmppConnection.addMucClient(newConfig) shouldBe true

                val ext = slot<ExtensionElement>()
                verifyOrder {
                    mucClient.setPresenceExtension(capture(ext))
                    anyConstructed<MucClientManager>().removeMucClient("shard1")
                    anyConstructed<MucClientManager>().addMucClient(any())
                }
                (ext.captured as ColibriStatsExtension).getValueAsString("shutting_down") shouldBe "true"
            }
        }
        context("Stopping") {
            every { anyConstructed<MucClientManager>().setPresenceExtension(any()) } returns Unit
            every { anyConstructed<MucClientManager>().stop() } returns Unit
            val xmppConnection = XmppConnection().apply { start() }

            should("advertise shutting_down in all MUCs before it leaves them") {
                val ext = slot<ExtensionElement>()
                xmppConnection.stop()
                verifyOrder {
                    anyConstructed<MucClientManager>().setPresenceExtension(capture(ext))
                    anyConstructed<MucClientManager>().stop()
                }
                (ext.captured as ColibriStatsExtension).getValueAsString("shutting_down") shouldBe "true"
            }
        }
    }
}
