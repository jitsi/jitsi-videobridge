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
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.Stoppable
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.transform.NodeVisitor
import org.jitsi.nlj.util.Util.Companion.getMbps
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.PacketPredicate
import java.time.Duration
import java.util.function.Predicate
import kotlin.properties.Delegates
import kotlin.streams.toList

/**
 * An abstract base class for all [Node] subclasses.  This class
 * takes care of the following behaviors:
 * 1) Attaching the next node in the chain
 * 2) Adding and removing parent nodes
 * 3) Propagating [visit] calls
 *
 */
abstract class Node(var name: String
) : PacketHandler, EventHandler, NodeStatsProducer, Stoppable {

    private var nextNode: Node? = null
    private val inputNodes: MutableList<Node> by lazy { mutableListOf<Node>() }
    // Create these once here so we don't allocate a new string every time
    protected val nodeEntryString = "Entered node $name"
    protected val nodeExitString = "Exited node $name"

    protected val logger = getLogger(this.javaClass)

    open fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
    }

    /**
     * Marking this as open since [DemuxerNode] wants to throw an exception
     * if attach is called.
     */
    open fun attach(node: Node): Node {
        // Remove ourselves as an input from the node we're currently connected to
        nextNode?.removeParent(this)
        nextNode = node
        node.addParent(this)

        return node
    }

    open fun detachNext() {
        nextNode?.removeParent(this)
        nextNode = null
    }


    fun addParent(newParent: Node) {
        inputNodes.add(newParent)
    }

    fun removeParent(parent: Node) {
        inputNodes.remove(parent)
    }

    open fun getChildren(): Collection<Node> {
        val actualNextNode = nextNode ?: return listOf()
        return listOf(actualNextNode)
    }

    open fun getParents(): Collection<Node> = inputNodes

    override fun handleEvent(event: Event) {
        // No-op by default
    }

    override fun stop() {
        // No-op by default
    }

    protected fun next(packetInfo: PacketInfo) {
        nextNode?.processPacket(packetInfo)
    }

    protected fun next(packetInfos: List<PacketInfo>) {
        packetInfos.forEach { packetInfo ->
            nextNode?.processPacket(packetInfo)
        }
    }
}

/**
 * A [Node] which keeps track of some basic statistics, such as number of packets and bytes that passed through
 * it, and the processing time. In order to accurately compute the processing time, this class depends on its
 * subclasses calling [doneProcessing] when they finish their own processing of the packet, but before they pass the
 * packet to any children. The intention is for this class to not be subclassed directly, except from classes defined
 * in this file (but making it 'private' doesn't seem possible).
 */
sealed class StatsKeepingNode(name: String): Node(name) {
    /**
     * The time at which processing of the currently processed packet started (in nanos).
     */
    private var startTime: Long = 0

    /**
     * The time (in nanos) that [processPacket] was first called.
     */
    private var firstPacketTime: Long = -1

    /**
     * The time (in nanos) that [processPacket] was last called.
     */
    private var lastPacketTime: Long = -1

    /**
     * Total nanoseconds spent processing packets in this node.
     */
    private var totalProcessingDuration: Long = 0

    private var numInputPackets = 0
    private var numOutputPackets = 0
    private var numInputBytes: Long = 0

    /**
     * The function that all subclasses should implement to do the actual
     * packet processing.  A protected method is used for this so we can
     * guarantee all packets pass through this base for stat-tracking
     * purposes.
     */
    protected abstract fun doProcessPacket(packetInfo: PacketInfo)

    override fun processPacket(packetInfo: PacketInfo) {
        onEntry(packetInfo)
        doProcessPacket(packetInfo)
    }

    override fun getNodeStats(): NodeStatsBlock {
        return NodeStatsBlock("Node $name ${hashCode()}").apply {
            addStat("numInputPackets: $numInputPackets")
            addStat("numOutputPackets: $numOutputPackets")
            addStat("total time spent: ${Duration.ofNanos(totalProcessingDuration).toMillis()} ms")
            addStat("average time spent per packet: ${Duration.ofNanos(totalProcessingDuration / Math.max(numInputPackets, 1)).toNanos()} ns")
            addStat("$numInputBytes bytes over ${Duration.ofNanos(lastPacketTime - firstPacketTime).toMillis()} ms")
            addStat("throughput: ${getMbps(numInputBytes, Duration.ofNanos(lastPacketTime - firstPacketTime))} mbps")
            addStat("processing throughput: ${getMbps(numInputBytes, Duration.ofNanos(totalProcessingDuration))} mbps")
        }
    }

    private fun onEntry(packetInfo: PacketInfo) {
        startTime = System.nanoTime()
        if (firstPacketTime == -1L) {
            firstPacketTime = startTime
        }

        numInputPackets++
        numInputBytes += packetInfo.packet.sizeBytes

        packetInfo.addEvent(nodeEntryString)
        lastPacketTime = startTime
    }

    /**
     * Should be called by sub classes when they finish processing of the input packet, but before they call into any
     * other nodes, so that [Node] can keep track of its statistics.
     */
    protected fun doneProcessing(packetInfo: PacketInfo?) {
        val processingDuration = System.nanoTime() - startTime
        totalProcessingDuration += processingDuration

        packetInfo?.let {
            numOutputPackets++
            it.addEvent(nodeExitString)
        }
    }

    /**
     * Should be called by sub classes when they finish processing of the input packet, but before they call into any
     * other nodes, so that [Node] can keep track of its statistics.
     */
    protected fun doneProcessing(packetInfos: List<PacketInfo>) {
        val processingDuration = System.nanoTime() - startTime
        totalProcessingDuration += processingDuration

        numOutputPackets += packetInfos.size
        packetInfos.forEach {
            it.addEvent(nodeExitString)
        }
    }
}

/**
 * A [Node] which transforms a single packet, possibly dropping it (by returning null).
 */
abstract class TransformerNode(
    name: String
) : StatsKeepingNode(name) {

    protected abstract fun transform(packetInfo: PacketInfo): PacketInfo?

    override fun doProcessPacket(packetInfo: PacketInfo) {
        val transformedPacket = transform(packetInfo)
        doneProcessing(transformedPacket)
        next(packetInfo)
    }
}

/**
 * A [Node] which drops some of the packets (the ones which are not accepted).
 */
abstract class FilterNode(
    name: String
) : TransformerNode(name) {

    protected abstract fun accept(packetInfo: PacketInfo): Boolean

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        return if (accept(packetInfo)) packetInfo else null
    }
}

typealias PacketInfoPredicate = Predicate<PacketInfo>
abstract class PredicateFilterNode(
    name: String,
    val predicate: PacketInfoPredicate
): FilterNode(name) {
    override fun accept(packetInfo: PacketInfo): Boolean {
        return predicate.test(packetInfo)
    }
}

/**
 * A [Node] which observes packets, but makes no modifications.
 */
abstract class ObserverNode(
    name: String
) : TransformerNode(name) {

    protected abstract fun observe(packetInfo: PacketInfo)

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        observe(packetInfo)
        return packetInfo
    }
}

/**
 * A node which consumes all packets (i.e. does something with them, but does not forward them to another node).
 */
abstract class ConsumerNode(
    name: String
) : TransformerNode(name) {

    protected abstract fun consume(packetInfo: PacketInfo)

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        consume(packetInfo)
        return null
    }

    // Consumer nodes shouldn't have children, because they don't forward
    // any packets anyway.
    override fun attach(node: Node) = throw Exception()
}

/**
 * A [Node] which transforms a single packet into a list of packets.
 */
abstract class MultipleOutputTransformerNode(
    name: String
) : StatsKeepingNode(name) {

    protected abstract fun transform(packetInfo: PacketInfo): List<PacketInfo>

    override fun doProcessPacket(packetInfo: PacketInfo) {

        val outputPacketInfos = transform(packetInfo)
        doneProcessing(outputPacketInfos)
        next(outputPacketInfos)
    }
}

class ConditionalPacketPath() {
    var name: String by Delegates.notNull()
    var predicate: PacketPredicate by Delegates.notNull()
    var path: Node by Delegates.notNull()
    var packetsAccepted: Int = 0

    constructor(name: String): this() {
        this.name = name
    }
}

abstract class DemuxerNode(name: String) : StatsKeepingNode("$name demuxer") {
    protected var transformPaths: MutableSet<ConditionalPacketPath> = mutableSetOf()
    protected var packetsDropped: Int = 0

    fun addPacketPath(packetPath: ConditionalPacketPath): DemuxerNode {
        transformPaths.add(packetPath)
        // DemuxerNode never uses the plain 'next' call since it doesn't have a single 'next'
        // node (it has multiple downstream paths), but we want to make sure the paths correctly
        // see this Demuxer in their 'inputNodes' so that we can traverse the reverse tree
        // correctly, so we call attach here to get the inputNodes wired correctly.
        super.attach(packetPath.path)

        return this
    }

    fun addPacketPath(name: String, predicate: PacketPredicate, root: Node): DemuxerNode {
        val path = ConditionalPacketPath(name)
        path.predicate = predicate
        path.path = root

        return addPacketPath(path)
    }

    fun removePacketPaths() {
        //TODO: concurrency issues here
        transformPaths.forEach { it.path.removeParent(this) }
        transformPaths.clear()
    }

    override fun attach(node: Node) = throw Exception()
    override fun detachNext() = throw Exception()

    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
        transformPaths.forEach { conditionalPath ->
            conditionalPath.path.visit(visitor)
        }
    }

    override fun getChildren(): Collection<Node> = transformPaths.stream().map(ConditionalPacketPath::path).toList()

    override fun getNodeStats(): NodeStatsBlock {
        val superStats = super.getNodeStats()

        val demuxerBlock = NodeStatsBlock("Path packet counts:")
        transformPaths.forEach { path ->
            demuxerBlock.addStat("${path.name}: ${path.packetsAccepted}")
        }
        demuxerBlock.addStat("Dropped: $packetsDropped")
        superStats.addStat(demuxerBlock.name, demuxerBlock)

        return superStats
    }
}

/**
 * Packets are passed only to the first path which accepts them
 */
class ExclusivePathDemuxer(name: String) : DemuxerNode(name) {
    override fun doProcessPacket(packetInfo: PacketInfo) {
        transformPaths.forEach { conditionalPath ->
            if (conditionalPath.predicate.test(packetInfo.packet)) {
                doneProcessing(packetInfo)
                conditionalPath.packetsAccepted++
                conditionalPath.path.processPacket(packetInfo)
                return
            }
        }
        packetsDropped++
    }
}