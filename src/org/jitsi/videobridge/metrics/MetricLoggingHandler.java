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
package org.jitsi.videobridge.metrics;

import java.util.*;

import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.eventadmin.*;

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
 */
public class MetricLoggingHandler
    implements EventHandler
{

    private static final Logger logger = Logger.getLogger(MetricLoggingHandler.class);
    private List<MetricServicePublisher> publishers;

    public static final String METRIC_CONFERENCES = "Conferences";
    public static final String METRIC_CHANNELS = "Channels";
    public static final String METRIC_CONFERENCELENGTH = "Conference length";
    public static final String METRIC_CHANNELSTART_POSTFIX = " start";

    public MetricLoggingHandler(ConfigurationService config)
    {
        this.publishers = new LinkedList<MetricServicePublisher>();
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
                    String serviceClassName = config.getString(propName);
                    Class<?> serviceClass = Class.forName(serviceClassName);
                    MetricServicePublisher publisher
                        = (MetricServicePublisher) serviceClass.getConstructor()
                            .newInstance();
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
    private void publishNumericMetric(String metricName, int metricValue)
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
                publisher.startMeasuredTransaction(transactionType, transactionId);
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
                publisher.endMeasuredTransaction(transactionType, transactionId);
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

            publishNumericMetric(
                METRIC_CHANNELS, videobridge.getChannelCount());
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

            publishNumericMetric(
                METRIC_CONFERENCES, videobridge.getConferenceCount());

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

            publishNumericMetric(
                METRIC_CONFERENCES, videobridge.getConferenceCount());

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
