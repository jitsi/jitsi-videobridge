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

package org.jitsi.videobridge

import io.kotest.matchers.shouldBe
import org.jitsi.ConfigTest
import org.jitsi.xmpp.extensions.colibri.ShutdownIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XMPPError
import org.jxmpp.jid.impl.JidCreate

@Suppress("BlockingMethodInNonBlockingContext")
class VideobridgeTest : ConfigTest() {
    private val videobridge = Videobridge()
    init {
        context("handleShutdownIQ") {
            context("when no shutdown source pattern is set") {
                val iq = ShutdownIQ.createGracefulShutdownIQ().apply {
                    from = JidCreate.bareFrom("alice@jitsi.org")
                }
                val resp = videobridge.handleShutdownIQ(iq)
                resp.error.condition shouldBe XMPPError.Condition.service_unavailable
            }
            context("when a shutdown source pattern is set") {
                context("in legacy config") {
                    withLegacyConfig("org.jitsi.videobridge.shutdown.ALLOWED_SOURCE_REGEXP=alice@.*") {
                        context("and the IQ from field matches") {
                            val iq = ShutdownIQ.createGracefulShutdownIQ().apply {
                                from = JidCreate.bareFrom("alice@jitsi.org")
                            }
                            val resp = videobridge.handleShutdownIQ(iq)
                            resp.type shouldBe IQ.Type.result
                        }
                        context("and the IQ from field doesn't match") {
                            val iq = ShutdownIQ.createGracefulShutdownIQ().apply {
                                from = JidCreate.bareFrom("bob@jitsi.org")
                            }
                            val resp = videobridge.handleShutdownIQ(iq)
                            resp.type shouldBe IQ.Type.error
                            resp.error.condition shouldBe XMPPError.Condition.not_authorized
                        }
                    }
                }
                context("in new config") {
                    withNewConfig("videobridge.apis.xmpp-client.allow-shutdown-pattern=\"alice@.*\"") {
                        context("and the IQ from field matches") {
                            val iq = ShutdownIQ.createGracefulShutdownIQ().apply {
                                from = JidCreate.bareFrom("alice@jitsi.org")
                            }
                            val resp = videobridge.handleShutdownIQ(iq)
                            resp.type shouldBe IQ.Type.result
                        }
                        context("and the IQ from field doesn't match") {
                            val iq = ShutdownIQ.createGracefulShutdownIQ().apply {
                                from = JidCreate.bareFrom("bob@jitsi.org")
                            }
                            val resp = videobridge.handleShutdownIQ(iq)
                            resp.type shouldBe IQ.Type.error
                            resp.error.condition shouldBe XMPPError.Condition.not_authorized
                        }
                    }
                }
            }
        }
    }
}
