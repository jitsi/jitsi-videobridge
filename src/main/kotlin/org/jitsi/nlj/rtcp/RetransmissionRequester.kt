/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.rtcp

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.SortedSet
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.logging2.cwarn
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacketBuilder
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.util.isNextAfter
import org.jitsi.rtp.util.isOlderThan
import org.jitsi.rtp.util.numPacketsTo
import org.jitsi.utils.logging2.Logger

class RetransmissionRequester(
    private val rtcpSender: (RtcpPacket) -> Unit,
    private val scheduler: ScheduledExecutorService,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = createChildLogger(parentLogger)
    private val streamPacketRequesters: MutableMap<Long, StreamPacketRequester> = HashMap()

    fun packetReceived(ssrc: Long, seqNum: Int) {
        val streamPacketRequester = synchronized(streamPacketRequesters) {
            streamPacketRequesters.computeIfAbsent(ssrc) { key ->
                StreamPacketRequester(key, scheduler, clock, rtcpSender, logger)
            }
        }
        streamPacketRequester.packetReceived(seqNum)
    }

    fun stop() {
        synchronized(streamPacketRequesters) {
            streamPacketRequesters.values.forEach(StreamPacketRequester::stop)
            streamPacketRequesters.clear()
        }
    }

    /**
     * Manages retransmission requests for all packets for a specific SSRC
     */
    class StreamPacketRequester(
        val ssrc: Long,
        private val scheduler: ScheduledExecutorService,
        private val clock: Clock,
        private val rtcpSender: (RtcpPacket) -> Unit,
        parentLogger: Logger,
        private val maxMissingSeqNums: Int = 100
    ) {
        companion object {
            val NO_REQUEST_DUE: Instant = Instant.MAX
        }
        private var running: AtomicBoolean = AtomicBoolean(true)
        private val logger =
            createChildLogger(parentLogger, mapOf("ssrc" to ssrc.toString()))
        private var highestReceivedSeqNum = -1
        private val requests: MutableMap<Int, PacketRetransmissionRequest> = HashMap()
        private val taskHandleLock = Any()
        private var currentTaskHandle: ScheduledFuture<*>? = null

        fun packetReceived(seqNum: Int) {
            if (highestReceivedSeqNum == -1) {
                highestReceivedSeqNum = seqNum
                return
            }
            synchronized(requests) {
                when {
                    seqNum isOlderThan highestReceivedSeqNum -> {
                        logger.cdebug { "$ssrc packet $seqNum was received, currently missing ${getMissingSeqNums()}" }
                        // An older packet, possibly already requested
                        requests.remove(seqNum)
                        if (requests.isEmpty()) {
                            logger.cdebug { "$ssrc no more missing seq nums, cancelling pending work" }
                            updateWorkDueTime(NO_REQUEST_DUE)
                        }
                    }
                    seqNum isNextAfter highestReceivedSeqNum -> {
                        highestReceivedSeqNum = seqNum
                    }
                    highestReceivedSeqNum numPacketsTo seqNum < maxMissingSeqNums -> {
                        logger.cdebug {
                            "$ssrc missing packet detected! Just received " +
                                "$seqNum, last received was $highestReceivedSeqNum"
                        }
                        RtpUtils.sequenceNumbersBetween(highestReceivedSeqNum, seqNum).forEach { missingSeqNum ->
                            val request = PacketRetransmissionRequest(missingSeqNum)
                            requests[missingSeqNum] = request
                            updateWorkDueTime(clock.instant())
                        }
                        highestReceivedSeqNum = seqNum
                    }
                    else -> { // diff > maxMissingSeqNums
                        logger.cwarn {
                            "$ssrc large jump in sequence numbers detected (highest received was " +
                                "$highestReceivedSeqNum, current is $seqNum, jump of " +
                                "${highestReceivedSeqNum numPacketsTo seqNum}) , not requesting retransmissions"
                        }
                        highestReceivedSeqNum = seqNum
                        // Reset and clear any pending work to do for this source
                        requests.clear()
                        logger.cdebug { "$ssrc large packet gap, resetting and clearing all work" }
                        updateWorkDueTime(NO_REQUEST_DUE)
                    }
                }
            }
        }

        fun stop() {
            running.set(false)
            synchronized(taskHandleLock) {
                currentTaskHandle?.cancel(false)
            }
            synchronized(requests) {
                requests.clear()
            }
        }

        private fun updateWorkDueTime(newWorkDueTs: Instant) {
            logger.cdebug { "$ssrc updating next work due time to $newWorkDueTs" }
            synchronized(taskHandleLock) {
                if (!running.get()) {
                    logger.cdebug { "$ssrc is stopped, not rescheduling task" }
                }
                when (newWorkDueTs) {
                    NO_REQUEST_DUE -> {
                        logger.cdebug { "$ssrc no more work to do, cancelling job handle" }
                        currentTaskHandle?.cancel(false)
                    }
                    else -> {
                        // TODO(brian): only re-schedule if the change is larger than X ms?
                        // The work is now due either sooner or later than we previously thought, so
                        // re-schedule the task
                        currentTaskHandle?.cancel(false)
                        currentTaskHandle = scheduler.schedule(
                            ::doWork,
                            Duration.between(clock.instant(), newWorkDueTs).toMillis(),
                            TimeUnit.MILLISECONDS
                        )
                    }
                }
            }
        }

        private fun doWork() {
            logger.cdebug { "$ssrc doing work at ${clock.instant()}" }
            val now = clock.instant()
            val missingSeqNums = getMissingSeqNums()
            val nackPacket = RtcpFbNackPacketBuilder(
                mediaSourceSsrc = ssrc,
                missingSeqNums = missingSeqNums
            ).build()
            notifyNackSent(now, missingSeqNums)
            rtcpSender(nackPacket)
        }

        private fun notifyNackSent(timestamp: Instant, nackedSeqNums: Collection<Int>) {
            synchronized(requests) {
                nackedSeqNums.forEach { nackedSeqNum ->
                    // It's possible that in between sending the nack and calling this method the packet
                    // was received and is no longer in the requests map
                    requests[nackedSeqNum]?.let { request ->
                        request.requested(timestamp)
                        if (request.numTimesRequested == MAX_REQUESTS) {
                            logger.cdebug {
                                "$ssrc generated the last NACK for seq num ${request.seqNum}, " +
                                    "time since the first request = " +
                                    "${Duration.between(request.firstRequestTimestamp, timestamp)}"
                            }

                            requests.remove(nackedSeqNum)
                        }
                    } ?: run {
                        logger.cdebug {
                            "$ssrc packet $nackedSeqNum must have just been received, it was " +
                                "no longer in the requests map"
                        }
                    }
                }
                val nextDueTime = if (requests.isNotEmpty()) timestamp.plus(REQUEST_INTERVAL) else NO_REQUEST_DUE
                logger.cdebug { "$ssrc nack sent at $timestamp, next one will be sent at $nextDueTime" }
                updateWorkDueTime(nextDueTime)
            }
        }

        private fun getMissingSeqNums(): SortedSet<Int> = synchronized(requests) { requests.keys.toSortedSet() }
    }

    /**
     * Tracks a request for retransmission of a specific RTP packet.
     */
    private class PacketRetransmissionRequest(
        val seqNum: Int
    ) {
        var numTimesRequested = 0
            private set
        var firstRequestTimestamp: Instant = Instant.MIN
            private set

        fun requested(timestamp: Instant) {
            if (firstRequestTimestamp == Instant.MIN) {
                firstRequestTimestamp = timestamp
            }
            numTimesRequested++
        }
    }

    companion object {
        private const val MAX_REQUESTS = 10
        private val REQUEST_INTERVAL = Duration.ofMillis(150)
    }
}
