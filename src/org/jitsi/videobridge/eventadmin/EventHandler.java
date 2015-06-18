/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.eventadmin;

/**
 * Listener for Events.
 *
 * @author George Politis
 */
public interface EventHandler
{
    /**
     * Called by the <tt>EventAdmin</tt> service to notify the listener of an
     * event.
     *
     * @param event
     */
    public void handleEvent(Event event);
}
