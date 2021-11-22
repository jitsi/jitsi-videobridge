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

import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.signaling.api.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.colibri.*;
import java.util.List;

/**
 * Implements a {@link StatsTransport} which publishes via Presence in an XMPP MUC.
 *
 * @author Boris Grozev
 */
public class MucStatsTransport
    implements StatsTransport
{
    /**
     * The <tt>Logger</tt> used by the <tt>MucStatsTransport</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger = new LoggerImpl(MucStatsTransport.class.getName());

    private final XmppConnection xmppConnection;

    public MucStatsTransport(XmppConnection xmppConnection)
    {
        this.xmppConnection = xmppConnection;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publishStatistics(Statistics stats, long measurementInterval)
    {
        logger.debug(() -> "Publishing statistics through MUC: " + stats);

        ColibriStatsExtension statsExt;

        if (xmppConnection.getConfig().getStatsFilterEnabled())
        {
            List<String> whitelist = xmppConnection.getConfig().getStatsWhitelist();
            logger.debug(() -> "Statistics filter applied: " + whitelist);
            statsExt = Statistics.toXmppExtensionElementFiltered(stats, whitelist);
        }
        else
        {
            statsExt = Statistics.toXmppExtensionElement(stats);
        }

        if (JvbApiConfig.enabled())
        {
//                statsExt.addStat(
//                    "jvb-api-version",
//                    SupportedApiVersionsKt.toPresenceString(ApplicationKt.SUPPORTED_API_VERSIONS)
//                );
        }

        xmppConnection.setPresenceExtension(statsExt);
    }
}

