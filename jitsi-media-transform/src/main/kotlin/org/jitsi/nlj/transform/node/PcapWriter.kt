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
import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.toHex
import org.pcap4j.core.Pcaps
import org.pcap4j.packet.EthernetPacket
import org.pcap4j.packet.IpPacket
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
import java.net.InetAddress
import java.nio.ByteBuffer


class PcapWriter : Node("PCAP writer") {
    val handle = Pcaps.openDead(DataLinkType.EN10MB, 65536);
    val writer = handle.dumpOpen("/tmp/${hashCode()}.pcap")
    override fun doProcessPackets(p: List<PacketInfo>) {
        p.forEach {
            val udpPayload = UnknownPacket.Builder()
            udpPayload.rawData(it.packet.getBuffer().array())
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

        next(p)
    }
}
