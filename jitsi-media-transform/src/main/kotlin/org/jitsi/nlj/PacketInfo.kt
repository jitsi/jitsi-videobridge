/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.format.PayloadType
import org.jitsi.rtp.Packet
import org.jitsi.utils.logging2.createLogger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections

@SuppressFBWarnings("CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE")
class EventTimeline(
    /**
     * We want this thread safe, because while PacketInfo objects are only handled by a single thread at a time,
     * [StatsKeepingNode] may add an "exit" event after the packets has been added to another queue potentially handled
     * by a different thread. This is not critical as it only affects the timeline and the result is just some "exit"
     * events missing from the trace logs.
     */
    timelineArg: MutableList<Pair<String, Duration>> = mutableListOf(),
    private val clock: Clock = Clock.systemUTC()
) : Iterable<Pair<String, Duration>> {

    private val timeline = Collections.synchronizedList(timelineArg)

    /**
     * The [referenceTime] refers to the first timestamp we have
     * in the timeline.  In the timeline this is used as time "0" and
     * all other times are represented as deltas from this 0.
     */
    var referenceTime: Instant? = null

    val size: Int
        get() = timeline.size

    fun addEvent(desc: String) {
        val now = clock.instant()
        if (referenceTime == null) {
            referenceTime = now
        }
        timeline.add(desc to Duration.between(referenceTime, now))
    }

    fun clone(): EventTimeline {
        val clone = EventTimeline(timeline.toMutableList(), clock)
        clone.referenceTime = referenceTime
        return clone
    }

    override fun iterator(): Iterator<Pair<String, Duration>> = timeline.iterator()

    /**
     * Return the total time between this packet's first event and last event
     * or -1 if there is no reference time
     */
    fun totalDelay(): Duration {
        return referenceTime?.let {
            return timeline.last().second
        } ?: Duration.ofMillis(-1)
    }

    override fun toString(): String {
        return with(StringBuffer()) {
            referenceTime?.let {
                append("Reference time: $referenceTime; ")
                synchronized(timeline) {
                    append(timeline.joinToString(separator = "; "))
                }
            } ?: run {
                append("[No timeline]")
            }
            toString()
        }
    }
}

/**
 * The origin of a packet in the system; used to track outgoing packets in [OutgoingStatisticsTracker]
 * to measure the bitrates of each type of data.
 *
 * Currently only used for RTP, so RTCP, SCTP, and Datachannel will all be either Routed or Misc.
 */
enum class PacketOrigin {
    Routed,
    Retransmission,
    Probing,
    Padding,
    Synthesized,
    Misc
    /* TODO: Add RTCP, SCTP, and datachannel if needed */
}

/**
 * [PacketInfo] is a wrapper around a [Packet] instance to be passed through
 * a pipeline.  Since the [Packet] can change as it moves through the pipeline
 * (as it is parsed into different types), the wrapping [PacketInfo] stays consistent
 * and allows for metadata to be passed along with a packet.
 */
@SuppressFBWarnings("CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE")
open class PacketInfo @JvmOverloads constructor(
    var packet: Packet,
    /** The original length of the packet, i.e. before decryption.  Stays unchanged even if the packet is updated. */
    val originalLength: Int = packet.length,
    val timeline: EventTimeline? = if (enableTimeline) EventTimeline() else null
) {
    /**
     * An explicit tag for when this packet was originally received (assuming it
     * was an incoming packet and not one created by jvb itself).
     */
    var receivedTime: Instant? = null
        set(value) {
            field = value
            if (timeline != null && timeline.referenceTime == null) {
                timeline.referenceTime = value
            }
        }

    /** Whether the packet originally had cryptex RTP header extensions. */
    var originalHadCryptex: Boolean = false

    /**
     * Whether this packet has been recognized to contain only shouldDiscard.
     */
    var shouldDiscard: Boolean = false

    /**
     * The ID of the endpoint associated with this packet (i.e. the source endpoint).
     */
    var endpointId: String? = null

    /**
     * Whether this packet indicates a point in which its stream's layering changed, in
     * a way that indicates that bitrate allocation may need to be recomputed.
     */
    var layeringChanged = false

    var payloadType: PayloadType? = null

    /**
     * The payload verification string for the packet, or 'null' if payload verification is disabled. Calculating the
     * it is expensive, thus we only do it when the flag is enabled.
     */
    var payloadVerification = if (enablePayloadVerification) packet.payloadVerification else null

    /**
     * Information about whether this packet is used for probing by the transport-cc engine.
     * The type is internal to that object.
     */
    var probingInfo: Any? = null

    /**
     * The origin of the packet, used for tracking the sources of media being routed.
     */
    var packetOrigin: PacketOrigin = PacketOrigin.Misc

    /**
     * Re-calculates the expected payload verification string. This should be called any time that the code
     * intentionally modifies the packet in a way that could change the verification string (for example, re-creates
     * it with a new type (parsing), or intentionally modifies the payload (SRTP)).
     */
    fun resetPayloadVerification() {
        payloadVerification = if (enablePayloadVerification) packet.payloadVerification else null
    }

    /**
     * Get the contained packet cast to [ExpectedPacketType]
     */
    @Suppress("UNCHECKED_CAST")
    fun <ExpectedPacketType : Packet> packetAs(): ExpectedPacketType {
        return packet as ExpectedPacketType
    }

    /**
     * Create a deep clone of this PacketInfo (both the contained packet and the metadata map
     * will be copied for the cloned PacketInfo).
     */
    fun clone(): PacketInfo {
        val clone = PacketInfo(packet.clone(), originalLength, timeline?.clone())
        clone.receivedTime = receivedTime
        clone.originalHadCryptex = originalHadCryptex
        clone.shouldDiscard = shouldDiscard
        clone.endpointId = endpointId
        clone.payloadType = payloadType
        clone.layeringChanged = layeringChanged
        clone.payloadVerification = payloadVerification
        clone.probingInfo = probingInfo
        clone.packetOrigin = packetOrigin
        @Suppress("UNCHECKED_CAST") // ArrayList.clone() really does return ArrayList, not Object.
        clone.onSentActions = onSentActions?.clone() as ArrayList<(PacketInfo) -> Unit>?
        return clone
    }

    fun addEvent(desc: String) = timeline?.addEvent(desc)

    /**
     * The list of pending actions, or [null] if none.
     */
    private var onSentActions: ArrayList<(PacketInfo) -> Unit>? = null

    /**
     * Add an action to be performed when the packet is sent (i.e. when this packet's
     * [sent] method is called).
     *
     * If this [PacketInfo] object is cloned, the action will be called for every
     * cloned instance.  If packet is dropped (i.e. [sent] is never called), the
     * action will not be called.
     */
    fun onSent(action: (PacketInfo) -> Unit) {
        synchronized(this) {
            if (onSentActions == null) {
                onSentActions = ArrayList(1)
            }
            onSentActions!!.add(action)
        }
    }

    /**
     * Invoke any actions previously registered with this [PacketInfo]'s [onSent]
     * method.  This should be called just before, or after, this packet is sent.
     */
    fun sent() {
        var actions: List<(PacketInfo) -> Unit> = Collections.emptyList()
        synchronized(this) {
            onSentActions?.let {
                actions = it
                onSentActions = null
            } ?: run { return@sent }
        }
        for (action in actions) {
            action.invoke(this)
        }
    }

    companion object {
        private val enableTimeline: Boolean by config {
            "jmt.debug.packet-timeline.enabled".from(JitsiConfig.newConfig)
        }

        private val enablePayloadVerificationDefault: Boolean by config {
            "jmt.debug.payload-verification.enabled".from(JitsiConfig.newConfig)
        }

        /**
         * If this is enabled all [Node]s will verify that the payload didn't unexpectedly change. This is expensive.
         */
        @field:Suppress("ktlint:standard:property-naming")
        var enablePayloadVerification = enablePayloadVerificationDefault

        init {
            if (enableTimeline) {
                createLogger().info("Packet timeline is enabled.")
            }
            if (enablePayloadVerification) {
                createLogger().info("Payload verification is enabled.")
            }
        }
    }
}

/**
 * This is a specialization of [org.jitsi.nlj.util.forEachAs] method which makes it easier
 * to operate on lists of [PacketInfo] when the caller wants to treat the contained [Packet]
 * as a specific packet type.  This method iterates over the iterable of [PacketInfo]s and calls
 * the given lambda with the [PacketInfo] instance and the contained [Packet] instance, cast
 * as [ExpectedPacketType].  This will throw if the cast attempt is unsuccessful.
 */
@Suppress("UNCHECKED_CAST")
inline fun <ExpectedPacketType> Iterable<PacketInfo>.forEachAs(action: (PacketInfo, ExpectedPacketType) -> Unit) {
    for (element in this) action(element, element.packet as ExpectedPacketType)
}
