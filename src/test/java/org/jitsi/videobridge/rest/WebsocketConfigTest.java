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

package org.jitsi.videobridge.rest;

import com.typesafe.config.*;
import org.junit.*;

import static org.junit.Assert.*;

public class WebsocketConfigTest
{
    private Config getConfig(String configName)
    {
        return ConfigFactory.parseResources("websocket-test.conf").getConfig(configName);
    }

    @Test
    public void testNoTlsSet()
    {
        Config config = getConfig("websocket-config-no-tls");
        WebsocketConfig websocketConfig  = ConfigBeanFactory.create(config, WebsocketConfig.class);
        assertTrue(websocketConfig.enabled);
        assertEquals("default-id", websocketConfig.serverId);
        assertEquals("domain", websocketConfig.domain);
        assertNull(websocketConfig.tls);
    }

    @Test
    public void testTlsSet()
    {
        Config config = getConfig("websocket-config-tls-set");
        WebsocketConfig websocketConfig  = ConfigBeanFactory.create(config, WebsocketConfig.class);
        assertTrue(websocketConfig.enabled);
        assertEquals("default-id", websocketConfig.serverId);
        assertEquals("domain", websocketConfig.domain);
        assertEquals(false, websocketConfig.tls);
    }
}