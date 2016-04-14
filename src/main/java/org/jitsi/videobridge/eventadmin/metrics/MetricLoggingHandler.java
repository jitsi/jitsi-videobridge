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
package org.jitsi.videobridge.eventadmin.metrics;

import org.jitsi.eventadmin.Event;
import org.jitsi.eventadmin.EventHandler;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.neomedia.MediaStream;
import org.jitsi.service.neomedia.MediaStreamStats;
import org.jitsi.util.Logger;
import org.jitsi.videobridge.*;

import java.util.LinkedList;
import java.util.List;

/**
 * A generic service interface to push metrics to cloud based collector servers.
 *
 * Service publishers can be enabled with environment variables (e.g.
 * sip-communicator file) with prefix: "org.jitsi.videobridge.metricservice.".
 * Publisher instances must implement
 * <tt>MetricServicePublisher</tt> interface. It's not necessary to implement
 * all publish methods. Methods not implemented will simply be ignored (though
 * metric will not be published)
 *
 * Current metric entry points (metrics):
 * <ul>
 * <li>Create and expire conference</li>
 * <li>Create and expire channel</li>
 * <li>Conference length</li>
 * <li>Channel connection (incl. remote IP address)</li>
 * </ul>
 *
 * @author zbettenbuk
 * @author George Politis
 * @author Damian Minkov
 */
public class MetricLoggingHandler
    implements EventHandler
{
    private static final Logger logger
        = Logger.getLogger(MetricLoggingHandler.class);

    private List<MetricServicePublisher> publishers;

    public static final String METRIC_CONFERENCES = "Conferences";
    public static final String METRIC_CHANNELS = "Channels";
    public static final String METRIC_CONFERENCELENGTH = "Conference length";
    public static final String METRIC_CHANNELSTART_POSTFIX = " start";

    /**
     * The video streams metric name.
     */
    public static final String METRIC_VIDEO_STREAMS = "Video Streams";

    /**
     * The number of RTP packets sent by the remote side, but not
     * received by us.
     */
    public static final String METRIC_DOWNLOAD_PACKET_LOSS
        = "Nb download packet loss";

    /**
     * The number of RTP packets sent by us, but not
     * received by the remote side.
     */
    public static final String METRIC_UPLOAD_PACKET_LOSS
        = "Nb upload packet loss";

    /**
     * The minimum RTP jitter value reported by us in an RTCP report, in
     * millisecond.
     */
    public static final String METRIC_DOWNLOAD_MIN_JITTER
        = "Min download jitter";

    /**
     * The maximum RTP jitter value reported by us in an RTCP report, in
     * milliseconds.
     */
    public static final String METRIC_DOWNLOAD_MAX_JITTER
        = "Max download jitter";

    /**
     * The average of the RTP jitter values reported to us in RTCP reports,
     * in milliseconds
     */
    public static final String METRIC_DOWNLOAD_AVG_JITTER
        = "Avg download jitter";

    /**
     * The minimum RTP jitter value reported to us in an RTCP report, in
     * milliseconds.
     */
    public static final String METRIC_UPLOAD_MIN_JITTER
        = "Min upload jitter";

    /**
     * The maximum RTP jitter value reported to us in an RTCP report, in
     * milliseconds.
     */
    public static final String METRIC_UPLOAD_MAX_JITTER
        = "Max upload jitter";

    /**
     * The average of the RTP jitter values reported to us in RTCP reports,
     * in milliseconds.
     */
    public static final String METRIC_UPLOAD_AVG_JITTER
        = "Avg upload jitter";

    /** 
     * Total number or endpoints connected to a conference 
     */
    public static final String METRIC_ENDPOINTS = "Endpoints";

    public MetricLoggingHandler(ConfigurationService config)
    {
        this.publishers = new LinkedList<>();
        List<String> propNames
            = config.getPropertyNamesByPrefix(
                    "org.jitsi.videobridge.metricservice.",
                    false);
        logger.info("Metric services enabled: "
                        + (propNames == null ? "0" : propNames.size()));
        if (propNames != null)
        {
            for (String propName : propNames)
            {
                logger.info("Initialising metric service: " + propName);
                try
                {
                    String className = config.getString(propName);

                    // We moved the metrics package (and the influxdb package)
                    // deeper into a new eventadmin package. Be
                    // backwards-compatible.
                    String oldPkgName = "org.jitsi.videobridge.metrics.";

                    if (className.startsWith(oldPkgName))
                    {
                        String newPkgName
                            = "org.jitsi.videobridge.eventadmin.metrics.";

                        className
                            = newPkgName
                                + className.substring(oldPkgName.length());
                    }

                    Class<?> clazz = Class.forName(className);
                    MetricServicePublisher publisher
                        = (MetricServicePublisher)
                            clazz.getConstructor().newInstance();

                    this.publishers.add(publisher);
                }
                catch (Throwable t)
                {
                    logger.error("Error initialising metric service", t);
                }
            }
        }
    }

    /**
     * Method to publish numeric type metrics
     *
     * @param metricName Name of the metric
     * @param metricValue Value of the metric
     */
    private void publishNumericMetric(String metricName, long metricValue)
    {
        for (MetricServicePublisher publisher : this.publishers)
        {
            try
            {
                publisher.publishNumericMetric(metricName, metricValue);
            }
            catch (UnsupportedOperationException e)
            {
                if (logger.isDebugEnabled())
                    logger.debug(publisher.getName()
                        + " publisher doesn't support numeric metric: "
                        + metricName);
            }
            catch (Throwable t)
            {
                logger.error("Error publishing metric \"" + metricName
                             + "\" with publisher: " + publisher.getName(), t);
            }
        }
    }

    /**
     * Method to publish numeric type metrics
     *
     * @param metricName Name of the metric
     * @param metricValue Value of the metric
     */
    private void publishNumericMetric(String metricName, float metricValue)
    {
        for (MetricServicePublisher publisher : this.publishers)
        {
            try
            {
                publisher.publishNumericMetric(metricName, metricValue);
            }
            catch (UnsupportedOperationException e)
            {
                if (logger.isDebugEnabled())
                    logger.debug(publisher.getName()
                        + " publisher doesn't support numeric metric: "
                        + metricName);
            }
            catch (Throwable t)
            {
                logger.error("Error publishing metric \"" + metricName
                             + "\" with publisher: " + publisher.getName(), t);
            }
        }
    }

    /**
     * Method to publish string type metrics
     *
     * @param metricName Name of the metric
     * @param metricValue Value of the metric
     */
    private void publishStringMetric(String metricName, String metricValue)
    {
        for (MetricServicePublisher publisher : this.publishers)
        {
            try
            {
                publisher.publishStringMetric(metricName, metricValue);
            }
            catch (UnsupportedOperationException e)
            {
                logger.debug(publisher.getName()
                             + " publisher doesn't support string metric: "
                             + metricName);
            }
            catch (Throwable t)
            {
                logger.error("Error publishing metric \"" + metricName
                             + "\" with publisher: " + publisher.getName(), t);
            }
        }
    }

    /**
     * Method to publish an incremental metric. Metric value with
     * <tt>metricName</tt> will be increased by 1.
     *
     * @param metricName Name of the metric
     */
    private void publishIncrementalMetric(String metricName)
    {
        for (MetricServicePublisher publisher : this.publishers)
        {
            try
            {
                publisher.publishIncrementalMetric(metricName);
            }
            catch (UnsupportedOperationException e)
            {
                if (logger.isDebugEnabled())
                    logger.debug(publisher.getName()
                        + " publisher doesn't support incremental metric: "
                        + metricName);

            }
            catch (Throwable t)
            {
                logger.error("Error publishing metric \"" + metricName
                             + "\" with publisher: " + publisher.getName(), t);
            }
        }
    }

    /**
     * Method to publish an incremental metric. Metric value with
     * <tt>metricName</tt> will be increased by <tt>metricName</tt>.
     *
     * @param metricName Name of the metric
     * @param increment Value to increase the metric with
     */
    private void publishIncrementalMetric(String metricName, int increment)
    {
        for (MetricServicePublisher publisher : this.publishers)
        {
            try
            {
                publisher.publishIncrementalMetric(metricName, increment);
            }
            catch (UnsupportedOperationException e)
            {
                logger.debug(publisher.getName()
                             + " publisher doesn't support incremental metric: "
                             + metricName);
            }
            catch (Throwable t)
            {
                logger.error("Error publishing metric \"" + metricName
                             + "\" with publisher: " + publisher.getName(), t);
            }
        }
    }

    /**
     * Records the start of a transaction to have the length published later.
     *
     * @param transactionType Type of the transaction (e.g. create conference)
     * @param transactionId Unique id of the transaction (e.g. conference ID)
     */
    private void startMeasuredTransaction(String transactionType,
                                         String transactionId)
    {
        for (MetricServicePublisher publisher : this.publishers)
        {
            try
            {
                publisher.startMeasuredTransaction(
                    transactionType, transactionId);
            }
            catch (UnsupportedOperationException e)
            {
                if (logger.isDebugEnabled())
                    logger.debug(publisher.getName()
                        + " publisher doesn't support measured transaction "
                        + "metric: " + transactionType);
            }
            catch (Throwable t)
            {
                logger.error("Error publishing metric \"" + transactionType
                             + "\" with publisher: " + publisher.getName(), t);
            }
        }
    }

    /**
     * Records the finish of a transaction and publishes length metric
     *
     * @param transactionType Type of the transaction (e.g. create conference)
     * @param transactionId Unique id of the transaction (e.g. conference ID)
     */
    private void endMeasuredTransaction(String transactionType,
                                       String transactionId)
    {
        for (MetricServicePublisher publisher : this.publishers)
        {
            try
            {
                publisher.endMeasuredTransaction(
                    transactionType, transactionId);
            }
            catch (UnsupportedOperationException e)
            {
                logger.debug(publisher.getName()
                    + " publisher doesn't support measured transaction metric: "
                    + transactionType);
            }
            catch (Throwable t)
            {
                logger.error("Error publishing metric \"" + transactionType
                             + "\" with publisher: " + publisher.getName(), t);
            }
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

        if (EventFactory.CHANNEL_CREATED_TOPIC.equals(event.getTopic())
            || EventFactory.CHANNEL_EXPIRED_TOPIC.equals(event.getTopic()))
        {
            Channel channel
                = (Channel) event.getProperty(EventFactory.EVENT_SOURCE);

            if (channel == null)
            {
                logger.debug("Could not log channel expired event because " +
                    "the channel is null.");
                return;
            }

            Content content = channel.getContent();
            if (content == null)
            {
                logger.debug("Could not log channel expired event because " +
                    "the content is null.");
                return;
            }

            Conference conference = content.getConference();
            if (conference == null)
            {
                logger.debug("Could not log channel expired event because " +
                    "the conference is null.");
                return;
            }

            Videobridge videobridge = conference.getVideobridge();
            if (videobridge == null)
            {
                logger.debug("Could not log channel expired event because " +
                    "the videobridge is null.");
                return;
            }

            int[] conferenceChannelAndStreamCount
                = videobridge.getConferenceChannelAndStreamCount();
            publishNumericMetric(
                METRIC_CHANNELS, conferenceChannelAndStreamCount[1]);

            if(EventFactory.CHANNEL_EXPIRED_TOPIC.equals(event.getTopic()))
            {
                // the channel has expired lets record its stats
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

                if (stats != null)
                {
                    publishNumericMetric(METRIC_DOWNLOAD_PACKET_LOSS,
                        stats.getDownloadNbPacketLost());
                    publishNumericMetric(METRIC_UPLOAD_PACKET_LOSS,
                        stats.getUploadNbPacketLost());

                    publishNumericMetric(METRIC_DOWNLOAD_MIN_JITTER,
                        (float)stats.getMinDownloadJitterMs());
                    publishNumericMetric(METRIC_DOWNLOAD_MAX_JITTER,
                        (float)stats.getMaxDownloadJitterMs());
                    publishNumericMetric(METRIC_DOWNLOAD_AVG_JITTER,
                        (float)stats.getAvgDownloadJitterMs());

                    publishNumericMetric(METRIC_UPLOAD_MIN_JITTER,
                        (float)stats.getMinUploadJitterMs());
                    publishNumericMetric(METRIC_UPLOAD_MAX_JITTER,
                        (float)stats.getMaxUploadJitterMs());
                    publishNumericMetric(METRIC_UPLOAD_AVG_JITTER,
                        (float)stats.getAvgUploadJitterMs());
                }
            }

            publishNumericMetric(
                METRIC_VIDEO_STREAMS, conferenceChannelAndStreamCount[2]);

            publishNumericMetric(METRIC_ENDPOINTS, conference.getEndpointCount());
        }
        else if (
            EventFactory.CONFERENCE_CREATED_TOPIC.equals(event.getTopic()))
        {
            Conference conference
                = (Conference) event.getProperty(EventFactory.EVENT_SOURCE);

            Videobridge videobridge = conference.getVideobridge();
            if (videobridge == null)
            {
                logger.debug("Could not log conference expired event because " +
                    "the videobridge is null.");
                return;
            }

            // the conferences created event is fired in the moment of the
            // creation, before adding it to the list of conferences
            // so we add one as it will be added, just after current call
            publishNumericMetric(
                METRIC_CONFERENCES, videobridge.getConferenceCount() + 1);

            startMeasuredTransaction(METRIC_CONFERENCELENGTH,
                conference.getID());
        }
        else if (
            EventFactory.CONFERENCE_EXPIRED_TOPIC.equals(event.getTopic()))
        {
            Conference conference
                = (Conference) event.getProperty(EventFactory.EVENT_SOURCE);

            Videobridge videobridge = conference.getVideobridge();
            if (videobridge == null)
            {
                logger.debug("Could not log conference expired event because " +
                    "the videobridge is null.");
                return;
            }

            // the conferences expired event is fired in the moment of the
            // expiration, before removing it from the list of conferences
            // so we subtract one as it will be removed,
            // just after current execution
            publishNumericMetric(
                METRIC_CONFERENCES, videobridge.getConferenceCount() - 1);

            endMeasuredTransaction(METRIC_CONFERENCELENGTH, conference.getID());
        }
        else if (EventFactory.STREAM_STARTED_TOPIC.equals(event.getTopic()))
        {
            RtpChannel rtpChannel
                = (RtpChannel) event.getProperty(EventFactory.EVENT_SOURCE);

            publishStringMetric(
                rtpChannel.getClass().getName() + METRIC_CHANNELSTART_POSTFIX,
                rtpChannel.getStreamTarget().getDataAddress().getHostAddress());
        }
    }
}
