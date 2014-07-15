/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats.transport;

/**
 * Defines a class for events that are received by
 * <tt>StatsTransportListener</tt>s
 *
 * @author Hristo Terezov
 */
public class StatsTransportEvent
{
    public static enum StatTransportEventTypes
    {
        INIT_SUCCESS,
        INIT_FAIL,
        PUBLISH_SUCCESS,
        PUBLISH_FAIL
    }

    private final StatTransportEventTypes eventType;

    /**
     * Constructs new <tt>StatsTransportEvent</tt> instance.
     * @param eventType the type of the event.
     */
    public StatsTransportEvent(StatTransportEventTypes eventType)
    {
        this.eventType = eventType;
    }

    /**
     * Returns the type of the event.
     * @return the type of the event.
     */
    public StatTransportEventTypes getType()
    {
        return eventType;
    }

}
