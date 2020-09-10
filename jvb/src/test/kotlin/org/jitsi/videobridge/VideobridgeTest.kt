/*
 * Copyright @ 2020 - Present, 8x8, Inc.
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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.jitsi.nlj.util.OrderedJsonObject
import org.jitsi.shutdown.ShutdownServiceImpl
import org.jitsi.videobridge.shutdown.ShutdownServiceSupplier
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.XMPPError
import org.json.simple.parser.JSONParser
import org.jxmpp.jid.impl.JidCreate

class VideobridgeTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val shutdownService: ShutdownServiceImpl = mockk(relaxed = true)
    private val shutdownServiceSupplier: ShutdownServiceSupplier = mockk {
        every { get() } returns shutdownService
    }
    private val videobridge = Videobridge()
    init {
        mockkStatic("org.jitsi.videobridge.shutdown.ShutdownServiceSupplierKt")
        every { org.jitsi.videobridge.shutdown.singleton() } returns shutdownServiceSupplier
    }
    init {
        context("Debug state should be JSON") {
            videobridge.getDebugState(null, null, true).shouldBeValidJson()
        }
        context("Shutdown") {
            context("when a conference is active") {
                val conf = videobridge.createConference(JidCreate.entityBareFrom("conf@domain.org"), true)
                context("starting a graceful shutdown") {
                    videobridge.shutdown(true)
                    should("report that shutdown is in progress") {
                        videobridge.isShutdownInProgress shouldBe true
                    }
                    should("not have started the shutdown yet") {
                        verify(exactly = 0) { shutdownService.beginShutdown() }
                    }
                    should("respond with an error if a new conference create is received via XMPP") {
                        val confCreateIq = ColibriUtilities.createConferenceIq(JidCreate.from("focusJid"))
                        val resp = videobridge.handleColibriConferenceIQ(confCreateIq)
                        resp.shouldBeInstanceOf<ErrorIQ>()
                        resp as ErrorIQ
                        resp.error.condition shouldBe XMPPError.Condition.service_unavailable
                    }
                    context("and once the conference expires") {
                        videobridge.expireConference(conf)
                        should("start the shutdown") {
                            verify(exactly = 1) { shutdownService.beginShutdown() }
                        }
                    }
                }
            }
        }
    }
}

fun OrderedJsonObject.shouldBeValidJson() {
    JSONParser().parse(this.toJSONString())
}
