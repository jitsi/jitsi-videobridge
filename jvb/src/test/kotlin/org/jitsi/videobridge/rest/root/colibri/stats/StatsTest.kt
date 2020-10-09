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
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jetty.http.HttpStatus
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.test.JerseyTest
import org.glassfish.jersey.test.TestProperties
import org.jitsi.videobridge.rest.MockBinder
import org.jitsi.videobridge.stats.StatsCollector
import org.jitsi.videobridge.stats.VideobridgeStatistics
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.junit.Test
import javax.ws.rs.core.Application
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

class StatsTest : JerseyTest() {
    private lateinit var statsCollector: StatsCollector
    private val baseUrl = "/colibri/stats"

    override fun configure(): Application {
        statsCollector = mockk()

        enable(TestProperties.LOG_TRAFFIC)
        enable(TestProperties.DUMP_ENTITY)
        return object : ResourceConfig() {
            init {
                register(MockBinder(statsCollector, StatsCollector::class.java))
                register(Stats::class.java)
            }
        }
    }

    @Test
    fun testGetStats() {
        val fakeStats = mutableMapOf<String, Any>("stat1" to "value1", "stat2" to "value2")
        val videobridgeStatistics = mockk<VideobridgeStatistics>()
        every { videobridgeStatistics.stats } returns fakeStats
        every { statsCollector.statistics } returns videobridgeStatistics

        val resp = target(baseUrl).request().get()
        resp.status shouldBe HttpStatus.OK_200
        resp.mediaType shouldBe MediaType.APPLICATION_JSON_TYPE
        resp.getResultAsJson() shouldBe mapOf("stat1" to "value1", "stat2" to "value2")
    }

    private fun Response.getResultAsJson(): JSONObject {
        val obj = JSONParser().parse(readEntity(String::class.java))
        obj.shouldBeInstanceOf<JSONObject>()
        return obj as JSONObject
    }
}
