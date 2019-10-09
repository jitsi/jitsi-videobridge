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

package org.jitsi.videobridge.config;

import com.typesafe.config.*;
import org.jitsi.testutils.*;
import org.junit.*;

import static org.junit.Assert.*;

public class EnabledApiConfigsPropertyTest
{
    @Test
    public void testApiNAmesCommandLineOnly()
    {
        new ConfigSetup()
            .withCommandLineArg("--apis", "rest,xmpp")
            .finishSetup();

        EnabledApiConfigsProperty.EnabledApiNamesProperty enabledApiNames = new EnabledApiConfigsProperty.EnabledApiNamesProperty();

        assertEquals(2, enabledApiNames.get().size());
        assertTrue(enabledApiNames.get().contains("rest"));
        assertTrue(enabledApiNames.get().contains("xmpp"));
    }

    @Test
    public void testXmppConfigCommandLineOnly()
    {
        new ConfigSetup()
            .withCommandLineArg("--apis", "xmpp")
            .withCommandLineArg("--host", "hostname-from-cl")
            .withCommandLineArg("--domain", "domain-from-cl")
            .withCommandLineArg("--subdomain", "subdomain-from-cl")
            .withCommandLineArg("--secret", "secret-from-cl")
            .withCommandLineArg("--port", "4242")
            .finishSetup();

        assertEquals("hostname-from-cl", new EnabledApiConfigsProperty.XmppApiConfig.HostProperty().get());
        assertEquals("domain-from-cl", new EnabledApiConfigsProperty.XmppApiConfig.DomainProperty().get());
        assertEquals("subdomain-from-cl", new EnabledApiConfigsProperty.XmppApiConfig.SubdomainProperty().get());
        assertEquals("secret-from-cl", new EnabledApiConfigsProperty.XmppApiConfig.SecretProperty().get());
        assertEquals("4242", new EnabledApiConfigsProperty.XmppApiConfig.PortProperty().get());
    }

    @Test
    public void testNewConfigOnly()
    {
        Config newConfig = ConfigFactory.parseResources("apis-config.conf").getConfig("xmpp-conf");
        new ConfigSetup()
            .withNewConfig(newConfig)
            .finishSetup();

        assertEquals("hostname-from-config", new EnabledApiConfigsProperty.XmppApiConfig.HostProperty().get());
        assertEquals("domain-from-config", new EnabledApiConfigsProperty.XmppApiConfig.DomainProperty().get());
        assertEquals("subdomain-from-config", new EnabledApiConfigsProperty.XmppApiConfig.SubdomainProperty().get());
        assertEquals("secret-from-config", new EnabledApiConfigsProperty.XmppApiConfig.SecretProperty().get());
        assertEquals("4243", new EnabledApiConfigsProperty.XmppApiConfig.PortProperty().get());
    }

    @Test
    public void testCommandLineWithSomeFallingBackToNewConfig()
    {
        Config newConfig = ConfigFactory.parseResources("apis-config.conf").getConfig("xmpp-conf");
        new ConfigSetup()
            .withNewConfig(newConfig)
            .withCommandLineArg("--apis", "xmpp")
            .withCommandLineArg("--domain", "domain-from-cl")
            .withCommandLineArg("--secret", "secret-from-cl")
            .finishSetup();

        assertEquals("hostname-from-config", new EnabledApiConfigsProperty.XmppApiConfig.HostProperty().get());
        assertEquals("domain-from-cl", new EnabledApiConfigsProperty.XmppApiConfig.DomainProperty().get());
        assertEquals("subdomain-from-config", new EnabledApiConfigsProperty.XmppApiConfig.SubdomainProperty().get());
        assertEquals("secret-from-cl", new EnabledApiConfigsProperty.XmppApiConfig.SecretProperty().get());
        assertEquals("4243", new EnabledApiConfigsProperty.XmppApiConfig.PortProperty().get());
    }
}