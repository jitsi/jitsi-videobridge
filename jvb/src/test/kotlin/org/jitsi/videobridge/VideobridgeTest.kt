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
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.jitsi.config.withNewConfig
import org.jitsi.shutdown.ShutdownServiceImpl
import org.jitsi.test.concurrent.FakeScheduledExecutorService
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.videobridge.shutdown.ShutdownConfig
import org.jitsi.videobridge.shutdown.ShutdownState
import org.jitsi.videobridge.util.TaskPools
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.StanzaError
import org.json.simple.parser.JSONParser
import org.jxmpp.jid.impl.JidCreate

class VideobridgeTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val shutdownService: ShutdownServiceImpl = mockk(relaxed = true)
    private val mockExecutor = spyk<FakeScheduledExecutorService>()
    private val videobridge = Videobridge(null, shutdownService, mockk(), null, mockExecutor.clock)

    override fun beforeAny(testCase: TestCase) = super.beforeAny(testCase).also {
        TaskPools.SCHEDULED_POOL = mockExecutor
    }

    override fun afterAny(testCase: TestCase, result: TestResult) = super.afterAny(testCase, result).also {
        TaskPools.resetScheduledPool()
    }

    init {
        context("Debug state should be JSON") {
            videobridge.getDebugState(null, null, true).shouldBeValidJson()
        }
        context("Shutdown") {
            context("when a conference is active") {
                withNewConfig("videobridge.shutdown.graceful-shutdown-min-participants=10") {
                    repeat(15) { videobridge.localEndpointCreated() }
                    context("starting a graceful shutdown") {
                        videobridge.shutdown(true)
                        should("report that shutdown is in progress") {
                            videobridge.isInGracefulShutdown shouldBe true
                            videobridge.shutdownState shouldBe ShutdownState.GRACEFUL_SHUTDOWN
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
                        context("When the number of participants drops below the threshold") {
                            repeat(10) { videobridge.localEndpointExpired() }
                            videobridge.shutdownState shouldBe ShutdownState.SHUTTING_DOWN
                            mockExecutor.clock.elapse(ShutdownConfig.config.shuttingDownDelay)
                            mockExecutor.run()
                            verify(exactly = 1) { shutdownService.beginShutdown() }
                        }
                        context("When the graceful shutdown period expires") {
                            should("go to SHUTTING_DOWN") {
                                mockExecutor.clock.elapse(ShutdownConfig.config.gracefulShutdownMaxDuration)
                                mockExecutor.run()
                                videobridge.shutdownState shouldBe ShutdownState.SHUTTING_DOWN
                                should("and then shut down after shuttingDownDelay") {
                                    mockExecutor.clock.elapse(ShutdownConfig.config.shuttingDownDelay)
                                    mockExecutor.run()
                                    verify(exactly = 1) { shutdownService.beginShutdown() }
                                }
                            }
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
