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

import org.jitsi.nlj.Event
import org.jitsi.nlj.EventHandler
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.transform.StatsProducer
import org.jitsi.nlj.transform.module.getMbps
import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.rtp.Packet
import java.time.Duration
import kotlin.properties.Delegates

interface NodeVisitor {
    fun visit(node: Node)
}

class NodeStatsVisitor(val sb: StringBuffer = StringBuffer()) : NodeVisitor {
    override fun visit(node: Node) {
        //TODO: How to do the indent?
        sb.append(node.getStats())
    }
}

class NodeEventVisitor(val event: Event) : NodeVisitor {
    override fun visit(node: Node) {
        node.handleEvent(event)
    }
}

/**
 * An abstract base class for all [Node] subclasses.  This class
 * takes care of the following behaviors:
 * 1) Attaching the next node in the chain
 * 2) Basic stat tracking (time duration, packets in/out, bytes in/out,
 * throughput, etc.)
 *
 */
abstract class Node(
    override var name: String
) : PacketHandler, EventHandler, StatsProducer {
    private var nextNode: Node? = null

    // Stats stuff
    private var startTime: Long = 0
    private var totalProcessingDuration: Long = 0
    private var numInputPackets = 0
    private var numOutputPackets = 0
    private var firstPacketTime: Long = -1
    private var lastPacketTime: Long = -1
    private var numBytes: Long = 0

    open fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
        nextNode?.visit(visitor)
    }
    /**
     * The function that all subclasses should implement to do the actual
     * packet processing.  A protected method is used for this so we can
     * guarantee all packets pass through this base for stat-tracking
     * purposes.
     */
    protected abstract fun doProcessPackets(p: List<Packet>)

    /**
     * Marking this as open since Demuxer wants to throw an exception
     * if attach is called.
     */
    open fun attach(node: Node) {
        nextNode = node
    }

    override fun processPackets(pkts: List<Packet>) {
        onEntry(pkts)
        doProcessPackets(pkts)
    }

    override fun handleEvent(event: Event) {
        // No-op by default
    }

    override fun getStats(indent: Int): String {
        return with (StringBuffer()) {
            appendLnIndent(indent, "$name stats:")
            appendLnIndent(indent + 2, "numInputPackets: $numInputPackets")
            appendLnIndent(indent + 2, "numOutputPackets: $numOutputPackets")
            appendLnIndent(indent + 2, "total time spent: ${Duration.ofNanos(totalProcessingDuration).toMillis()} ms")
            appendLnIndent(indent + 2, "average time spent per packet: ${Duration.ofNanos(totalProcessingDuration / Math.max(numInputPackets, 1)).toMillis()} ms")
            appendLnIndent(indent + 2, "$numBytes bytes over ${Duration.ofNanos(lastPacketTime - firstPacketTime).toMillis()} ms")
            appendLnIndent(indent + 2, "throughput: ${getMbps(
                numBytes,
                Duration.ofNanos(lastPacketTime - firstPacketTime)
            )} mbps")
            appendLnIndent(indent + 2, "individual module throughput: ${getMbps(
                numBytes,
                Duration.ofNanos(totalProcessingDuration)
            )} mbps")
            toString()
        }
    }

    protected fun next(outPackets: List<Packet>) {
        onExit()
        numOutputPackets += outPackets.size
        if (outPackets.isNotEmpty()) {
            nextNode?.processPackets(outPackets)
        }
    }

    /**
     * Allow the implementing class to specify the next handler to invoke with the
     * given packets.  This is necessary for things like [DemuxerModule] which have
     * multiple subsequent paths packets can flow down, so they don't use the singular
     * [nextNode].
     */
    protected fun next(nextNode: Node, outPackets: List<Packet>) {
        onExit()
        numOutputPackets += outPackets.size
        nextNode.processPackets(outPackets)
    }

    private fun onEntry(incomingPackets: List<Packet>) {
        startTime = System.nanoTime()
        if (firstPacketTime == -1L) {
            firstPacketTime = startTime
        }
        incomingPackets.forEach { numBytes += it.size }
        lastPacketTime = System.nanoTime()
        numInputPackets += incomingPackets.size
    }

    private fun onExit() {
        val processingDuration = System.nanoTime() - startTime
        totalProcessingDuration += processingDuration
    }
}

class PacketPath {
    var predicate: PacketPredicate by Delegates.notNull()
    var path: Node by Delegates.notNull()
}

