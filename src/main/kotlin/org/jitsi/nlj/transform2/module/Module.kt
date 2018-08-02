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
package org.jitsi.nlj.transform2.module

import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtcp.RtcpPacket
import java.math.BigDecimal
import java.time.Duration
import kotlin.properties.Delegates
import kotlin.reflect.KClass
import kotlin.system.measureTimeMillis

typealias PacketHandler = (List<Packet>) -> Unit

fun getMbps(numBytes: Long, duration: Duration): String {
    if (duration.seconds == 0L) {
        return "Infinity"
    }
    val numBits = BigDecimal(numBytes * 8)
    val megaBits = (numBits / BigDecimal(1000000.0)).toFloat()
    return "%.2f".format(megaBits / duration.seconds)
}

class ModuleChain {
    val modules = mutableListOf<Module>()
    val durations = mutableListOf<Double>()
    private var name: String = ""

    fun name(n: String) {
        this.name = n
    }

    fun module(m: Module) {
        addAndConnect(m)
    }

    fun demux(b: DemuxerModule.() -> Unit) {
        val sm = DemuxerModule().apply(b)
        addAndConnect(sm)
    }

    fun mux(b: MuxerModule.() -> Unit) {
        val mm = MuxerModule().apply(b)
        addAndConnect(mm)
    }

    //TODO: trying this as an easy way to add a final output, but that means
    // should probably enforce that nothing else can be added after this
    // other option would be to force the user to implement a module to put
    // the packets somewhere.
    fun attach(handler: PacketHandler) {
        val previousModule = modules.lastOrNull()
        //println("Attaching handler to $previousModule")
        previousModule?.attach(handler)
    }

    private fun addAndConnect(m: Module) {
        val previousModule = modules.lastOrNull()
        modules.add(m)
        previousModule?.attach(m::processPackets)
    }

    fun processPackets(pkts: List<Packet>) {
        val time = measureTimeMillis {
            modules[0].processPackets(pkts)
        }
        durations.add(time / pkts.size.toDouble() )
        durations.dropLastWhile { durations.size > 100 }
    }

    fun getStats(indent: Int = 0): String {
        return with (StringBuffer()) {
            appendLnIndent(indent, name)
            appendLnIndent(indent, "Average time spent in this chain per packet: ${durations.average()} ms")
            modules.forEach { append(it.getStats(indent + 2)) }
            toString()
        }
    }

    fun findFirst(moduleClass: KClass<*>): Module? {
        for (m in modules) {
            if (m::class == moduleClass) {
                return m
            } else if (m is DemuxerModule) {
                val nested = m.findFirst(moduleClass)
                if (nested != null) { return nested }
            }
        }
        return null
    }

    fun findAll(moduleClass: KClass<*>): List<Module> {
        return modules.filter { it -> it::class == moduleClass }
    }
}

abstract class Module(var name: String, protected val debug: Boolean = false) {
    private var nextModule: (List<Packet>) -> Unit = {}
    private var startTime: Long by Delegates.notNull()
    private var totalTime: Long = 0
    private var numInputPackets = 0
    private var numOutputPackets = 0
    private var firstPacketTime: Long = -1
    private var lastPacketTime: Long = -1
    private var numBytes: Long = 0

    open fun attach(nextModule: Function1<List<Packet>, Unit>) {
        this.nextModule = nextModule
    }

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

    private fun onExit(outgoingPackets: List<Packet>) {
        val time = getTime() - startTime
        if (debug) {
            println("Exiting module $name, took $time nanos")
        }
        totalTime += time
    }

    protected abstract fun doProcessPackets(p: List<Packet>)

    protected fun next(pkts: List<Packet>) {
        onExit(pkts)
        numOutputPackets += pkts.size
        nextModule.invoke(pkts)
    }

    protected fun next(chain: ModuleChain, pkts: List<Packet>) {
        onExit(pkts)
        numOutputPackets += pkts.size
        chain.processPackets(pkts)
    }

    fun processPackets(p: List<Packet>) {
        onEntry(p)
        doProcessPackets(p)
    }

    open fun getStats(indent: Int = 0): String {
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
    var path: ModuleChain by Delegates.notNull()
}


//class RtpRtcpSplitterModule(
//    rtpPath: ModuleChain,
//    rtcpPath: ModuleChain
//) : DemuxerModule("RTP/RTCP splitter") {
//    init {
//        packetPath(rtpPath, Packet::isRtp)
//        packetPath(rtcpPath) { it -> !it.isRtp }
//    }
//}

inline fun <Expected> Iterable<*>.forEachAs(action: (Expected) -> Unit): Unit {
    for (element in this) action(element as Expected)
}
inline fun <reified Expected> Iterable<*>.forEachIf(action: (Expected) -> Unit): Unit {
    for (element in this) {
        if (element is Expected) action(element)
    }
}



class RtpHandlerModule(private val handler: (Packet) -> Unit) : Module("RTP handler") {
    override fun doProcessPackets(p: List<Packet>) {
        if (debug) {
            println("RTP handler")
        }
        p.forEach(handler)
    }
}

class RtcpHandlerModule : Module("RTCP handler") {
    override fun doProcessPackets(p: List<Packet>) {
        p.forEachAs<RtcpPacket> { pkt ->
            if (debug) {
                println("RTCP handler")
            }
        }
    }
}
