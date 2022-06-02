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
package org.jitsi.nlj.transform.node

import org.jitsi.nlj.Event
import org.jitsi.nlj.EventHandler
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.Stoppable
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.transform.NodeVisitor
import org.jitsi.nlj.transform.node.debug.PayloadVerificationPlugin
import org.jitsi.nlj.util.BufferPool
import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.nlj.util.addMbps
import org.jitsi.nlj.util.addRatio
import org.json.simple.JSONObject
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.function.Predicate
import kotlin.properties.Delegates
import kotlin.streams.toList

/**
 * An abstract base class for all [Node] subclasses.  This class
 * takes care of the following behaviors:
 * 1) Attaching the next node in the chain
 * 2) Adding and removing parent nodes
 * 3) Propagating [visit] calls
 */
sealed class Node(
    var name: String
) : PacketHandler, EventHandler, NodeStatsProducer, Stoppable {

    private var nextNode: Node? = null
    private val inputNodes: MutableList<Node> by lazy { mutableListOf<Node>() }
    // Create these once here so we don't allocate a new string every time
    protected val nodeEntryString = "Entered node $name"
    protected val nodeExitString = "Exited node $name"

    open fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
    }

    /**
     * Marking this as open since [DemuxerNode] wants to throw an exception
     * if attach is called.
     */
    open fun attach(node: Node): Node {
        // Remove ourselves as an input from the node we're currently connected to
        if (nextNode != null) {
            throw Exception("Attempt to replace a Node's child. If this is intentional, use detachNext first.")
        }
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
        if (PLUGINS_ENABLED) {
            plugins.forEach { it.observe(this, packetInfo) }
        }
        nextNode?.processPacket(packetInfo)
    }

    protected fun next(packetInfos: List<PacketInfo>) {
        packetInfos.forEach { packetInfo ->
            if (PLUGINS_ENABLED) {
                plugins.forEach { it.observe(this, packetInfo) }
            }
            nextNode?.processPacket(packetInfo)
        }
    }
    /**
     * This function must be implemented by leaf nodes, as
     * ```
     *     override fun trace(f: () -> Unit) = f.invoke()
     * ```
     * or the Java equivalent.  When [NODE_TRACING] is
     * turned on, this ensures that call stacks always include an method from
     * the derived class, rather than one of the parent classes.  This can greatly
     * aid debugging and profiling.
     */

    abstract fun trace(f: () -> Unit)

    companion object {
        var TRACE_ENABLED = false
        var PLUGINS_ENABLED = false
        // 'Plugins' are observers which, when enabled, will be passed every packet that passes through
        // every node
        val plugins: MutableSet<NodePlugin> = mutableSetOf()

        fun enablePayloadVerification(enable: Boolean) {
            if (enable) {
                PLUGINS_ENABLED = true
                plugins.add(PayloadVerificationPlugin)
                PacketInfo.ENABLE_PAYLOAD_VERIFICATION = true
            } else {
                plugins.remove(PayloadVerificationPlugin)
                PLUGINS_ENABLED = plugins.isNotEmpty()
                PacketInfo.ENABLE_PAYLOAD_VERIFICATION = false
            }
        }

        fun isPayloadVerificationEnabled(): Boolean = PacketInfo.ENABLE_PAYLOAD_VERIFICATION

        fun enableNodeTracing(enable: Boolean) {
            TRACE_ENABLED = enable
        }

        fun isNodeTracingEnabled(): Boolean = TRACE_ENABLED
    }
}

/**
 * A [Node] which keeps track of some basic statistics, such as number of packets and bytes that passed through
 * it, and the processing time. In order to accurately compute the processing time, this class depends on its
 * subclasses calling [doneProcessing] when they finish their own processing of the packet, but before they pass the
 * packet to any children. The intention is for this class to not be subclassed directly, except from classes defined
 * in this file (but making it 'private' doesn't seem possible).
 */
sealed class StatsKeepingNode(name: String) : Node(name) {
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
     * Keeps stats for this [Node]
     */
    private val stats = NodeStats()

    /**
     * Avoid stopping more than once.
     */
    private var stopped = false

    /**
     * The function that all subclasses should implement to do the actual
     * packet processing.  A protected method is used for this so we can
     * guarantee all packets pass through this base for stat-tracking
     * purposes.
     */
    protected abstract fun doProcessPacket(packetInfo: PacketInfo)

    override fun processPacket(packetInfo: PacketInfo) {
        onEntry(packetInfo)
        if (TRACE_ENABLED) {
            trace { doProcessPacket(packetInfo) }
        } else {
            doProcessPacket(packetInfo)
        }
    }

    override fun getNodeStats() = NodeStatsBlock("Node $name ${hashCode()}").apply {
        this@StatsKeepingNode.stats.appendTo(this)
        val numBytes = this@StatsKeepingNode.stats.numInputBytes

        val duration = Duration.ofNanos(lastPacketTime - firstPacketTime)
        addNumber("num_input_bytes", numBytes)
        addNumber("duration_ms", duration.toMillis())
        addMbps("throughput_mbps", "num_input_bytes", "duration_ms")
    }

    /**
     * Get the stats that should be aggregated per-class.
     */
    protected open fun getNodeStatsToAggregate() = getNodeStats()
    protected open val aggregationKey: String = javaClass.name.split(".").last()

    private fun onEntry(packetInfo: PacketInfo) {
        if (enableStatistics) {
            startTime = System.nanoTime()
            if (firstPacketTime == -1L) {
                firstPacketTime = startTime
            }

            stats.numInputPackets++
            stats.numInputBytes += packetInfo.packet.length

            packetInfo.addEvent(nodeEntryString)
            lastPacketTime = startTime
        }
    }

    /**
     * Should be called by sub classes when they finish processing of the input packet, but before they call into any
     * other nodes, so that [Node] can keep track of its statistics.
     */
    protected fun doneProcessing(packetInfo: PacketInfo?) {
        if (enableStatistics) {
            val processingDuration = System.nanoTime() - startTime
            stats.totalProcessingDurationNs += processingDuration
            stats.maxProcessingDurationNs = Math.max(stats.maxProcessingDurationNs, processingDuration)

            packetInfo?.let {
                stats.numOutputPackets++
                it.addEvent(nodeExitString)
            }
        }
    }

    /**
     * Should be called by sub classes when they finish processing of the input packet, but before they call into any
     * other nodes, so that [Node] can keep track of its statistics.
     */
    protected fun doneProcessing(packetInfos: List<PacketInfo>) {
        if (enableStatistics) {
            val processingDuration = System.nanoTime() - startTime
            stats.totalProcessingDurationNs += processingDuration
            stats.maxProcessingDurationNs = Math.max(stats.maxProcessingDurationNs, processingDuration)

            stats.numOutputPackets += packetInfos.size
            packetInfos.forEach {
                it.addEvent(nodeExitString)
            }
        }
    }

    protected open fun packetDiscarded(packetInfo: PacketInfo) {
        stats.numDiscardedPackets++
        BufferPool.returnBuffer(packetInfo.packet.buffer)
    }

    override fun stop() {
        if (stopped) {
            return
        }
        stopped = true

        if (enableStatistics && stats.numInputPackets > 0) {
            synchronized(globalStats) {
                val classStats = globalStats.computeIfAbsent(aggregationKey) { NodeStatsBlock(aggregationKey) }
                classStats.aggregate(getNodeStatsToAggregate())
            }
        }
    }

    companion object {
        /**
         * Maps a [Node]'s class name to a [NodeStats] object with aggregated stats for all instances of that class.
         */
        private val globalStats: MutableMap<String, NodeStatsBlock> = ConcurrentHashMap()

        var enableStatistics = true

        /**
         * Gets the aggregated statistics for all classes as a JSON map.
         */
        fun getStatsJson(): JSONObject {
            val jsonObject = JSONObject()
            globalStats.forEach { (className, stats) ->
                jsonObject[className] = stats.toJson()
            }
            jsonObject["num_payload_verification_failures"] = PayloadVerificationPlugin.numFailures.get()
            return jsonObject
        }
    }

    /**
     * This just holds the stats kept by [StatsKeepingNode] itself.
     */
    data class NodeStats(
        /**
         * Total nanoseconds spent processing packets in this node.
         */
        var totalProcessingDurationNs: Long = 0,
        var numInputPackets: Long = 0,
        var numOutputPackets: Long = 0,
        var numInputBytes: Long = 0,
        var numDiscardedPackets: Long = 0,
        /**
         * The longest time it took to process a single packet.
         */
        var maxProcessingDurationNs: Long = 0
    ) {
        private val maxProcessingDurationMs: Double
            get() = maxProcessingDurationNs / 1_000_000.0

        fun appendTo(block: NodeStatsBlock) {
            block.apply {
                addNumber("num_input_packets", numInputPackets)
                addNumber("num_output_packets", numOutputPackets)
                addNumber("num_discarded_packets", numDiscardedPackets)
                addNumber("total_time_spent_ns", totalProcessingDurationNs)
                addCompoundValue("total_time_spent_ms") {
                    Duration.ofNanos(it.getNumberOrDefault("total_time_spent_ns", 0).toLong()).toMillis()
                }
                addRatio("average_time_per_packet_ns", "total_time_spent_ns", "num_input_packets")
                addMbps("processing_throughput_mbps", "num_input_bytes", "total_time_spent_ms")
                addNumber("max_packet_process_time_ms", maxProcessingDurationMs)
            }
        }
    }
}

/**
 * A node which will always forward the [PacketInfo] it is given.
 * NOTE that the [PacketInfo] instance may be modified, but only the original
 * [PacketInfo] instance will be forwarded.
 */
abstract class NeverDiscardNode(name: String) : StatsKeepingNode(name) {
    protected abstract fun handlePacket(packetInfo: PacketInfo)

    final override fun doProcessPacket(packetInfo: PacketInfo) {
        handlePacket(packetInfo)
        doneProcessing(packetInfo)
        next(packetInfo)
    }
}

/**
 * A [Node] which transforms a single packet, possibly dropping it (by returning null).
 * If null is returned, the [PacketInfo] instance given to [transform] will be
 * discarded.
 */
abstract class TransformerNode(name: String) : StatsKeepingNode(name) {
    protected abstract fun transform(packetInfo: PacketInfo): PacketInfo?

    override fun doProcessPacket(packetInfo: PacketInfo) {
        val transformedPacket = transform(packetInfo)
        doneProcessing(transformedPacket)
        when (transformedPacket) {
            null -> super.packetDiscarded(packetInfo)
            else -> next(transformedPacket)
        }
    }

    final override fun packetDiscarded(packetInfo: PacketInfo) {
        throw Exception(
            "No subclass of TransformerNode should call packetDiscarded, return null from 'transform' instead"
        )
    }
}

/**
 * Unlike a [TransformerNode], [ModifierNode] modifies a packet in-place and never
 * outright 'fails', meaning the original [PacketInfo] will *always* be forwarded.
 */
abstract class ModifierNode(name: String) : NeverDiscardNode(name) {
    protected abstract fun modify(packetInfo: PacketInfo): PacketInfo

    override fun handlePacket(packetInfo: PacketInfo) {
        modify(packetInfo)
    }
}

/**
 * A [Node] which drops some of the packets (the ones which are not accepted).
 */
abstract class FilterNode(name: String) : TransformerNode(name) {
    protected abstract fun accept(packetInfo: PacketInfo): Boolean

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        return if (accept(packetInfo)) {
            packetInfo
        } else {
            null
        }
    }
}

typealias PacketInfoPredicate = Predicate<PacketInfo>
abstract class PredicateFilterNode(
    name: String,
    val predicate: PacketInfoPredicate
) : FilterNode(name) {
    override fun accept(packetInfo: PacketInfo): Boolean {
        return predicate.test(packetInfo)
    }
}

/**
 * A [Node] which observes packets, but makes no modifications.
 */
abstract class ObserverNode(name: String) : NeverDiscardNode(name) {

    protected abstract fun observe(packetInfo: PacketInfo)

    override fun handlePacket(packetInfo: PacketInfo) {
        observe(packetInfo)
    }
}

/**
 * A node which consumes all packets (i.e. does something with them, but does not forward them to another node).
 */
abstract class ConsumerNode(name: String) : StatsKeepingNode(name) {

    protected abstract fun consume(packetInfo: PacketInfo)

    override fun doProcessPacket(packetInfo: PacketInfo) {
        consume(packetInfo)
        doneProcessing(packetInfo)
    }

    // Consumer nodes shouldn't have children, because they don't forward
    // any packets anyway.
    override fun attach(node: Node): Node =
        throw Exception("ConsumerNode must be a terminal and should not have child nodes attached.")
}

/**
 * A [Node] which transforms a single packet into a list of packets.
 */
abstract class MultipleOutputTransformerNode(name: String) : StatsKeepingNode(name) {

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

    constructor(name: String) : this() {
        this.name = name
    }
}

abstract class DemuxerNode(name: String) : StatsKeepingNode("$name demuxer") {
    protected var transformPaths: MutableSet<ConditionalPacketPath> = CopyOnWriteArraySet()

    fun addPacketPath(packetPath: ConditionalPacketPath): DemuxerNode {
        transformPaths.add(packetPath)
        // We want to make sure the paths correctly see this Demuxer in their 'inputNodes' so that we can traverse the
        // reverse tree correctly.
        packetPath.path.addParent(this)

        return this
    }

    fun addPacketPath(name: String, predicate: PacketPredicate, root: Node): DemuxerNode {
        val path = ConditionalPacketPath(name)
        path.predicate = predicate
        path.path = root

        return addPacketPath(path)
    }

    fun removePacketPaths() {
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

        transformPaths.forEach { path ->
            superStats.addNumber("packets_accepted_${path.name}", path.packetsAccepted)
        }
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
        packetDiscarded(packetInfo)
    }
    override val aggregationKey = this.name

    override fun trace(f: () -> Unit) = f.invoke()
}
