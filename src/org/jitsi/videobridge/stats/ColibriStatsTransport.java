/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats;

import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.util.*;

import org.jitsi.videobridge.*;
import org.jitsi.videobridge.xmpp.*;
import org.jivesoftware.smack.packet.*;
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
        = Logger.getLogger(ColibriStatsTransport.class);

    /**
     * Builds the IQ packet that will be sent.
     * @param statistics the statistics that will be sent
     * @return the packet that will be sent.
     */
    private static IQ buildStatsIQ(Statistics statistics)
    {
        final ColibriStatsExtension ext = Statistics.toXMPP(statistics);
        IQ iq
            = new IQ()
            {
                @Override
                public String getChildElementXML()
                {
                    return ext.toXML();
                }
            };

        iq.setType(IQ.Type.RESULT);
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
            Collection<Videobridge> videobridges
                = Videobridge.getVideobridges(bundleContext);
            IQ statsIQ = null;

            for (Videobridge videobridge : videobridges)
            {
                Collection<ComponentImpl> components
                    = videobridge.getComponents();

                if (!components.isEmpty())
                {
                    Conference[] conferences = videobridge.getConferences();

                    if (conferences.length != 0)
                    {
                        if (statsIQ == null)
                            statsIQ = buildStatsIQ(stats);

                        for (Conference conference : conferences)
                        {
                            String focus = conference.getLastKnowFocus();

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
}
