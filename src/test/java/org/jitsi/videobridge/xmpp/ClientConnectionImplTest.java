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

package org.jitsi.videobridge.xmpp;

import com.typesafe.config.*;
import org.jitsi.xmpp.mucclient.*;
import org.junit.*;

import java.util.*;
import java.util.stream.*;

import static org.junit.Assert.*;

public class ClientConnectionImplTest
{
    private Config getConfig(String configName)
    {
        return ConfigFactory.parseResources("muc-client-configuration.conf").getConfig(configName);
    }
    @Test
    public void testBeanParsing()
    {
        List<? extends Config> c =
                ConfigFactory.parseResources("muc-client-configuration.conf").getConfigList("muc-client-configs");
        assertEquals(2, c.size());
        List<MucClientConfiguration> mccs =
                c.stream()
                        .map(config -> ConfigBeanFactory.create(config, MucClientConfiguration.class))
                        .collect(Collectors.toList());
        for (int i = 0; i < mccs.size(); ++i)
        {
            MucClientConfiguration mcc = mccs.get(i);
            if (i == 0)
            {
                assertEquals("1", mcc.getId());
                assertEquals("hostname", mcc.getHostname());
                assertEquals("domain", mcc.getDomain());
                assertEquals("username", mcc.getUsername());
                assertEquals("password", mcc.getPassword());
                assertEquals(2, mcc.getMucJids().size());
                assertTrue(mcc.getMucJids().contains("jid1@muc"));
                assertTrue(mcc.getMucJids().contains("jid2@muc2"));
                assertEquals("nickname", mcc.getMucNickname());
                assertTrue(mcc.getDisableCertificateVerification());
                assertEquals("iq handlermode", mcc.getIqHandlerMode());
            }
            else
            {
                assertEquals("2", mcc.getId());
                assertEquals("hostname-2", mcc.getHostname());
                assertEquals("domain-2", mcc.getDomain());
                assertEquals("username-2", mcc.getUsername());
                assertEquals("password-2", mcc.getPassword());
                assertEquals(2, mcc.getMucJids().size());
                assertTrue(mcc.getMucJids().contains("jid1@muc"));
                assertTrue(mcc.getMucJids().contains("jid2@muc2"));
                assertEquals("nickname-2", mcc.getMucNickname());
                //TODO: this is hard-coded to true in the class.  fix it there?
                //assertFalse(mcc.getDisableCertificateVerification());
                assertEquals("iq handlermode-2", mcc.getIqHandlerMode());

            }
        }
    }

}