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
package org.jitsi.nlj.transform.module

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.transform.StatsProducer
import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.nlj.util.Util.Companion.getMbps
import org.jitsi.nlj.util.appendIndent
import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.rtp.Packet
import java.math.BigDecimal
import java.time.Duration
import kotlin.properties.Delegates



abstract class Module(
    override var name: String,
    protected val debug: Boolean = false
) : PacketHandler, StatsProducer {
    private var nextHandler: PacketHandler? = null
    // Stats stuff
    private var startTime: Long = 0
    private var totalTime: Long = 0
    private var numInputPackets = 0
    private var numOutputPackets = 0
    private var firstPacketTime: Long = -1
    private var lastPacketTime: Long = -1
    private var numBytes: Long = 0

//    override fun attach(nextHandler: PacketHandler) {
//        this.nextHandler = nextHandler
//    }

    private fun getTime(): Long = System.nanoTime()

    private fun onEntry(incomingPackets: List<Packet>) {
        if (debug) {
            println("Entering module $name")
        }
        startTime = getTime()
        if (firstPacketTime == -1L) {
            firstPacketTime = System.currentTimeMillis()
        }
        incomingPackets.forEach { numBytes += it.size }
        lastPacketTime = System.currentTimeMillis()
        numInputPackets += incomingPackets.size
    }

    private fun onExit() {
        val time = getTime() - startTime
        if (debug) {
            println("Exiting module $name, took $time nanos")
        }
        totalTime += time
    }

    /**
     * The function that all subclasses should implement to do the actual
     * packet processing.  A protected method is used for this so we can
     * guarantee all packets pass through this base for stat-tracking
     * purposes.
     */
    protected abstract fun doProcessPackets(p: List<Packet>)

    protected fun next(pkts: List<Packet>) {
        onExit()
        numOutputPackets += pkts.size
        if (pkts.isNotEmpty()) {
            nextHandler?.processPackets(pkts)
        }
    }

    /**
     * Allow the implementing class to specify the next handler to invoke with the
     * given packets.  This is necessary for things like [DemuxerModule] which have
     * multiple subsequent paths packets can flow down, so they don't use the singular
     * [nextHandler].
     */
    protected fun next(handler: PacketHandler, pkts: List<Packet>) {
        onExit()
        numOutputPackets += pkts.size
        handler.processPackets(pkts)
    }

    override fun processPackets(pkts: List<Packet>) {
        onEntry(pkts)
        doProcessPackets(pkts)
    }

    override fun handleEvent(event: Event) {
        nextHandler?.handleEvent(event)
    }

//    override fun getRecursiveStats(indent: Int): String {
//        return with (StringBuffer()) {
//            append(getStats(indent))
//            nextHandler?.let { append(it.getStats(indent))}
//            toString()
//        }
//    }

    override fun getStats(indent: Int): String {
        return with (StringBuffer()) {
            appendLnIndent(indent, "$name stats:")
            appendLnIndent(indent + 2, "numInputPackets: $numInputPackets")
            appendLnIndent(indent + 2, "numOutputPackets: $numOutputPackets")
            appendLnIndent(indent + 2, "total time spent: ${totalTime / 1000000.0} ms")
            appendLnIndent(indent + 2, "average time spent per packet: ${(totalTime / Math.max(numInputPackets, 1)) / 1000000.0} ms")
            appendLnIndent(indent + 2, "$numBytes bytes over ${lastPacketTime - firstPacketTime} ms")
            appendLnIndent(indent + 2, "throughput: ${getMbps(numBytes, Duration.ofMillis(lastPacketTime - firstPacketTime))} mbps")
            appendLnIndent(indent + 2, "individual module throughput: ${getMbps(numBytes, Duration.ofNanos(totalTime))} mbps")
            toString()
        }
    }
}


class PacketPath {
    var predicate: PacketPredicate by Delegates.notNull()
    var path: PacketHandler by Delegates.notNull()
}

/**
 * This method should only be called when the caller is confident the
 * contents of the iterable contain [Expected] types.  Because of this,
 * throwing an exception if that isn't the case is desired.
 */
@Suppress("UNCHECKED_CAST")
inline fun <Expected> Iterable<*>.forEachAs(action: (Expected) -> Unit) {
    for (element in this) action(element as Expected)
}
inline fun <reified Expected> Iterable<*>.forEachIf(action: (Expected) -> Unit) {
    for (element in this) {
        if (element is Expected) action(element)
    }
}
