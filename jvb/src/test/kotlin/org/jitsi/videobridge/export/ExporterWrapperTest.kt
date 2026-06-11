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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.colibri2.FeatureNotImplementedException
import org.jitsi.xmpp.extensions.colibri2.Connect
import java.net.URI

class ExporterWrapperTest : ShouldSpec() {
    private val logger = LoggerImpl(javaClass.name)

    /** Records the mock [Exporter] created for each connect URL, so tests can verify calls against them. */
    private inner class Fixture {
        val exporters = mutableMapOf<URI, Exporter>()
        val wrapper = ExporterWrapper(logger, { }) { connect ->
            mockk<Exporter>(relaxed = true).also { exporters[connect.url] = it }
        }
        operator fun get(url: String): Exporter = exporters.getValue(URI(url))
    }

    private fun connect(
        url: String,
        type: Connect.Types = Connect.Types.TRANSCRIBER,
        headers: Map<String, String> = emptyMap(),
        exports: List<String> = emptyList(),
        requests: List<String> = emptyList(),
        ping: Pair<Int, Int>? = null
    ): Connect = Connect(URI(url), Connect.Protocols.MEDIAJSON, type).apply {
        headers.forEach { (n, v) -> addHttpHeader(n, v) }
        exports.forEach { addExport(it) }
        requests.forEach { addRequest(it) }
        ping?.let { setPing(it.first, it.second) }
    }

    init {
        val urlA = "wss://example.com/a"
        val urlB = "wss://example.com/b"

        context("Starting connects") {
            should("create an exporter per connect") {
                val f = Fixture()
                f.wrapper.setConnects(listOf(connect(urlA), connect(urlB)))

                f.wrapper.started shouldBe true
                f.exporters.keys shouldBe setOf(URI(urlA), URI(urlB))
            }
            should("stop all and mark not started when given an empty list") {
                val f = Fixture()
                f.wrapper.setConnects(listOf(connect(urlA), connect(urlB)))

                f.wrapper.setConnects(emptyList())

                f.wrapper.started shouldBe false
                verify(exactly = 1) { f[urlA].stop() }
                verify(exactly = 1) { f[urlB].stop() }
            }
        }

        context("Reconciling connects") {
            should("stop only the exporters whose URL is no longer requested") {
                val f = Fixture()
                f.wrapper.setConnects(listOf(connect(urlA), connect(urlB)))

                f.wrapper.setConnects(listOf(connect(urlA)))

                verify(exactly = 0) { f[urlA].stop() }
                verify(exactly = 1) { f[urlB].stop() }
                f.wrapper.started shouldBe true
            }
            should("not recreate or update an exporter when an identical connect is re-signaled") {
                val f = Fixture()
                f.wrapper.setConnects(listOf(connect(urlA, exports = listOf("s1"))))

                f.wrapper.setConnects(listOf(connect(urlA, exports = listOf("s1"))))

                f.exporters.size shouldBe 1
                verify(exactly = 0) { f[urlA].update(any(), any()) }
            }
        }

        context("Updating exports/requests in place") {
            should("apply changed exports and requests to the running exporter") {
                val f = Fixture()
                f.wrapper.setConnects(listOf(connect(urlA, exports = listOf("s1"), requests = listOf("s1.en"))))

                f.wrapper.setConnects(
                    listOf(connect(urlA, exports = listOf("s1", "s2"), requests = listOf("s1.en", "s2.fr")))
                )

                verify(exactly = 1) { f[urlA].update(listOf("s1", "s2"), listOf("s1.en", "s2.fr")) }
                verify(exactly = 0) { f[urlA].stop() }
            }
            should("not treat reordered source names as a change") {
                val f = Fixture()
                f.wrapper.setConnects(listOf(connect(urlA, exports = listOf("s1", "s2"))))

                f.wrapper.setConnects(listOf(connect(urlA, exports = listOf("s2", "s1"))))

                verify(exactly = 0) { f[urlA].update(any(), any()) }
            }
        }

        context("Rejecting non-updatable changes") {
            should("throw and leave the exporter untouched when HTTP headers change") {
                val f = Fixture()
                f.wrapper.setConnects(listOf(connect(urlA, headers = mapOf("Authorization" to "Bearer a"))))

                shouldThrow<FeatureNotImplementedException> {
                    f.wrapper.setConnects(listOf(connect(urlA, headers = mapOf("Authorization" to "Bearer b"))))
                }

                verify(exactly = 0) { f[urlA].stop() }
                verify(exactly = 0) { f[urlA].update(any(), any()) }
            }
            should("throw when the ping configuration changes") {
                val f = Fixture()
                f.wrapper.setConnects(listOf(connect(urlA, ping = 1000 to 2000)))

                shouldThrow<FeatureNotImplementedException> {
                    f.wrapper.setConnects(listOf(connect(urlA, ping = 3000 to 4000)))
                }
            }
            should("throw when the type changes") {
                val f = Fixture()
                f.wrapper.setConnects(listOf(connect(urlA, type = Connect.Types.TRANSCRIBER)))

                shouldThrow<FeatureNotImplementedException> {
                    f.wrapper.setConnects(listOf(connect(urlA, type = Connect.Types.TRANSLATOR)))
                }
            }
            should("reject a video connect") {
                val f = Fixture()
                val videoConnect = Connect(URI(urlA), Connect.Protocols.MEDIAJSON, Connect.Types.RECORDER, video = true)

                shouldThrow<FeatureNotImplementedException> {
                    f.wrapper.setConnects(listOf(videoConnect))
                }
            }
        }
    }
}
