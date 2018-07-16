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
package org.jitsi.nlj.transform2

import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpHeader
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.rtcp.RtcpPacket
import java.util.*
import kotlin.properties.Delegates
import kotlin.reflect.KClass

typealias NextModule = (List<Packet>) -> Unit
typealias PacketHandler = (List<Packet>) -> Unit

class ModuleChain {
    val modules = mutableListOf<Module>()
    private var name: String = ""

    fun name(n: String) {
        this.name = n
    }

    fun module(m: Module) {
        addAndConnect(m)
    }

    fun demux(b: Demuxer.() -> Unit) {
        val sm = Demuxer().apply(b)
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
        println("Attaching handler to $previousModule")
        previousModule?.attach(handler)
    }

    private fun addAndConnect(m: Module) {
        val previousModule = modules.lastOrNull()
        modules.add(m)
        previousModule?.attach(m::processPackets)
    }

    fun processPackets(pkts: List<Packet>) {
        modules[0].processPackets(pkts)
    }

    fun getStats(indent: Int = 0): String {
        return with (StringBuffer()) {
            appendLnIndent(indent, name)
            modules.forEach { append(it.getStats(indent + 2)) }
            toString()
        }
    }

    fun findFirst(moduleClass: KClass<*>): Module? {
        for (m in modules) {
            if (m::class == moduleClass) {
                return m
            } else if (m is Demuxer) {
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
    protected var nextModule: (List<Packet>) -> Unit = {}
    private var startTimeNanos: Long by Delegates.notNull()
    private var totalNanos: Long = 0
    private var numInputPackets = 0
    protected var numOutputPackets = 0
    open fun attach(nextModule: Function1<List<Packet>, Unit>) {
        this.nextModule = nextModule
    }

    private fun onEntry(p: List<Packet>) {
        if (debug) {
            println("Entering module $name")
        }
//        startTimeNanos = System.nanoTime()
        startTimeNanos = System.currentTimeMillis()
        numInputPackets += p.size
    }

    private fun onExit() {
//        val time = System.nanoTime() - startTimeNanos
        val time = System.currentTimeMillis() - startTimeNanos
        if (debug) {
            println("Exiting module $name, took $time nanos")
        }
        totalNanos += time
    }

    protected abstract fun doProcessPackets(p: List<Packet>)

    fun processPackets(p: List<Packet>) {
        val originalNumInputPackets = numInputPackets
        val originalNumOutputPackets = numOutputPackets
        onEntry(p)
        doProcessPackets(p)
        for (i in 0..1_000_000) { }
        // TODO: can we do the splitter in such a way that this won't end
        // up being everything downstream, but just the splitter itself?
        // (like any other module)
        onExit()
//        val newNumInputPackets = numInputPackets - originalNumInputPackets
//        val newNumOutputPackets = numOutputPackets - originalNumOutputPackets
//        if (newNumInputPackets != newNumOutputPackets) {
//            println("packet stats had different input and output!")
//        }
    }

    open fun getStats(indent: Int = 0): String {
        return with (StringBuffer()) {
            appendLnIndent(indent, "$name stats:")
            appendLnIndent(indent + 2, "numInputPackets: $numInputPackets")
            appendLnIndent(indent + 2, "numOutputPackets: $numOutputPackets")
            appendLnIndent(indent + 2, "total nanos spent: $totalNanos")
            toString()
        }
    }
}

class PacketStatsModule : Module("RX Packet stats") {
    var totalBytesRx = 0
    override fun doProcessPackets(p: List<Packet>) {
        p.forEach { pkt -> totalBytesRx += pkt.size }
        numOutputPackets += p.size
        nextModule.invoke(p)
    }

    override fun getStats(indent: Int): String {
        return with (StringBuffer()) {
            append(super.getStats(indent))
            appendLnIndent(indent + 2, "total bytes rx: $totalBytesRx")
            toString()
        }
    }
}

class SrtpDecrypt : Module("SRTP Decrypt") {
    override fun doProcessPackets(p: List<Packet>) {
        if (debug) {
            println("SRTP Decrypt")
        }
        for (i in 0..500_000)
        numOutputPackets += p.size
        nextModule.invoke(p)
    }
}

class PacketPath {
    var predicate: PacketPredicate by Delegates.notNull()
    var path: ModuleChain by Delegates.notNull()
}

/*abstract*/ class Demuxer : Module("") {
    private var transformPaths: MutableMap<PacketPredicate, ModuleChain> = mutableMapOf()
    var tempFirstPath: ModuleChain? = null

    fun packetPath(b: PacketPath.() -> Unit) {
        val pp = PacketPath().apply(b)
        transformPaths[pp.predicate] = pp.path
        if (tempFirstPath == null) {
            tempFirstPath = pp.path
        }
    }

    override fun attach(nextModule: (List<Packet>) -> Unit) {//= throw Exception()
    }

    override fun doProcessPackets(p: List<Packet>) {
//        tempFirstPath?.processPackets(p)
        p.forEach { packet ->
            transformPaths.forEach { predicate, chain ->
                if (predicate(packet)) {
                    numOutputPackets++
                    chain.processPackets(listOf(packet))
                }
            }
        }
    }

    fun findFirst(moduleClass: KClass<*>): Module? {
        for (m in transformPaths.values) {
            val mod = m.findFirst(moduleClass)
            if (mod != null) { return mod }
        }
        return null
    }



    override fun getStats(indent: Int): String {
        return with (StringBuffer()) {
            append(super.getStats(indent))
            transformPaths.values.forEach {
                append(it.getStats(indent + 2))
            }
            toString()
        }
    }
}

//class RtpRtcpSplitterModule(
//    rtpPath: ModuleChain,
//    rtcpPath: ModuleChain
//) : Demuxer("RTP/RTCP splitter") {
//    init {
//        packetPath(rtpPath, Packet::isRtp)
//        packetPath(rtcpPath) { it -> !it.isRtp }
//    }
//}

class PacketLossMonitorModule : Module("Packet loss monitor") {
    var lastSeqNumSeen: Int? = null
    var lostPackets = 0

    override fun doProcessPackets(p: List<Packet>) {
        if (debug) {
            println("Packet loss monitor")
        }
        p.forEach { pkt ->
            if (pkt is RtpPacket) {
                lastSeqNumSeen?.let {
                    if (pkt.header.sequenceNumber > it + 1) {
                        lostPackets += (pkt.header.sequenceNumber - it - 1)
                    }
                }
                lastSeqNumSeen = pkt.header.sequenceNumber
            } else {
                throw Exception("Expected RtpPacket")
            }
        }
        numOutputPackets += p.size
        nextModule.invoke(p)
    }
}


inline fun <Expected> Iterable<*>.forEachAs(action: (Expected) -> Unit): Unit {
    for (element in this) action(element as Expected)
}

class FecReceiver : Module("FEC Receiver") {
    val handlers = mutableListOf<(Int) -> Unit>()
//    fun validate(p: Packet): RtpPacket {
//        return when (p) {
//            is RtpPacket -> p
//            else -> throw Exception()
//        }
//    }
    override fun doProcessPackets(p: List<Packet>) {
        p.forEachAs<RtpPacket> {
            if (Random().nextInt(100) > 90) {
                if (debug) {
                    println("FEC receiver recovered packet")
                }
                handlers.forEach { it.invoke(1000)}
            }
        }
        numOutputPackets += p.size
        nextModule.invoke(p)
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
        p.forEach { pkt ->
            if (pkt is RtcpPacket) {
                if (debug) {
                    println("RTCP handler")
                }
            } else {
                throw Exception("Expected RtcpPacket")
            }
        }
    }
}


// Outgoing

class SrtpEncryptModule : Module("SRTP Encrypt") {
    override fun doProcessPackets(p: List<Packet>) {
        if (debug) {
            println("SRTP Encrypt")
        }
        for (i in 0..500_000)
        numOutputPackets += p.size
        nextModule.invoke(p)
    }
}

// I don't think we need any special handling in MuxerModule: we can just
// attach one muxer module to multiple other modules
// we'd just need to make sure we didn't get into any concurrency issues
// (so maybe muxer should enforce posting all jobs to be handled
// by its executor?) if not, do we need it at all?  the first module
// can just take all the inputs.  we could give each module an executor
// and then the modules don't need to be aware of the thread boundaries:
// they'd either be sharing an executor/thread or not but that would be handled
// at a layer above
class MuxerModule : Module("Muxer") {
    override fun doProcessPackets(p: List<Packet>) {
        nextModule.invoke(p)
    }

    fun attachInput(m: Module) {
        m.attach(this::doProcessPackets)
    }

    fun attachInput(m: ModuleChain) {
        m.modules.last().attach(this::doProcessPackets)
    }
}

class FecSenderModule : Module("FEC sender") {
    private var packetCount = 0
    override fun doProcessPackets(p: List<Packet>) {
        packetCount += p.size
        if (false && packetCount % 5 == 0) {
            // Generate a FEC packet
            val packet = RtpPacket.fromValues {
                header = RtpHeader.fromValues {
                    payloadType = 92
                    sequenceNumber = packetCount
                }
            }
            nextModule.invoke(p + packet)
        } else {
            nextModule.invoke(p)
        }

    }
}

