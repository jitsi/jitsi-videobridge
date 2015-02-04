/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.eventadmin;

import java.util.*;

/**
 * An event. <tt>Event</tt> objects are delivered to EventHandler services which
 * subscribe to the topic of the event.
 *
 * @author George Politis
 */
public class Event
{
    private final String topic;
    private final Dictionary properties;

    public Event(String topic, Dictionary properties)
    {
        this.topic = topic;
        this.properties = properties;
    }

    public Object getProperty(Object key)
    {
        return this.properties != null ? properties.get(key) : null;
    }

    public String getTopic()
    {
        return topic;
    }
}
