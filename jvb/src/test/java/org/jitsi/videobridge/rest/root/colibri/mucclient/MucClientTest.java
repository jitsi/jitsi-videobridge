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

package org.jitsi.videobridge.rest.root.colibri.mucclient;

import org.eclipse.jetty.http.*;
import org.glassfish.jersey.server.*;
import org.glassfish.jersey.test.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.json.simple.*;
import org.junit.*;
import org.mockito.*;

import javax.ws.rs.client.*;
import javax.ws.rs.core.*;
import javax.ws.rs.core.Application;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MucClientTest extends JerseyTest
{
    protected ClientConnectionImplSupplier clientConnectionImplSupplier;
    protected ClientConnectionImpl clientConnection;
    protected static final String BASE_URL = "/colibri/muc-client";

    @Override
    protected Application configure()
    {
        clientConnectionImplSupplier = mock(ClientConnectionImplSupplier.class);
        clientConnection = mock(ClientConnectionImpl.class);
        when(clientConnectionImplSupplier.get()).thenReturn(clientConnection);

        enable(TestProperties.LOG_TRAFFIC);
        enable(TestProperties.DUMP_ENTITY);
        return new ResourceConfig() {
            {
                register(new MockBinder<>(clientConnectionImplSupplier, ClientConnectionImplSupplier.class));
                register(MucClient.class);
            }
        };
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testAddMuc()
    {
        ArgumentCaptor<JSONObject> jsonConfigCaptor =
                ArgumentCaptor.forClass(JSONObject.class);

        when(clientConnection.addMucClient(jsonConfigCaptor.capture())).thenReturn(true);
        JSONObject json = new JSONObject();
        json.put("id", "id");
        json.put("hostname", "hostname");
        json.put("username", "username");
        json.put("password", "password");
        json.put("muc_jids", "jid1, jid2");
        json.put("muc_nickname", "muc_nickname");

        Response resp = target(BASE_URL + "/add").request().post(Entity.json(json.toJSONString()));
        assertEquals(HttpStatus.OK_200, resp.getStatus());
        assertEquals(json, jsonConfigCaptor.getValue());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testAddMucFailure()
    {
        when(clientConnection.addMucClient(any())).thenReturn(false);
        JSONObject json = new JSONObject();
        json.put("id", "id");
        json.put("hostname", "hostname");
        json.put("username", "username");
        json.put("password", "password");
        json.put("muc_jids", "jid1, jid2");
        json.put("muc_nickname", "muc_nickname");

        Response resp = target(BASE_URL + "/add").request().post(Entity.json(json.toJSONString()));
        assertEquals(HttpStatus.BAD_REQUEST_400, resp.getStatus());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testRemoveMuc()
    {
        ArgumentCaptor<JSONObject> jsonConfigCaptor =
                ArgumentCaptor.forClass(JSONObject.class);

        when(clientConnection.removeMucClient(jsonConfigCaptor.capture())).thenReturn(true);
        JSONObject json = new JSONObject();
        json.put("id", "id");

        Response resp = target(BASE_URL + "/remove").request().post(Entity.json(json.toJSONString()));
        assertEquals(HttpStatus.OK_200, resp.getStatus());
        assertEquals(json, jsonConfigCaptor.getValue());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testRemoveMucFailure()
    {
        when(clientConnection.removeMucClient(any())).thenReturn(false);
        JSONObject json = new JSONObject();
        json.put("id", "id");

        Response resp = target(BASE_URL + "/remove").request().post(Entity.json(json.toJSONString()));
        assertEquals(HttpStatus.BAD_REQUEST_400, resp.getStatus());
    }
}
