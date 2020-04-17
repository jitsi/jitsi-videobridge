/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.videobridge.octo

class OctoRelayServiceStats(
    val bytesReceived: Long,
    val bytesSent: Long,
    val packetsReceived: Long,
    val packetsSent: Long,
    val packetsDropped: Long,
    val receiveBitrate: Long,
    val receivePacketRate: Long,
    val sendBitrate: Long,
    val sendPacketRate: Long,
    val relayId: String
) {

    /**
     * For java
     */
    data class Builder(
        var bytesReceived: Long? = null,
        var bytesSent: Long? = null,
        var packetsReceived: Long? = null,
        var packetsSent: Long? = null,
        var packetsDropped: Long? = null,
        var receiveBitrate: Long? = null,
        var receivePacketRate: Long? = null,
        var sendBitrate: Long? = null,
        var sendPacketRate: Long? = null,
        var relayId: String? = null
    ) {
        fun bytesReceived(bytesReceived: Long) = apply { this.bytesReceived = bytesReceived }
        fun bytesSent(bytesSent: Long) = apply { this.bytesSent = bytesSent }
        fun packetsReceived(packetsReceived: Long) = apply { this.packetsReceived = packetsReceived }
        fun packetsSent(packetsSent: Long) = apply { this.packetsSent = packetsSent }
        fun packetsDropped(packetsDropped: Long) = apply { this.packetsDropped = packetsDropped }
        fun receiveBitrate(receiveBitrate: Long) = apply { this.receiveBitrate = receiveBitrate }
        fun receivePacketRate(receivePacketRate: Long) = apply { this.receivePacketRate = receivePacketRate }
        fun sendBitrate(sendBitrate: Long) = apply { this.sendBitrate = sendBitrate }
        fun sendPacketRate(sendPacketRate: Long) = apply { this.sendPacketRate = sendPacketRate }
        fun relayId(relayId: String) = apply { this.relayId = relayId }
        fun build(): OctoRelayServiceStats = OctoRelayServiceStats(
            bytesReceived = bytesReceived!!,
            bytesSent = bytesSent!!,
            packetsReceived = packetsReceived!!,
            packetsSent = packetsSent!!,
            packetsDropped = packetsDropped!!,
            receiveBitrate = receiveBitrate!!,
            receivePacketRate = receivePacketRate!!,
            sendBitrate = sendBitrate!!,
            sendPacketRate = sendPacketRate!!,
            relayId = relayId!!
        )
    }
}
