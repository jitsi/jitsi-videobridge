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
import org.jitsi.nlj.transform.node.DemuxerNode
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.PacketPath
import org.jitsi.rtp.Packet

//fun chain(receiver: ModuleChain.() -> Unit): ModuleChain = ModuleChain().apply(receiver)

//fun DemuxerModule.packetPath(b: PacketPath.() -> Unit) {
//    this.addPacketPath(PacketPath().apply(b))
//}

fun DemuxerNode.packetPath(b: PacketPath.() -> Unit) {
    this.addPacketPath(PacketPath().apply(b))
}

/**
 * A [PipelineManager] is used for:
 * 1) building a packet pipeline
 * 2) propagating events to all pipeline nodes
 */
class PipelineManager(private val parentPipelineManager: PipelineManager? = null) {
    private var head: Node? = null
    private var tail: Node? = null

    /**
     * All nodes managed by this [PipelineManager].  Note that this
     * list is NOT used for packet processing, this is merely for
     * bookkeeping and the order of [Node]s in this list does not
     * represent their order in the actual pipeline.
     */
    /*private*/ val nodes = mutableListOf<Node>()

    fun getRootNode(): Node = head!!

    /**
     * Register this node in the top-level [PipelineManager]'s list
     * so we can build a comprehensive list of all nodes in a pipeline
     */
    protected fun registerNode(node: Node) {
        parentPipelineManager?.registerNode(node) ?: run {
            nodes.add(node)
        }
    }

    private fun addNode(node: Node) {
        // Notify the parent of this node if we have one
        registerNode(node)
        if (head == null) {
            head = node
        }
        if (tail is DemuxerNode) {
            throw Exception("Cannot attach node to a DemuxerNode")
        }
        tail?.attach(node)
        tail = node
    }

    fun node(block: () -> Node) {
        val createdNode = block()
        addNode(createdNode)
    }

    fun node(node: Node) = addNode(node)

    /**
     * simpleNode allows the caller to pass in a block of code which takes a list of input
     * [Packet]s and returns a list of output [Packet]s to be forwarded to the next
     * [Node]
     */
    fun simpleNode(name: String, packetHandler: (List<Packet>) -> List<Packet>) {
        val node = object : Node(name) {
            override fun doProcessPackets(p: List<Packet>) {
                next(packetHandler.invoke(p))
            }
        }
        addNode(node)
    }

    fun demux(block: DemuxerNode.() -> Unit) {
        val demuxer = DemuxerNode().apply(block)
        addNode(demuxer)
    }
}

fun pipelineManager(block: PipelineManager.() -> Unit): PipelineManager = PipelineManager().apply(block)
fun pipelineManager(parentPipelineManager: PipelineManager, block: PipelineManager.() -> Unit): PipelineManager = PipelineManager(parentPipelineManager).apply(block)

// A packet tree is defined by a single root PacketHandler and represents
// potentially multiple packet paths (as it may branch out)
//class PacketTreeBuilder {
//    private var head: PacketHandler? = null
//    private var tail: PacketHandler? = null
//    private var treeTerminated = false
//
//    private fun addHandler(handler: PacketHandler) {
//        if (treeTerminated) {
//            throw Exception("Handler cannot be added after tree has been terminated")
//        }
//        if (head == null) {
//            head = handler
//        }
//        tail?.attach(handler)
//        tail = handler
//    }
//
//    fun handler(block: () -> PacketHandler): PacketHandler {
//        val createdHandler = block()
//        addHandler(createdHandler)
//        return createdHandler
//    }
//
//    fun simpleHandler(name: String, block: SimplePacketHandler.(List<Packet>) -> Unit): PacketHandler {
//        val simpleHandler = SimplePacketHandler(name, block)
//        addHandler(simpleHandler)
//        return simpleHandler
//    }
//
//    fun handler(handler: PacketHandler) = addHandler(handler)
//
//    /**
//     * After adding a demuxer, no more handlers can be added directly to this
//     * tree.  A demuxer represents a subtree and all paths from here on
//     * must stem from that subtree.
//     */
//    fun demux(block: DemuxerModule.() -> Unit): PacketHandler {
//        val demuxerModule = DemuxerModule().apply(block)
//        addHandler(demuxerModule)
//        treeTerminated = true
//
//        return demuxerModule
//    }
//
//    fun build(): PacketHandler = head!!
//}
//
//fun packetTree(block: PacketTreeBuilder.() -> Unit): PacketHandler {
//    val builder = PacketTreeBuilder().apply(block)
//
//    return builder.build()
//}

//fun onPackets(block: SimplePacketHandler.(List<Packet>) -> Unit): PacketHandler {
//    return SimplePacketHandler(block)
//}

