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
package org.jitsi.videobridge.eventadmin.callstats;

import org.jitsi.nlj.stats.*;
import org.jitsi.nlj.transform.node.incoming.*;
import org.jitsi.nlj.transform.node.outgoing.*;
import org.jitsi.stats.media.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * Extends a {@link AbstractStatsPeriodicRunnable} which periodically generates
 * a statistics for the conference channels.
 *
 * @author Damian Minkov
 */
public class ConferencePeriodicRunnable
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
            conference.getName() == null
                  ? "null" : conference.getName().toString(),
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
                id = endpoint.getID();
            }
            EndpointStats endpointStats = new EndpointStats(id);

            TransceiverStats transceiverStats
                    = endpoint.getTransceiver().getTransceiverStats();
            int rtt_ms
                    = (int) transceiverStats.getEndpointConnectionStats().getRtt();

            Map<Long, IncomingSsrcStats.Snapshot> incomingStats
                    = transceiverStats.getIncomingStats().getSsrcStats();
            incomingStats.forEach((ssrc, stats) ->
            {
                SsrcStats receiveStats = new SsrcStats();
                receiveStats.ssrc = ssrc;
                receiveStats.bytes = stats.getNumReceivedBytes();
                receiveStats.packets = stats.getNumReceivedPackets();
                receiveStats.packetsLost = stats.getCumulativePacketsLost();
                receiveStats.rtt_ms = rtt_ms;
                // TODO: the incoming stats don't have the fractional packet
                //  loss, it has to be computed between snapshots.
                //receiveStats.fractionalPacketLoss = TODO;
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
                sendStats.rtt_ms = rtt_ms;
                // TODO: we don't keep track of outgoing loss per ssrc.
                //sendStats.fractionalPacketLoss = TODO
                // TODO: we don't keep track of outgoing loss per ssrc.
                //sendStats.jitter_ms = TODO
                endpointStats.addSendStats(sendStats);
            });

            allEndpointStats.add(endpointStats);
        }

        return allEndpointStats;
    }
}
