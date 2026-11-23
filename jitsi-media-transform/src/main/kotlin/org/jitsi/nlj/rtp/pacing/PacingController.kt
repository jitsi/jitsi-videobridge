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
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.pacing

import org.jitsi.nlj.rtp.bandwidthestimation2.BitrateProber
import org.jitsi.nlj.rtp.bandwidthestimation2.BitrateProberConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.PacedPacketInfo
import org.jitsi.nlj.rtp.bandwidthestimation2.PacerConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.ProbeClusterConfig
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.dataSentAt
import org.jitsi.nlj.util.min
import org.jitsi.nlj.util.rateOf
import org.jitsi.nlj.util.timeToSend
import org.jitsi.utils.NEVER
import org.jitsi.utils.isFinite
import org.jitsi.utils.isInfinite
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.max
import org.jitsi.utils.min
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

private val kCongestedPacketInterval = 500.ms

// TODO(sprang): Consider dropping this limit.
// The maximum debt level, in terms of time, capped when sending packets.
private val kMaxDebtInTime = 500.ms
private val kMaxElapsedTime = 2.secs

/**
 * Jitsi local: the time of the clock as a whole number of microseconds, which a Timestamp in WebRTC is. The
 * arithmetic of the pacer truncates a duration to microseconds, so with a finer time each update of the budget
 * would lose the remainder.
 */
private fun Clock.instantInMicros(): Instant = instant().truncatedTo(ChronoUnit.MICROS)

/** Jitsi local: a copy with the number of bytes sent, which [PacedPacketInfo.copy] does not copy. */
private fun PacedPacketInfo.copyWithBytesSent(): PacedPacketInfo =
    copy().also { it.probeClusterBytesSent = probeClusterBytesSent }

/** Pacing controller,
 * based on WebRTC modules/pacing/pacing_controller.{h,cc} in
 * WebRTC commit 42a497714596d8f726da4dae4fb4f133cbaa15df (main, 2026-11-15).
 *
 * This class implements a leaky-bucket packet pacing algorithm. It handles the
 * logic of determining which packets to send when, but the actual timing of
 * the processing is done externally (e.g. RtpPacketPacer). Furthermore, the
 * forwarding of packets when they are ready to be sent is also handled
 * externally, via the PacingController::PacketSender interface.
 */
class PacingController(
    parentLogger: Logger,
    private val clock: Clock,
    private val packetSender: PacketSender,
    fieldTrials: FieldTrials = FieldTrials(),
    configuration: Configuration = Configuration()
) {
    private val logger = createChildLogger(parentLogger)

    interface PacketSender {
        fun sendPacket(packet: RtpPacketToSend, clusterInfo: PacedPacketInfo)

        // Should be called after each call to SendPacket().
        fun fetchFec(): List<RtpPacketToSend>

        fun generatePadding(size: DataSize): List<RtpPacketToSend>

        // TODO(bugs.webrtc.org/1439830): Make pure virtual once subclasses adapt.
        fun onBatchComplete() {}

        // TODO(bugs.webrtc.org/11340): Make pure virtual once downstream projects
        // have been updated.
        fun onAbortedRetransmissions(ssrc: Long, sequenceNumbers: List<Int>) {}

        fun getRtxSsrcForMedia(ssrc: Long): Long? = null

        /**
         * Jitsi local: called with each packet that the pacer removes from its queue without sending it. WebRTC
         * frees such a packet. Here the sender has to return its buffer.
         */
        fun onPacketDropped(packet: RtpPacketToSend) {}
    }

    /**
     * Jitsi local: the settings that are field trials in WebRTC. The defaults here are the defaults there, except
     * for the settings of the prober, which the bridge already has defaults for.
     */
    data class FieldTrials(
        // WebRTC-Pacer-DrainQueue
        val drainQueue: Boolean = true,
        // WebRTC-Pacer-PadInSilence
        val padInSilence: Boolean = false,
        // WebRTC-Pacer-BlockAudio
        val blockAudio: Boolean = false,
        // WebRTC-Pacer-IgnoreTransportOverhead
        val ignoreTransportOverhead: Boolean = false,
        // WebRTC-Pacer-FastRetransmissions
        val fastRetransmissions: Boolean = false,
        // WebRTC-RetransmitFlushedPackets
        val retransmitFlushedPackets: Boolean = false,
        // WebRTC-Bwe-ProbingBehavior
        val proberConfig: BitrateProberConfig = BitrateProberConfig()
    )

    data class Configuration(
        // If the pacer queue grows longer than the configured max queue limit,
        // pacer sends at the minimum rate needed to keep the max queue limit and
        // ignore the current bandwidth estimate.
        val drainLargeQueues: Boolean = true,
        // Expected max pacer delay. If ExpectedQueueTime() is higher than
        // this value, the packet producers should wait (eg drop frames rather than
        // encoding them). Bitrate sent may temporarily exceed target set by
        // SetPacingRates() so that this limit will be upheld if
        // `drain_large_queues` is set.
        val queueTimeLimit: Duration = kMaxExpectedQueueLength,
        // Audio retransmission is prioritized before video retransmission packets.
        val prioritizeAudioRetransmission: Boolean = false,
        // Configure separate timeouts per priority. After a timeout, a packet of
        // that sort will not be paced and instead dropped.
        // Note: to set TTL on audio retransmission,
        // `prioritize_audio_retransmission` must be true.
        val packetQueueTtl: PacketQueueTTL = PacketQueueTTL(),
        // The pacer is allowed to send enqueued packets in bursts and can build up
        // a packet "debt" that correspond to approximately the send rate during the
        // burst interval.
        val sendBurstInterval: Duration = PacerConfig.kDefaultTimeInterval,
        val initialPacerConfig: PacerConfig? = null
    )

    // Adds the packet to the queue and calls PacketRouter::SendPacket() when
    // it's time to send.
    fun enqueuePacket(packet: RtpPacketToSend) {
        check(pacingRate > Bandwidth.ZERO) { "SetPacingRate must be called before InsertPacket." }

        if (packet.packetType == RtpPacketMediaType.kVideo &&
            packet.isKeyFrame &&
            packet.isFirstPacketOfFrame &&
            !packetQueue.hasKeyframePackets(packet.ssrc)
        ) {
            // First packet of a keyframe (and no keyframe packets currently in the
            // queue). Flush any pending packets currently in the queue for that stream
            // in order to get the new keyframe out as quickly as possible.
            val flushedPackets = packetQueue.removePacketsForSsrc(packet.ssrc)
            if (flushedPackets.isNotEmpty()) {
                // Note that this can truncate a picture that has only been partially
                // sent, in which case the receiver never sees the end of that picture.
                logger.info {
                    "Pacer: discarded ${flushedPackets.size} queued packets for ssrc ${packet.ssrc} on key frame"
                }
                if (retransmitFlushedPackets) {
                    for (flushedPacket in flushedPackets) {
                        flushedPacket.allowSending = false
                        packetSender.sendPacket(flushedPacket, PacedPacketInfo())
                    }
                } else {
                    // Jitsi local
                    flushedPackets.forEach { packetSender.onPacketDropped(it) }
                }
            }
            val rtxSsrc = packetSender.getRtxSsrcForMedia(packet.ssrc)
            if (rtxSsrc != null) {
                // Jitsi local: the packets are passed to onPacketDropped.
                packetQueue.removePacketsForSsrc(rtxSsrc).forEach { packetSender.onPacketDropped(it) }
            }
        }

        prober.onIncomingPacket(packet.size().bytes)

        val now = currentTime()
        if (packetQueue.empty()) {
            // If queue is empty, we need to "fast-forward" the last process time,
            // so that we don't use passed time as budget for sending the first new
            // packet.
            var targetProcessTime = now
            val nextSendTime = nextSendTime()
            if (nextSendTime.isFinite()) {
                // There was already a valid planned send time, such as a keep-alive.
                // Use that as last process time only if it's prior to now.
                targetProcessTime = minOf(now, nextSendTime)
            }
            updateBudgetWithElapsedTime(updateTimeAndGetElapsed(targetProcessTime))
        }
        packetQueue.push(now, packet)
        seenFirstPacket = true

        // Queue length has increased, check if we need to change the pacing rate.
        maybeUpdateMediaRateDueToLongQueue(now)
    }

    fun createProbeClusters(probeClusterConfigs: List<ProbeClusterConfig>) {
        for (probeClusterConfig in probeClusterConfigs) {
            prober.createProbeCluster(probeClusterConfig)
        }
    }

    // Temporarily pause all sending.
    fun pause() {
        if (!paused) {
            logger.info("PacedSender paused.")
        }
        paused = true
        packetQueue.setPauseState(paused || congested, currentTime())
    }

    // Resume sending packets.
    fun resume() {
        if (paused) {
            logger.info("PacedSender resumed.")
        }
        paused = false
        packetQueue.setPauseState(paused || congested, currentTime())
    }

    fun isPaused(): Boolean = paused

    fun setCongested(congested: Boolean) {
        if (this.congested && !congested) {
            updateBudgetWithElapsedTime(updateTimeAndGetElapsed(currentTime()))
        }
        this.congested = congested
        packetQueue.setPauseState(paused || this.congested, currentTime())
    }

    // Sets the pacing rates. Must be called once before packets can be sent.
    @Deprecated("Use setPacerConfig")
    fun setPacingRates(pacingRate: Bandwidth, paddingRate: Bandwidth) {
        setPacerConfig(PacerConfig.create(Instant.EPOCH, pacingRate, paddingRate, sendBurstInterval))
    }

    fun setPacerConfig(pacerConfig: PacerConfig) {
        check(pacerConfig.timeWindow.isFinite())
        if (pacerConfig.padRate() > pacerConfig.dataRate()) {
            logger.warn {
                "Padding rate ${pacerConfig.padRate()} is higher than the pacing rate $paddingRate, capping."
            }
            paddingRate = pacerConfig.dataRate()
        } else {
            paddingRate = pacerConfig.padRate()
        }

        pacingRate = pacerConfig.dataRate()
        sendBurstInterval = pacerConfig.timeWindow

        maybeUpdateMediaRateDueToLongQueue(currentTime())

        logger.trace { "bwe:pacer_updated pacing_kbps=$pacingRate padding_budget_kbps=$paddingRate" }
    }

    fun pacingRate(): Bandwidth = adjustedMediaRate

    // Currently audio traffic is not accounted by pacer and passed through.
    // With the introduction of audio BWE audio traffic will be accounted for
    // the pacer budget calculation. The audio traffic still will be injected
    // at high priority.
    fun setAccountForAudioPackets(accountForAudio: Boolean) {
        this.accountForAudio = accountForAudio
    }

    fun setIncludeOverhead() {
        includeOverhead = true
    }

    fun setTransportOverhead(overheadPerPacket: DataSize) {
        if (ignoreTransportOverhead) {
            return
        }
        transportOverheadPerPacket = overheadPerPacket
    }

    // The pacer is allowed to send enqued packets in bursts and can build up a
    // packet "debt" that correspond to approximately the send rate during
    // 'burst_interval'.
    @Deprecated("Use setPacerConfig")
    fun setSendBurstInterval(burstInterval: Duration) {
        sendBurstInterval = burstInterval
    }

    // A probe may be sent without first waing for a media packet.
    fun setAllowProbeWithoutMediaPacket(allow: Boolean) {
        prober.setAllowProbeWithoutMediaPacket(allow)
    }

    // Returns the time when the oldest packet was queued.
    fun oldestPacketEnqueueTime(): Instant = packetQueue.oldestEnqueueTime()

    // Number of packets in the pacer queue.
    fun queueSizePackets(): Int = packetQueue.sizeInPackets()

    // Number of packets in the pacer queue per media type (RtpPacketMediaType
    // values are used as lookup index).
    fun sizeInPacketsPerRtpPacketMediaType(): IntArray = packetQueue.sizeInPacketsPerRtpPacketMediaType()

    // Totals size of packets in the pacer queue.
    fun queueSizeData(): DataSize {
        var size = packetQueue.sizeInPayloadBytes()
        if (includeOverhead) {
            size += transportOverheadPerPacket * packetQueue.sizeInPackets()
        }
        return size
    }

    // Current buffer level, i.e. max of media and padding debt.
    fun currentBufferLevel(): DataSize = maxOf(mediaDebt, paddingDebt)

    // Returns the time when the first packet was sent.
    fun firstSentPacketTime(): Instant? = firstSentPacketTime

    // Returns the number of milliseconds it will take to send the current
    // packets in the queue, given the current size and bitrate, ignoring prio.
    fun expectedQueueTime(): Duration {
        check(adjustedMediaRate > Bandwidth.ZERO)
        return timeToSend(queueSizeData(), adjustedMediaRate)
    }

    fun setQueueTimeLimit(limit: Duration) {
        queueTimeLimit = limit
    }

    // Enable bitrate probing. Enabled by default, mostly here to simplify
    // testing. Must be called before any packets are being sent to have an
    // effect.
    fun setProbingEnabled(enabled: Boolean) {
        check(!seenFirstPacket)
        prober.setEnabled(enabled)
    }

    // Returns the next time we expect ProcessPackets() to be called.
    fun nextSendTime(): Instant {
        val now = currentTime()
        var nextSendTime: Instant

        if (paused) {
            return lastSendTime + kPausedProcessInterval
        }

        // If probing is active, that always takes priority.
        if (prober.isProbing() && !probingSendFailure) {
            val probeTime = prober.nextProbeTime(now)
            if (probeTime != Instant.MAX) {
                return if (probeTime == NEVER) now else probeTime
            }
        }

        // If queue contains a packet which should not be paced, its target send time
        // is the time at which it was enqueued.
        val unpacedSendTime = nextUnpacedSendTime()
        if (unpacedSendTime.isFinite()) {
            return unpacedSendTime
        }

        if (congested || !seenFirstPacket) {
            // We need to at least send keep-alive packets with some interval.
            return lastSendTime + kCongestedPacketInterval
        }

        if (adjustedMediaRate > Bandwidth.ZERO && !packetQueue.empty()) {
            // If packets are allowed to be sent in a burst, the
            // debt is allowed to grow up to one packet more than what can be sent
            // during 'send_burst_period_'.
            val drainTime = timeToSend(mediaDebt, adjustedMediaRate)
            // Ensure that a burst of sent packet is not larger than kMaxBurstSize in
            // order to not risk overfilling socket buffers at high bitrate.
            val sendBurstInterval = min(sendBurstInterval, timeToSend(kMaxBurstSize, adjustedMediaRate))
            nextSendTime = lastProcessTime + (if (sendBurstInterval > drainTime) Duration.ZERO else drainTime)
        } else if (paddingRate > Bandwidth.ZERO && packetQueue.empty()) {
            // If we _don't_ have pending packets, check how long until we have
            // bandwidth for padding packets. Both media and padding debts must
            // have been drained to do this.
            check(adjustedMediaRate > Bandwidth.ZERO)
            var drainTime = max(timeToSend(mediaDebt, adjustedMediaRate), timeToSend(paddingDebt, paddingRate))

            if (drainTime.isZero && (mediaDebt != DataSize.ZERO || paddingDebt != DataSize.ZERO)) {
                // We have a non-zero debt, but drain time is smaller than tick size of
                // TimeDelta, round it up to the smallest possible non-zero delta.
                drainTime = Duration.ofNanos(1000)
            }
            nextSendTime = lastProcessTime + drainTime
        } else {
            // Nothing to do.
            nextSendTime = lastProcessTime + kPausedProcessInterval
        }

        if (sendPaddingIfSilent) {
            nextSendTime = minOf(nextSendTime, lastSendTime + kPausedProcessInterval)
        }

        return nextSendTime
    }

    // Check queue of pending packets and send them or padding packets, if budget
    // is available.
    fun processPackets() {
        try {
            doProcessPackets()
        } finally {
            packetSender.onBatchComplete()
        }
    }

    private fun doProcessPackets() {
        val now = currentTime()
        var targetSendTime: Instant

        if (shouldSendKeepalive(now)) {
            var keepaliveDataSent = DataSize.ZERO
            // We can not send padding unless a normal packet has first been sent. If
            // we do, timestamps get messed up.
            if (seenFirstPacket) {
                val keepalivePackets = packetSender.generatePadding(1.bytes)
                for (packet in keepalivePackets) {
                    keepaliveDataSent += (packet.payloadSize + packet.paddingSize).bytes
                    packetSender.sendPacket(packet, PacedPacketInfo())
                    for (fecPacket in packetSender.fetchFec()) {
                        enqueuePacket(fecPacket)
                    }
                }
            }
            onPacketSent(RtpPacketMediaType.kPadding, keepaliveDataSent, now)
        }

        if (paused) {
            return
        }

        val earlyExecuteMargin = if (prober.isProbing()) kMaxEarlyProbeProcessing else Duration.ZERO

        targetSendTime = nextSendTime()
        if (now + earlyExecuteMargin < targetSendTime) {
            // We are too early, but if queue is empty still allow draining some debt.
            // Probing is allowed to be sent up to kMinSleepTime early.
            updateBudgetWithElapsedTime(updateTimeAndGetElapsed(now))
            return
        }

        val elapsedTime = updateTimeAndGetElapsed(targetSendTime)

        if (elapsedTime > Duration.ZERO) {
            updateBudgetWithElapsedTime(elapsedTime)
        }

        var pacingInfo = PacedPacketInfo()
        var recommendedProbeSize = DataSize.ZERO
        var isProbing = prober.isProbing()
        if (isProbing) {
            // Probe timing is sensitive, and handled explicitly by BitrateProber, so
            // use actual send time rather than target.
            pacingInfo = prober.currentCluster(now) ?: PacedPacketInfo()
            if (pacingInfo.probeClusterId != PacedPacketInfo.kNotAProbe) {
                recommendedProbeSize = prober.recommendedMinProbeSize()
                check(recommendedProbeSize > DataSize.ZERO)
            } else {
                // No valid probe cluster returned, probe might have timed out.
                isProbing = false
            }
        }

        var dataSent = DataSize.ZERO
        var iteration = 0
        var packetsSent = 0
        var paddingPacketsGenerated = 0
        while (iteration < circuitBreakerThreshold) {
            // Fetch packet, so long as queue is not empty or budget is not
            // exhausted.
            val rtpPacket = getPendingPacket(pacingInfo, targetSendTime, now)
            if (rtpPacket == null) {
                // No packet available to send, check if we should send padding.
                if (Duration.between(targetSendTime, now) > kMaxPaddingReplayDuration) {
                    // The target send time is more than `kMaxPaddingReplayDuration` behind
                    // the real-time clock. This can happen if the clock is adjusted forward
                    // without `ProcessPackets()` having been called at the expected times.
                    targetSendTime = now - kMaxPaddingReplayDuration
                    lastProcessTime = maxOf(lastProcessTime, targetSendTime)
                }

                val paddingToAdd = paddingToAdd(recommendedProbeSize, dataSent)
                if (paddingToAdd > DataSize.ZERO) {
                    val paddingPackets = packetSender.generatePadding(paddingToAdd)
                    if (paddingPackets.isNotEmpty()) {
                        paddingPacketsGenerated += paddingPackets.size
                        for (packet in paddingPackets) {
                            enqueuePacket(packet)
                        }
                        // Continue loop to send the padding that was just added.
                        ++iteration
                        continue
                    } else {
                        // Can't generate padding, still update padding budget for next send
                        // time.
                        updatePaddingBudgetWithSentData(paddingToAdd)
                    }
                }
                // Can't fetch new packet and no padding to send, exit send loop.
                break
            } else {
                val packetType = rtpPacket.packetType
                var packetSize = (rtpPacket.payloadSize + rtpPacket.paddingSize).bytes

                if (includeOverhead) {
                    packetSize += rtpPacket.headersSize.bytes + transportOverheadPerPacket
                }

                // Jitsi local: WebRTC passes a reference to a struct, which a sender that keeps it copies. The
                // number of bytes sent changes below while probing, so the sender gets a copy then.
                packetSender.sendPacket(rtpPacket, if (isProbing) pacingInfo.copyWithBytesSent() else pacingInfo)
                for (packet in packetSender.fetchFec()) {
                    enqueuePacket(packet)
                }
                dataSent += packetSize
                ++packetsSent

                // Send done, update send time.
                onPacketSent(packetType, packetSize, now)

                if (isProbing) {
                    pacingInfo.probeClusterBytesSent += packetSize.bytes.toInt()
                    // If we are currently probing, we need to stop the send loop when we
                    // have reached the send target.
                    if (dataSent >= recommendedProbeSize) {
                        break
                    }
                }

                // Update target send time in case that are more packets that we are late
                // in processing.
                targetSendTime = nextSendTime()
                if (targetSendTime > now) {
                    // Exit loop if not probing.
                    if (!isProbing) {
                        break
                    }
                    targetSendTime = now
                }
                updateBudgetWithElapsedTime(updateTimeAndGetElapsed(targetSendTime))
            }
            ++iteration
        }

        if (iteration >= circuitBreakerThreshold) {
            // Circuit break activated. Log warning, adjust send time and return.
            // TODO(sprang): Consider completely clearing state.
            logger.error {
                "PacingController exceeded max iterations in send-loop. Debug info: " +
                    " packets sent = $packetsSent" +
                    ", padding packets generated = $paddingPacketsGenerated" +
                    ", bytes sent = ${dataSent.bytes}" +
                    ", probing = $isProbing" +
                    ", recommended_probe_size = ${recommendedProbeSize.bytes}" +
                    ", now = $now" +
                    ", target_send_time = $targetSendTime" +
                    ", last_process_time = $lastProcessTime" +
                    ", last_send_time = $lastSendTime" +
                    ", paused = $paused" +
                    ", media_debt = ${mediaDebt.bytes}" +
                    ", padding_debt = ${paddingDebt.bytes}" +
                    ", pacing_rate = ${pacingRate.bps}" +
                    ", adjusted_media_rate = ${adjustedMediaRate.bps}" +
                    ", padding_rate = ${paddingRate.bps}" +
                    ", queue size (packets) = ${packetQueue.sizeInPackets()}" +
                    ", queue size (payload bytes) = ${packetQueue.sizeInPayloadBytes()}"
            }
            lastSendTime = now
            lastProcessTime = now
            return
        }

        if (isProbing) {
            probingSendFailure = dataSent == DataSize.ZERO
            if (!probingSendFailure) {
                prober.probeSent(currentTime(), dataSent)
            }
        }

        // Queue length has probably decreased, check if pacing rate needs to updated.
        // Poll the time again, since we might have enqueued new fec/padding packets
        // with a later timestamp than `now`.
        maybeUpdateMediaRateDueToLongQueue(currentTime())
    }

    fun isProbing(): Boolean = prober.isProbing()

    // Note: Intended for debugging purposes only, will be removed.
    // Sets the number of iterations of the main loop in `ProcessPackets()` that
    // is considered erroneous to exceed.
    fun setCircuitBreakerThreshold(numIterations: Int) {
        circuitBreakerThreshold = numIterations
    }

    // Remove any pending packets matching this SSRC from the packet queue.
    fun removePacketsForSsrc(ssrc: Long) {
        // Jitsi local: the packets are passed to onPacketDropped.
        packetQueue.removePacketsForSsrc(ssrc).forEach { packetSender.onPacketDropped(it) }
    }

    private fun updateTimeAndGetElapsed(now: Instant): Duration {
        // If no previous processing, or last process was "in the future" because of
        // early probe processing, then there is no elapsed time to add budget for.
        if (lastProcessTime == NEVER || now < lastProcessTime) {
            return Duration.ZERO
        }
        var elapsedTime = Duration.between(lastProcessTime, now)
        lastProcessTime = now
        if (elapsedTime > kMaxElapsedTime) {
            logger.warn("Elapsed time ($elapsedTime) longer than expected, limiting to $kMaxElapsedTime")
            elapsedTime = kMaxElapsedTime
        }
        return elapsedTime
    }

    private fun shouldSendKeepalive(now: Instant): Boolean {
        if (sendPaddingIfSilent || paused || congested || !seenFirstPacket) {
            // We send a padding packet every 500 ms to ensure we won't get stuck in
            // congested state due to no feedback being received.
            if (Duration.between(lastSendTime, now) >= kCongestedPacketInterval) {
                return true
            }
        }
        return false
    }

    // Updates the number of bytes that can be sent for the next time interval.
    private fun updateBudgetWithElapsedTime(delta: Duration) {
        mediaDebt -= min(mediaDebt, dataSentAt(adjustedMediaRate, delta))
        paddingDebt -= min(paddingDebt, dataSentAt(paddingRate, delta))
    }

    private fun updateBudgetWithSentData(size: DataSize) {
        mediaDebt += size
        mediaDebt = min(mediaDebt, dataSentAt(adjustedMediaRate, kMaxDebtInTime))
        updatePaddingBudgetWithSentData(size)
    }

    private fun updatePaddingBudgetWithSentData(size: DataSize) {
        paddingDebt += size
        paddingDebt = min(paddingDebt, dataSentAt(paddingRate, kMaxDebtInTime))
    }

    private fun paddingToAdd(recommendedProbeSize: DataSize, dataSent: DataSize): DataSize {
        if (!packetQueue.empty()) {
            // Actual payload available, no need to add padding.
            return DataSize.ZERO
        }

        if (congested) {
            // Don't add padding if congested, even if requested for probing.
            return DataSize.ZERO
        }

        if (recommendedProbeSize != DataSize.ZERO) {
            if (recommendedProbeSize > dataSent) {
                return recommendedProbeSize - dataSent
            }
            return DataSize.ZERO
        }

        if (paddingRate > Bandwidth.ZERO && paddingDebt == DataSize.ZERO) {
            return dataSentAt(paddingRate, kTargetPaddingDuration)
        }
        return DataSize.ZERO
    }

    private fun getPendingPacket(pacingInfo: PacedPacketInfo, targetSendTime: Instant, now: Instant): RtpPacketToSend? {
        val isProbe = pacingInfo.probeClusterId != PacedPacketInfo.kNotAProbe
        // If first packet in probe, insert a small padding packet so we have a
        // more reliable start window for the rate estimation.
        if (isProbe && pacingInfo.probeClusterBytesSent == 0) {
            val padding = packetSender.generatePadding(1.bytes)
            // If no RTP modules sending media are registered, we may not get a
            // padding packet back.
            if (padding.isNotEmpty()) {
                // We should never get more than one padding packets with a requested
                // size of 1 byte.
                check(padding.size == 1)
                return padding[0]
            }
        }

        if (packetQueue.empty()) {
            return null
        }

        // First, check if there is any reason _not_ to send the next queued packet.
        // Unpaced packets and probes are exempted from send checks.
        if (nextUnpacedSendTime().isInfinite() && !isProbe) {
            if (congested) {
                // Don't send anything if congested.
                return null
            }

            if (now <= targetSendTime && sendBurstInterval.isZero) {
                // We allow sending slightly early if we think that we would actually
                // had been able to, had we been right on time - i.e. the current debt
                // is not more than would be reduced to zero at the target sent time.
                // If we allow packets to be sent in a burst, packet are allowed to be
                // sent early.
                val flushTime = timeToSend(mediaDebt, adjustedMediaRate)
                if (now + flushTime > targetSendTime) {
                    return null
                }
            }
        }

        return packetQueue.pop()
    }

    private fun onPacketSent(packetType: RtpPacketMediaType, packetSize: DataSize, sendTime: Instant) {
        if (firstSentPacketTime == null && packetType != RtpPacketMediaType.kPadding) {
            firstSentPacketTime = sendTime
        }

        val audioPacket = packetType == RtpPacketMediaType.kAudio
        if ((!audioPacket || accountForAudio) && packetSize > DataSize.ZERO) {
            updateBudgetWithSentData(packetSize)
        }

        lastSendTime = sendTime
    }

    private fun maybeUpdateMediaRateDueToLongQueue(now: Instant) {
        adjustedMediaRate = pacingRate
        if (!drainLargeQueues) {
            return
        }

        val queueSizeData = queueSizeData()
        if (queueSizeData > DataSize.ZERO) {
            // Assuming equal size packets and input/output rate, the average packet
            // has avg_time_left_ms left to get queue_size_bytes out of the queue, if
            // time constraint shall be met. Determine bitrate needed for that.
            packetQueue.updateAverageQueueTime(now)
            val avgTimeLeft = max(1.ms, queueTimeLimit - packetQueue.averageQueueTime())
            val minRateNeeded = rateOf(queueSizeData, avgTimeLeft)
            if (minRateNeeded > pacingRate) {
                adjustedMediaRate = minRateNeeded
                logger.trace { "bwe:large_pacing_queue pacing_rate_kbps=$pacingRate" }
            }
        }
    }

    private fun currentTime(): Instant {
        var time = clock.instantInMicros()
        if (time < lastTimestamp) {
            // Jitsi local: WebRTC also has a debug check here.
            logger.warn(
                "Non-monotonic clock behavior observed. Previous timestamp: $lastTimestamp, new timestamp: $time"
            )
            time = lastTimestamp
        }
        lastTimestamp = time
        return time
    }

    // Helper methods for packet that may not be paced. Returns a finite Timestamp
    // if a packet type is configured to not be paced and the packet queue has at
    // least one packet of that type. Otherwise returns
    // Timestamp::MinusInfinity().
    private fun nextUnpacedSendTime(): Instant {
        if (!paceAudio) {
            val leadingAudioSendTime = packetQueue.leadingPacketEnqueueTime(RtpPacketMediaType.kAudio)
            if (leadingAudioSendTime.isFinite()) {
                return leadingAudioSendTime
            }
        }
        if (fastRetransmissions) {
            val leadingRetransmissionSendTime = packetQueue.leadingPacketEnqueueTimeForRetransmission()
            if (leadingRetransmissionSendTime.isFinite()) {
                return leadingRetransmissionSendTime
            }
        }
        return NEVER
    }

    private val drainLargeQueues = configuration.drainLargeQueues && fieldTrials.drainQueue
    private val sendPaddingIfSilent = fieldTrials.padInSilence
    private val paceAudio = fieldTrials.blockAudio
    private val ignoreTransportOverhead = fieldTrials.ignoreTransportOverhead
    private val fastRetransmissions = fieldTrials.fastRetransmissions
    private var transportOverheadPerPacket = DataSize.ZERO
    private var sendBurstInterval: Duration =
        configuration.initialPacerConfig?.timeWindow ?: configuration.sendBurstInterval

    // TODO(webrtc:9716): Remove this when we are certain clocks are monotonic.
    // The last millisecond timestamp returned by `clock_`.
    private var lastTimestamp: Instant = clock.instantInMicros()
    private var paused = false

    // Amount of outstanding data for media and padding.
    private var mediaDebt = DataSize.ZERO
    private var paddingDebt = DataSize.ZERO

    // The target pacing rate, signaled via SetPacingRates().
    private var pacingRate: Bandwidth = configuration.initialPacerConfig?.dataRate() ?: Bandwidth.ZERO

    // The media send rate, which might adjusted from pacing_rate_, e.g. if the
    // pacing queue is growing too long.
    private var adjustedMediaRate: Bandwidth = pacingRate

    // The padding target rate. We aim to fill up to this rate with padding what
    // is not already used by media.
    private var paddingRate: Bandwidth =
        configuration.initialPacerConfig?.let { min(it.padRate(), it.dataRate()) } ?: Bandwidth.ZERO

    private val prober = BitrateProber(logger, fieldTrials.proberConfig)
    private var probingSendFailure = false

    private var lastProcessTime: Instant = clock.instantInMicros()
    private var lastSendTime: Instant = lastProcessTime
    private var firstSentPacketTime: Instant? = null
    private var seenFirstPacket = false

    private val packetQueue = PrioritizedPacketQueue(
        logger,
        creationTime = lastProcessTime,
        configuration.prioritizeAudioRetransmission,
        configuration.packetQueueTtl,
        packetSender::onPacketDropped
    )

    private var congested = false

    private var queueTimeLimit: Duration = configuration.queueTimeLimit
    private var accountForAudio = false
    private var includeOverhead = false

    private var circuitBreakerThreshold = 1 shl 16

    // TODO(bugs.webrtc.org/564720400): Either remove or make permanent once
    // experiment is concluded.
    private val retransmitFlushedPackets = fieldTrials.retransmitFlushedPackets

    init {
        if (!drainLargeQueues) {
            logger.warn("Pacer queues will not be drained, pushback experiment must be enabled.")
        }
    }

    companion object {
        // If no media or paused, wake up at least every `kPausedProcessIntervalMs` in
        // order to send a keep-alive packet so we don't get stuck in a bad state due
        // to lack of feedback.
        val kPausedProcessInterval: Duration = kCongestedPacketInterval

        // The default minimum time that should elapse calls to `ProcessPackets()`.
        val kMinSleepTime: Duration = 1.ms

        // When padding should be generated, add packets to the buffer with a size
        // corresponding to this duration times the current padding rate.
        val kTargetPaddingDuration: Duration = 5.ms

        // The maximum time that the pacer can use when "replaying" passed time where
        // padding should have been generated.
        val kMaxPaddingReplayDuration: Duration = 50.ms

        // Allow probes to be processed slightly ahead of inteded send time. Currently
        // set to 1ms as this is intended to allow times be rounded down to the
        // nearest millisecond.
        val kMaxEarlyProbeProcessing: Duration = 1.ms

        // Max total size of packets expected to be sent in a burst in order to not
        // risk loosing packets due to too small send socket buffers. It upper limits
        // the send burst interval.
        // Ex: max send burst interval = 63Kb / 10Mbit/s = 50ms.
        val kMaxBurstSize: DataSize = (63 * 1000).bytes

        // Configuration default values.
        val kMaxExpectedQueueLength: Duration = 2000.ms
    }
}
