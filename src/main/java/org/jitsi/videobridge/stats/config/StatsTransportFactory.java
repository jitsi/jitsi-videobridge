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

package org.jitsi.videobridge.stats.config;

import org.jitsi.videobridge.stats.*;
import org.jivesoftware.smackx.pubsub.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

/**
 * Given a {@link StatsTransportConfig}, create the appropriate
 * {@link org.jitsi.videobridge.stats.StatsTransport}
 */
public class StatsTransportFactory
{
    public static StatsTransport create(StatsTransportConfig config)
    {
        if (StatsManagerBundleActivator.STAT_TRANSPORT_CALLSTATS_IO.equalsIgnoreCase(config.name))
        {
            return new CallStatsIOTransport();
        }
        else if (StatsManagerBundleActivator.STAT_TRANSPORT_COLIBRI.equalsIgnoreCase(config.name))
        {
            return new ColibriStatsTransport();
        }
        else if (StatsManagerBundleActivator.STAT_TRANSPORT_MUC.equalsIgnoreCase(config.name))
        {
            return new MucStatsTransport();
        } else if (StatsManagerBundleActivator.STAT_TRANSPORT_PUBSUB.equalsIgnoreCase(config.getName()))
        {
            PubSubStatsTransportConfig pubSubStatsTransportConfig =
                    (PubSubStatsTransportConfig)config;
            Jid service;
            try
            {
                service = JidCreate.from(pubSubStatsTransportConfig.serviceName);
            }
            catch (XmppStringprepException e)
            {
                return null;
            }

            return new PubSubStatsTransport(service, pubSubStatsTransportConfig.nodeName);
        }

        return null;
    }
}
