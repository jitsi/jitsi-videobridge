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

/**
 * Defines an interface for classes that will send statistics.
 *
 * @author Hristo Terezov
 * @author Lyubomir Marinov
 */
public abstract class StatsTransport
{
    /**
     * Publishes a specific (set of) <tt>Statistics</tt> through this
     * <tt>StatsTransport</tt>.
     *
     * @param statistics the <tt>Statistics</tt> to be published through this
     * <tt>StatsTransport</tt>
     */
    public abstract void publishStatistics(Statistics statistics);

    /**
     * Publishes a specific (set of) <tt>Statistics</tt> through this
     * <tt>StatsTransport</tt>. The default implementation invokes
     * {@link #publishStatistics(Statistics)} to preserve legacy
     * implementations.
     *
     * @param statistics the <tt>Statistics</tt> to be published through this
     * <tt>StatsTransport</tt>
     * @param measurementInterval the interval of time in milliseconds covered
     * by the measurements carried by the specified {@code statistics}
     */
    public void publishStatistics(Statistics statistics, long measurementInterval)
    {
        publishStatistics(statistics);
    }
}
