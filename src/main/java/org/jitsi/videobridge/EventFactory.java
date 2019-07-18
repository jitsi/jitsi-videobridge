/*
 * Copyright @ 2015 - Present, 8x8 Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge;

import java.util.*;

import org.ice4j.ice.*;
import org.jitsi.eventadmin.*;

/**
 * A utility class with static methods which initialize <tt>Event</tt> instances
 * with pre-determined fields.
 *
 * @author Boris Grozev
 * @author George Politis
 */
public class EventFactory
    extends AbstractEventFactory
{
    /**
     * The name of the topic of a "conference created" event.
     */
    public static final String CONFERENCE_CREATED_TOPIC
        = "org/jitsi/videobridge/Conference/CREATED";

    /**
     * The name of the topic of a "conference expired" event.
     */
    public static final String CONFERENCE_EXPIRED_TOPIC
        = "org/jitsi/videobridge/Conference/EXPIRED";

    /**
     * The name of the topic of a "endpoint created" event.
     */
    public static final String ENDPOINT_CREATED_TOPIC
        = "org/jitsi/videobridge/Endpoint/CREATED";

    /**
     * The name of the topic of a "message transport ready" event triggered on
     * an endpoint instance when it's message transport connection is ready for
     * sending/receiving data.
     */
    public static final String MSG_TRANSPORT_READY_TOPIC
        = "org/jitsi/videobridge/Endpoint/MSG_TRANSPORT_READY_TOPIC";

    /**
     * The name of the topic of a "transport connected" event.
     */
    public static final String TRANSPORT_CONNECTED_TOPIC
        = "org/jitsi/videobridge/IceTransport/"
            + "TRANSPORT_CHANNEL_CONNECTED";

    /**
     * The name of the topic of a "transport created" event.
     */
    public static final String TRANSPORT_CREATED_TOPIC
        = "org/jitsi/videobridge/IceTransport/CREATED";

    /**
     * The name of the topic of a "transport state changed" event.
     */
    public static final String TRANSPORT_STATE_CHANGED_TOPIC
        = "org/jitsi/videobridge/IceTransport/TRANSPORT_CHANGED";

    /**
     * Creates a new "conference created" <tt>Event</tt>, which indicates the
     * creation of a new COLIBRI conference.
     * @param conference the newly created COLIBRI conference.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event conferenceCreated(Conference conference)
    {
        return new Event(CONFERENCE_CREATED_TOPIC, makeProperties(conference));
    }

    /**
     * Creates a new "conference expired" <tt>Event</tt>, which indicates the
     * expiry of a COLIBRI conference.
     * @param conference the expired COLIBRI conference.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event conferenceExpired(Conference conference)
    {
        return new Event(CONFERENCE_EXPIRED_TOPIC, makeProperties(conference));
    }

    /**
     * Creates a new "endpoint created" <tt>Event</tt>, which indicates that
     * a COLIBRI endpoint was created.
     * @param endpoint the newly created endpoint.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event endpointCreated(AbstractEndpoint endpoint)
    {
        return new Event(ENDPOINT_CREATED_TOPIC, makeProperties(endpoint));
    }

    /**
     * Creates a new "message transport ready" <tt>Event</tt>, which means that
     * the endpoint passed in {@link #EVENT_SOURCE} property has now it's
     * message transport connection established and ready for sending/receiving
     * data.
     *
     * @param endpoint the endpoint for which the message transport is now ready
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event endpointMessageTransportReady(AbstractEndpoint endpoint)
    {
        Dictionary<String, Object> properties = new Hashtable<>(1);

        properties.put(EVENT_SOURCE, endpoint);
        return new Event(MSG_TRANSPORT_READY_TOPIC, properties);
    }

    /**
     * Creates a new "transport connected" <tt>Event</tt>, which indicates
     * that a Jitsi Videobridge TransportManager has changed its state to
     * connected.
     * @param transportManager the connected transport manager object.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event transportConnected(
            IceTransport transportManager)
    {
        return
            new Event(
                    TRANSPORT_CONNECTED_TOPIC,
                    makeProperties(transportManager));
    }

    /**
     * Creates a new "transport created" <tt>Event</tt>, which indicates the
     * creation of a new Jitsi Videobridge TransportManager.
     * @param transportManager the newly created transport manager object.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event transportCreated(
            IceTransport transportManager)
    {
        return
            new Event(
                    TRANSPORT_CREATED_TOPIC,
                    makeProperties(transportManager));
    }

    /**
     * Creates a new "transport manager state changed" <tt>Event</tt>, which
     * indicates that a Jitsi Videobridge TransportManager has changed its
     * state.
     * @param transportManager the changed transport manager object
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event transportStateChanged(
            IceTransport transportManager,
            IceProcessingState oldState,
            IceProcessingState newState)
    {
        Dictionary<String, Object> properties = new Hashtable<>(3);

        properties.put(EVENT_SOURCE, transportManager);
        properties.put("oldState", oldState);
        properties.put("newState", newState);
        return new Event(TRANSPORT_STATE_CHANGED_TOPIC, properties);
    }
}
