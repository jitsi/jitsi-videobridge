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
package org.jitsi.nlj.experiment

import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.rtcp.RtcpPacket
import java.util.*
import kotlin.properties.Delegates
import kotlin.reflect.KClass

typealias NextModule = (List<Packet>) -> Unit

class ModuleChain<InputPacketType : Packet> {
    private val modules: MutableList<Module<out Packet, out Packet>> = mutableListOf()
    private var name: String = ""

    fun name(n: String) {
        this.name = n
    }

    fun<
    ModuleInputPacketType : Packet,
    ModuleOutputPacketType : Packet
    >
    module(m: Module<ModuleInputPacketType, ModuleOutputPacketType>) {
        addAndConnect(m)
    }

    fun<DemuxerInputPacketType : Packet> demux(b: SplitterModule<DemuxerInputPacketType>.() -> Unit) {
        val sm = SplitterModule<DemuxerInputPacketType>().apply(b)
        addAndConnect(sm)
    }

    private fun<
    ModuleInputPacketType : Packet,
    ModuleOutputPacketType : Packet
        > addAndConnect(m: Module<ModuleInputPacketType, ModuleOutputPacketType>) {
        val previousModule = modules.lastOrNull()
        modules.add(m)
//        previousModule?.attach(m::processPackets)
    }

    fun processPackets(pkts: List<InputPacketType>) {
//        modules[0].processPackets(pkts)
    }

    fun getStats(indent: Int = 0): String {
        return with (StringBuffer()) {
            appendLnIndent(indent, name)
            modules.forEach { append(it.getStats(indent + 2)) }
            toString()
        }
    }

    fun findFirst(moduleClass: KClass<*>): Module<*, *>? {
        for (m in modules) {
            if (m::class == moduleClass) {
                return m
            } else if (m is SplitterModule) {
                val nested = m.findFirst(moduleClass)
                if (nested != null) { return nested }
            }
        }
        return null
    }

    fun findAll(moduleClass: KClass<*>): List<Module<*, *>> {
        return modules.filter { it -> it::class == moduleClass }
    }
}

class ModuleChainBuilder {
    companion object {
        fun<InputPacketType : Packet> build(f: ModuleChain<InputPacketType>.() -> Unit): ModuleChain<*> {
            val chain = ModuleChain<InputPacketType>()
            chain.f()
            return chain
        }
        fun<InputPacketType : Packet> chain(block: ModuleChain<InputPacketType>.() -> Unit): ModuleChain<InputPacketType> = ModuleChain<InputPacketType>().apply(block)
    }
}

// InputPacketType used as an input parameter (processPackets)
// OutputPacketType used as an input parameter (attach)
abstract class Module<InputPacketType : Packet, OutputPacketType : Packet>(
    var name: String,
    protected val debug: Boolean = false
) {
    protected var nextModule: (List<OutputPacketType>) -> Unit = {}
    protected var startTimeNanos: Long by Delegates.notNull()
    protected var totalNanos: Long = 0
    protected var numInputPackets = 0
    protected var numOutputPackets = 0
    open fun attach(nextModule: Function1<List<OutputPacketType>, Unit>) {
        this.nextModule = nextModule
    }

    private fun onEntry(p: List<InputPacketType>) {
        if (debug) {
            println("Entering module $name")
        }
        startTimeNanos = System.nanoTime()
        numInputPackets += p.size
    }

    private fun onExit() {
        val time = System.nanoTime() - startTimeNanos
        if (debug) {
            println("Exiting module $name, took $time nanos")
        }
        totalNanos += time
    }

    protected abstract fun doProcessPackets(p: List<InputPacketType>)

    fun processPackets(p: List<InputPacketType>) {
        onEntry(p)
        doProcessPackets(p)
        // TODO: can we do the splitter in such a way that this won't end
        // up being everything downstream, but just the splitter itself?
        // (like any other module)
        onExit()
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

class PacketStatsModule : Module<Packet, Packet>("RX Packet stats") {
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

class SrtpModule : Module<Packet, Packet>("SRTP Decrypt") {
    override fun doProcessPackets(p: List<Packet>) {
        if (debug) {
            println("SRTP Decrypt")
        }
        numOutputPackets += p.size
        nextModule.invoke(p)
    }
}

class PacketPath {
    var predicate: PacketPredicate by Delegates.notNull()
    var path: ModuleChain<*> by Delegates.notNull()

    fun predicate(p: PacketPredicate) {
        predicate = p
    }

    fun path(m: ModuleChain<*>) {
        path = m
    }
}

/*abstract*/ class SplitterModule<InputPacketType : Packet>: Module<InputPacketType, Nothing>("") {
    // I think a map in the splitter module (whatever it is) will be useful because
    // it can be handy to have packets potentially go down multiple paths (this allows
    // for easy insertion of debug modules at different points, for example) so the
    // predicates will have to accept ONLY what they want (which seems like a good
    // thing anyway)
    private var transformPaths: MutableMap<PacketPredicate, ModuleChain<Packet>> = mutableMapOf()

    fun addSubChain(b: PacketPath.() -> Unit) {
        val pp = PacketPath()
        pp.b()
//        transformPaths[pp.predicate] = pp.path
    }

    override fun attach(nextModule: (List<Nothing>) -> Unit) = throw Exception()

    override fun doProcessPackets(p: List<InputPacketType>) {
        p.forEach { packet ->
            transformPaths.forEach { predicate, chain ->
                if (predicate(packet)) {
                    numOutputPackets++
                    chain.processPackets(listOf(packet))
                }
            }
        }
    }

    fun findFirst(moduleClass: KClass<*>): Module<*, *>? {
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
//) : SplitterModule("RTP/RTCP splitter") {
//    init {
//        addSubChain(rtpPath, Packet::isRtp)
//        addSubChain(rtcpPath) { it -> !it.isRtp }
//    }
//}

class PacketLossMonitorModule : Module<RtpPacket, RtpPacket>("Packet loss monitor") {
    var lastSeqNumSeen: Int? = null
    var lostPackets = 0
    override fun doProcessPackets(p: List<RtpPacket>) {
        if (debug) {
            println("Packet loss monitor")
        }
        p.forEach { pkt ->
            lastSeqNumSeen?.let {
                if (pkt.header.sequenceNumber > it + 1) {
                    lostPackets += (pkt.header.sequenceNumber - it - 1)
                }
            }
            lastSeqNumSeen = pkt.header.sequenceNumber
        }
        numOutputPackets += p.size
        nextModule.invoke(p)
    }
}

class FecReceiver : Module<RtpPacket, RtpPacket>("FEC Receiver") {
    val handlers = mutableListOf<(Int) -> Unit>()
    override fun doProcessPackets(p: List<RtpPacket>) {
        if (Random().nextInt(100) > 90) {
            println("FEC receiver recovered packet")
            handlers.forEach { it.invoke(1000)}
        }
    }
}

class RtpHandlerModule(private val handler: (Packet) -> Unit) : Module<RtpPacket, RtpPacket>("RTP handler") {
    override fun doProcessPackets(p: List<RtpPacket>) {
        if (debug) {
            println("RTP handler")
        }
        p.forEach(handler)
    }
}

class RtcpHandlerModule : Module<RtcpPacket, RtcpPacket>("RTCP handler") {
    override fun doProcessPackets(p: List<RtcpPacket>) {
        if (debug) {
            println("RTCP handler")
        }
    }
}


