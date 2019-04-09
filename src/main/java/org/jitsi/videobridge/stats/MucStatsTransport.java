/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.utils.logging.*;

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
        = Logger.getLogger(MucStatsTransport.class);

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
            if (logger.isDebugEnabled())
            {
                logger.debug("Publishing statistics through MUC: " + stats);
            }

            clientConnectionImpl
                .setPresenceExtension(Statistics.toXmppExtensionElement(stats));
        }
        else
        {
            logger.warn(
                "Can not publish via presence, no ClientConnectionImpl.");
        }
    }
}

