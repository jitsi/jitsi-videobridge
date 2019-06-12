/*
 * Copyright @ 2015 Atlassian Pty Ltd
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

import org.jitsi.service.neomedia.stats.*;
import org.jitsi.stats.media.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging.*;
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
     * The <tt>Logger</tt> used by the <tt>ConferencePeriodicRunnable</tt>
     * class and its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(ConferencePeriodicRunnable.class);

    /**
     * The {@link MediaType}s for which we will report to callstats.
     */
    private static final MediaType[] MEDIA_TYPES
        = { MediaType.AUDIO, MediaType.VIDEO };

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

    @Override
    protected Map<String, Collection<? extends ReceiveTrackStats>>
        getReceiveTrackStats()
    {
        return getTrackStats(true);
    }

    @Override
    protected Map<String, Collection<? extends SendTrackStats>>
        getSendTrackStats()
    {
        return getTrackStats(false);
    }

    /**
     * Get stats receive or send from current conference.
     *
     * @param receive whether to get receive if <tt>true</tt> or
     * send statistics otherwise.
     * @param <T> the type of result stats Collection, ReceiveTrackStats
     * or SendTrackStats.
     * @return the result collection of stats grouped by endpointID.
     */
    private <T extends Collection> Map<String, T> getTrackStats(boolean receive)
    {
        Map<String, T> resultStats = new HashMap<>();

        for (AbstractEndpoint endpoint : o.getEndpoints())
        {
            for (MediaType mediaType : MEDIA_TYPES)
            {
                //TODO(brian): reimplement this
//                for (RtpChannel channel : endpoint.getChannels(mediaType))
//                {
//                    if (channel == null)
//                    {
//                        logger.debug("Could not log the channel expired event "
//                            + "because the channel is null.");
//                        continue;
//                    }
//
//                    if (channel.getReceiveSSRCs().length == 0)
//                    {
//                        continue;
//                    }
//
//                    MediaStream stream = channel.getStream();
//                    if (stream == null)
//                    {
//                        continue;
//                    }
//
//                    MediaStreamStats2 stats = stream.getMediaStreamStats();
//                    if (stats == null)
//                    {
//                        continue;
//                    }
//
//                    // uses statsId if it is available
//                    String endpointID
//                        = endpoint.getStatsId()
//                            != null ? endpoint.getStatsId(): endpoint.getID();
//
//                    Collection newStats
//                        = receive
//                            ? stats.getAllReceiveStats()
//                            : stats.getAllSendStats();
//
//                    T previousResults = resultStats.get(endpointID);
//                    if (previousResults != null)
//                    {
//                        previousResults.addAll(newStats);
//                    }
//                    else
//                    {
//                        resultStats.put(
//                            endpointID, (T)new ArrayList<>(newStats));
//                    }
//                }
            }
        }

        return resultStats;
    }
}
