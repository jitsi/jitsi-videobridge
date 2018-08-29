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

import org.jitsi.nlj.transform.StatsProducer
import org.jitsi.rtp.Packet

interface PacketHandler : EventHandler, StatsProducer {
    var name: String
    /**
     * Process the given packets
     */
    fun processPackets(pkts: List<Packet>)
}

object EmptyPacketHandler : PacketHandler {
    override var name: String = "Empty packet handler"
    override fun processPackets(pkts: List<Packet>) {}
    override fun getStats(indent: Int): String = ""
    override fun handleEvent(event: Event) {}
}

/**
 * [SimplePacketHandler] will take care of holding the [next] member and
 * assigning it correctly, but the given [handler] is responsible for invoking it
 * once it's finished
 */
//class SimplePacketHandler(override var name: String, private val handler: SimplePacketHandler.(List<Packet>) -> Unit) : PacketHandler {
//    var next: PacketHandler? = null
//    override fun processPackets(pkts: List<Packet>) = handler(pkts)
//
//    override fun attach(nextHandler: PacketHandler) {
//        this.next = nextHandler
//    }
//
//    override fun handleEvent(event: Event) {
//        next?.handleEvent(event)
//    }
//
//    override fun getRecursiveStats(indent: Int): String {
//        return with (StringBuffer()) {
//            append(getStats(indent))
//            next?.let { append(it.getRecursiveStats(indent))}
//            toString()
//        }
//    }
//
//    override fun getStats(indent: Int): String {
//        return with (StringBuffer()) {
//            appendLnIndent(indent, "SimpleHandler $name")
//            toString()
//        }
//    }
//}
