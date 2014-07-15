/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats.transport;

import java.util.*;

import org.jitsi.videobridge.stats.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;

/**
 * A class that implements static methods for formating of the
 * <tt>Statistics</tt> object.
 *
 * @author Hristo Terezov
 */
public class StatsFormatter
{
    /**
     * Formats statistics in <tt>ColibriStatsExtension</tt> object
     * @param stats the statistics instance
     * @return the <tt>ColibriStatsExtension</tt> instance.
     */
    public static ColibriStatsExtension format(Statistics stats)
    {
        ColibriStatsExtension statsExt = new ColibriStatsExtension();
        Map<String, Object> statValues = stats.getStats();
        for(String name : statValues.keySet())
            statsExt.addStat(
                new ColibriStatsExtension.Stat(name, statValues.get(name)));
        return statsExt;
    }

}
