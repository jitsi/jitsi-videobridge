/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.eventadmin;

/**
 * The Event Admin service. Bundles wishing to publish events must obtain the
 * Event Admin service and call the event delivery method.
 *
 * @author George Politis
 */
public interface EventAdmin
{
    /**
     * Initiate synchronous delivery of an event. This method does not return to
     * the caller until delivery of the event is completed.
     *
     * @param event The event to send to all listeners which subscribe to the
     * topic of the event.
     */
    void sendEvent(Event event);
}
