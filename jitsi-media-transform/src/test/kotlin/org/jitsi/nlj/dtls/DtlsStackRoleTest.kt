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

package org.jitsi.nlj.dtls

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.resources.logging.StdoutLogger
import java.util.logging.Level

/**
 * The role of a [DtlsStack] is set from the peer's `setup` attribute, which the peer re-signals with transport
 * updates that have nothing to do with DTLS. Re-applying the role the stack already has must leave it alone:
 * building a new [DtlsRole] would discard the one that negotiated the connection.
 */
class DtlsStackRoleTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val stack = DtlsStack(StdoutLogger(_level = Level.OFF))

    init {
        context("Setting the role for the first time") {
            should("set it to server") {
                stack.actAsServer() shouldBe true
                (stack.role is DtlsServer) shouldBe true
            }
            should("set it to client") {
                stack.actAsClient() shouldBe true
                (stack.role is DtlsClient) shouldBe true
            }
        }

        context("Setting the role again") {
            should("keep the server role that is already set") {
                stack.actAsServer() shouldBe true
                val role = stack.role

                stack.actAsServer() shouldBe false
                (stack.role === role) shouldBe true
            }
            should("keep the client role that is already set") {
                stack.actAsClient() shouldBe true
                val role = stack.role

                stack.actAsClient() shouldBe false
                (stack.role === role) shouldBe true
            }
        }

        context("Changing the role") {
            should("replace a server role with a client one") {
                stack.actAsServer() shouldBe true

                stack.actAsClient() shouldBe true
                (stack.role is DtlsClient) shouldBe true
            }
            should("replace a client role with a server one") {
                stack.actAsClient() shouldBe true

                stack.actAsServer() shouldBe true
                (stack.role is DtlsServer) shouldBe true
            }
        }
    }
}
