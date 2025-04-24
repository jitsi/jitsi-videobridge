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
package org.jitsi.nlj.transform.node

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.PacketStreamStats
import org.jitsi.nlj.util.appendAll
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger

/**
 * A [Node] which keeps track of the basic statistics for a stream of packets (packet and bit rates)
 *
 * @author Boris Grozev
 */
class PacketStreamStatsNode(
    private val diagnosticContext: DiagnosticContext,
    private val direction: String,
    private val packetStreamStats: PacketStreamStats = PacketStreamStats()
) : ObserverNode("PacketStreamStats") {

    override fun observe(packetInfo: PacketInfo) {
        packetStreamStats.update(packetInfo.packet.length)
    }

    override fun trace(f: () -> Unit) = f.invoke()

    fun snapshot() = packetStreamStats.snapshot().also {
        if (timeseriesLogger.isTraceEnabled) {
            timeseriesLogger.trace(
                diagnosticContext.makeTimeSeriesPoint("${direction}_packet_stream_stats")
                    .addField("bitrate_bps", it.bitrate.bps)
                    .addField("packet_rate", it.packetRate)
            )
        }
    }

    fun getBitrate() = snapshot().bitrate

    override fun statsJson() = super.statsJson().appendAll(
        packetStreamStats.snapshot().toJson()
    )

    override fun getNodeStats() = super.getNodeStats().apply {
        val snapshot = packetStreamStats.snapshot()
        addNumber("bitrate_bps", snapshot.bitrate.bps)
        addNumber("packet_rate", snapshot.packetRate)
        addNumber("bytes_sent", snapshot.bytes)
        addNumber("packets_sent", snapshot.packets)
    }

    /**
     * Creates a new [Node] instance which shares the same [packetStreamStats]. Useful when we want to add nodes to
     * different branches of a [Node] tree.
     */
    fun createNewNode() = PacketStreamStatsNode(diagnosticContext, direction, packetStreamStats)

    companion object {
        private val timeseriesLogger = TimeSeriesLogger.getTimeSeriesLogger(PacketStreamStatsNode::class.java)
    }
}
