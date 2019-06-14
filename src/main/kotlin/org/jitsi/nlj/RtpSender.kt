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
package org.jitsi.nlj

import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.stats.EndpointConnectionStats
import org.jitsi.nlj.stats.PacketStreamStats
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsSnapshot

/**
 * Not an 'RtpSender' in the sense that it sends only RTP (and not
 * RTCP) but in the sense of a webrtc 'RTCRTPSender' which handles
 * all RTP and RTP control packets.
 */
abstract class RtpSender :
    StatsKeepingPacketHandler(),
    EventHandler,
    Stoppable,
    NodeStatsProducer,
    EndpointConnectionStats.EndpointConnectionStatsListener {

    abstract fun sendProbing(mediaSsrc: Long, numBytes: Int): Int
    abstract fun onOutgoingPacket(handler: PacketHandler)
    abstract fun setSrtpTransformers(srtpTransformers: SrtpTransformers)
    abstract fun getStreamStats(): OutgoingStatisticsSnapshot
    abstract fun getCombinedStreamStats(): PacketStreamStats.Snapshot
    abstract fun requestKeyframe(mediaSsrc: Long)
    abstract fun tearDown()
}
