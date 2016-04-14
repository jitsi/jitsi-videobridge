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

import com.newrelic.api.agent.NewRelic;
import java.util.*;

/**
 * A reference implementation of MetricServicePublisher for
 * <a href="http://www.NewRelic.com">NewRelic.com</a>
 *
 * @author zbettenbuk
 * @author Damian Minkov
 */
public class NewRelicMetricPublisher
    implements MetricServicePublisher
{
    /**
     * The transaction map.
     */
    private final Map<String, Map<String, Long>> transactions;

    /**
     * The prefix used for the properties published.
     */
    private static final String prefix = "Custom/";

    /**
     * Constructs publisher.
     */
    public NewRelicMetricPublisher()
    {
        this.transactions = new LinkedHashMap<>();
    }

    /**
     * Name of the publisher class.
     * @return name of the publisher class.
     */
    @Override
    public String getName()
    {
        return this.getClass().getName();
    }

    /**
     * Method to publish numeric type metrics
     *
     * @param metricName Name of the metric
     * @param metricValue Value of the metric
     */
    @Override
    public void publishNumericMetric(String metricName, long metricValue)
    {
        NewRelic.recordMetric(prefix + metricName, metricValue);
    }

    /**
     * Method to publish numeric type metrics
     *
     * @param metricName Name of the metric
     * @param metricValue Value of the metric
     */
    @Override
    public void publishNumericMetric(String metricName, float metricValue)
    {
        NewRelic.recordMetric(prefix + metricName, metricValue);
    }

    /**
     * Method to publish string type metrics
     *
     * @param metricName Name of the metric
     * @param metricValue Value of the metric
     */
    @Override
    public void publishStringMetric(String metricName, String metricValue)
    {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Method to publish an incremental metric. Metric value with
     * <tt>metricName</tt> will be increased by 1.
     *
     * @param metricName Name of the metric
     */
    @Override
    public void publishIncrementalMetric(String metricName)
    {
        NewRelic.incrementCounter(prefix + metricName);
    }

    /**
     * Method to publish an incremental metric. Metric value with
     * <tt>metricName</tt> will be increased by <tt>metricName</tt>.
     *
     * @param metricName Name of the metric
     * @param increment Value to increase the metric with
     */
    @Override
    public void publishIncrementalMetric(String metricName, int increment)
    {
        NewRelic.incrementCounter(prefix + metricName, increment);
    }

    /**
     * Records the start of a transaction to have the length published later.
     *
     * @param transactionType Type of the transaction (e.g. create conference)
     * @param transactionId Unique id of the transaction (e.g. conference ID)
     */
    @Override
    public void startMeasuredTransaction(
        String transactionType, String transactionId)
    {
        long startTimeStamp = Calendar.getInstance().getTimeInMillis();
        this.getTransactionStore(transactionType).put(transactionId, startTimeStamp);
    }

    /**
     * Records the finish of a transaction and publishes length metric
     *
     * @param transactionType Type of the transaction (e.g. create conference)
     * @param transactionId Unique id of the transaction (e.g. conference ID)
     */
    @Override
    public void endMeasuredTransaction(
        String transactionType, String transactionId)
    {
        long endTimeStamp = Calendar.getInstance().getTimeInMillis();
        Long startTimeStamp
            = this.getTransactionStore(transactionType).get(transactionId);
        if (startTimeStamp == null)
        {
            throw new RuntimeException(
                "Error publishing transaction. It is not started: "
                    + transactionType + ":" + transactionId);
        }
        // metric based unit
        int div = 1;
        if(transactionType.equals(MetricLoggingHandler.METRIC_CONFERENCELENGTH))
            div = 1000;
        else
            div = 1;

        NewRelic.recordMetric(
            prefix + transactionType, (endTimeStamp - startTimeStamp) / div);
    }

    /**
     * Returns the current transactions map.
     * @param transactionType type of transaction
     * @return transactions map.
     */
    private Map<String, Long> getTransactionStore(String transactionType)
    {
        Map<String, Long> store = this.transactions.get(transactionType);
        if (store == null)
        {
            store = new LinkedHashMap<>();
            this.transactions.put(transactionType, store);
        }
        return store;
    }

}