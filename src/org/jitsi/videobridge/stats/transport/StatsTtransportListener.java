/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats.transport;

/**
 * Interface for classes that will listen for <tt>StatsTransportEvent</tt>s
 * @author Hristo Terezov
 */
public interface StatsTtransportListener
{
    /**
     * Notifies the listener about event
     * @param event the event.
     */
    public void onStatsTransportEvent(StatsTransportEvent event);

}
