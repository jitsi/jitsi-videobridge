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

import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpHeader
import org.jitsi.rtp.RtpPacket
import java.util.*
import kotlin.properties.Delegates

abstract class Transformer(val name: String) {
    protected abstract fun doProcessPackets(p: List<Packet>): List<Packet>
    private var moduleEntryTime: Long by Delegates.notNull()
    var totalTimeSpentInModule: Long = 0
    var numInputPackets = 0
    var numOutputPackets = 0
    fun processPackets(p: List<Packet>): List<Packet> {
        onEntry(p)
        val result = doProcessPackets(p)
        onExit(result)
        return result
    }

    // Maybe the time tracking/telemetry addModule should be another subclass, instead of in Transformer,
    // which modules can choose to inherit from (or use in some other way: composition? maybe we want to be able to
    // leverage multiple 'helper' modules?)
    private fun onEntry(p: List<Packet>) {
//        println("Entering addModule $name")
        moduleEntryTime = System.nanoTime()
        numInputPackets += p.size
    }

    private fun onExit(p: List<Packet>) {
        val currTime = System.nanoTime() - moduleEntryTime
        totalTimeSpentInModule += currTime
        numOutputPackets += p.size
//        println("Exiting addModule $name, took $currTime nanosecs")
    }

    open fun getStats(): String {
        return with (StringBuffer()) {
            appendln("$name stats:")
            appendln("  numInputPackets: $numInputPackets")
            appendln("  numOutputPackets: $numOutputPackets")
            appendln("  total nanos spent: $totalTimeSpentInModule")
            toString()
        }
    }
}

class PacketStats : Transformer("RX Packet stats") {
    var totalBytesReceived: Int = 0
    override fun doProcessPackets(p: List<Packet>): List<Packet> {
        p.forEach { it -> totalBytesReceived += it.size }
        return p
    }

    fun onRecoveredPacket(recoveredSeqNum: Int) {
        println("PacketStats was notified of recovered packet $recoveredSeqNum")
    }

    override fun getStats(): String {
        return with (StringBuffer()) {
            appendln("$name stats:")
            append(super.getStats())
            appendln("  total bytes rx: $totalBytesReceived")

            toString()
        }
    }
}

class SrtpTransformer : Transformer("SRTP Decrypt") {
    override fun doProcessPackets(p: List<Packet>): List<Packet> {
        println("SRTP decrypt")
        return p
    }
}

/**
 * How to resolve this API with normal transformers?  A splitting transformer
 * would have an input of a list of packets and, applying (a) predicate(s),
 * send them down one of two transform paths.  would it return the final
 * result of whatever path the packet ended up going down?
 */
open class SplittingTransformer(name: String) : Transformer("Splitter: $name") {
    val transformPaths: MutableMap<PacketPredicate, List<Transformer>> = mutableMapOf()
    override fun doProcessPackets(p: List<Packet>): List<Packet> {
        val resultPackets = mutableListOf<Packet>()
        p.forEach { packet ->
            transformPaths.forEach { predicate, chain ->
                if (predicate(packet)) {
                    chain.forEach { resultPackets.addAll(it.processPackets(listOf(packet))) }
                }
            }
        }
        // TODO: What makes sense to return here?  Should a 'splitter' have to fit
        // into same API definition as other transformers (packets in,
        // packets out)? If not, how can we generalize the parts of a
        // transform chain so that code need not care?
        // Would having modules call a downstream handler (rather than returning)
        // make this easier?  it reduces the 'lowest common denominator' api
        // to something that defines a function which takes a list of packets.
        return resultPackets
    }

    override fun getStats(): String {
        return with (StringBuffer()) {
            append(super.getStats())
            transformPaths.values.forEach { transformPath ->
                transformPath.forEach { transformer ->
                    append(transformer.getStats())
                }
            }
            toString()
        }
    }
}

class RtpRtcpSplitter(
    rtpPath: List<Transformer>,
    rtcpPath: List<Transformer>
) : SplittingTransformer("RTP/RTCP") {
    private val rtpPredicate: PacketPredicate = Packet::isRtp
    private val rtcpPredicate: PacketPredicate = { pkt -> !pkt.isRtp}
    init {
        transformPaths[rtpPredicate] = rtpPath
        transformPaths[rtcpPredicate] = rtcpPath
    }
}

class PacketLossMonitor : Transformer("Packet loss monitor") {
    var lastSeqNumSeen: Int? = null
    var lostPackets = 0
    override fun doProcessPackets(p: List<Packet>): List<Packet> {
        println("PacketLossMonitor")
        p.forEach { pkt ->
            if (pkt is RtpPacket) {
                lastSeqNumSeen?.let {
                    if (pkt.header.sequenceNumber > it + 1) {
                        lostPackets += (pkt.header.sequenceNumber - it - 1)
                    }
                }
                lastSeqNumSeen = pkt.header.sequenceNumber
            }
        }
        return p
    }

    override fun getStats(): String {
        return with (StringBuffer()) {
            append(super.getStats())
            appendln("  lost packets: $lostPackets")

            toString()
        }
    }
}

class FecHandler : Transformer("FEC") {
    protected val handlers = mutableListOf<(Int) -> Unit>()
    override fun doProcessPackets(p: List<Packet>): List<Packet> {
        if (Random().nextInt(100) > 90) {
            val recoveredSeqNum = 100
            println("FEC handler recreated packet")
            handlers.forEach { it.invoke(recoveredSeqNum)}
            return p + RtpPacket.fromValues {
                header = RtpHeader.fromValues {
                    sequenceNumber = recoveredSeqNum
                }
            }
        }
        return p
    }

    fun subscribe(handler: (Int) -> Unit) {
        handlers.add(handler)
    }
}

class RtpHandler : Transformer("RTP") {
    override fun doProcessPackets(p: List<Packet>): List<Packet> {
        println("RTP handler")
        p.forEach {
            if (!it.isRtp) {
                throw Exception("Non RTP packet passed to RTP path")
            }
        }
        return p
    }
}


class RtcpHandler : Transformer("RTCP") {
    override fun doProcessPackets(p: List<Packet>): List<Packet> {
        println("RTCP handler")
        p.forEach {
            if (it.isRtp) {
                throw Exception("Non RTP packet passed to RTP path")
            }
        }
        // Swallow RTCP
        return listOf()
    }
}


