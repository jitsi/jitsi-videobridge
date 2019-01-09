package org.jitsi.nlj.stats

import org.jitsi.nlj.transform.node.incoming.IncomingStreamStatistics
import org.jitsi.nlj.transform.node.outgoing.OutgoingStreamStatistics


data class TransceiverStreamStats(
    val incomingStreamStatistics: Map<Long, IncomingStreamStatistics.Snapshot>,
    val outgoingStreamStatistics: Map<Long, OutgoingStreamStatistics.Snapshot>
)