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

package org.jitsi.videobridge.xmpp.config;

import com.typesafe.config.*;
import org.jitsi.testutils.*;
import org.jitsi.xmpp.mucclient.*;
import org.junit.*;

import java.util.*;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;

public class ConfigTest
{

    @Test
    public void testMultipleConfigs() throws Exception
    {
        new ConfigSetup()
            .withNewConfig(ConfigFactory.parseResources("xmpp_client_configs.conf").getConfig("multiple-muc-config"))
            .finishSetup();

        List<MucClientConfiguration> configs = Config.XmppClientConfig.getClientConfigs();
        assertEquals(2, configs.size());

        MucClientConfiguration config1 = configs.stream()
            .filter(c -> c.getId().equalsIgnoreCase("shard-1"))
            .findFirst()
            .orElseThrow(() -> new Exception("shard-1 config not found"));
        assertTrue(config1.getDisableCertificateVerification());
        assertEquals("auth.brian.jitsi.net", config1.getDomain());
        assertEquals("password\\+", config1.getPassword());
        assertEquals("brian.jitsi.net", config1.getHostname());
        assertEquals("brian_local", config1.getMucNickname());
        assertEquals("username", config1.getUsername());
        assertEquals(1, config1.getMucJids().size());
        String mucJid = config1.getMucJids().get(0);
        assertEquals("JvbBrewery@internal.auth.brian.jitsi.net", mucJid);

        MucClientConfiguration config2 = configs.stream()
            .filter(c -> c.getId().equalsIgnoreCase("shard-2"))
            .findFirst()
            .orElseThrow(() -> new Exception("shard-2 config not found"));
        assertTrue(config1.getDisableCertificateVerification());
        assertEquals("auth.brian2.jitsi.net", config2.getDomain());
        assertEquals("password\\+", config2.getPassword());
        assertEquals("brian2.jitsi.net", config2.getHostname());
        assertEquals("brian_local2", config2.getMucNickname());
        assertEquals("username", config2.getUsername());
        assertEquals(1, config2.getMucJids().size());
        mucJid = config2.getMucJids().get(0);
        assertEquals("JvbBrewery@internal.auth.brian2.jitsi.net", mucJid);
    }
}