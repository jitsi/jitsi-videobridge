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

import org.jitsi.nlj.RtpExtensionEventListener
import org.jitsi.nlj.RtpPayloadTypeEventListener
import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.service.neomedia.RTPExtension
import org.jitsi.service.neomedia.format.MediaFormat
import java.math.BigDecimal
import java.time.Duration
import kotlin.properties.Delegates

typealias PacketHandler = (List<Packet>) -> Unit

fun getMbps(numBytes: Long, duration: Duration): String {
    val numBits = BigDecimal(numBytes * 8)
    val megaBits = (numBits / BigDecimal(1000000.0)).toFloat()
    return "%.2f".format((megaBits / duration.toMillis()) * Duration.ofSeconds(1).toMillis())
}


abstract class Module(
    var name: String,
    protected val debug: Boolean = false
) : RtpExtensionEventListener, RtpPayloadTypeEventListener {
    /**
     * The next handler in the chain, after this one.  This is held
     * as a method, instead of an entire [Module], because a module
     * only needs to have a method to call, it doesn't need to know
     * anything else about the next handler in the chain.  By making it private
     * we can enforce that module implementations will have to call the
     * [next] method defined in [Module] to invoke the next handler
     * in the chain, which means we can put common logic for statistics
     * in this class.
     * TODO: maybe define a 'PacketHandler' interface that module
     * implements and use that type here? -> but then we have to create
     * an anonymous instance instead of being able to pass a lambda
     */
    private var nextModule: PacketHandler = {}
    // Stats stuff
    private var startTime: Long = 0
    private var totalTime: Long = 0
    private var numInputPackets = 0
    private var numOutputPackets = 0
    private var firstPacketTime: Long = -1
    private var lastPacketTime: Long = -1
    private var numBytes: Long = 0

    /**
     * Attach a handler to come in the chain after this one
     */
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
        //TODO: add handling for empty 'pkts' here, instead of having
        // submodules do it
        onExit(pkts)
        numOutputPackets += pkts.size
        if (pkts.isNotEmpty()) {
            nextModule.invoke(pkts)
        }
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

    override fun onRtpExtensionAdded(extensionId: Byte, rtpExtension: RTPExtension) {
        //No-op by default
    }

    override fun onRtpExtensionRemoved(extensionId: Byte) {
        // No-op by default
    }

    override fun onRtpPayloadTypeAdded(payloadType: Byte, format: MediaFormat) {
        // No-op by default
    }

    override fun onRtpPayloadTypeRemoved(payloadType: Byte) {
        // No-op by default
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
