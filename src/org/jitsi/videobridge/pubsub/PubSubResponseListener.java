/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.pubsub;

import org.jivesoftware.smack.packet.*;

/**
 * Interface for listener of PubSub responses.
 *
 * @author Hristo Terezov
 * @author Lyubomir Marinov
 */
public interface PubSubResponseListener
{
    /**
     * Enum for responses.
     */
    public static enum Response
    {
        SUCCESS,
        FAIL
    };

    /**
     * The method is called when response for node creation is received
     * @param response the type of the response.
     */
    public void onCreateNodeResponse(Response response);

    /**
     * The method is called when response for publish is received
     *
     * @param type the type of the response.
     * @param iq the response <tt>IQ</tt>
     */
    public void onPublishResponse(Response type, IQ iq);
}
