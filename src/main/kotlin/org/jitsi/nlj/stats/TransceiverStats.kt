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

package org.jitsi.nlj.stats

import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsSnapshot
import org.jitsi.nlj.transform.node.incoming.VideoParser
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsSnapshot
import org.jitsi.utils.OrderedJsonObject

data class TransceiverStats(
    val endpointConnectionStats: EndpointConnectionStats.Snapshot,
    val rtpReceiverStats: RtpReceiverStats,
    val outgoingStats: OutgoingStatisticsSnapshot,
    val outgoingPacketStreamStats: PacketStreamStats.Snapshot,
    val bandwidthEstimatorStats: BandwidthEstimator.StatisticsSnapshot,
    val tccEngineStats: TransportCcEngine.StatisticsSnapshot
) {
    fun toJson() = OrderedJsonObject().apply {
        put("endpoint_connection_stats", endpointConnectionStats.toJson())
        put("rtp_receiver_stats", rtpReceiverStats.toJson())
        put("outgoing_stats", outgoingStats.toJson())
        put("outgoing_packet_stream_stats", outgoingPacketStreamStats.toJson())
        put("bandwidth_estimator_stats", bandwidthEstimatorStats.toJson())
        put("tcc_engine_stats", tccEngineStats.toJson())
    }
}

data class RtpReceiverStats(
    val incomingStats: IncomingStatisticsSnapshot,
    val packetStreamStats: PacketStreamStats.Snapshot,
    val videoParserStats: VideoParser.Stats.Snapshot
) {
    fun toJson() = OrderedJsonObject().apply {
        put("incoming_stats", incomingStats.toJson())
        put("packet_stream_stats", packetStreamStats.toJson())
        put("video_parser_stats", videoParserStats.toJson())
    }
}
