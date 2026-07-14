/*
 * Copyright @ 2024 - Present, 8x8 Inc
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
package org.jitsi.videobridge.export

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.colibri2.FeatureNotImplementedException
import org.jitsi.videobridge.colibri2.IqProcessingException
import org.jitsi.xmpp.extensions.colibri2.Connect
import java.net.URI

class ExporterWrapperTest : ShouldSpec() {
    private val logger = LoggerImpl(javaClass.name)

    /** Records the mock [Exporter] created for each connect id, so tests can verify calls against them. */
    private inner class Fixture {
        val exporters = mutableMapOf<String, Exporter>()
        val wrapper = ExporterWrapper(logger, { }, { }, { null }, { false }) { connect ->
            mockk<Exporter>(relaxed = true).also { exporters[connect.id] = it }
        }
        operator fun get(id: String): Exporter = exporters.getValue(id)
    }

    private fun connect(
        id: String,
        url: String = "wss://example.com/$id",
        type: Connect.Types = Connect.Types.TRANSCRIBER,
        create: Boolean = false,
        expire: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        exports: List<String> = emptyList(),
        requests: List<String> = emptyList(),
        ping: Pair<Int, Int>? = null
    ): Connect = Connect(
        id = id,
        url = URI(url),
        protocol = Connect.Protocols.MEDIAJSON,
        type = type,
        create = create,
        expire = expire
    ).apply {
        headers.forEach { (n, v) -> addHttpHeader(n, v) }
        exports.forEach { addExport(it) }
        requests.forEach { addRequest(it) }
        ping?.let { setPing(it.first, it.second) }
    }

    init {
        context("Creating connects") {
            should("create an exporter per connect, keyed by id") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", create = true), connect("b", create = true)))

                f.wrapper.started shouldBe true
                f.exporters.keys shouldBe setOf("a", "b")
            }
            should("allow multiple connects with the same url but different ids") {
                val f = Fixture()
                val url = "wss://example.com/shared"
                f.wrapper.applyConnects(
                    listOf(connect("a", url = url, create = true), connect("b", url = url, create = true))
                )

                f.exporters.keys shouldBe setOf("a", "b")
            }
            should("reject create for an already-existing id") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", create = true)))

                shouldThrow<IqProcessingException> {
                    f.wrapper.applyConnects(listOf(connect("a", create = true)))
                }
                verify(exactly = 0) { f["a"].stop() }
            }
            should("reject a connect for an unknown id without create") {
                val f = Fixture()
                shouldThrow<IqProcessingException> {
                    f.wrapper.applyConnects(listOf(connect("a")))
                }
                f.wrapper.started shouldBe false
            }
        }

        context("Delta semantics") {
            should("leave unmentioned connects running") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", create = true), connect("b", create = true)))

                // A delta that only updates 'a' must not touch 'b'.
                f.wrapper.applyConnects(listOf(connect("a", exports = listOf("s1"))))

                verify(exactly = 0) { f["a"].stop() }
                verify(exactly = 0) { f["b"].stop() }
                f.wrapper.started shouldBe true
            }
            should("not stop everything on an empty delta") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", create = true)))

                f.wrapper.applyConnects(emptyList())

                verify(exactly = 0) { f["a"].stop() }
                f.wrapper.started shouldBe true
            }
            should("expire only the named connect") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", create = true), connect("b", create = true)))

                f.wrapper.applyConnects(listOf(connect("b", expire = true)))

                verify(exactly = 0) { f["a"].stop() }
                verify(exactly = 1) { f["b"].stop() }
                f.wrapper.started shouldBe true
            }
            should("ignore an expire for an unknown id") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", create = true)))

                f.wrapper.applyConnects(listOf(connect("unknown", expire = true)))

                f.exporters.keys shouldBe setOf("a")
                verify(exactly = 0) { f["a"].stop() }
            }
        }

        context("Same-id combinations within one delta") {
            should("replace a connect via expire+create of the same id, regardless of order") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", url = "wss://example.com/a1", create = true)))
                val old = f["a"]

                f.wrapper.applyConnects(
                    listOf(
                        connect("a", url = "wss://example.com/a2", create = true),
                        connect("a", url = "wss://example.com/a1", expire = true)
                    )
                )

                verify(exactly = 1) { old.stop() }
                f.wrapper.started shouldBe true
                // The replacement exporter (created last for id "a") must not have been stopped.
                verify(exactly = 0) { f["a"].stop() }
            }
            should("reject two creates for the same id") {
                val f = Fixture()
                shouldThrow<IqProcessingException> {
                    f.wrapper.applyConnects(
                        listOf(
                            connect("a", url = "wss://example.com/a1", create = true),
                            connect("a", url = "wss://example.com/a2", create = true)
                        )
                    )
                }
                f.wrapper.started shouldBe false
                f.exporters shouldBe emptyMap()
            }
            should("reject an update for an id expired in the same delta") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", create = true)))

                shouldThrow<IqProcessingException> {
                    f.wrapper.applyConnects(
                        listOf(connect("a", expire = true), connect("a", exports = listOf("s1")))
                    )
                }
                // The whole delta is rejected before anything is applied.
                verify(exactly = 0) { f["a"].stop() }
                f.wrapper.started shouldBe true
            }
            should("reject duplicate expires for the same id") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", create = true)))

                shouldThrow<IqProcessingException> {
                    f.wrapper.applyConnects(listOf(connect("a", expire = true), connect("a", expire = true)))
                }
                verify(exactly = 0) { f["a"].stop() }
            }
        }

        context("stop()") {
            should("stop all exporters and mark not started") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", create = true), connect("b", create = true)))

                f.wrapper.stop()

                f.wrapper.started shouldBe false
                verify(exactly = 1) { f["a"].stop() }
                verify(exactly = 1) { f["b"].stop() }
            }
            should("reject connects after stop, so a racing delta can't start an unstoppable exporter") {
                val f = Fixture()
                f.wrapper.stop()

                shouldThrow<IqProcessingException> {
                    f.wrapper.applyConnects(listOf(connect("a", create = true)))
                }
                f.wrapper.started shouldBe false
                f.exporters shouldBe emptyMap()
            }
        }

        context("Updating exports/requests in place") {
            should("apply changed exports and requests to the running exporter") {
                val f = Fixture()
                f.wrapper.applyConnects(
                    listOf(connect("a", create = true, exports = listOf("s1"), requests = listOf("s1.en")))
                )

                f.wrapper.applyConnects(
                    listOf(connect("a", exports = listOf("s1", "s2"), requests = listOf("s1.en", "s2.fr")))
                )

                verify(exactly = 1) { f["a"].update(listOf("s1", "s2"), listOf("s1.en", "s2.fr")) }
                verify(exactly = 0) { f["a"].stop() }
            }
            should("not update for an identical re-signaled connect") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", create = true, exports = listOf("s1"))))

                f.wrapper.applyConnects(listOf(connect("a", exports = listOf("s1"))))

                verify(exactly = 0) { f["a"].update(any(), any()) }
            }
            should("not treat reordered source names as a change") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", create = true, exports = listOf("s1", "s2"))))

                f.wrapper.applyConnects(listOf(connect("a", exports = listOf("s2", "s1"))))

                verify(exactly = 0) { f["a"].update(any(), any()) }
            }
        }

        context("Rejecting non-updatable changes") {
            should("throw and leave the exporter untouched when HTTP headers change") {
                val f = Fixture()
                f.wrapper.applyConnects(
                    listOf(connect("a", create = true, headers = mapOf("Authorization" to "Bearer a")))
                )

                shouldThrow<FeatureNotImplementedException> {
                    f.wrapper.applyConnects(listOf(connect("a", headers = mapOf("Authorization" to "Bearer b"))))
                }

                verify(exactly = 0) { f["a"].stop() }
                verify(exactly = 0) { f["a"].update(any(), any()) }
            }
            should("throw when the ping configuration changes") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", create = true, ping = 1000 to 2000)))

                shouldThrow<FeatureNotImplementedException> {
                    f.wrapper.applyConnects(listOf(connect("a", ping = 3000 to 4000)))
                }
            }
            should("throw when the url changes for an existing id") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", url = "wss://example.com/a1", create = true)))

                shouldThrow<FeatureNotImplementedException> {
                    f.wrapper.applyConnects(listOf(connect("a", url = "wss://example.com/a2")))
                }
            }
            should("throw when the type changes") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", create = true, type = Connect.Types.TRANSCRIBER)))

                shouldThrow<FeatureNotImplementedException> {
                    f.wrapper.applyConnects(listOf(connect("a", type = Connect.Types.TRANSLATOR)))
                }
            }
            should("reject a video connect") {
                val f = Fixture()
                val videoConnect = Connect(
                    id = "a",
                    url = URI("wss://example.com/a"),
                    protocol = Connect.Protocols.MEDIAJSON,
                    type = Connect.Types.RECORDER,
                    video = true,
                    create = true
                )

                shouldThrow<FeatureNotImplementedException> {
                    f.wrapper.applyConnects(listOf(videoConnect))
                }
            }
        }

        context("Debug state") {
            should("tag each exporter's debug info with its connect id") {
                val f = Fixture()
                f.wrapper.applyConnects(listOf(connect("a", create = true), connect("b", create = true)))
                // The mock exporters are relaxed, so give them real debug nodes for the tag to be added to.
                every { f["a"].debugState() } returns JsonNodeFactory.instance.objectNode()
                every { f["b"].debugState() } returns JsonNodeFactory.instance.objectNode()

                val tags = f.wrapper.debugState().get("exporters").map { it.get("tag").asText() }.toSet()
                tags shouldBe setOf("a", "b")
            }
        }
    }
}
