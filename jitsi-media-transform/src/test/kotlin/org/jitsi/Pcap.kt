package org.jitsi

import io.kotest.core.spec.style.ShouldSpec
import org.jcodec.common.io.NIOUtils
import org.jitsi.nlj.rtp.codec.av1.dd.DependencyDescriptorReader
import org.jitsi.nlj.rtp.codec.av1.dd.DependencyDescriptor
import org.jitsi.nlj.rtp.codec.av1.dd.FrameDependencyStructure
import org.jitsi.nlj.rtp.codec.av1.dd.TwoBytesExtNormalizer
import org.jitsi.nlj.rtp.codec.av1.dd.BytesView
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.extensions.looksLikeRtcp
import org.jitsi.rtp.extensions.looksLikeRtp
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.pcap4j.core.PcapHandle
import org.pcap4j.core.Pcaps
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.Packet
import org.pcap4j.packet.UdpPacket
import java.io.EOFException
import java.nio.ByteBuffer

class Pcap : ShouldSpec() {
    init {
        context("parse") {
            parse()
        }
    }

    fun parse() {
        val rawPackets = Pcaps.openOffline("/Users/jackz/Downloads/av1_2.pcapng").getPackets().toList()
        // BsdLoopbackPacket
        val udpPackets = rawPackets
            .mapNotNull { it.payload as? IpV4Packet }
            .filter { it.header.dstAddr.hostAddress == "217.61.26.65" }
            .mapNotNull { it.payload as? UdpPacket }
            .toList()

        val rtpPackets = udpPackets
            .asSequence()
            .mapNotNull { ByteBuffer.wrap(it.payload.rawData.clone()).toPacket() }
            .filterIsInstance<RtpPacket>()
            .toList()

        val av1PayloadType = 41
        val rtxPayloadType = 42
//        val ddExtId = 11
        val ddExtId = 12
//        val vlaExtId = 12

        val av1PacketsPerSSRC = rtpPackets
            .filter { it.payloadType == av1PayloadType }
            .groupBy { it.ssrc }

        val descriptors = mutableListOf<DependencyDescriptor>()
        var lastStructure: FrameDependencyStructure? = null
        val av1WithDescriptors = mutableListOf<RtpPacket>()
        val av1WithoutDescriptors = mutableListOf<RtpPacket>()
        val twoBytesExtNormalizer = TwoBytesExtNormalizer()

        av1PacketsPerSSRC.entries.first().value.forEach { rtpPacket ->
            val twoBytesExtensions = twoBytesExtNormalizer.handle(rtpPacket)

            val ddView = twoBytesExtensions.find { it.id == ddExtId }?.let { BytesView(it) }
                ?: rtpPacket.getHeaderExtension(ddExtId)?.let { BytesView(it) }

            ddView?.let {
                val (descriptor, structure) = DependencyDescriptorReader(it, lastStructure).parse()
                lastStructure = structure
                descriptors.add(descriptor)
            }

//            val vlaExt = rtpPacket.getHeaderExtension(vlaExtId)
//
//            vlaExt
//                ?.let { VideoLayersAllocation.parse(it) }
//                ?.let { vlas.add(it) }

            if (ddView != null) {
                av1WithDescriptors.add(rtpPacket)
            } else {
                av1WithoutDescriptors.add(rtpPacket)
            }
        }

        descriptors.forEach {
            println(it)
        }

        println("AV1 with descriptors: ")
        av1WithDescriptors.forEach {
            println(it.sequenceNumber)
        }
        println("AV1 without descriptors: ")
        av1WithoutDescriptors.forEach {
            println(it.sequenceNumber)
        }
    }

    companion object {
        fun PcapHandle.getPackets(): Sequence<Packet> {
            return generateSequence {
                try {
                    this.nextPacketEx
                } catch (e: EOFException) {
                    null
                }
            }
        }

        fun ByteBuffer.toPacket(): org.jitsi.rtp.Packet? {
            val unparsed = if (this.hasArray()) {
                // use duplicate to reset cursor
                this.duplicate().let { UnparsedPacket(it.array(), it.position(), it.remaining()) }
            } else {
                UnparsedPacket(this.toArray())
            }

            if (unparsed.looksLikeRtp()) {
                return unparsed.toOtherType(::RtpPacket)
            } else if (unparsed.looksLikeRtcp()) {
                return unparsed.toOtherType(::CompoundRtcpPacket)
            }
            return null
        }

        fun ByteBuffer.toArray(): ByteArray {
            return NIOUtils.toArray(this)
        }
    }
}
