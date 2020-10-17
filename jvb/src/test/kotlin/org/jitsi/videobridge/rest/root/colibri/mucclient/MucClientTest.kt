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

package org.jitsi.videobridge.rest.root.colibri.mucclient

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.eclipse.jetty.http.HttpStatus
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.test.JerseyTest
import org.glassfish.jersey.test.TestProperties
import org.jitsi.videobridge.rest.MockBinder
import org.jitsi.videobridge.xmpp.XmppConnection
import org.json.simple.JSONObject
import org.junit.Test
import javax.ws.rs.client.Entity
import javax.ws.rs.core.Application

class MucClientTest : JerseyTest() {
    private lateinit var xmppConnection: XmppConnection
    private val baseUrl = "/colibri/muc-client"

    override fun configure(): Application {
        xmppConnection = mockk()

        enable(TestProperties.LOG_TRAFFIC)
        enable(TestProperties.DUMP_ENTITY)
        return object : ResourceConfig() {
            init {
                register(MockBinder(xmppConnection, XmppConnection::class.java))
                register(MucClient::class.java)
            }
        }
    }

    @Test
    fun testAddMuc() {
        val jsonConfigSlot = slot<JSONObject>()

        every { xmppConnection.addMucClient(capture(jsonConfigSlot)) } returns true

        val json = JSONObject().apply {
            put("id", "id")
            put("hostname", "hostname")
            put("username", "username")
            put("password", "password")
            put("muc_jids", "jid1, jid2")
            put("muc_nickname", "muc_nickname")
        }

        val resp = target("$baseUrl/add").request().post(Entity.json(json.toJSONString()))
        resp.status shouldBe HttpStatus.OK_200
        jsonConfigSlot.captured shouldBe json
    }

    @Test
    fun testAddMucFailure() {
        val jsonConfigSlot = slot<JSONObject>()

        every { xmppConnection.addMucClient(capture(jsonConfigSlot)) } returns false

        val json = JSONObject().apply {
            put("id", "id")
            put("hostname", "hostname")
            put("username", "username")
            put("password", "password")
            put("muc_jids", "jid1, jid2")
            put("muc_nickname", "muc_nickname")
        }

        val resp = target("$baseUrl/add").request().post(Entity.json(json.toJSONString()))
        resp.status shouldBe HttpStatus.BAD_REQUEST_400
    }

    @Test
    fun testRemoveMuc() {
        val jsonConfigSlot = slot<JSONObject>()

        every { xmppConnection.addMucClient(capture(jsonConfigSlot)) } returns true

        val json = JSONObject().apply {
            put("id", "id")
        }

        val resp = target("$baseUrl/add").request().post(Entity.json(json.toJSONString()))
        resp.status shouldBe HttpStatus.OK_200
        jsonConfigSlot.captured shouldBe json
    }

    @Test
    fun testRemoveMucFailure() {
        every { xmppConnection.addMucClient(any()) } returns false

        val json = JSONObject().apply {
            put("id", "id")
        }
        val resp = target("$baseUrl/add").request().post(Entity.json(json.toJSONString()))
        resp.status shouldBe HttpStatus.BAD_REQUEST_400
    }
}
