/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.influxdb;

import org.ice4j.ice.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.eventadmin.*;
import org.json.simple.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Allows logging of {@link InfluxDBEvent}s using an
 * <tt>InfluxDB</tt> instance.
 *
 * @author Boris Grozev
 * @author George Politis
 */
public class InfluxDBLoggingHandler
        implements EventHandler
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
        = CONTENT_CREATED_COLUMNS;

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
     * The names of the columns of a "channel expired" event.
     */
    private static final String[] CHANNEL_EXPIRED_COLUMNS
        = new String[]
        {
            "channel_id",
            "content_name",
            "conference_id",

            "local_ip",
            "local_port",
            "remote_ip",
            "remote_port",

            "nb_received_bytes",
            "nb_sent_bytes",
            "nb_discarded",
            "nb_discarded_full",
            "nb_discarded_late",
            "nb_discarded_reset",
            "nb_discarded_shrink",
            "nb_fec",

            "nb_packets",
            "nb_packets_lost",
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
        = TRANSPORT_CHANNEL_ADDED_COLUMNS;

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
     * The names of the columns of a "focus created" event.
     */
    private static final String[] FOCUS_CREATED_COLUMNS
        = new String[]
        {
            "room_jid"
        };

    /**
     * The names of the columns of a "conference room" event.
     */
    private static final String[] CONFERENCE_ROOM_COLUMNS
        = new String[]
        {
            "conference_id",
            "room_jid"
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
     * The name of the property which specifies whether logging to an
     * <tt>InfluxDB</tt> is enabled.
     */
    public static final String ENABLED_PNAME
        = "org.jitsi.videobridge.log.INFLUX_DB_ENABLED";

    /**
     * The name of the property which specifies the protocol, hostname and
     * port number (in URL format) to use to connect to <tt>InfluxDB</tt>.
     */
    public static final String URL_BASE_PNAME
        = "org.jitsi.videobridge.log.INFLUX_URL_BASE";

    /**
     * The name of the property which specifies the name of the
     * <tt>InfluxDB</tt> database.
     */
    public static final String DATABASE_PNAME
        = "org.jitsi.videobridge.log.INFLUX_DATABASE";

    /**
     * The name of the property which specifies the username to use to connect
     * to <tt>InfluxDB</tt>.
     */
    public static final String USER_PNAME
        = "org.jitsi.videobridge.log.INFLUX_USER";

    /**
     * The name of the property which specifies the password to use to connect
     * to <tt>InfluxDB</tt>.
     */
    public static final String PASS_PNAME
        = "org.jitsi.videobridge.log.INFLUX_PASS";

    /**
     * The <tt>Logger</tt> used by the <tt>InfluxDBLoggingService</tt> class
     * and its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(InfluxDBLoggingHandler.class);

    /**
     * The <tt>Executor</tt> which is to perform the task of sending data to
     * <tt>InfluxDB</tt>.
     */
    private final Executor executor
        = ExecutorUtils
        .newCachedThreadPool(true, InfluxDBLoggingHandler.class.getName());

    /**
     * The <tt>URL</tt> to be used to POST to <tt>InfluxDB</tt>. Besides the
     * protocol, host and port also encodes the database name, user name and
     * password.
     */
    private final URL url;

    /**
     * Initializes a new <tt>InfluxDBLoggingService</tt> instance, by reading
     * its configuration from <tt>cfg</tt>.
     * @param cfg the <tt>ConfigurationService</tt> to use.
     *
     * @throws Exception if initialization fails
     */
    InfluxDBLoggingHandler(ConfigurationService cfg)
        throws Exception
    {
        if (cfg == null)
            throw new NullPointerException("cfg");

        String s = "Required property not set: ";
        String urlBase = cfg.getString(URL_BASE_PNAME, null);
        if (urlBase == null)
            throw new Exception(s + URL_BASE_PNAME);

        String database = cfg.getString(DATABASE_PNAME, null);
        if (database == null)
            throw new Exception(s + DATABASE_PNAME);

        String user = cfg.getString(USER_PNAME, null);
        if (user == null)
            throw new Exception(s + USER_PNAME);

        String pass = cfg.getString(PASS_PNAME, null);
        if (pass == null)
            throw new Exception(s + PASS_PNAME);

        String urlStr
            = urlBase +  "/db/" + database + "/series?u=" + user +"&p=" +pass;

        url = new URL(urlStr);

        logger.info("Initialized InfluxDBLoggingService for " + urlBase
            + ", database \"" + database + "\"");
    }

    /**
     * Logs an <tt>Event</tt> to an <tt>InfluxDB</tt> database. This method
     * returns without blocking, the blocking operations are performed by a
     * thread from {@link #executor}.
     *
     * @param e the <tt>Event</tt> to log.
     */
    @SuppressWarnings("unchecked")
    private void logEvent(InfluxDBEvent e)
    {
        // The following is a sample JSON message in the format used by InfluxDB
        //  [
        //    {
        //     "name": "series_name",
        //     "columns": ["column1", "column2"],
        //     "points": [
        //           ["value1", 1234],
        //           ["value2", 5678]
        //          ]
        //    }
        //  ]

        boolean useLocalTime = e.useLocalTime();
        long now = System.currentTimeMillis();
        boolean multipoint = false;
        int pointCount = 1;
        JSONArray columns = new JSONArray();
        JSONArray points = new JSONArray();
        Object[] values = e.getValues();

        if (useLocalTime)
            columns.add("time");
        Collections.addAll(columns, e.getColumns());

        if (values[0] instanceof Object[])
        {
            multipoint = true;
            pointCount = values.length;
        }

        if (multipoint)
        {
            for (int i = 0; i < pointCount; i++)
            {
                JSONArray point = new JSONArray();
                if (useLocalTime)
                    point.add(now);
                Collections.addAll(point, values[i]);
                points.add(point);
            }
        }
        else
        {
            JSONArray point = new JSONArray();
            if (useLocalTime)
                point.add(System.currentTimeMillis());
            // Make sure points.length>0
            Collections.addAll(point, values);
            points.add(point);
        }

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("name", e.getName());
        jsonObject.put("columns", columns);
        jsonObject.put("points", points);

        JSONArray jsonArray = new JSONArray();
        jsonArray.add(jsonObject);

        // TODO: this is probably a good place to optimize by grouping multiple
        // events in a single POST message and/or multiple points for events
        // of the same type together).
        final String jsonString = jsonArray.toJSONString();
        executor.execute(new Runnable()
        {
            @Override
            public void run()
            {
                sendPost(jsonString);
            }
        });
    }

    /**
     * Sends the string <tt>s</tt> as the contents of an HTTP POST request to
     * {@link #url}.
     * @param s the content of the POST request.
     */
    private void sendPost(final String s)
    {
        try
        {
            HttpURLConnection connection
                = (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-type",
                "application/json");

            connection.setDoOutput(true);
            DataOutputStream outputStream
                = new DataOutputStream(connection.getOutputStream());
            outputStream.writeBytes(s);
            outputStream.flush();
            outputStream.close();

            int responseCode = connection.getResponseCode();
            if (responseCode != 200)
                throw new IOException("HTTP response code: "
                    + responseCode);
        }
        catch (IOException ioe)
        {
            logger.info("Failed to post to influxdb: " + ioe);
        }
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
        if (conference == null)
        {
            logger.debug("Could not log endpoint created event because " +
                "the conference is null.");
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
        if (conference == null)
        {
            logger.debug("Could not log endpoint display name changed " +
                " event because the conference is null.");
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
        TransportManager transportManager;
        try
        {
            transportManager = channel.getTransportManager();
        }
        catch (IOException e)
        {
            logger.error("Could not log the transport channel added event " +
                "because of an error.", e);
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
        TransportManager transportManager;
        try
        {
            transportManager = channel.getTransportManager();
        }
        catch (IOException e)
        {
            logger.error("Could not log the transport channel removed event " +
                "because of an error.", e);
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
            logger.debug("Could not log the transport created event " +
                "because the conference is null.");
            return;
        }

        Agent agent = transportManager.getAgent();
        if (agent == null)
        {
            logger.debug("Could not log the transport created event " +
                "because the agent is null.");
            return;
        }

        logEvent(new InfluxDBEvent("transport_created",
            TRANSPORT_CREATED_COLUMNS,
            new Object[]{
                String.valueOf(transportManager.hashCode()),
                conference.getID(),
                transportManager.getNumComponents(),
                agent.getLocalUfrag(),
                Boolean.valueOf(transportManager.isControlling()).toString()
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
            s.append(pair.getLocalCandidate().getTransportAddress())
                .append(" -> ")
                .append(pair.getRemoteCandidate().getTransportAddress())
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

        Object[] values;
        if (stats != null)
        {
            values = new Object[] {
                channel.getID(),
                content.getName(),
                conference.getID(),

                stats.getLocalIPAddress(),
                stats.getLocalPort(),
                stats.getRemoteIPAddress(),
                stats.getRemotePort(),

                stats.getNbReceivedBytes(),
                stats.getNbSentBytes(),
                stats.getNbDiscarded(),
                stats.getNbDiscardedFull(),
                stats.getNbDiscardedLate(),
                stats.getNbDiscardedReset(),
                stats.getNbDiscardedShrink(),
                stats.getNbFec(),

                stats.getNbPackets(),
                stats.getNbPacketsLost(),
            };
        }
        else
        {
            values = new Object[] {
                channel.getID(),
                content.getName(),
                conference.getID()
            };
        }

        logEvent(new InfluxDBEvent(
            "channel_expired", CHANNEL_EXPIRED_COLUMNS, values));
    }

    @Override
    public void handleEvent(Event event)
    {
        if (event == null)
        {
            logger.debug("Could not handle the event because it was null.");
            return;
        }

        if (EventFactory.CHANNEL_CREATED_TOPIC.equals(event.getTopic()))
        {
            Channel channel
                = (Channel) event.getProperty(EventFactory.EVENT_SOURCE);
            channelCreated(channel);
        }
        else if (EventFactory.CHANNEL_EXPIRED_TOPIC.equals(event.getTopic()))
        {
            Channel channel
                = (Channel) event.getProperty(EventFactory.EVENT_SOURCE);
            channelExpired(channel);
        }
        else if (
            EventFactory.CONFERENCE_CREATED_TOPIC.equals(event.getTopic()))
        {
            Conference conference
                = (Conference) event.getProperty(EventFactory.EVENT_SOURCE);
            conferenceCreated(conference);
        }
        else if (
            EventFactory.CONFERENCE_EXPIRED_TOPIC.equals(event.getTopic()))
        {
            Conference conference
                = (Conference) event.getProperty(EventFactory.EVENT_SOURCE);
            conferenceExpired(conference);
        }
        else if (EventFactory.CONTENT_CREATED_TOPIC.equals(event.getTopic()))
        {
            Content content
                = (Content) event.getProperty(EventFactory.EVENT_SOURCE);
            contentCreated(content);
        }
        else if (EventFactory.CONTENT_EXPIRED_TOPIC.equals(event.getTopic()))
        {
            Content content
                = (Content) event.getProperty(EventFactory.EVENT_SOURCE);
            contentExpired(content);
        }
        else if (EventFactory.ENDPOINT_CREATED_TOPIC.equals(event.getTopic()))
        {
            Endpoint endpoint
                = (Endpoint) event.getProperty(EventFactory.EVENT_SOURCE);
            endpointCreated(endpoint);
        }
        else if (EventFactory.ENDPOINT_DISPLAY_NAME_CHANGED_TOPIC
            .equals(event.getTopic()))
        {
            Endpoint endpoint
                = (Endpoint) event.getProperty(EventFactory.EVENT_SOURCE);
            endpointDisplayNameChanged(endpoint);

        }
        else if (EventFactory.TRANSPORT_CHANNEL_ADDED_TOPIC
            .equals(event.getTopic()))
        {
            Channel channel
                = (Channel) event.getProperty(EventFactory.EVENT_SOURCE);
            transportChannelAdded(channel);
        }
        else if (EventFactory.TRANSPORT_CHANNEL_REMOVED_TOPIC
            .equals(event.getTopic()))
        {
            Channel channel
                = (Channel) event.getProperty(EventFactory.EVENT_SOURCE);
            transportChannelRemoved(channel);
        }
        else if (EventFactory.TRANSPORT_CONNECTED_TOPIC
            .equals(event.getTopic()))
        {
            IceUdpTransportManager transportManager
                = (IceUdpTransportManager) event.getProperty(
                EventFactory.EVENT_SOURCE);

            transportConnected(transportManager);
        }
        else if (EventFactory.TRANSPORT_CREATED_TOPIC.equals(event.getTopic()))
        {
            IceUdpTransportManager transportManager
                = (IceUdpTransportManager) event.getProperty(
                EventFactory.EVENT_SOURCE);
            transportCreated(transportManager);
        }
        else if (EventFactory.TRANSPORT_STATE_CHANGED_TOPIC
            .equals(event.getTopic()))
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
