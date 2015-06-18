/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats;

/**
 * Defines an interface for classes that will send statistics.
 *
 * @author Hristo Terezov
 * @author Lyubomir Marinov
 */
public abstract class StatsTransport
    extends BundleContextHolder2
{
    /**
     * Publishes a specific (set of) <tt>Statistics</tt> through this
     * <tt>StatsTransport</tt>.
     *
     * @param statistics the <tt>Statistics</tt> to be published through this
     * <tt>StatsTransport</tt>
     */
    public abstract void publishStatistics(Statistics statistics);
}
