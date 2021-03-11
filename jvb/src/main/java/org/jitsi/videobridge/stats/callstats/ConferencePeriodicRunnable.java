/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
package org.jitsi.videobridge.stats.callstats;

import org.jitsi.nlj.stats.*;
import org.jitsi.nlj.transform.node.incoming.*;
import org.jitsi.nlj.transform.node.outgoing.*;
import org.jitsi.stats.media.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * A {@link org.jitsi.utils.concurrent.RecurringRunnable} which periodically extracts statistics for a specific
 * {@link Conference} and sends them to callstats.
 *
 * @author Damian Minkov
 */
class ConferencePeriodicRunnable
    extends AbstractStatsPeriodicRunnable<Conference>
{
    /**
     * Constructs <tt>ConferencePeriodicRunnable</tt>.
     * @param conference the conference.
     * @param period the reporting interval.
     * @param statsService the StatsService to use for reporting.
     * @param conferenceIDPrefix prefix to use when creating conference IDs.
     * @param initiatorID the id which identifies the current bridge.
     */
    ConferencePeriodicRunnable(
        Conference conference,
        long period,
        StatsService statsService,
        String conferenceIDPrefix,
        String initiatorID)
    {
        super(conference,
            period,
            statsService,
            conference.getName(),
            conferenceIDPrefix,
            initiatorID);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected List<EndpointStats> getEndpointStats()
    {
        List<EndpointStats> allEndpointStats = new LinkedList<>();

        for (Endpoint endpoint : o.getLocalEndpoints())
        {
            String id = endpoint.getStatsId();
            if (id == null)
            {
                id = endpoint.getId();
            }
            EndpointStats endpointStats = new EndpointStats(id);

            TransceiverStats transceiverStats = endpoint.getTransceiver().getTransceiverStats();
            int rttMs = (int) (transceiverStats.getEndpointConnectionStats().getRtt() + 0.5);

            Map<Long, IncomingSsrcStats.Snapshot> incomingStats
                    = transceiverStats.getIncomingStats().getSsrcStats();
            incomingStats.forEach((ssrc, stats) ->
            {
                SsrcStats receiveStats = new SsrcStats();
                receiveStats.ssrc = ssrc;
                receiveStats.bytes = stats.getNumReceivedBytes();
                receiveStats.packets = stats.getNumReceivedPackets();
                receiveStats.packetsLost = stats.getCumulativePacketsLost();
                receiveStats.rtt_ms = rttMs;
                // TODO: The per-SSRC stats do not keep the fraction loss in the last interval. We use the per-endpoint
                // value, which is an average over all received SSRCs.
                receiveStats.fractionalPacketLoss
                        = getFractionLost(transceiverStats.getEndpointConnectionStats().getIncomingLossStats());
                receiveStats.jitter_ms = stats.getJitter();
                endpointStats.addReceiveStats(receiveStats);
            });

            Map<Long, OutgoingSsrcStats.Snapshot> outgoingStats
                    = transceiverStats.getOutgoingStats().getSsrcStats();
            outgoingStats.forEach((ssrc, stats) ->
            {
                SsrcStats sendStats = new SsrcStats();
                sendStats.ssrc = ssrc;
                sendStats.bytes = stats.getOctetCount();
                sendStats.packets = stats.getPacketCount();
                // TODO: we don't keep track of outgoing loss per ssrc.
                //sendStats.packetsLost = TODO
                sendStats.rtt_ms = rttMs;
                // TODO: The per-SSRC stats do not keep the fraction loss in the last interval. We use the per-endpoint
                // value, which is an average over all received SSRCs.
                sendStats.fractionalPacketLoss
                        = getFractionLost(transceiverStats.getEndpointConnectionStats().getOutgoingLossStats());
                //sendStats.jitter_ms = TODO
                endpointStats.addSendStats(sendStats);
            });

            allEndpointStats.add(endpointStats);
        }

        return allEndpointStats;
    }

    private static double getFractionLost(EndpointConnectionStats.LossStatsSnapshot lossStatsSnapshot)
    {
        if (lossStatsSnapshot.getPacketsLost() + lossStatsSnapshot.getPacketsReceived() > 0)
        {
            return lossStatsSnapshot.getPacketsLost() /
                    ((double) (lossStatsSnapshot.getPacketsLost() + lossStatsSnapshot.getPacketsReceived()));

        }
        return 0;
    }

}
