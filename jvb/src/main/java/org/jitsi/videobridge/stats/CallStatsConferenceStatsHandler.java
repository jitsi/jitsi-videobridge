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
package org.jitsi.videobridge.stats;

import java.util.*;
import java.util.concurrent.*;

import org.jitsi.stats.media.*;
import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;

/**
 * Handles events of the bridge for creating a conference/channel or expiring it
 * and reports statistics per endpoint.
 *
 * @author Damian Minkov
 */
class CallStatsConferenceStatsHandler
{
    /**
     * The <tt>Logger</tt> used by the <tt>CallStatsConferenceStatsHandler</tt>
     * class and its instances to print debug information.
     */
    private static final Logger logger
        = new LoggerImpl(CallStatsConferenceStatsHandler.class.getName());

    /**
     * The {@link RecurringRunnableExecutor} which periodically invokes
     * generating and pushing statistics per conference for every Channel.
     */
    private static final RecurringRunnableExecutor statisticsExecutor
        = new RecurringRunnableExecutor(CallStatsConferenceStatsHandler.class.getSimpleName() + "-statisticsExecutor");

    /**
     * The entry point into the jitsi-stats library.
     */
    private StatsService statsService;

    /**
     * The id which identifies the current bridge.
     */
    private String bridgeId;

    /**
     * The prefix to use when creating conference ID to report.
     */
    private String conferenceIDPrefix;

    /**
     * List of the processor per conference. Kept in order to stop and
     * deRegister them from the executor.
     */
    private final Map<Conference, ConferencePeriodicRunnable> statisticsProcessors = new ConcurrentHashMap<>();

    /**
     * The interval to poll for stats and to push them to the callstats service.
     */
    private int interval;

    /**
     * Starts the handler with initialized callstats library.
     * @param statsService entry point into the jitsi-statsß library.
     * @param bridgeId the id which identifies the current bridge.
     * @param conferenceIDPrefix prefix to use when creating conference IDs.
     * @param interval interval to poll for stats and
     * to push them to the callstats service.
     */
    void start(StatsService statsService, String bridgeId,
        String conferenceIDPrefix,
        int interval)
    {
        this.statsService = statsService;
        this.bridgeId = bridgeId;
        this.interval = interval;

        this.conferenceIDPrefix = conferenceIDPrefix;
    }

    /**
     * Stops and cancels all pending operations. Clears all listeners.
     */
    void stop()
    {
        // Let's stop all left runnables.
        for (ConferencePeriodicRunnable cpr : statisticsProcessors.values())
        {
            statisticsExecutor.deRegisterRecurringRunnable(cpr);
        }
    }

    /**
     * Conference created.
     * @param conference the conference that is created.
     */
    void conferenceCreated(final Conference conference)
    {
        if (conference == null)
        {
            logger.debug(() -> "Could not log conference created event because the conference is null.");
            return;
        }

        // Create a new PeriodicRunnable and start it.
        ConferencePeriodicRunnable cpr
            = new ConferencePeriodicRunnable(
                    conference,
                    interval,
                    this.statsService,
                    this.conferenceIDPrefix,
                    this.bridgeId);
        cpr.start();

        // register for periodic execution.
        statisticsProcessors.put(conference, cpr);
        statisticsExecutor.registerRecurringRunnable(cpr);
    }

    /**
     * Conference expired.
     * @param conference the conference that expired.
     */
    void conferenceExpired(Conference conference)
    {
        if (conference == null)
        {
            logger.debug(() -> "Could not log conference expired event because the conference is null.");
            return;
        }

        ConferencePeriodicRunnable cpr = statisticsProcessors.remove(conference);

        if (cpr == null)
        {
            return;
        }

        cpr.stop();
        statisticsExecutor.deRegisterRecurringRunnable(cpr);
    }
}
