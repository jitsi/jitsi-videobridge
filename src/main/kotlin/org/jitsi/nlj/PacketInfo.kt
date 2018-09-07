/*
 * Copyright @ 2018 Atlassian Pty Ltd
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

/**
 * [PacketInfo] is a wrapper around a [Packet] instance to be passed through
 * a pipeline.  Since the [Packet] can change as it moves through the pipeline
 * (as it is parsed into different types), the wrapping [PacketInfo] stays consistent
 * and allows for metadata to be passed along with a packet.
 */
class PacketInfo @JvmOverloads constructor(var packet: Packet, val metaData: MutableMap<Any, Any> = mutableMapOf()) {
    /**
     * Get the contained packet cast to [ExpectedPacketType]
     */
    @Suppress("UNCHECKED_CAST")
    fun <ExpectedPacketType>packetAs(): ExpectedPacketType {
        return packet as ExpectedPacketType
    }

    /**
     * Create a deep clone of this PacketInfo (both the contained packet and the metadata map
     * will be copied for the cloned PacketInfo).
     */
    fun clone(): PacketInfo = PacketInfo(packet.clone(), metaData.toMutableMap())
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
    for (element in this) action (element, element.packet as ExpectedPacketType)
}
