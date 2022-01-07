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

package org.jitsi.videobridge.rest.root.debug

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import org.eclipse.jetty.http.HttpStatus
import org.glassfish.hk2.utilities.binding.AbstractBinder
import org.glassfish.jersey.client.ClientConfig
import org.glassfish.jersey.logging.LoggingFeature
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.test.JerseyTest
import org.glassfish.jersey.test.TestProperties
import org.jitsi.health.HealthCheckService
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.Endpoint
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.relay.Relay
import org.jitsi.videobridge.rest.RestApis
import org.jitsi.videobridge.rest.annotations.EnabledByConfig
import org.junit.Test
import org.reflections.Reflections
import org.reflections.scanners.SubTypesScanner
import org.reflections.scanners.TypeAnnotationsScanner
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder
import org.reflections.util.FilterBuilder
import java.util.logging.Level
import java.util.logging.Logger
import javax.ws.rs.Path
import javax.ws.rs.client.Entity
import javax.ws.rs.core.Application

class DebugTest : JerseyTest() {
    private val endpoint: Endpoint = mockk(relaxed = true)
    private val relay: Relay = mockk(relaxed = true)
    private val conference: Conference = mockk {
        every { getEndpoint("bar") } returns endpoint
        every { getLocalEndpoint("bar") } returns endpoint
        every { getRelay("baz") } returns relay
    }
    private val videobridge: Videobridge = mockk {
        every { getConference("foo") } returns conference
    }

    override fun configure(): Application {
        enable(TestProperties.LOG_TRAFFIC)
        enable(TestProperties.DUMP_ENTITY)

        return object : ResourceConfig() {
            init {
                register(object : AbstractBinder() {
                    override fun configure() {
                        bind(mockkClass(HealthCheckService::class)).to(HealthCheckService::class.java)
                        bind(videobridge).to(Videobridge::class.java)
                    }
                })
                register(Debug::class.java)
            }
        }
    }

    override fun configureClient(config: ClientConfig?) {
        config?.register(
            LoggingFeature(
                Logger.getAnonymousLogger(),
                Level.ALL,
                LoggingFeature.Verbosity.HEADERS_ONLY,
                1500
            )
        )
    }

    @Test
    fun testAllResourcesAreBehindConfig() {
        val reflections = Reflections(
            ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage("org.jitsi.videobridge.rest.root.debug"))
                .filterInputsBy(FilterBuilder().includePackage("org.jitsi.videobridge.rest.root.debug"))
                .setScanners(SubTypesScanner(false), TypeAnnotationsScanner())
        )

        reflections.getTypesAnnotatedWith(Path::class.java).forEach { clazz ->
            clazz.isAnnotationPresent(EnabledByConfig::class.java) shouldBe true
            val anno = clazz.getAnnotation(EnabledByConfig::class.java)
            anno.value shouldBe RestApis.DEBUG
        }
    }

    @Test
    fun testEnableJvbFeature() {
        val resp = target("$BASE_URL/features/jvb/${DebugFeatures.PAYLOAD_VERIFICATION.value}/true")
            .request()
            .post(Entity.json(null))

        resp.status shouldBe HttpStatus.OK_200
    }

    @Test
    fun testDisableJvbFeature() {
        val resp = target("$BASE_URL/features/jvb/${DebugFeatures.PAYLOAD_VERIFICATION.value}/false")
            .request()
            .post(Entity.json(null))

        resp.status shouldBe HttpStatus.OK_200
    }

    @Test
    fun testEnableNonexistentJvbFeature() {
        val resp = target("$BASE_URL/features/jvb/blah/true")
            .request()
            .post(Entity.json(null))

        resp.status shouldBe HttpStatus.NOT_FOUND_404
    }

    @Test
    fun testDisableNonexistentJvbFeature() {
        val resp = target("$BASE_URL/features/jvb/blah/false")
            .request()
            .post(Entity.json(null))

        resp.status shouldBe HttpStatus.NOT_FOUND_404
    }

    @Test
    fun testEnableEndpointFeature() {
        val resp = target("$BASE_URL/features/endpoint/foo/bar/${EndpointDebugFeatures.PCAP_DUMP.value}/true")
            .request()
            .post(Entity.json(null))

        resp.status shouldBe HttpStatus.OK_200
    }

    @Test
    fun testGetEndpointFeatureState() {
        every { endpoint.isFeatureEnabled(EndpointDebugFeatures.PCAP_DUMP) } returns true
        val resp = target("$BASE_URL/features/endpoint/foo/bar/${EndpointDebugFeatures.PCAP_DUMP.value}")
            .request()
            .get()

        resp.status shouldBe HttpStatus.OK_200
        resp.readEntity(String::class.java)!!.toBoolean() shouldBe true

        every { endpoint.isFeatureEnabled(EndpointDebugFeatures.PCAP_DUMP) } returns false
        val resp2 = target("$BASE_URL/features/endpoint/foo/bar/${EndpointDebugFeatures.PCAP_DUMP.value}")
            .request()
            .get()

        resp2.status shouldBe HttpStatus.OK_200
        resp2.readEntity(String::class.java)!!.toBoolean() shouldBe false
    }

    @Test
    fun testGetNonexistentEndpointFeatureState() {
        val resp = target("$BASE_URL/features/endpoint/foo/bar/nonexistent")
            .request()
            .get()

        resp.status shouldBe HttpStatus.NOT_FOUND_404
    }

    @Test
    fun testDisableEndpointFeature() {
        val resp = target("$BASE_URL/features/endpoint/foo/bar/${EndpointDebugFeatures.PCAP_DUMP.value}/false")
            .request()
            .post(Entity.json(null))

        resp.status shouldBe HttpStatus.OK_200
    }

    @Test
    fun testDisableInvalidEndpointFeature() {
        val resp = target("$BASE_URL/features/endpoint/foo/bar/nonexistent/false")
            .request()
            .post(Entity.json(null))

        resp.status shouldBe HttpStatus.NOT_FOUND_404
    }

    @Test
    fun testEnableRelayFeature() {
        val resp = target("$BASE_URL/features/relay/foo/baz/${EndpointDebugFeatures.PCAP_DUMP.value}/true")
            .request()
            .post(Entity.json(null))

        resp.status shouldBe HttpStatus.OK_200
    }

    @Test
    fun testGetRelayFeatureState() {
        every { relay.isFeatureEnabled(EndpointDebugFeatures.PCAP_DUMP) } returns true
        val resp = target("$BASE_URL/features/relay/foo/baz/${EndpointDebugFeatures.PCAP_DUMP.value}")
            .request()
            .get()

        resp.status shouldBe HttpStatus.OK_200
        resp.readEntity(String::class.java)!!.toBoolean() shouldBe true

        every { relay.isFeatureEnabled(EndpointDebugFeatures.PCAP_DUMP) } returns false
        val resp2 = target("$BASE_URL/features/relay/foo/baz/${EndpointDebugFeatures.PCAP_DUMP.value}")
            .request()
            .get()

        resp2.status shouldBe HttpStatus.OK_200
        resp2.readEntity(String::class.java)!!.toBoolean() shouldBe false
    }

    @Test
    fun testGetNonexistentRelayFeatureState() {
        val resp = target("$BASE_URL/features/relay/foo/baz/nonexistent")
            .request()
            .get()

        resp.status shouldBe HttpStatus.NOT_FOUND_404
    }

    @Test
    fun testDisableRelayFeature() {
        val resp = target("$BASE_URL/features/relay/foo/baz/${EndpointDebugFeatures.PCAP_DUMP.value}/false")
            .request()
            .post(Entity.json(null))

        resp.status shouldBe HttpStatus.OK_200
    }

    @Test
    fun testDisableInvalidRelayFeature() {
        val resp = target("$BASE_URL/features/relay/foo/baz/nonexistent/false")
            .request()
            .post(Entity.json(null))

        resp.status shouldBe HttpStatus.NOT_FOUND_404
    }

    companion object {
        private const val BASE_URL = "/debug"
    }
}
