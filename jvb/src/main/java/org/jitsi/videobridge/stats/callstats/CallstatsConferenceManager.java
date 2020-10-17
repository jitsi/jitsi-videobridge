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

import java.util.*;
import java.util.concurrent.*;

import org.jetbrains.annotations.*;
import org.jitsi.stats.media.*;
import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;

/**
 * Manages sending per-conference stats to callstats. Maintains a list of active conferences, and handles conference
 * create/expire events.
 *
 * @author Damian Minkov
 */
class CallstatsConferenceManager
    implements Videobridge.EventHandler
{
    /**
     * The <tt>Logger</tt> used by the <tt>CallStatsConferenceStatsHandler</tt>
     * class and its instances to print debug information.
     */
    private static final Logger logger = new LoggerImpl(CallstatsConferenceManager.class.getName());

    /**
     * The {@link RecurringRunnableExecutor} which sends per-conference statistics to callstats.
     */
    private static final RecurringRunnableExecutor executor
        = new RecurringRunnableExecutor(CallstatsConferenceManager.class.getSimpleName());

    /**
     * The entry point into the jitsi-stats library.
     */
    private final StatsService statsService;

    /**
     * The id which identifies the current bridge.
     */
    private final String bridgeId;

    /**
     * The prefix to use when creating conference ID to report.
     */
    private final String conferenceIDPrefix;

    /**
     * List of the processor per conference. Kept in order to stop and deregister them from the executor.
     */
    private final Map<Conference, ConferencePeriodicRunnable> statisticsProcessors = new ConcurrentHashMap<>();

    /**
     * The interval to poll for stats and to push them to the callstats service.
     */
    private final long interval;

    public CallstatsConferenceManager(
            StatsService statsService,
            String bridgeId,
            long intervalMs,
            String conferenceIDPrefix)
    {
        this.statsService = statsService;
        this.bridgeId = bridgeId;
        this.interval = intervalMs;
        this.conferenceIDPrefix = conferenceIDPrefix;
    }

    /**
     * Stops and cancels all pending operations. Clears all listeners.
     */
    public void stop()
    {
        // Let's stop all left runnables.
        for (ConferencePeriodicRunnable cpr : statisticsProcessors.values())
        {
            executor.deRegisterRecurringRunnable(cpr);
        }
    }

    /**
     * Conference created.
     * @param conference the conference that is created.
     */
    @Override
    public void conferenceCreated(@NotNull Conference conference)
    {
        if (conference.getName() != null)
        {
            // Create a new PeriodicRunnable and start it.
            ConferencePeriodicRunnable cpr = new ConferencePeriodicRunnable(
                conference,
                interval,
                statsService,
                conferenceIDPrefix,
                bridgeId);
            cpr.start();

            // register for periodic execution.
            statisticsProcessors.put(conference, cpr);
            executor.registerRecurringRunnable(cpr);
        }
    }

    /**
     * Conference expired.
     * @param conference the conference that expired.
     */
    @Override
    public void conferenceExpired(@NotNull Conference conference)
    {
        ConferencePeriodicRunnable cpr = statisticsProcessors.remove(conference);

        if (cpr == null)
        {
            logger.warn("Unknown conference expired.");
            return;
        }

        cpr.stop();
        executor.deRegisterRecurringRunnable(cpr);
    }
}
