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
package org.jitsi.videobridge.eventadmin.influxdb;

import org.ice4j.ice.*;
import org.jitsi.influxdb.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.eventadmin.*;

/**
 * Allows logging of {@link InfluxDBEvent}s using an
 * <tt>InfluxDB</tt> instance.
 *
 * @author Boris Grozev
 * @author George Politis
 */
public class LoggingHandler
    extends AbstractLoggingHandler
{
    /**
     * The names of the columns of a "conference created" event.
     */
    private static final String[] CONFERENCE_CREATED_COLUMNS
        = new String[] {"conference_id", "focus"};

    /**
     * The names of the columns of a "conference expired" event.
     */
    private static final String[] CONFERENCE_EXPIRED_COLUMNS
        = new String[] {"conference_id"};

    /**
     * The names of the columns of a "content created" event.
     */
    private static final String[] CONTENT_CREATED_COLUMNS
        = new String[] {"name", "conference_id"};

    /**
     * The names of the columns of a "content expired" event.
     */
    private static final String[] CONTENT_EXPIRED_COLUMNS
        = new String[] {"name", "conference_id"};

    /**
     * The names of the columns of a "channel created" event.
     */
    private static final String[] CHANNEL_CREATED_COLUMNS
        = new String[]
        {
            "channel_id",
            "content_name",
            "conference_id",
            "endpoint_id",
            "lastn"
        };

    /**
     * The names of the columns of a "rtp channel expired" event.
     */
    private static final String[] RTP_CHANNEL_EXPIRED_COLUMNS
        = new String[]
        {
            "channel_id",
            "content_name",
            "conference_id",
            "endpoint_id",

            "stats.local_ip",
            "stats.local_port",
            "stats.remote_ip",
            "stats.remote_port",

            "stats.nb_received_bytes",
            "stats.nb_sent_bytes",

            "stats.nb_received_packets",
            "stats.nb_received_packets_lost",

            "stats.nb_sent_packets",
            "stats.nb_sent_packets_lost",

            "stats.min_download_jitter_ms",
            "stats.max_download_jitter_ms",
            "stats.avg_download_jitter_ms",

            "stats.min_upload_jitter_ms",
            "stats.max_upload_jitter_ms",
            "stats.avg_upload_jitter_ms"
        };

    /**
     * The names of the columns of a "channel expired" event.
     */
    private static final String[] CHANNEL_EXPIRED_COLUMNS
        = new String[]
        {
            "channel_id",
            "content_name",
            "conference_id",
            "endpoint_id"
        };

    /**
     * The names of the columns of a "transport created" event.
     */
    private static final String[] TRANSPORT_CREATED_COLUMNS
        = new String[]
        {
            "hash_code",
            "conference_id",
            "num_components",
            "ufrag",
            "is_controlling"
        };

    /**
     * The names of the columns of a "transport manager channel added" event.
     */
    private static final String[] TRANSPORT_CHANNEL_ADDED_COLUMNS
        = new String[]
        {
            "hash_code",
            "conference_id",
            "channel_id",
        };

    /**
     * The names of the columns of a "transport manager channel removed" event.
     */
    private static final String[] TRANSPORT_CHANNEL_REMOVED_COLUMNS
        = new String[]
        {
            "hash_code",
            "conference_id",
            "channel_id",
        };

    /**
     * The names of the columns of a "transport manager connected" event.
     */
    private static final String[] TRANSPORT_CONNECTED_COLUMNS
        = new String[]
        {
            "hash_code",
            "conference_id",
            "selected_pairs"
        };

    /**
     * The names of the columns of a "transport manager connected" event.
     */
    private static final String[] TRANSPORT_STATE_CHANGED_COLUMNS
        = new String[]
        {
            "hash_code",
            "conference_id",
            "old_state",
            "new_state"
        };

    /**
     * The names of the columns of an "endpoint created" event.
     */
    private static final String[] ENDPOINT_CREATED_COLUMNS
        = new String[]
        {
            "conference_id",
            "endpoint_id",
        };

    /**
     * The names of the columns of an "endpoint display name" event.
     */
    private static final String[] ENDPOINT_DISPLAY_NAME_COLUMNS
        = new String[]
        {
            "conference_id",
            "endpoint_id",
            "display_name"
        };

    /**
     * The <tt>Logger</tt> used by the <tt>LoggingHandler</tt> class
     * and its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(LoggingHandler.class);

    /**
     * Initializes a new <tt>LoggingHandler</tt> instance, by reading
     * its configuration from <tt>cfg</tt>.
     * @param cfg the <tt>ConfigurationService</tt> to use.
     *
     * @throws Exception if initialization fails
     */
    public LoggingHandler(ConfigurationService cfg)
        throws Exception
    {
        super(cfg);
    }

    /**
     *
     * @param conference
     */
    private void conferenceCreated(Conference conference)
    {
        if (conference == null)
        {
            logger.debug("Could not log conference created event because " +
                "the conference is null.");
            return;
        }

        String focus = conference.getFocus();

        logEvent(new InfluxDBEvent("conference_created",
            CONFERENCE_CREATED_COLUMNS,
            new Object[]{
                conference.getID(),
                focus != null ? focus : "null"
            }));
    }

    /**
     *
     * @param conference
     */
    private void conferenceExpired(Conference conference)
    {
        if (conference == null)
        {
            logger.debug("Could not log conference expired event because " +
                "the conference is null.");
            return;
        }

        logEvent(new InfluxDBEvent("conference_expired",
            CONFERENCE_EXPIRED_COLUMNS,
            new Object[]{
                conference.getID()
            }));
    }

    /**
     *
     * @param endpoint
     */
    private void endpointCreated(Endpoint endpoint)
    {
        if (endpoint == null)
        {
            logger.debug("Could not log endpoint created event because " +
                "the endpoint is null.");
            return;
        }

        Conference conference = endpoint.getConference();
        if (conference.isExpired())
        {
            logger.debug("Could not log endpoint created event because " +
                "the conference has expired.");
            return;
        }

        logEvent(new InfluxDBEvent("endpoint_created",
            ENDPOINT_CREATED_COLUMNS,
            new Object[]{
                conference.getID(),
                endpoint.getID()
            }));
    }

    /**
     *
     * @param endpoint
     */
    private void endpointDisplayNameChanged(Endpoint endpoint)
    {
        if (endpoint == null)
        {
            logger.debug("Could not log endpoint display name changed" +
                " event because the endpoint is null.");
            return;
        }

        Conference conference = endpoint.getConference();
        if (conference.isExpired())
        {
            logger.debug("Could not log endpoint display name changed " +
                " event because the conference has expired.");
            return;
        }

        logEvent(new InfluxDBEvent("endpoint_display_name",
            ENDPOINT_DISPLAY_NAME_COLUMNS,
            new Object[]{
                conference.getID(),
                endpoint.getID(),
                endpoint.getDisplayName()
            }));
    }

    /**
     *
     * @param content
     */
    private void contentCreated(Content content)
    {
        if (content == null)
        {
            logger.debug("Could not log content created event because " +
                "the content is null.");
            return;
        }

        Conference conference = content.getConference();
        if (conference == null)
        {
            logger.debug("Could not log content created event because " +
                "the conference is null.");
            return;
        }

        logEvent(new InfluxDBEvent("content_created",
            CONTENT_CREATED_COLUMNS,
            new Object[] {
                content.getName(),
                conference.getID()
            }));
    }

    /**
     *
     * @param content
     */
    private void contentExpired(Content content)
    {
        if (content == null)
        {
            logger.debug("Could not log content expired event because " +
                "the content is null.");
            return;
        }

        Conference conference = content.getConference();
        if (conference == null)
        {
            logger.debug("Could not log content expired event because " +
                "the conference is null.");
            return;
        }

        logEvent(new InfluxDBEvent("content_expired",
            CONTENT_EXPIRED_COLUMNS,
            new Object[] {
                content.getName(),
                conference.getID()
            }));
    }

    private void transportChannelAdded(Channel channel)
    {
        TransportManager transportManager = channel.getTransportManager();
        if (transportManager == null)
        {
            logger.error("Could not log the transport channel added event: " +
                "the transport manager is null.");
            return;
        }

        Content content = channel.getContent();
        if (content == null)
        {
            logger.debug("Could not log the transport channel added event " +
                "because the content is null.");
            return;
        }

        Conference conference = content.getConference();
        if (conference == null)
        {
            logger.debug("Could not log the transport channel added event " +
                "because the conference is null.");
            return;
        }

        logEvent(new InfluxDBEvent("transport_channel_added",
            TRANSPORT_CHANNEL_ADDED_COLUMNS,
            new Object[]{
                String.valueOf(transportManager.hashCode()),
                conference.getID(),
                channel.getID()
            }));
    }

    /**
     *
     * @param channel
     */
    private void transportChannelRemoved(Channel channel)
    {
        TransportManager transportManager = channel.getTransportManager();
        if (transportManager == null)
        {
            logger.error("Could not log the transport channel removed event: " +
                "the transport manager is null.");
            return;
        }

        Content content = channel.getContent();
        if (content == null)
        {
            logger.debug("Could not log the transport channel removed event " +
                "because the content is null.");
            return;
        }

        Conference conference = content.getConference();
        if (conference == null)
        {
            logger.debug("Could not log the transport channel removed event " +
                "because the conference is null.");
            return;
        }

        logEvent(new InfluxDBEvent("transport_channel_removed",
            TRANSPORT_CHANNEL_REMOVED_COLUMNS,
            new Object[] {
                String.valueOf(transportManager.hashCode()),
                conference.getID(),
                channel.getID()
            }));
    }

    /**
     *
     * @param transportManager
     * @param oldState
     * @param newState
     */
    private void transportStateChanged(
        IceUdpTransportManager transportManager,
        IceProcessingState oldState,
        IceProcessingState newState)
    {
        Conference conference = transportManager.getConference();
        if (conference == null)
        {
            logger.debug("Could not log the transport state changed event " +
                "because the conference is null.");
            return;
        }

        logEvent(new InfluxDBEvent("transport_state_changed",
            TRANSPORT_STATE_CHANGED_COLUMNS,
            new Object[]{
                String.valueOf(transportManager.hashCode()),
                conference.getID(),
                oldState == null ? "null" : oldState.toString(),
                newState == null ? "null" : newState.toString()
            }));
    }

    /**
     *
     * @param transportManager
     */
    private void transportCreated(IceUdpTransportManager transportManager)
    {
        Conference conference = transportManager.getConference();
        if (conference == null)
        {
            logger.debug(
                    "Could not log the transport_created event"
                        + " because the conference is null.");
            return;
        }

        logEvent(
                new InfluxDBEvent(
                        "transport_created",
                        TRANSPORT_CREATED_COLUMNS,
                        new Object[]
                        {
                            String.valueOf(transportManager.hashCode()),
                            conference.getID(),
                            transportManager.getNumComponents(),
                            transportManager.getLocalUfrag(),
                            Boolean
                                .valueOf(transportManager.isControlling())
                                    .toString()
                        }));
    }

    /**
     *
     * @param transportManager
     */
    private void transportConnected(IceUdpTransportManager transportManager)
    {
        Conference conference = transportManager.getConference();
        if (conference == null)
        {
            logger.debug("Could not log the transport connected event " +
                "because the conference is null.");
            return;
        }

        IceMediaStream iceStream = transportManager.getIceStream();
        if (iceStream == null)
        {
            logger.debug("Could not log the transport connected event " +
                "because the iceStream is null.");
            return;
        }

        StringBuilder s = new StringBuilder();
        for (Component component : iceStream.getComponents())
        {
            CandidatePair pair = component.getSelectedPair();

            if (pair == null)
                continue;

            Candidate<?> localCandidate = pair.getLocalCandidate();
            Candidate<?> remoteCandidate = pair.getRemoteCandidate();

            s.append((localCandidate == null)
                         ? "unknown" : localCandidate.getTransportAddress())
                .append(" -> ")
                .append((remoteCandidate == null)
                        ? "unknown" : remoteCandidate.getTransportAddress())
                .append("; ");
        }

        logEvent(new InfluxDBEvent("transport_connected",
            TRANSPORT_CONNECTED_COLUMNS,
            new Object[]{
                String.valueOf(transportManager.hashCode()),
                conference.getID(),
                s.toString()
            }));
    }

    /**
     *
     * @param channel
     */
    private void channelCreated(Channel channel)
    {
        if (channel == null)
        {
            logger.debug("Could not log the channel created event " +
                "because the channel is null.");
            return;
        }
        Content content = channel.getContent();
        if (content == null)
        {
            logger.debug("Could not log the channel created event " +
                "because the content is null.");
            return;
        }

        Conference conference = content.getConference();
        if (conference == null)
        {
            logger.debug("Could not log the channel created event " +
                "because the conference is null.");
            return;
        }

        String endpointID = "";
        Endpoint endpoint = channel.getEndpoint();
        if (endpoint != null)
        {
            endpointID = endpoint.getID();
        }

        int lastN = -1;
        if (channel instanceof VideoChannel)
        {
            lastN = ((VideoChannel)channel).getLastN();
        }

        logEvent(new InfluxDBEvent("channel_created",
            CHANNEL_CREATED_COLUMNS,
            new Object[] {
                channel.getID(),
                content.getName(),
                conference.getID(),
                endpointID,
                lastN
            }));
    }

    /**
     *
     * @param channel
     */
    private void channelExpired(Channel channel)
    {
        if (channel == null)
        {
            logger.debug("Could not log the channel expired event " +
                "because the channel is null.");
            return;
        }

        Content content = channel.getContent();
        if (content == null)
        {
            logger.debug("Could not log the channel expired event " +
                "because the content is null.");
            return;
        }

        Conference conference = content.getConference();
        if (conference == null)
        {
            logger.debug("Could not log the channel expired event " +
                "because the conference is null.");
            return;
        }

        MediaStreamStats stats = null;
        if (channel instanceof RtpChannel)
        {
            RtpChannel rtpChannel = (RtpChannel) channel;
            MediaStream stream = rtpChannel.getStream();
            if (stream != null)
            {
                stats = stream.getMediaStreamStats();
            }
        }

        Endpoint endpoint = channel.getEndpoint();
        String endpointID = endpoint == null ? "" : endpoint.getID();

        if (stats != null)
        {
            Object[] values = new Object[] {
                channel.getID(),
                content.getName(),
                conference.getID(),
                endpointID,

                stats.getLocalIPAddress(),
                stats.getLocalPort(),
                stats.getRemoteIPAddress(),
                stats.getRemotePort(),

                stats.getNbReceivedBytes(),
                stats.getNbSentBytes(),

                // Number of packets sent from the other side
                stats.getNbPacketsReceived() + stats.getDownloadNbPacketLost(),
                stats.getDownloadNbPacketLost(),

                stats.getNbPacketsSent(),
                stats.getUploadNbPacketLost(),

                stats.getMinDownloadJitterMs(),
                stats.getMaxDownloadJitterMs(),
                stats.getAvgDownloadJitterMs(),

                stats.getMinUploadJitterMs(),
                stats.getMaxUploadJitterMs(),
                stats.getAvgUploadJitterMs()
            };

            logEvent(new InfluxDBEvent(
                "channel_expired", RTP_CHANNEL_EXPIRED_COLUMNS, values));
        }
        else
        {
            Object[] values = new Object[] {
                channel.getID(),
                content.getName(),
                conference.getID(),
                endpointID
            };

            logEvent(new InfluxDBEvent(
                "channel_expired", CHANNEL_EXPIRED_COLUMNS, values));
        }
    }

    @Override
    public void handleEvent(Event event)
    {
        if (event == null)
        {
            logger.debug("Could not handle the event because it was null.");
            return;
        }

        String topic = event.getTopic();
        if (EventFactory.CHANNEL_CREATED_TOPIC.equals(topic))
        {
            Channel channel
                = (Channel) event.getProperty(EventFactory.EVENT_SOURCE);
            channelCreated(channel);
        }
        else if (EventFactory.CHANNEL_EXPIRED_TOPIC.equals(topic))
        {
            Channel channel
                = (Channel) event.getProperty(EventFactory.EVENT_SOURCE);
            channelExpired(channel);
        }
        else if (
            EventFactory.CONFERENCE_CREATED_TOPIC.equals(topic))
        {
            Conference conference
                = (Conference) event.getProperty(EventFactory.EVENT_SOURCE);
            conferenceCreated(conference);
        }
        else if (EventFactory.CONFERENCE_EXPIRED_TOPIC.equals(topic))
        {
            Conference conference
                = (Conference) event.getProperty(EventFactory.EVENT_SOURCE);
            conferenceExpired(conference);
        }
        else if (EventFactory.CONTENT_CREATED_TOPIC.equals(topic))
        {
            Content content
                = (Content) event.getProperty(EventFactory.EVENT_SOURCE);
            contentCreated(content);
        }
        else if (EventFactory.CONTENT_EXPIRED_TOPIC.equals(topic))
        {
            Content content
                = (Content) event.getProperty(EventFactory.EVENT_SOURCE);
            contentExpired(content);
        }
        else if (EventFactory.ENDPOINT_CREATED_TOPIC.equals(topic))
        {
            Endpoint endpoint
                = (Endpoint) event.getProperty(EventFactory.EVENT_SOURCE);
            endpointCreated(endpoint);
        }
        else if (EventFactory.ENDPOINT_DISPLAY_NAME_CHANGED_TOPIC.equals(topic))
        {
            Endpoint endpoint
                = (Endpoint) event.getProperty(EventFactory.EVENT_SOURCE);
            endpointDisplayNameChanged(endpoint);
        }
        else if (EventFactory.TRANSPORT_CHANNEL_ADDED_TOPIC.equals(topic))
        {
            Channel channel
                = (Channel) event.getProperty(EventFactory.EVENT_SOURCE);
            transportChannelAdded(channel);
        }
        else if (EventFactory.TRANSPORT_CHANNEL_REMOVED_TOPIC.equals(topic))
        {
            Channel channel
                = (Channel) event.getProperty(EventFactory.EVENT_SOURCE);
            transportChannelRemoved(channel);
        }
        else if (EventFactory.TRANSPORT_CONNECTED_TOPIC.equals(topic))
        {
            IceUdpTransportManager transportManager
                = (IceUdpTransportManager) event.getProperty(
                EventFactory.EVENT_SOURCE);

            transportConnected(transportManager);
        }
        else if (EventFactory.TRANSPORT_CREATED_TOPIC.equals(topic))
        {
            IceUdpTransportManager transportManager
                = (IceUdpTransportManager) event.getProperty(
                EventFactory.EVENT_SOURCE);
            transportCreated(transportManager);
        }
        else if (EventFactory.TRANSPORT_STATE_CHANGED_TOPIC.equals(topic))
        {
            IceUdpTransportManager transportManager
                = (IceUdpTransportManager) event.getProperty(
                EventFactory.EVENT_SOURCE);

            IceProcessingState oldState
                = (IceProcessingState) event.getProperty("oldState");

            IceProcessingState newState
                = (IceProcessingState) event.getProperty("newState");

            transportStateChanged(transportManager, oldState, newState);
        }
    }
}
