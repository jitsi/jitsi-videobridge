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

package org.jitsi.nlj.protocol

import org.jitsi.nlj.PacketInfo

/**
 * Defines the input and output methods of a modeled [ProtocolStack].  There are 3 use cases modeled here:
 *
 * 1) A caller wants to send application data out 'over' a certain protocol:
 *     A caller may use [ProtocolStack#sendApplicationData] to send data out over this protocol.  The implementing stack
 *     is responsible for whatever encapsulation, etc. is necessary to send out the given application
 *     data over that protocol
 * 2) A stack wants to send data out to the network.  This data could be encapsulated application data
 *     (sent via use case #1 above) or it could be protocol-specific packets used for something like
 *     negotiation) and prompted by an incoming protocol negotiation packet, or even something like
 *     a protocol-specific keepalive packet driven by a timer.
 * 3) A packet has been received from the network that is determined to be of this protocol.  It is unknown
 *     whether this data is protocol data (e.g.  for negotiation) or application data.  It is
 *     fed into this [ProtocolStack] via [ProtocolStack#processIncomingProtocolData].  If the data was application data
 *     that has been 'unwrapped' and should continue on, that data should be returned as a result of
 *     [ProtocolStack#processIncomingProtocolData]
 */
interface ProtocolStack {
    /**
     * Send application data, wrapped within the packet in [packetInfo], out
     * through this protocol stack and to the network.
     *
     * This is used when sending application data 'over' a certain protocol, and implies
     * the stack will wrap the given application data in some manner specific to that
     * protocol.
     */
    fun sendApplicationData(packetInfo: PacketInfo)

    /**
     * Install a handler to be invoked whenever this [ProtocolStack] has data
     * it wants to send out to the network.  This data could be application
     * data or protocol-specific data (e.g. packets used in negotiation or keepalives,
     * etc.; basically data not explicitly sent out by the user via [sendApplicationData])
     */
    fun onOutgoingProtocolData(handler: (List<PacketInfo>) -> Unit)

    /**
     * Notify this [ProtocolStack] that incoming data, which has been determined to
     * belong to this protocol, has been received from the network.  Returns
     * potentially multiple [PacketInfo] packets representing protocol application
     * packets which have been processed by the stack.
     */
    fun processIncomingProtocolData(packetInfo: PacketInfo): List<PacketInfo>
}
