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

import com.typesafe.config.*;
import org.jitsi.videobridge.stats.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

/**
 * Given a new-style {@link Config}, create the appropriate
 * {@link org.jitsi.videobridge.stats.StatsTransport}
 */
public class StatsTransportFactory
{
    public static StatsTransport create(Config config)
    {
        //TODO: constants for the prop strings used here (a new property class? only for rnew config?)
        String name = config.getString("name");
        if (StatsManagerBundleActivator.STAT_TRANSPORT_CALLSTATS_IO.equalsIgnoreCase(name))
        {
            return new CallStatsIOTransport();
        }
        else if (StatsManagerBundleActivator.STAT_TRANSPORT_COLIBRI.equalsIgnoreCase(name))
        {
            return new ColibriStatsTransport();
        }
        else if (StatsManagerBundleActivator.STAT_TRANSPORT_MUC.equalsIgnoreCase(name))
        {
            return new MucStatsTransport();
        }
        else if (StatsManagerBundleActivator.STAT_TRANSPORT_PUBSUB.equalsIgnoreCase(name))
        {
            Jid service;
            try
            {
                service = JidCreate.from(config.getString("service"));
            }
            catch (XmppStringprepException e)
            {
                return null;
            }
            String nodeName = config.getString("node");

            return new PubSubStatsTransport(service, nodeName);
        }

        return null;
    }
}