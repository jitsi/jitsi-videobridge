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

/**
 * Generic interface for cloud based metric collectors.
 *
 * @author zbettenbuk
 */
public interface MetricServicePublisher
{

    /**
     * Method to publish numeric type metrics
     *
     * @param metricName Name of the metric
     * @param metricValue Value of the metric
     */
    public void publishNumericMetric(String metricName, long metricValue);

    /**
     * Method to publish numeric type metrics
     *
     * @param metricName Name of the metric
     * @param metricValue Value of the metric
     */
    public void publishNumericMetric(String metricName, float metricValue);

    /**
     * Method to publish string type metrics
     *
     * @param metricName Name of the metric
     * @param metricValue Value of the metric
     */
    public void publishStringMetric(String metricName, String metricValue);

    /**
     * Method to publish an incremental metric. Metric value with
     * <tt>metricName</tt> will be increased by 1.
     *
     * @param metricName Name of the metric
     */
    public void publishIncrementalMetric(String metricName);

    /**
     * Method to publish an incremental metric. Metric value with
     * <tt>metricName</tt> will be increased by <tt>metricName</tt>.
     *
     * @param metricName Name of the metric
     * @param increment Value to increase the metric with
     */
    public void publishIncrementalMetric(String metricName, int increment);

    /**
     * Records the start of a transaction to have the length published later.
     *
     * @param transactionType Type of the transaction (e.g. create conference)
     * @param transactionId Unique id of the transaction (e.g. conference ID)
     */
    public void startMeasuredTransaction(String transactionType,
                                         String transactionId);

    /**
     * Records the finish of a transaction and publishes length metric
     *
     * @param transactionType Type of the transaction (e.g. create conference)
     * @param transactionId Unique id of the transaction (e.g. conference ID)
     */
    public void endMeasuredTransaction(String transactionType,
                                       String transactionId);

    public String getName();

}
