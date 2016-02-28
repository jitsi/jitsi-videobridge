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
package org.jitsi.videobridge.stats;

import org.jitsi.impl.neomedia.rtp.remotebitrateestimator.*;

/**
 * Implmenets a {@link RecurringProcessible} which has its
 * {@link RecurringProcessible#process()} invoked at a specific interval/period.
 *
 * @author Lyubomir Marinov
 */
public abstract class PeriodicProcessible
    implements RecurringProcessible
{
    /**
     * The last time in milliseconds at which {@link #process} was invoked.
     */
    private long _lastProcessTime = System.currentTimeMillis();

    /**
     * The interval/period in milliseconds at which {@link #process} is to be
     * invoked.
     */
    private final long _period;

    /**
     * Initializes a new {@code PeriodicProcessible} instance which is to have
     * its {@link #process()} invoked at a specific interval/period.
     *
     * @param period the interval/period in milliseconds at which
     * {@link #process()} is to be invoked
     */
    public PeriodicProcessible(long period)
    {
        if (period < 1)
            throw new IllegalArgumentException("period " + period);

        _period = period;
    }

    /**
     * Gets the last time in milliseconds at which {@link #process} was invoked.
     *
     * @return the last time in milliseconds at which {@link #process} was
     * invoked
     */
    public final long getLastProcessTime()
    {
        return _lastProcessTime;
    }

    /**
     * Gets the interval/period in milliseconds at which {@link #process} is to
     * be invoked.
     *
     * @return the interval/period in milliseconds at which {@link #process} is
     * to be invoked
     */
    public final long getPeriod()
    {
        return _period;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getTimeUntilNextProcess()
    {
        long timeSinceLastProcess
            = Math.max(System.currentTimeMillis() - _lastProcessTime, 0);

        return Math.max(getPeriod() - timeSinceLastProcess, 0);
    }

    /**
     * {@inheritDoc}
     *
     * Updates {@link #_lastProcessTime}.
     */
    @Override
    public long process()
    {
        _lastProcessTime = System.currentTimeMillis();

        return 0;
    }
}
