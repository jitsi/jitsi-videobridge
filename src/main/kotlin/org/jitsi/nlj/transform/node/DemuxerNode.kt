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
package org.jitsi.nlj.transform.node

import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.rtp.Packet

class DemuxerNode : Node("Demuxer") {
    private var transformPaths: MutableMap<PacketPredicate, Node> = mutableMapOf()

    fun addPacketPath(pp: PacketPath) {
        transformPaths[pp.predicate] = pp.path
    }

    override fun attach(node: Node) = throw Exception()

    override fun doProcessPackets(p: List<Packet>) {
        // Is this scheme always better? Or only when the list of
        // packets is above a certain size?
        transformPaths.forEach { predicate, chain ->
            val pathPackets = p.filter(predicate)
            next(chain, pathPackets)
        }
    }

    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
        transformPaths.forEach { pred, path ->
            path.visit(visitor)
        }
    }

    // Will we do this here?  or will we rely on iterating over the bookkeeping
    // structure?
//    override fun handleEvent(event: Event) {
//        transformPaths.forEach { _, handler ->
//            handler.handleEvent(event)
//        }
//    }

}
