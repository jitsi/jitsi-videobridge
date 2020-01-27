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

package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.protocol.ProtocolStack
import org.jitsi.nlj.transform.node.TransformerNode

/**
 * Note that [ProtocolSender] functions as a [TransformerNode]
 * which always returns null from [transform]: this implies that
 * all [ProtocolStack] implementations *must* make a copy of
 * the packet in [ProtocolStack.sendApplicationData] if they need
 * access to that data after the method is done.
 */
open class ProtocolSender @JvmOverloads constructor(
    private val stack: ProtocolStack,
    name: String = stack::class.toString()
) : TransformerNode(name) {

    init {
        stack.onOutgoingProtocolData(::next)
    }

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        stack.sendApplicationData(packetInfo)

        return null
    }
}
