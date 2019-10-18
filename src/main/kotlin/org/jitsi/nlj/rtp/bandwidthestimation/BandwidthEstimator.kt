/*
 * Copyright @ 2019 - present 8x8, Inc.
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

package org.jitsi.nlj.rtp.bandwidthestimation

import java.time.Duration
import java.time.Instant
import java.util.LinkedList
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.NEVER
import org.jitsi.nlj.util.OrderedJsonObject
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.formatMilli
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger

/**
 * An abstract interface to a bandwidth estimation algorithm.
 *
 * The invoker of the algorithm will periodically call [processPacketArrival]
 * and/or [processPacketLoss] as it learns information about packets
 * that have traversed the network.
 *
 * All bandwidths/bitrates are in bits per second.
 */
abstract class BandwidthEstimator(
    protected val diagnosticContext: DiagnosticContext
) {
    /**
     * The [TimeSeriesLogger] to be used by this instance to print time
     * series.
     */
    protected val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(this.javaClass)

    /** The name of the algorithm implemented by this [BandwidthEstimator]. */
    abstract val algorithmName: String

    /** The initial bandwidth estimate. */
    abstract var initBw: Bandwidth

    /** The minimum bandwidth the estimator will return. */
    abstract var minBw: Bandwidth

    /** The maximum bandwidth the estimator will return. */
    abstract var maxBw: Bandwidth

    /**
     * Inform the bandwidth estimator about a packet that has arrived at its
     * destination.
     *
     * This function will be called at most once for any value of [seq];
     * however, it may be called after a call to [processPacketLoss] for the
     * same [seq] value, if a packet is delayed.
     *
     * It is possible (e.g., if feedback was lost) that neither
     * [processPacketArrival] nor [processPacketLoss] is called for a given [seq].
     *
     * The clocks reported by [now], [sendTime], and [recvTime] do not
     * necessarily have any relationship to each other, but must be consistent
     * within themselves across all calls to functions of this [BandwidthEstimator].
     *
     * @param[now] The current time, when this function is called.
     * @param[sendTime] The time the packet was sent, if known, or null.
     * @param[recvTime] The time the packet was received, if known, or null.
     * @param[seq] A 16-bit sequence number of packets processed by this
     *  [BandwidthEstimator].
     * @param[size] The size of the packet.
     * @param[ecn] The ECN markings with which the packet was received.
     */
    fun processPacketArrival(
        now: Instant,
        sendTime: Instant?,
        recvTime: Instant?,
        seq: Int,
        size: DataSize,
        ecn: Byte = 0
    ) {
        if (timeSeriesLogger.isTraceEnabled) {
            val point = diagnosticContext.makeTimeSeriesPoint("bwe_packet_arrival", now)
            if (sendTime != null) {
                point.addField("sendTime", sendTime.formatMilli())
            }
            if (recvTime != null) {
                point.addField("recvTime", recvTime.formatMilli())
            }
            point.addField("seq", seq)
            point.addField("size", size.bytes)
            if (ecn != 0.toByte()) {
                point.addField("ecn", ecn)
            }
            timeSeriesLogger.trace(point)
        }

        doProcessPacketArrival(now, sendTime, recvTime, seq, size, ecn)
    }

    /**
     * A subclass's implementation of [processPacketArrival].
     *
     * See that function for parameter details.
     */
    protected abstract fun doProcessPacketArrival(
        now: Instant,
        sendTime: Instant?,
        recvTime: Instant?,
        seq: Int,
        size: DataSize,
        ecn: Byte = 0
    )

    /**
     * Inform the bandwidth estimator that a packet was lost.
     *
     * @param[now] The current time, when this function is called.
     * @param[sendTime] The time the packet was sent, if known, or null.
     * @param[seq] A 16-bit sequence number of packets processed by this
     *  [BandwidthEstimator].
     */
    fun processPacketLoss(now: Instant, sendTime: Instant?, seq: Int) {
        if (timeSeriesLogger.isTraceEnabled) {
            val point = diagnosticContext.makeTimeSeriesPoint("bwe_packet_loss", now)
            if (sendTime != null) {
                point.addField("sendTime", sendTime.formatMilli())
            }
            point.addField("seq", seq)
            timeSeriesLogger.trace(point)
        }

        doProcessPacketLoss(now, sendTime, seq)
    }

    /**
     * A subclass's implementation of [processPacketLoss].
     *
     * See that function for parameter details.
     */
    protected abstract fun doProcessPacketLoss(now: Instant, sendTime: Instant?, seq: Int)

    /**
     * Inform the bandwidth estimator about a new round-trip time value
     */
    fun onRttUpdate(now: Instant, newRtt: Duration) {
        if (timeSeriesLogger.isTraceEnabled) {
            val point = diagnosticContext.makeTimeSeriesPoint("bwe_rtt", now)
            point.addField("rtt", newRtt.formatMilli())
            timeSeriesLogger.trace(point)
        }

        doRttUpdate(now, newRtt)
    }

    /**
     * A subclass's implementation of [onRttUpdate].
     *
     * See that function for parameter details.
     */
    protected abstract fun doRttUpdate(now: Instant, newRtt: Duration)

    /** Get the estimator's current estimate of the available bandwidth.
     *
     * @param[now] The current time, when this function is called.
     */
    abstract fun getCurrentBw(now: Instant): Bandwidth

    /** Get the current statistics related to this estimator.
     *
     * @param[now] The current time, when this function is called.
     */
    abstract fun getStats(now: Instant): StatisticsSnapshot

    /** Reset the estimator to its initial state. */
    abstract fun reset(): Unit

    interface Listener {
        fun bandwidthEstimationChanged(newValue: Bandwidth)
    }

    private val listeners = LinkedList<Listener>()
    private var curBandwidth = (-1).bps

    private var lastBweLogTime = NEVER
    private val minBweLogInterval = Duration.ofMillis(500)

    /**
     * Notifies registered listeners that the estimate of the available
     * bandwidth has changed.
     */
    @Synchronized
    protected fun reportBandwidthEstimate(now: Instant, newValue: Bandwidth) {
        if (timeSeriesLogger.isTraceEnabled) {
            if (newValue != curBandwidth ||
                lastBweLogTime == NEVER ||
                Duration.between(lastBweLogTime, now) >= minBweLogInterval) {
                val point = diagnosticContext.makeTimeSeriesPoint("bwe_estimate", now)
                point.addField("bw", newValue.bps)
                timeSeriesLogger.trace(point)
                lastBweLogTime = now
            }
        }

        if (newValue == curBandwidth) {
            return
        }
        for (listener in listeners) {
            listener.bandwidthEstimationChanged(newValue)
        }
        curBandwidth = newValue
    }

    /**
     * Adds a listener to be notified about changes to the bandwidth estimation.
     * @param listener
     */
    @Synchronized
    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    /**
     * Removes a listener.
     * @param listener
     */
    @Synchronized
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Holds a snapshot of stats specific to the bandwidth estimator.
     */
    class StatisticsSnapshot(algorithmName: String, currentEstimate: Bandwidth) {
        private val stats = mutableMapOf<String, Any>().apply {
            addString("algorithmName", algorithmName)
            addBandwidth("currentEstimate", currentEstimate)
        }
        var algorithmName: String by stats
        var currentEstimate: Bandwidth by stats

        fun getValue(name: String): Any? = stats[name]

        /**
         * Gets the value of a stat with a given name, if this [StatisticsSnapshot] has it and it is a [Number].
         * Otherwise returns 'null'.
         */
        fun getNumber(name: String): Number? = stats[name] as? Number

        /**
         * Promotes integer values to [Long] and floating point values to [Double]. Returns a
         * [Long], [Double], or null.
         */
        private fun promote(n: Number): Number? = when (n) {
            is Byte, is Short, is Int, is Long -> n.toLong()
            is Float, is Double -> n.toDouble()
            else -> null
        }

        /**
         * Adds a stat with a number value. Integral values are promoted to [Long], while floating point values are
         * promoted to [Double].
         */
        fun addNumber(name: String, value: Number) {
            promote(value)?.let { stats[name] = it }
        }

        /**
         * Adds a stat with a string value.
         */
        fun addString(name: String, value: String) {
            stats[name] = value
        }

        /**
         * Adds a stat with a boolean value.
         */
        fun addBoolean(name: String, value: Boolean) {
            stats[name] = value
        }

        /**
         * Adds a stat with a bandwidth value.
         */
        fun addBandwidth(name: String, value: Bandwidth) {
            stats[name] = value
        }

        /**
         * Returns a JSON representation of this [StatisticsSnapshot] object.
         */
        fun toJson(): OrderedJsonObject = OrderedJsonObject().apply {
            stats.forEach { (name, value) ->
                when (value) {
                    is Bandwidth -> put(name, value.bps)
                    else -> put(name, value)
                }
            }
        }
    }
}
