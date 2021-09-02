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
import io.mockk.mockk
import io.mockk.verify
import org.jitsi.shutdown.ShutdownServiceImpl
import org.jitsi.utils.OrderedJsonObject
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.StanzaError
import org.json.simple.parser.JSONParser
import org.jxmpp.jid.impl.JidCreate

class VideobridgeTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val shutdownService: ShutdownServiceImpl = mockk(relaxed = true)
    private val videobridge = Videobridge(null, shutdownService, mockk())
    init {
        context("Debug state should be JSON") {
            videobridge.getDebugState(null, null, true).shouldBeValidJson()
        }
        context("Shutdown") {
            context("when a conference is active") {
                val conf = videobridge.createConference(JidCreate.entityBareFrom("conf@domain.org"))
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
                        resp.error.condition shouldBe StanzaError.Condition.service_unavailable
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
