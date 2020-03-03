/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj

import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.stats.EndpointConnectionStats
import org.jitsi.nlj.stats.PacketStreamStats
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsSnapshot

abstract class RtpReceiver :
    StatsKeepingPacketHandler(), EventHandler, NodeStatsProducer, Stoppable,
    EndpointConnectionStats.EndpointConnectionStatsListener {
    /**
     * The handler which will be invoked for each RTP/RTCP packet received
     * by this receiver (after it has gone through the receiver's
     * input chain).
     */
    abstract var packetHandler: PacketHandler?
    /**
     * Enqueue an incoming packet to be processed
     */
    abstract fun enqueuePacket(p: PacketInfo)

    /**
     * Set the SRTP transformers to be used for RTP/RTCP encryption and decryption
     */
    abstract fun setSrtpTransformers(srtpTransformers: SrtpTransformers)

    abstract fun setAudioLevelListener(audioLevelListener: AudioLevelListener)

    abstract fun onBandwidthEstimateChanged(listener: BandwidthEstimator.Listener)

    abstract fun getStreamStats(): IncomingStatisticsSnapshot

    abstract fun getPacketStreamStats(): PacketStreamStats.Snapshot

    abstract fun tearDown()

    abstract fun isReceivingAudio(): Boolean
    abstract fun isReceivingVideo(): Boolean
}
