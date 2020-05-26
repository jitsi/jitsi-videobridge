/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.videobridge.stats;

import org.jitsi.osgi.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.signaling.api.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.colibri.*;

/**
 * Implements a {@link StatsTransport} which publishes via Presence in an XMPP
 * MUC.
 *
 * @author Boris Grozev
 */
public class MucStatsTransport
    extends StatsTransport
{
    /**
     * The <tt>Logger</tt> used by the <tt>MucStatsTransport</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
        = new LoggerImpl(MucStatsTransport.class.getName());

    /**
     * Gets the {@link ClientConnectionImpl} to be used to publish
     * statistics.
     * @return the {@link ClientConnectionImpl} or {@code null}.
     */
    private ClientConnectionImpl getUserConnectionBundleActivator()
    {
        return ServiceUtils2.getService(
            getBundleContext(), ClientConnectionImpl.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publishStatistics(Statistics stats)
    {
        ClientConnectionImpl clientConnectionImpl
            = getUserConnectionBundleActivator();
        if (clientConnectionImpl != null)
        {
            logger.debug(() -> "Publishing statistics through MUC: " + stats);

            ColibriStatsExtension statsExt = Statistics.toXmppExtensionElement(stats);

            if (SignalingApiConfig.Companion.enabled())
            {
                // When advertising the Signaling API, the public address
                // will come from the config, but the supported versions
                // come from the server itself
                String apiUrl = SignalingApiConfig.Companion.publicAddress();
                int apiPort = SignalingApiConfig.Companion.bindPort();
                // BridgeMucDetector expects a ColibriStatsExtension directly (no wrapper type)
                // so for now hack the api stuff into there
                statsExt.addStat("jvb-api-base-url", apiUrl);
                statsExt.addStat("jvb-api-port", apiPort);
                // TODO: how to get the values for version?
                statsExt.addStat("jvb-api-version", "v1");
            }

            clientConnectionImpl.setPresenceExtension(statsExt);
        }
        else
        {
            logger.warn(
                "Can not publish via presence, no ClientConnectionImpl.");
        }
    }
}

