/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.metrics;

import java.util.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;

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
 */
public class MetricService {

    private static final Logger logger = Logger.getLogger(MetricService.class);
    private List<MetricServicePublisher> publishers;

    public static final String METRIC_CONFERENCES = "Conferences";
    public static final String METRIC_CHANNELS = "Channels";
    public static final String METRIC_CONFERENCELENGTH = "Conference length";
    public static final String METRIC_CHANNELSTART_POSTFIX = " start";

    public MetricService(ConfigurationService config) {
        this.publishers = new LinkedList<>();
        List<String> propNames = config.getPropertyNamesByPrefix("org.jitsi.videobridge.metricservice.", false);
        logger.info("Metric services enabled: " + (propNames == null ? "0" : propNames.size()));
        if (propNames != null) {
            for (String propName : propNames) {
                logger.info("Initialising metric service: " + propName);
                try {
                    String serviceClassName = config.getString(propName);
                    Class<?> serviceClass = Class.forName(serviceClassName);
                    MetricServicePublisher publisher = (MetricServicePublisher) serviceClass.getConstructor().newInstance();
                    this.publishers.add(publisher);
                } catch (Throwable t) {
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
    public void publishNumericMetric(String metricName, int metricValue) {
        for (MetricServicePublisher publisher : this.publishers) {
            try {
                publisher.publishNumericMetric(metricName, metricValue);
            } catch (UnsupportedOperationException e) {
                logger.debug(publisher.getName() + " publisher doesn't support numeric metric: " + metricName);
            } catch (Throwable t) {
                logger.error("Error publishing metric \"" + metricName + "\" with publisher: " + publisher.getName(), t);
            }
        }
    }

    /**
     * Method to publish string type metrics
     *
     * @param metricName Name of the metric
     * @param metricValue Value of the metric
     */
    public void publishStringMetric(String metricName, String metricValue) {
        for (MetricServicePublisher publisher : this.publishers) {
            try {
                publisher.publishStringMetric(metricName, metricValue);
            } catch (UnsupportedOperationException e) {
                logger.debug(publisher.getName() + " publisher doesn't support string metric: " + metricName);
            } catch (Throwable t) {
                logger.error("Error publishing metric \"" + metricName + "\" with publisher: " + publisher.getName(), t);
            }
        }
    }

    /**
     * Metrod to publish an incremental metric. Metric value with
     * <tt>metricName</tt> will be increased by 1.
     *
     * @param metricName Name of the metric
     */
    public void publishIncrementalMetric(String metricName) {
        for (MetricServicePublisher publisher : this.publishers) {
            try {
                publisher.publishIncrementalMetric(metricName);
            } catch (UnsupportedOperationException e) {
                logger.debug(publisher.getName() + " publisher doesn't support incremental metric: " + metricName);
            } catch (Throwable t) {
                logger.error("Error publishing metric \"" + metricName + "\" with publisher: " + publisher.getName(), t);
            }
        }
    }

    /**
     * Metrod to publish an incremental metric. Metric value with
     * <tt>metricName</tt> will be increased by <tt>metricName</tt>.
     *
     * @param metricName Name of the metric
     * @param increment Value to increase the metric with
     */
    public void publishIncrementalMetric(String metricName, int increment) {
        for (MetricServicePublisher publisher : this.publishers) {
            try {
                publisher.publishIncrementalMetric(metricName, increment);
            } catch (UnsupportedOperationException e) {
                logger.debug(publisher.getName() + " publisher doesn't support incremental metric: " + metricName);
            } catch (Throwable t) {
                logger.error("Error publishing metric \"" + metricName + "\" with publisher: " + publisher.getName(), t);
            }
        }
    }

    /**
     * Records the start of a transaction to have the length published later.
     *
     * @param transactionType Type of the transaction (e.g. create conference)
     * @param transactionId Unique id of the transaction (e.g. conference ID)
     */
    public void startMeasuredTransaction(String transactionType, String transactionId) {
        for (MetricServicePublisher publisher : this.publishers) {
            try {
                publisher.startMeasuredTransaction(transactionType, transactionId);
            } catch (UnsupportedOperationException e) {
                logger.debug(publisher.getName() + " publisher doesn't support measured transaction metric: " + transactionType);
            } catch (Throwable t) {
                logger.error("Error publishing metric \"" + transactionType + "\" with publisher: " + publisher.getName(), t);
            }
        }
    }

    /**
     * Records the finish of a transaction and publishes length metric
     *
     * @param transactionType Type of the transaction (e.g. create conference)
     * @param transactionId Unique id of the transaction (e.g. conference ID)
     */
    public void endMeasuredTransaction(String transactionType, String transactionId) {
        for (MetricServicePublisher publisher : this.publishers) {
            try {
                publisher.endMeasuredTransaction(transactionType, transactionId);
            } catch (UnsupportedOperationException e) {
                logger.debug(publisher.getName() + " publisher doesn't support measured transaction metric: " + transactionType);
            } catch (Throwable t) {
                logger.error("Error publishing metric \"" + transactionType + "\" with publisher: " + publisher.getName(), t);
            }
        }
    }

}
