package org.jitsi.videobridge.metrics;

import com.newrelic.api.agent.NewRelic;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jitsi.util.Logger;

/**
 * A reference implementation of MetricServicePublisher for
 * <a href="http://www.NewRelic.com">NewRelic.com</a>
 *
 * @author zbettenbuk
 */
public class NewRelicMetricPublisher implements MetricServicePublisher {

  private static final Logger logger = Logger.getLogger(NewRelicMetricPublisher.class);
  private final Map<String, Map<String, Long>> transactions;

  private static final String prefix = "Custom/";

  public NewRelicMetricPublisher() {
    this.transactions = new LinkedHashMap<>();
  }

  @Override
  public String getName() {
    return this.getClass().getName();
  }

  @Override
  public void publishNumericMetric(String metricName, long metricValue) {
    NewRelic.recordMetric(prefix + metricName, metricValue);
  }

  @Override
  public void publishStringMetric(String metricName, String metricValue) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  @Override
  public void publishIncrementalMetric(String metricName) {
    NewRelic.incrementCounter(prefix + metricName);
  }

  @Override
  public void publishIncrementalMetric(String metricName, int increment) {
    NewRelic.incrementCounter(prefix + metricName, increment);
  }

  @Override
  public void startMeasuredTransaction(String transactionType, String transactionId) {
    long startTimeStamp = Calendar.getInstance().getTimeInMillis();
    this.getTransactionStore(transactionType).put(transactionId, startTimeStamp);
  }

  @Override
  public void endMeasuredTransaction(String transactionType, String transactionId) {
    long endTimeStamp = Calendar.getInstance().getTimeInMillis();
    Long startTimeStamp = this.getTransactionStore(transactionType).get(transactionId);
    if (startTimeStamp == null) {
      throw new RuntimeException("Error publishing transaction. It is not started: " + transactionType + ":" + transactionId);
    }
    // metric based unit
    int div = 1;
    switch (transactionType) {
      case MetricService.METRIC_CONFERENCELENGTH:
        div = 1000; // seconds
        break;
      default:
        div = 1;
        break;
    }
    NewRelic.recordMetric(prefix + transactionType, (endTimeStamp - startTimeStamp) / div);
  }

  private Map<String, Long> getTransactionStore(String transactionType) {
    Map<String, Long> store = this.transactions.get(transactionType);
    if (store == null) {
      store = new LinkedHashMap<>();
      this.transactions.put(transactionType, store);
    }
    return store;
  }

}
