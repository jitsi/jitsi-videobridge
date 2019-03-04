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

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.util.cinfo
import org.pcap4j.core.Pcaps
import org.pcap4j.packet.EthernetPacket
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.IpV4Rfc1349Tos
import org.pcap4j.packet.UdpPacket
import org.pcap4j.packet.UnknownPacket
import org.pcap4j.packet.namednumber.DataLinkType
import org.pcap4j.packet.namednumber.EtherType
import org.pcap4j.packet.namednumber.IpNumber
import org.pcap4j.packet.namednumber.IpVersion
import org.pcap4j.packet.namednumber.UdpPort
import org.pcap4j.util.MacAddress
import java.net.Inet4Address
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Random


class PcapWriter(
    filePath: String = "/tmp/${Random().nextLong()}.pcap}"
) : ObserverNode("PCAP writer") {
    val handle = Pcaps.openDead(DataLinkType.EN10MB, 65536);
    val writer = handle.dumpOpen(filePath)

    init {
        logger.cinfo { "Pcap writer writing to file $filePath" }
    }

    override fun observe(packetInfo: PacketInfo) {
        val udpPayload = UnknownPacket.Builder()
        val pktBuf = packetInfo.packet.getBuffer()
        // We can't pass offset/limit values to udpPayload.rawData, so we need to create an array that contains
        // only exactly what we want to write
        val subBuf = ByteBuffer.wrap(
            Arrays.copyOfRange(
                pktBuf.array(),
                pktBuf.arrayOffset(),
                pktBuf.arrayOffset() + pktBuf.limit()))
        udpPayload.rawData(subBuf.array())
        val udp = UdpPacket.Builder()
                .srcPort(UdpPort(123, "blah"))
                .dstPort(UdpPort(456, "blah"))
                .srcAddr(Inet4Address.getLocalHost() as Inet4Address)
                .dstAddr(Inet4Address.getLocalHost() as Inet4Address)
                .correctChecksumAtBuild(true)
                .correctLengthAtBuild(true)
                .payloadBuilder(udpPayload)

        val ipPacket = IpV4Packet.Builder()
                .srcAddr(Inet4Address.getLocalHost() as Inet4Address)
                .dstAddr(Inet4Address.getLocalHost() as Inet4Address)
                .protocol(IpNumber.UDP)
                .version(IpVersion.IPV4)
                .tos(IpV4Rfc1349Tos.newInstance(0))
                .correctLengthAtBuild(true)
                .payloadBuilder(udp)

        val eth = EthernetPacket.Builder()
                .srcAddr(MacAddress.ETHER_BROADCAST_ADDRESS)
                .dstAddr(MacAddress.ETHER_BROADCAST_ADDRESS)
                .type(EtherType.IPV4)
                .paddingAtBuild(true)
                .payloadBuilder(ipPacket)
                .build()

        writer.dump(eth)
    }
}
