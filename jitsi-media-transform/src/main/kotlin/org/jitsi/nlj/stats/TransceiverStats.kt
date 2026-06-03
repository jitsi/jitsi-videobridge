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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsSnapshot
import org.jitsi.nlj.transform.node.incoming.VideoParser
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsSnapshot

data class TransceiverStats(
    val endpointConnectionStats: EndpointConnectionStats.Snapshot,
    val rtpReceiverStats: RtpReceiverStats,
    val outgoingStats: OutgoingStatisticsSnapshot,
    val outgoingPacketStreamStats: PacketStreamStats.Snapshot,
    val tccEngineStats: TransportCcEngine.StatisticsSnapshot
) {
    fun toJson(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        set<ObjectNode>("endpoint_connection_stats", endpointConnectionStats.toJson())
        set<ObjectNode>("rtp_receiver_stats", rtpReceiverStats.toJson())
        set<ObjectNode>("outgoing_stats", outgoingStats.toJson())
        set<ObjectNode>("outgoing_packet_stream_stats", outgoingPacketStreamStats.toJson())
        set<ObjectNode>("tcc_engine_stats", tccEngineStats.toJson())
    }
}

data class RtpReceiverStats(
    val incomingStats: IncomingStatisticsSnapshot,
    val packetStreamStats: PacketStreamStats.Snapshot,
    val videoParserStats: VideoParser.Stats.Snapshot
) {
    fun toJson(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        set<ObjectNode>("incoming_stats", incomingStats.toJson())
        set<ObjectNode>("packet_stream_stats", packetStreamStats.toJson())
        set<ObjectNode>("video_parser_stats", videoParserStats.toJson())
    }
}
