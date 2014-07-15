/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats.transport;

import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.util.*;

import org.jitsi.videobridge.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.stats.transport.StatsTransportEvent.*;
import org.jitsi.videobridge.xmpp.*;
import org.jivesoftware.smack.packet.*;

/**
 * Implements <tt>StatsTransport</tt> for COLIBRI IQ packets.
 *
 * @author Hristo Terezov
 */
public class ColibriStatsTransport
    extends StatsTransport
{
    /**
     * The <tt>Videobridge</tt> instance.
     */
    private Videobridge videobridge;

    /**
     * Creates new <tt>ColibriStatsTransport</tt> instance.
     * @param videobridge the <tt>Videobridge</tt> instance.
     */
    public ColibriStatsTransport(Videobridge videobridge)
    {
        this.videobridge = videobridge;

    }

    @Override
    public void init()
    {
        fireStatsTransportEvent(new StatsTransportEvent(
            StatTransportEventTypes.INIT_SUCCESS));
    }

    @Override
    public void publishStatistics(Statistics stats)
    {
        IQ statsIQ = buildStatsIQ(stats);
        Collection<ComponentImpl> components = videobridge.getComponents();

        for(Conference conference : videobridge.getConferences())
        {
            String focus = conference.getLastKnowFocus();
            if(focus == null)
                continue;
            statsIQ.setTo(focus);
            for(ComponentImpl component : components)
                try
                {
                    component.send(statsIQ);
                }
                catch (Exception e)
                {
                    Logger.getLogger(getClass()).error(
                        "Error sending statistics.");
                }
        }
    }

    /**
     * Builds the IQ packet that will be sent.
     * @param stats the statistics that will be sent
     * @return the packet that will be sent.
     */
    private static IQ buildStatsIQ(Statistics stats)
    {
        final ColibriStatsExtension statsExt = StatsFormatter.format(stats);
        IQ statsIQ = new IQ()
        {

            @Override
            public String getChildElementXML()
            {
                return statsExt.toXML();
            }
        };
        statsIQ.setType(IQ.Type.RESULT);
        return statsIQ;
    }

}
