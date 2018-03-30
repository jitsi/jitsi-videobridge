/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
     * The name of the topic of a "channel created" event.
     */
    public static final String CHANNEL_CREATED_TOPIC
        = "org/jitsi/videobridge/Channel/CREATED";

    /**
     * The name of the topic of a "channel expired" event.
     */
    public static final String CHANNEL_EXPIRED_TOPIC
        = "org/jitsi/videobridge/Channel/EXPIRED";

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
     * The name of the topic of a "content created" event.
     */
    public static final String CONTENT_CREATED_TOPIC
        = "org/jitsi/videobridge/Content/CREATED";

    /**
     * The name of the topic of a "content expired" event.
     */
    public static final String CONTENT_EXPIRED_TOPIC
        = "org/jitsi/videobridge/Content/EXPIRED";

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
     * The name of the topic of a "stream started" event.
     */
    public static final String STREAM_STARTED_TOPIC
        = "org/jitsi/videobridge/Endpoint/STREAM_STARTED";

    /**
     * The name of the topic of a "transport channel created" event.
     */
    public static final String TRANSPORT_CHANNEL_ADDED_TOPIC
        = "org/jitsi/videobridge/IceUdpTransportManager/"
            + "TRANSPORT_CHANNEL_ADDED";

    /**
     * The name of the topic of a "transport channel removed" event.
     */
    public static final String TRANSPORT_CHANNEL_REMOVED_TOPIC
        = "org/jitsi/videobridge/IceUdpTransportManager/" +
        "TRANSPORT_CHANNEL_REMOVED";
    /**
     * The name of the topic of a "transport connected" event.
     */
    public static final String TRANSPORT_CONNECTED_TOPIC
        = "org/jitsi/videobridge/IceUdpTransportManager/"
            + "TRANSPORT_CHANNEL_CONNECTED";

    /**
     * The name of the topic of a "transport created" event.
     */
    public static final String TRANSPORT_CREATED_TOPIC
        = "org/jitsi/videobridge/IceUdpTransportManager/CREATED";

    /**
     * The name of the topic of a "transport state changed" event.
     */
    public static final String TRANSPORT_STATE_CHANGED_TOPIC
        = "org/jitsi/videobridge/IceUdpTransportManager/TRANSPORT_CHANGED";

    /**
     * Creates a new "channel created" <tt>Event</tt>, which indicates the
     * creation of a new COLIBRI channel.
     * @param channel the newly created COLIBRI channel.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event channelCreated(Channel channel)
    {
        return new Event(CHANNEL_CREATED_TOPIC, makeProperties(channel));
    }

    /**
     * Creates a new "channel expired" <tt>Event</tt>, which indicates the
     * expiry of a COLIBRI channel.
     * @param channel the expired COLIBRI channel.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event channelExpired(Channel channel)
    {
        return new Event(CHANNEL_EXPIRED_TOPIC, makeProperties(channel));
    }

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
     * Creates a new "content created" <tt>Event</tt>, which indicates the
     * creation of a new COLIBRI content.
     * @param content the newly created COLIBRI content.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event contentCreated(Content content)
    {
        return new Event(CONTENT_CREATED_TOPIC, makeProperties(content));
    }

    /**
     * Creates a new "content expired" <tt>Event</tt>, which indicates the
     * expiry of a COLIBRI content.
     * @param content the expired COLIBRI content.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event contentExpired(Content content)
    {
        return new Event(CONTENT_EXPIRED_TOPIC, makeProperties(content));
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
     * Creates a new "endpoint display name changed" <tt>Event</tt>, which
     * conference ID to the JID of the associated MUC.
     *
     * @param endpoint the changed endpoint.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event endpointDisplayNameChanged(AbstractEndpoint endpoint)
    {
        return
            new Event(
                    ENDPOINT_DISPLAY_NAME_CHANGED_TOPIC,
                    makeProperties(endpoint));
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

    public static Event streamStarted(RtpChannel rtpChannel)
    {
        return new Event(STREAM_STARTED_TOPIC, makeProperties(rtpChannel));
    }

    /**
     * Creates a new "transport channel added" <tt>Event</tt>, which indicates
     * that a COLIBRI channel was added to a Jitsi Videobridge TransportManager.
     * @param channel the added COLIBRI channel.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event transportChannelAdded(
            Channel channel)
    {
        return
            new Event(
                    TRANSPORT_CHANNEL_ADDED_TOPIC,
                    makeProperties(channel));
    }

    /**
     * Creates a new "transport channel removed" <tt>Event</tt>, which indicates
     * that a COLIBRI channel was removed from a Jitsi Videobridge
     * TransportManager.
     * @param channel the removed COLIBRI channel.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event transportChannelRemoved(Channel channel)
    {
        return
            new Event(
                    TRANSPORT_CHANNEL_REMOVED_TOPIC,
                    makeProperties(channel));
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
            IceUdpTransportManager transportManager)
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
            IceUdpTransportManager transportManager)
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
            IceUdpTransportManager transportManager,
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
