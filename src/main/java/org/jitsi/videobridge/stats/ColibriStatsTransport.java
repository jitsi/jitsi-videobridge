/*
 * Copyright @ 2015 - Present, 8x8 Inc
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

import java.util.*;

import org.jitsi.osgi.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;
import org.osgi.framework.*;

/**
 * Implements <tt>StatsTransport</tt> for COLIBRI IQ packets.
 *
 * @author Hristo Terezov
 */
public class ColibriStatsTransport
    extends StatsTransport
{
    /**
     * The <tt>Logger</tt> used by the <tt>ColibriStatsTransport</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
        = new LoggerImpl(ColibriStatsTransport.class.getName());

    /**
     * Builds the IQ packet that will be sent.
     * @param statistics the statistics that will be sent
     * @return the packet that will be sent.
     */
    private static IQ buildStatsIQ(Statistics statistics)
    {
        ColibriStatsIQ iq = Statistics.toXmppIq(statistics);
        iq.setType(IQ.Type.result);
        return iq;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publishStatistics(Statistics stats)
    {
        BundleContext bundleContext = getBundleContext();

        if (bundleContext != null)
        {
            Videobridge videobridge
                    = ServiceUtils2.getService(bundleContext, Videobridge.class);
            Collection<ComponentImpl> components
                = videobridge.getComponents();
            IQ statsIQ = null;

            if (!components.isEmpty())
            {
                Conference[] conferences = videobridge.getConferences();

                if (conferences.length != 0)
                {
                    if (statsIQ == null)
                        statsIQ = buildStatsIQ(stats);

                    for (Conference conference : conferences)
                    {
                        Jid focus = conference.getLastKnowFocus();

                        if (focus != null)
                        {
                            statsIQ.setTo(focus);
                            for (ComponentImpl component : components)
                            {
                                try
                                {
                                    component.send(statsIQ);
                                }
                                catch (Exception ex)
                                {
                                    logger.error(
                                            "Failed to publish"
                                                + " statistics.");
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
