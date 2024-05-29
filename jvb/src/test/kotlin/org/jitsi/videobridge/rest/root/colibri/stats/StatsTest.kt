/*
 * Copyright @ 2020 - present 8x8, Inc.
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

package org.jitsi.videobridge.rest.root.colibri.stats

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.ws.rs.core.Application
import jakarta.ws.rs.core.MediaType
import org.eclipse.jetty.http.HttpStatus
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.test.JerseyTest
import org.glassfish.jersey.test.TestProperties
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.junit.Test

class StatsTest : JerseyTest() {
    private val baseUrl = "/colibri/stats"

    override fun configure(): Application {
        enable(TestProperties.LOG_TRAFFIC)
        enable(TestProperties.DUMP_ENTITY)
        return object : ResourceConfig() {
            init {
                register(Stats::class.java)
            }
        }
    }

    @Test
    fun testGetStats() {
        val resp = target(baseUrl).request().get()
        resp.status shouldBe HttpStatus.OK_200
        resp.mediaType shouldBe MediaType.APPLICATION_JSON_TYPE
        val parsed = JSONParser().parse(resp.readEntity(String::class.java))
        parsed.shouldBeInstanceOf<JSONObject>()
    }
}
