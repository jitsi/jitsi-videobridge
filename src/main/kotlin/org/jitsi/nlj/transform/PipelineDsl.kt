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
package org.jitsi.nlj.transform

import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.SimplePacketHandler
import org.jitsi.nlj.transform.module.DemuxerModule
import org.jitsi.nlj.transform.module.PacketPath
import org.jitsi.rtp.Packet

//fun chain(receiver: ModuleChain.() -> Unit): ModuleChain = ModuleChain().apply(receiver)

fun DemuxerModule.packetPath(b: PacketPath.() -> Unit) {
    this.addPacketPath(PacketPath().apply(b))
}

// A packet tree is defined by a single root PacketHandler and represents
// potentially multiple packet paths (as it may branch out)
class PacketTreeBuilder {
    private var head: PacketHandler? = null
    private var tail: PacketHandler? = null
    private var treeTerminated = false

    private fun addHandler(handler: PacketHandler) {
        if (treeTerminated) {
            throw Exception("Handler cannot be added after tree has been terminated")
        }
        if (head == null) {
            head = handler
        }
        tail?.attach(handler)
        tail = handler
    }

    fun handler(block: () -> PacketHandler): PacketHandler {
        val createdHandler = block()
        addHandler(createdHandler)
        return createdHandler
    }

    fun simpleHandler(name: String, block: SimplePacketHandler.(List<Packet>) -> Unit): PacketHandler {
        val simpleHandler = SimplePacketHandler(name, block)
        addHandler(simpleHandler)
        return simpleHandler
    }

    fun handler(handler: PacketHandler) = addHandler(handler)

    /**
     * After adding a demuxer, no more handlers can be added directly to this
     * tree.  A demuxer represents a subtree and all paths from here on
     * must stem from that subtree.
     */
    fun demux(block: DemuxerModule.() -> Unit): PacketHandler {
        val demuxerModule = DemuxerModule().apply(block)
        addHandler(demuxerModule)
        treeTerminated = true

        return demuxerModule
    }

    fun build(): PacketHandler = head!!
}

fun packetTree(block: PacketTreeBuilder.() -> Unit): PacketHandler {
    val builder = PacketTreeBuilder().apply(block)

    return builder.build()
}

//fun onPackets(block: SimplePacketHandler.(List<Packet>) -> Unit): PacketHandler {
//    return SimplePacketHandler(block)
//}

