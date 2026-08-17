/*
 * Copyright @ 2026 - Present, 8x8 Inc
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
package org.jitsi.videobridge.transport.ice

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.ice4j.ice.Agent
import org.ice4j.ice.Component
import org.ice4j.ice.IceMediaStream
import org.ice4j.ice.IceProcessingState
import org.ice4j.ice.KeepAliveStrategy
import org.jitsi.config.withNewConfig
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener

/**
 * Tests the ICE restart state machine of [IceTransport]: which Agent is described, which one gets the peer's
 * credentials, and which ones are freed. The ice4j [Agent]s are mocked, because real ones bind ports and can
 * only change state by actually running ICE.
 */
class IceTransportRestartTest : ShouldSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val agents = FakeAgents()
    fun createTransport() = IceTransport(
        id = "test",
        controlling = true,
        useUniquePort = false,
        advertisePrivateAddresses = false,
        parentLogger = LoggerImpl("test"),
        agentFactory = agents.factory
    )

    context("Before ICE is established") {
        val transport = createTransport()
        should("keep the existing Agent instead of restarting") {
            transport.requestIceRestart() shouldBe IceRestartResult.KEEP_EXISTING
            agents.created.size shouldBe 1
        }
        should("describe the existing Agent with no ice-generation") {
            transport.requestIceRestart()
            with(transport.describe()) {
                ufrag shouldBe agents.created[0].ufrag
                iceGeneration shouldBe IceUdpTransportPacketExtension.GENERATION_UNSPECIFIED
            }
        }
    }

    context("With ICE established") {
        val transport = createTransport()
        val initial = agents.created[0]
        initial.state = IceProcessingState.COMPLETED

        should("create a new Agent and describe it") {
            transport.requestIceRestart() shouldBe IceRestartResult.STARTED
            agents.created.size shouldBe 2
            with(transport.describe()) {
                ufrag shouldBe agents.created[1].ufrag
                password shouldBe agents.created[1].password
                iceGeneration shouldBe 1
            }
        }
        should("keep sending on the established Agent until the new one connects") {
            transport.requestIceRestart()
            transport.send(ByteArray(10), 0, 10)
            verify(exactly = 1) { initial.component.send(any(), any(), any()) }
            verify(exactly = 0) { agents.created[1].component.send(any(), any(), any()) }
        }
        should("not start connectivity checks before the peer's credentials arrive") {
            transport.requestIceRestart()
            agents.created[1].startCalls shouldBe 0
        }

        context("A repeated request") {
            should("re-describe the pending Agent while it has not started checks") {
                transport.requestIceRestart() shouldBe IceRestartResult.STARTED
                transport.requestIceRestart() shouldBe IceRestartResult.STARTED

                agents.created.size shouldBe 2
                agents.created[1].freed shouldBe false
                transport.describe().iceGeneration shouldBe 1
            }
            should("supersede a restart whose checks have started") {
                transport.requestIceRestart()
                transport.startConnectivityEstablishment(remoteTransport(generation = 1))
                agents.created[1].startCalls shouldBe 1

                transport.requestIceRestart() shouldBe IceRestartResult.STARTED
                agents.created.size shouldBe 3
                transport.describe().iceGeneration shouldBe 2
                awaitTrue { agents.created[1].freed }
            }
        }

        context("The peer's credentials") {
            should("be applied when they carry the pending generation") {
                transport.requestIceRestart()
                transport.startConnectivityEstablishment(remoteTransport(generation = 1))

                agents.created[1].remoteUfrag shouldBe "remote-ufrag"
                agents.created[1].remotePassword shouldBe "remote-pwd"
                agents.created[1].startCalls shouldBe 1
            }
            should("be ignored when they carry another generation") {
                transport.requestIceRestart()
                transport.startConnectivityEstablishment(remoteTransport(generation = 7))

                agents.created[1].remoteUfrag shouldBe null
                agents.created[1].startCalls shouldBe 0
            }
            should("not be taken from an untagged transport update") {
                // The peer stamps the generation on the restart answer and on nothing else, so an untagged
                // update is an ordinary one and carries the peer's old credentials.
                transport.requestIceRestart()
                transport.startConnectivityEstablishment(remoteTransport(generation = null))

                agents.created[1].remoteUfrag shouldBe null
                agents.created[1].startCalls shouldBe 0
            }
            should("not start the checks twice") {
                transport.requestIceRestart()
                transport.startConnectivityEstablishment(remoteTransport(generation = 1))
                transport.startConnectivityEstablishment(remoteTransport(generation = 1))

                agents.created[1].startCalls shouldBe 1
            }
            should("not be replaced once the checks are running") {
                // ice4j reads the remote credentials off the stream every time it signs a check or validates
                // one, so a repeated update must not touch them.
                transport.requestIceRestart()
                transport.startConnectivityEstablishment(remoteTransport(generation = 1))
                transport.startConnectivityEstablishment(
                    remoteTransport(generation = 1, remoteUfrag = "other-ufrag", remotePassword = "other-pwd")
                )

                agents.created[1].remoteUfrag shouldBe "remote-ufrag"
                agents.created[1].remotePassword shouldBe "remote-pwd"
            }
        }

        context("When the new Agent connects") {
            should("cut over to it") {
                transport.requestIceRestart()
                agents.created[1].fireState(IceProcessingState.COMPLETED)

                transport.send(ByteArray(10), 0, 10)
                verify(exactly = 1) { agents.created[1].component.send(any(), any(), any()) }
            }
            should("describe it without an ice-generation") {
                // The generation marks a transport to restart against. The established one is not that.
                transport.requestIceRestart()
                agents.created[1].fireState(IceProcessingState.COMPLETED)

                with(transport.describe()) {
                    ufrag shouldBe agents.created[1].ufrag
                    iceGeneration shouldBe IceUdpTransportPacketExtension.GENERATION_UNSPECIFIED
                }
            }
            should("free the old Agent only after the transition window") {
                transport.requestIceRestart()
                agents.created[1].fireState(IceProcessingState.COMPLETED)

                initial.freed shouldBe false
            }
        }

        context("When the new Agent fails") {
            should("keep the established one") {
                transport.requestIceRestart()
                agents.created[1].fireState(IceProcessingState.FAILED)

                transport.hasFailed() shouldBe false
                transport.describe().ufrag shouldBe initial.ufrag
                awaitTrue { agents.created[1].freed }
            }
            should("not restart the transport's own failure handling") {
                var failed = false
                transport.eventHandler = object : IceTransport.EventHandler {
                    override fun connected() {}
                    override fun failed() {
                        failed = true
                    }
                    override fun consentUpdated(time: java.time.Instant) {}
                    override fun writeable() {}
                }
                transport.requestIceRestart()
                agents.created[1].fireState(IceProcessingState.FAILED)

                failed shouldBe false
            }
        }

        context("stop()") {
            should("free the current and the pending Agent") {
                transport.requestIceRestart()
                transport.stop()

                initial.freed shouldBe true
                agents.created[1].freed shouldBe true
            }
            should("free an Agent that is still inside its transition window") {
                transport.requestIceRestart()
                agents.created[1].fireState(IceProcessingState.COMPLETED)
                transport.stop()

                initial.freed shouldBe true
                agents.created[1].freed shouldBe true
            }
        }

        context("When the new Agent can not be created") {
            should("report that a restart is unavailable") {
                // A resource problem, so the endpoint is told to fall back to a full re-invite.
                agents.failNext = true
                transport.requestIceRestart() shouldBe IceRestartResult.UNAVAILABLE
                transport.describe().ufrag shouldBe initial.ufrag
            }
            should("leave a later restart working") {
                agents.failNext = true
                transport.requestIceRestart()
                transport.requestIceRestart() shouldBe IceRestartResult.STARTED
                transport.describe().iceGeneration shouldBe 1
            }
        }
    }

    context("With ICE restarts disabled") {
        should("report that a restart is unavailable") {
            withNewConfig("videobridge.ice.restart.enabled = false") {
                val transport = createTransport()
                agents.created[0].state = IceProcessingState.COMPLETED
                transport.requestIceRestart() shouldBe IceRestartResult.UNAVAILABLE
                agents.created.size shouldBe 1
            }
        }
    }

    context("With a non-positive restart timeout") {
        should("report that a restart is unavailable") {
            withNewConfig("videobridge.ice.restart.timeout = 0 seconds") {
                val transport = createTransport()
                agents.created[0].state = IceProcessingState.COMPLETED
                transport.requestIceRestart() shouldBe IceRestartResult.UNAVAILABLE
                agents.created.size shouldBe 1
            }
        }
    }
})

private fun IceTransport.describe() = IceUdpTransportPacketExtension().also { describe(it) }

private fun remoteTransport(
    generation: Int?,
    remoteUfrag: String = "remote-ufrag",
    remotePassword: String = "remote-pwd"
) = IceUdpTransportPacketExtension().apply {
    ufrag = remoteUfrag
    password = remotePassword
    generation?.let { iceGeneration = it }
}

/** Waits for something another thread does (freeing an Agent happens on the IO pool). */
private fun awaitTrue(timeoutMs: Long = 5000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition() && System.currentTimeMillis() < deadline) {
        Thread.sleep(10)
    }
    condition() shouldBe true
}

private class FakeAgents {
    val created = mutableListOf<FakeAgent>()

    /** Whether the next call to [factory] fails, the way a failure to bind a port would. */
    var failNext = false

    val factory: (Logger) -> Agent = {
        if (failNext) {
            failNext = false
            throw java.io.IOException("Failed to bind")
        }
        FakeAgent(created.size).also { created.add(it) }.agent
    }
}

private class FakeAgent(index: Int) {
    val ufrag = "ufrag-$index"
    val password = "password-$index"

    var state: IceProcessingState = IceProcessingState.WAITING
    var freed = false
    var startCalls = 0
    var remoteUfrag: String? = null
    var remotePassword: String? = null

    private val stateChangeListeners = mutableListOf<PropertyChangeListener>()

    val component: Component = mockk(relaxed = true)

    val stream: IceMediaStream = mockk(relaxed = true) {
        every { remoteUfrag = any() } answers { this@FakeAgent.remoteUfrag = firstArg() }
        every { remoteUfrag } answers { this@FakeAgent.remoteUfrag }
        every { remotePassword = any() } answers { this@FakeAgent.remotePassword = firstArg() }
        every { remotePassword } answers { this@FakeAgent.remotePassword }
    }

    val agent: Agent = mockk(relaxed = true) {
        every { localUfrag } returns ufrag
        every { localPassword } returns password
        every { state } answers { this@FakeAgent.state }
        every { createMediaStream(any()) } returns stream
        every {
            createComponent(any<IceMediaStream>(), any<KeepAliveStrategy>(), any<Boolean>())
        } returns component
        every { addStateChangeListener(any()) } answers { stateChangeListeners.add(firstArg()) }
        every { removeStateChangeListener(any()) } answers { stateChangeListeners.remove(firstArg()) }
        every { startConnectivityEstablishment() } answers { startCalls++ }
        every { free() } answers { freed = true }
    }

    fun fireState(newState: IceProcessingState, oldState: IceProcessingState = IceProcessingState.RUNNING) {
        state = newState
        val event = PropertyChangeEvent(agent, IceProcessingState::class.java.name, oldState, newState)
        stateChangeListeners.toList().forEach { it.propertyChange(event) }
    }
}
