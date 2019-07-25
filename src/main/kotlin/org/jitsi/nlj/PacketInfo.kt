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

import org.jitsi.rtp.Packet
import java.time.Duration

class EventTimeline(
    private val timeline: MutableList<Pair<String, Long>> = mutableListOf()
) : Iterable<Pair<String, Long>> {
    /**
     * The [referenceTime] refers to the first timestamp we have
     * in the timeline.  In the timeline this is used as time "0" and
     * all other times are represented as deltas from this 0.
     */
    private var referenceTime: Long? = null

    fun addEvent(desc: String) {
        val now = System.currentTimeMillis()
        if (referenceTime == null) {
            referenceTime = now
        }
        timeline.add(desc to (now - referenceTime!!))
    }

    fun clone(): EventTimeline {
        val clone = EventTimeline(timeline.toMutableList())
        clone.referenceTime = referenceTime
        return clone
    }

    override fun iterator(): Iterator<Pair<String, Long>> = timeline.iterator()

    /**
     * Return the total time between this packet's first event and last event
     * or -1 if there is no reference time
     */
    fun totalDelay(): Duration {
        return referenceTime?.let {
            return Duration.ofMillis(timeline.last().second)
        } ?: Duration.ofMillis(-1)
    }

    override fun toString(): String {
        return with(StringBuffer()) {
            referenceTime?.let {
                appendln("Reference time: ${referenceTime}ms")
                timeline.forEach {
                    appendln(it.toString())
                }
            } ?: run {
                appendln("[No timeline]")
            }
            toString()
        }
    }
}

/**
 * [PacketInfo] is a wrapper around a [Packet] instance to be passed through
 * a pipeline.  Since the [Packet] can change as it moves through the pipeline
 * (as it is parsed into different types), the wrapping [PacketInfo] stays consistent
 * and allows for metadata to be passed along with a packet.
 */
open class PacketInfo @JvmOverloads constructor(
    var packet: Packet,
    val timeline: EventTimeline = EventTimeline()
) {
    /**
     * An explicit tag for when this packet was originally received (assuming it
     * was an incoming packet and not one created by jvb itself).
     */
    var receivedTime: Long = -1L

    /**
     * Whether this packet has been recognized to contain only shouldDiscard.
     */
    var shouldDiscard: Boolean = false

    /**
     * The ID of the endpoint associated with this packet (i.e. the source endpoint).
     */
    var endpointId: String? = null

    /**
     * The payload verification string for the packet, or 'null' if payload verification is disabled. Calculating the
     * it is expensive, thus we only do it when the flag is enabled.
     */
    var payloadVerification = if (ENABLE_PAYLOAD_VERIFICATION) packet.payloadVerification else null

    /**
     * Re-calculates the expected payload verification string. This should be called any time that the code
     * intentionally modifies the packet in a way that could change the verification string (for example, re-creates
     * it with a new type (parsing), or intentionally modifies the payload (SRTP)).
     */
    fun resetPayloadVerification() {
        payloadVerification = if (ENABLE_PAYLOAD_VERIFICATION) packet.payloadVerification else null
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
        val clone = if (ENABLE_TIMELINE) {
            PacketInfo(packet.clone(), timeline.clone())
        } else {
            // If the timeline isn't enabled, we can just share the same one.
            // (This would change if we allowed enabling the timeline at runtime)
            PacketInfo(packet.clone(), timeline)
        }
        clone.receivedTime = receivedTime
        clone.payloadVerification = payloadVerification
        return clone
    }

    fun addEvent(desc: String) {
        if (ENABLE_TIMELINE) {
            timeline.addEvent(desc)
        }
    }

    companion object {
        // TODO: we could make this a public var to allow changing this at runtime
        private const val ENABLE_TIMELINE = false

        /**
         * If this is enabled all [Node]s will verify that the payload didn't unexpectedly change. This is expensive.
         */
        var ENABLE_PAYLOAD_VERIFICATION = false
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
